package com.nse.feed;

import com.nse.feed.model.PriceBandRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies the NSE daily delta (band-change) file onto existing seed CSV files.
 *
 * ─── Why delta instead of full? ────────────────────────────────────────────
 *   NSE publishes two price band related files every trading day:
 *
 *   1. circuit_DDMMYYYY.csv         – FULL file: ~2000 rows, ALL EQ symbols
 *   2. eq_band_changes_DDMMYYYY.csv – DELTA file: ~10-50 rows, ONLY those
 *                                     symbols whose price band % changed today
 *
 *   Columns in eq_band_changes_DDMMYYYY.csv:
 *     SYMBOL, SERIES, NEW_BAND, OLD_BAND, EFFECTIVE_DATE
 *
 *   Using the delta means we only re-process ~10-50 rows per day instead of
 *   ~2000, making the daily run extremely fast and avoiding unnecessary
 *   writes to seed files whose band hasn't changed.
 *
 * ─── Usage ──────────────────────────────────────────────────────────────────
 *   Called by Main on every regular trading day AFTER cold-start is done.
 *   Cold-start (first run / Monday / --force-full) uses PriceBandDownloader
 *   instead to rebuild the full baseline.
 *
 * ─── Algorithm ──────────────────────────────────────────────────────────────
 *   1. Download eq_band_changes_DDMMYYYY.csv
 *   2. Parse → Map<symbol, newBandPct>
 *   3. Return the map to Main, which passes it to DataMerger.mergeDeltas()
 *   4. DataMerger applies the delta only to those symbols; all others keep
 *      their existing band values (no file I/O for unchanged symbols).
 */
public class DeltaBandUpdater {

    private static final Logger log = LoggerFactory.getLogger(DeltaBandUpdater.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final NseClient client;

    public DeltaBandUpdater(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads today's eq_band_changes CSV and parses it.
     *
     * @param date  trade date
     * @return map of NSE symbol → updated PriceBandRecord (only changed symbols)
     *         Empty map means no band changes today (perfectly normal).
     */
    public Map<String, PriceBandRecord> downloadAndParse(LocalDate date) throws IOException {
        byte[] raw = client.downloadDeltaBandCsv(date);
        String csv = new String(raw, StandardCharsets.UTF_8).trim();

        if (csv.isEmpty() || csv.startsWith("<")) {
            log.info("[Delta] No band changes today (empty or HTML response) for date: {}", date);
            return new HashMap<>();
        }

        Map<String, PriceBandRecord> changes = parseDeltaCsv(csv, date);
        log.info("[Delta] {} symbol(s) have band changes today ({}).", changes.size(), date);
        return changes;
    }

    // ── Parser ───────────────────────────────────────────────────────────────

    /**
     * Parses eq_band_changes_DDMMYYYY.csv
     *
     * Expected columns (NSE format – verified May 2026):
     *   SYMBOL, SERIES, NEW_BAND (or PRICE BAND), OLD_BAND, EFFECTIVE_DATE
     *
     * NEW_BAND values are typically: 2, 5, 10, 20 (percent) or "NO BAND" for F&O stocks.
     */
    Map<String, PriceBandRecord> parseDeltaCsv(String csv, LocalDate date) throws IOException {
        Map<String, PriceBandRecord> map = new HashMap<>();

        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            int idxSymbol  = findCol(header, "SYMBOL", "SCRIP");
            int idxSeries  = findCol(header, "SERIES");
            // "NEW BAND" or "NEW_BAND" or "PRICE BAND" — NSE is inconsistent
            int idxNewBand = findCol(header,
                    "NEW_BAND", "NEW BAND", "NEWBAND", "PRICE BAND", "PRICEBAND", "PBAND");
            // OLD_BAND is informational only; we log it for audit trail
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

                    log.info("[Delta] Band change: {} | {} → {} %",
                            symbol, oldBandPct, newBandPct);

                    // lower/upper absolute levels are NOT in the delta file;
                    // set to 0.0 — SeedWriter will skip writing them if both are 0
                    // (the band % column in the seed is what TradingView uses).
                    map.put(symbol, new PriceBandRecord(
                            symbol, series, 0.0, 0.0, 0.0, newBandPct));
                } catch (Exception e) {
                    log.debug("[Delta] Skipping malformed row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[Delta] CSV parse error: " + e.getMessage(), e);
        }
        return map;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int findCol(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    private String clean(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /**
     * Parses band% value. NSE may write "5 %", "5", "NO BAND", "-", "NA".
     * Returns 0.0 for "no static band" (F&O stocks).
     */
    private double parseBandPct(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim().replace("%", "").replace(",", "").trim();
        if (val.isEmpty() || val.equals("-")
                || val.equalsIgnoreCase("NA")
                || val.equalsIgnoreCase("NO BAND")
                || val.equalsIgnoreCase("NONE")) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
