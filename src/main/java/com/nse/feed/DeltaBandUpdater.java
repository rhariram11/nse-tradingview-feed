package com.nse.feed;

import com.nse.feed.model.PriceBandRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses NSE daily delta band-change file.
 *
 * Source  : https://nsearchives.nseindia.com/content/equities/eq_band_changes_{ddmmyyyy}.csv
 *
 * ── CRITICAL DATE RULE ────────────────────────────────────────────────────────
 * NSE names this file with the NEXT trading day's date — NOT today's.
 * Example: band changes effective Monday 19-May-2026 are published
 *          after Friday (16-May-2026) close in file eq_band_changes_19052026.csv
 * NseClient.downloadBandChanges() handles this by trying nextTradingDay(runDate) first.
 *
 * ── Actual CSV columns (verified from live file 18-May-2026) ──────────────────
 *   SYMBOL, SERIES, PRICE_BAND_NEW, PRICE_BAND_OLD, EFFECTIVE_DATE, REMARKS
 *
 * REMARKS contains ASM context, e.g.:
 *   "ASM Stage 1", "LTASM Stage II", "Price Band Change", "Short Term ASM"
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
     * Downloads and parses eq_band_changes CSV.
     * NseClient handles the next-trading-day filename logic.
     *
     * @return symbol → PriceBandRecord (with REMARKS populated) for changed symbols;
     *         EMPTY map if no changes or non-trading day.
     */
    public Map<String, PriceBandRecord> downloadAndParse(LocalDate runDate) throws IOException {
        String csv = client.downloadBandChanges(runDate); // null on 404

        if (csv == null) {
            log.info("[Delta] No eq_band_changes file available for runDate={}.", runDate);
            return new HashMap<>();
        }

        Map<String, PriceBandRecord> changes = parseDeltaCsv(csv);
        log.info("[Delta] {} symbol(s) with band changes for runDate={}.", changes.size(), runDate);
        return changes;
    }

    // ── CSV Parser ────────────────────────────────────────────────────────────

    Map<String, PriceBandRecord> parseDeltaCsv(String csv) throws IOException {
        Map<String, PriceBandRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            log.debug("[Delta] CSV header: {}", String.join(",", header));

            int idxSymbol  = findCol(header, "SYMBOL", "SCRIP");
            int idxSeries  = findCol(header, "SERIES");
            // New column names from verified live file:
            int idxNewBand = findCol(header,
                    "PRICE_BAND_NEW", "NEW_PRICE_BAND",  // verified live names
                    "NEW_BAND", "NEW BAND", "NEWBAND",   // alternate names
                    "PRICE BAND", "PRICEBAND", "PBAND"); // legacy names
            int idxOldBand = findCol(header,
                    "PRICE_BAND_OLD", "OLD_PRICE_BAND",  // verified live names
                    "OLD_BAND", "OLD BAND", "OLDBAND");   // alternate names
            int idxRemarks = findCol(header,
                    "REMARKS", "REMARK", "REASON", "DESCRIPTION");

            if (idxSymbol < 0) {
                log.warn("[Delta] SYMBOL column not found. Header: {}", String.join(",", header));
                return map;
            }
            if (idxNewBand < 0) {
                log.warn("[Delta] New band column not found. Header: {}", String.join(",", header));
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series    = idxSeries  >= 0 ? clean(row[idxSeries])  : "EQ";
                    double newBandPct = parseBandPct(row, idxNewBand);
                    double oldBandPct = parseBandPct(row, idxOldBand);
                    String remarks   = idxRemarks >= 0 ? row[idxRemarks].trim() : "";

                    log.info("[Delta] Band change: {} {}% → {}%  [{}]",
                            symbol, oldBandPct, newBandPct, remarks);

                    PriceBandRecord rec =
                        new PriceBandRecord(symbol, series, 0.0, 0.0, 0.0, newBandPct);
                    rec.setRemarks(remarks);
                    map.put(symbol, rec);
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
        String val = row[idx].trim()
                .replace("%", "")
                .replace(",", "")
                .trim();
        if (val.isEmpty() || val.equals("-")
                || val.equalsIgnoreCase("NA")
                || val.equalsIgnoreCase("NO BAND")
                || val.equalsIgnoreCase("NONE")) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
