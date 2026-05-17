package com.nse.feed;

import com.nse.feed.model.PriceBandRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and parses the NSE FULL daily price band circuit file.
 *
 * Source file  : circuit_DDMMYYYY.csv
 * NSE endpoint : /api/reports?archives=[{"name":"Security Wise Daily Price Band",...}]&date=DD-MMM-YYYY
 *
 * This class is used ONLY for:
 *   - Cold-start (first ever run, no seed data present)
 *   - Monday refresh (full re-baseline every week)
 *   - Manual --force-full flag
 *
 * For normal daily updates use DeltaBandUpdater which downloads only the
 * much smaller eq_band_changes_DDMMYYYY.csv (~10-50 rows vs ~2000 rows).
 *
 * CSV columns (NSE format):
 *   SYMBOL, SERIES, LOWER_BAND, UPPER_BAND, PBAND (band %)
 *   Note: NSE circuit file does NOT carry PREV_CLOSE — that is 0.0 here.
 *         PREV_CLOSE is available in Bhavcopy if needed in future.
 */
public class PriceBandDownloader {

    private static final Logger log = LoggerFactory.getLogger(PriceBandDownloader.class);

    private final NseClient client;

    public PriceBandDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads the full circuit CSV for the given trade date and parses it.
     * Returns all ~2000 EQ symbols with their current price band %.
     *
     * @param date trade date (use today, or last trading day if today is holiday/weekend)
     */
    public List<PriceBandRecord> downloadFull(LocalDate date) throws IOException {
        log.info("[PriceBand] Starting FULL download for date: {}", date);
        byte[] raw = client.downloadFullBandCsv(date);
        String csv = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();

        if (csv.isEmpty() || csv.startsWith("<")) {
            // NSE sometimes returns HTML error page on non-trading days
            throw new IOException(
                "[PriceBand] Full circuit file empty or HTML for date: " + date
                + ". Is this a trading day?");
        }

        List<PriceBandRecord> records = parseCsv(csv);
        log.info("[PriceBand] FULL download complete: {} records for {}", records.size(), date);
        return records;
    }

    // ── CSV Parser ───────────────────────────────────────────────────────────

    List<PriceBandRecord> parseCsv(String csv) throws IOException {
        List<PriceBandRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return records;

            // Flexible column detection — NSE occasionally renames columns
            int idxSymbol = findCol(header, "SYMBOL", "SCRIP_CD");
            int idxSeries = findCol(header, "SERIES");
            int idxLower  = findCol(header, "LOWER_BAND", "LOWERBAND", "LOWER BAND", "LB", "LOWER");
            int idxUpper  = findCol(header, "UPPER_BAND", "UPPERBAND", "UPPER BAND", "UB", "UPPER");
            int idxPct    = findCol(header, "PBAND", "BAND_PERCENT", "BANDPCT", "BAND%",
                                           "PRICE BAND", "PRICEBAND", "PCT_BAND");

            if (idxSymbol < 0) {
                throw new IOException("[PriceBand] Cannot find SYMBOL column in header: "
                        + String.join(",", header));
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol  = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series  = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    double lower   = parseDouble(row, idxLower);
                    double upper   = parseDouble(row, idxUpper);
                    double bandPct = parseDouble(row, idxPct);
                    // prevClose = 0.0 (not in circuit file; available in Bhavcopy)
                    records.add(new PriceBandRecord(symbol, series, 0.0, lower, upper, bandPct));
                } catch (Exception e) {
                    log.debug("[PriceBand] Skipping malformed row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[PriceBand] CSV parse error: " + e.getMessage(), e);
        }
        return records;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    int findCol(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    String clean(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    double parseDouble(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim().replace(",", "");
        if (val.isEmpty() || val.equals("-") || val.equalsIgnoreCase("NA")) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
