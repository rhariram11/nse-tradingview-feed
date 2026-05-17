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
import java.util.Collections;
import java.util.List;

/**
 * Downloads and parses the NSE FULL daily price band master file.
 *
 * Source  : https://nsearchives.nseindia.com/content/equities/sec_list_{ddmmyyyy}.csv
 * Used for: cold-start / Monday / --force-full runs only
 *
 * Actual sec_list CSV columns (verified from live file):
 *   SYMBOL, NAME_OF_COMPANY, SERIES, DATE_OF_LISTING, PAID_UP_VALUE,
 *   MARKET_LOT, ISIN_NUMBER, FACE_VALUE, PRICE_BAND
 *
 * PRICE_BAND values: "2%", "5%", "10%", "20%", "No Band", "NA"
 * Returns EMPTY list (not exception) on 404 / non-trading day.
 */
public class PriceBandDownloader {

    private static final Logger log = LoggerFactory.getLogger(PriceBandDownloader.class);

    private final NseClient client;

    public PriceBandDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads full sec_list CSV.
     * NseClient.downloadSecList() tries up to 7 trading days backwards.
     *
     * @return list of PriceBandRecords; EMPTY list if not a trading day
     */
    public List<PriceBandRecord> downloadFull(LocalDate date) throws IOException {
        log.info("[PriceBand] Starting FULL download (sec_list) for: {}", date);
        String csv = client.downloadSecList(date);

        if (csv == null) {
            log.warn("[PriceBand] sec_list not available for {} (non-trading day / holiday).", date);
            return Collections.emptyList();
        }

        List<PriceBandRecord> records = parseSecListCsv(csv);
        log.info("[PriceBand] FULL download complete: {} records.", records.size());
        return records;
    }

    // ── CSV Parser — sec_list ─────────────────────────────────────────────────

    List<PriceBandRecord> parseSecListCsv(String csv) throws IOException {
        List<PriceBandRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return records;

            // Column discovery — handles minor NSE header wording variations
            int idxSymbol = findCol(header, "SYMBOL", "SCRIP_CD", "SCRIP");
            int idxSeries = findCol(header, "SERIES");
            int idxBand   = findCol(header,
                    "PRICE_BAND", "PBAND", "BAND",
                    "PRICE BAND", "PRICEBAND", "BAND_PERCENT",
                    "BAND%", "PCT_BAND");

            if (idxSymbol < 0) {
                throw new IOException(
                    "[PriceBand] SYMBOL column not found in sec_list. Header: "
                    + String.join(",", header));
            }

            if (idxBand < 0) {
                log.warn("[PriceBand] PRICE_BAND column not found. Columns: {}",
                        String.join(",", header));
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series   = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    double bandPct  = parseBandPct(row, idxBand);
                    // sec_list has no absolute lower/upper prices — just band %
                    records.add(new PriceBandRecord(symbol, series, 0.0, 0.0, 0.0, bandPct));
                } catch (Exception e) {
                    log.debug("[PriceBand] Skipping row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[PriceBand] CSV parse error: " + e.getMessage(), e);
        }
        return records;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    int findCol(String[] header, String... names) {
        for (String name : names)
            for (int i = 0; i < header.length; i++)
                if (header[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    String clean(String s) { return s == null ? "" : s.trim().toUpperCase(); }

    double parseBandPct(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim()
                .replace("%", "")
                .replace(",", "")
                .trim();
        if (val.isEmpty() || val.equalsIgnoreCase("-")
                || val.equalsIgnoreCase("NA")
                || val.equalsIgnoreCase("NO BAND")
                || val.equalsIgnoreCase("NONE")) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
