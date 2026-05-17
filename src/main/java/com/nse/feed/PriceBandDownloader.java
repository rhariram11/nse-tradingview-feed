package com.nse.feed;

import com.nse.feed.model.PriceBandRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Downloads and parses the NSE FULL daily price band circuit file.
 *
 * Source : circuit_DDMMYYYY.csv
 * Used   : cold-start / Monday / --force-full only
 *
 * Returns EMPTY list (not an exception) on 404 / non-trading day.
 * Main detects the empty list and exits cleanly.
 */
public class PriceBandDownloader {

    private static final Logger log = LoggerFactory.getLogger(PriceBandDownloader.class);

    private final NseClient client;

    public PriceBandDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads full circuit CSV for the given trade date.
     *
     * @return list of ~2000 PriceBandRecords; EMPTY list if not a trading day
     */
    public List<PriceBandRecord> downloadFull(LocalDate date) throws IOException {
        log.info("[PriceBand] Starting FULL download for: {}", date);
        byte[] raw = client.downloadFullBandCsv(date); // null on 404

        if (raw == null) {
            log.warn("[PriceBand] No circuit file for {} (non-trading day / holiday).", date);
            return Collections.emptyList();
        }

        String csv = new String(raw, StandardCharsets.UTF_8).trim();
        if (csv.isEmpty() || csv.startsWith("<")) {
            log.warn("[PriceBand] Empty or HTML body for {}. Not a trading day?", date);
            return Collections.emptyList();
        }

        List<PriceBandRecord> records = parseCsv(csv);
        log.info("[PriceBand] FULL download complete: {} records.", records.size());
        return records;
    }

    // ── CSV Parser ────────────────────────────────────────────────────────────

    List<PriceBandRecord> parseCsv(String csv) throws IOException {
        List<PriceBandRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return records;

            int idxSymbol = findCol(header, "SYMBOL", "SCRIP_CD");
            int idxSeries = findCol(header, "SERIES");
            int idxLower  = findCol(header, "LOWER_BAND", "LOWERBAND", "LOWER BAND", "LB", "LOWER");
            int idxUpper  = findCol(header, "UPPER_BAND", "UPPERBAND", "UPPER BAND", "UB", "UPPER");
            int idxPct    = findCol(header, "PBAND", "BAND_PERCENT", "BANDPCT",
                                           "BAND%", "PRICE BAND", "PRICEBAND", "PCT_BAND");

            if (idxSymbol < 0) {
                throw new IOException("[PriceBand] SYMBOL column not found. Header: "
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
                    records.add(new PriceBandRecord(symbol, series, 0.0, lower, upper, bandPct));
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

    double parseDouble(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim().replace(",", "");
        if (val.isEmpty() || val.equals("-") || val.equalsIgnoreCase("NA")) return 0.0;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0.0; }
    }
}
