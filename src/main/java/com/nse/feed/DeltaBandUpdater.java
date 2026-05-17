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
import java.util.HashMap;
import java.util.Map;

/**
 * Applies the NSE daily delta (band-change) file onto seed data.
 *
 * Source : eq_band_changes_DDMMYYYY.csv
 * Columns: Symbol, Series, New Band, Old Band, Effective Date
 *
 * Returns EMPTY map on 404 / non-trading day (handled gracefully by Main).
 */
public class DeltaBandUpdater {

    private static final Logger log = LoggerFactory.getLogger(DeltaBandUpdater.class);

    private final NseClient client;

    public DeltaBandUpdater(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads and parses the delta band-change file.
     *
     * @return map of symbol → PriceBandRecord for changed symbols only;
     *         EMPTY map if not a trading day or no changes today.
     */
    public Map<String, PriceBandRecord> downloadAndParse(LocalDate date) throws IOException {
        byte[] raw = client.downloadDeltaBandCsv(date); // null on 404

        if (raw == null) {
            log.warn("[Delta] No band-change file for {} (non-trading day / holiday).", date);
            return new HashMap<>();
        }

        String csv = new String(raw, StandardCharsets.UTF_8).trim();
        if (csv.isEmpty() || csv.startsWith("<")) {
            log.info("[Delta] Empty delta file for {} (no band changes today).", date);
            return new HashMap<>();
        }

        Map<String, PriceBandRecord> changes = parseDeltaCsv(csv);
        log.info("[Delta] {} symbol(s) with band changes on {}.", changes.size(), date);
        return changes;
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    Map<String, PriceBandRecord> parseDeltaCsv(String csv) throws IOException {
        Map<String, PriceBandRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            int idxSymbol  = findCol(header, "SYMBOL", "SCRIP");
            int idxSeries  = findCol(header, "SERIES");
            int idxNewBand = findCol(header,
                    "NEW_BAND", "NEW BAND", "NEWBAND", "PRICE BAND", "PRICEBAND", "PBAND");
            int idxOldBand = findCol(header, "OLD_BAND", "OLD BAND", "OLDBAND");

            if (idxSymbol < 0 || idxNewBand < 0) {
                log.warn("[Delta] Unexpected CSV header: {}", String.join(",", header));
                return map;
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series     = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    double newBandPct = parseBandPct(row, idxNewBand);
                    double oldBandPct = parseBandPct(row, idxOldBand);
                    log.info("[Delta] Band change: {} {} → {}%", symbol, oldBandPct, newBandPct);
                    map.put(symbol, new PriceBandRecord(symbol, series, 0.0, 0.0, 0.0, newBandPct));
                } catch (Exception e) {
                    log.debug("[Delta] Skipping row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[Delta] CSV parse error: " + e.getMessage(), e);
        }
        return map;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int findCol(String[] header, String... names) {
        for (String name : names)
            for (int i = 0; i < header.length; i++)
                if (header[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private String clean(String s) { return s == null ? "" : s.trim().toUpperCase(); }

    private double parseBandPct(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim().replace("%", "").replace(",", "").trim();
        if (val.isEmpty() || val.equals("-")
                || val.equalsIgnoreCase("NA")
                || val.equalsIgnoreCase("NO BAND")
                || val.equalsIgnoreCase("NONE")) return 0.0;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0.0; }
    }
}
