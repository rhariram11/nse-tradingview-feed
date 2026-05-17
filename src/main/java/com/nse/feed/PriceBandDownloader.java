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
 * Downloads and parses NSE full price-band master (sec_list_{ddmmyyyy}.csv).
 *
 * <p>Source: nsearchives.nseindia.com/content/equities/sec_list_{ddmmyyyy}.csv
 *
 * <h3>Verified columns from live file</h3>
 * SYMBOL, NAME_OF_COMPANY, SERIES, DATE_OF_LISTING, PAID_UP_VALUE,
 * MARKET_LOT, ISIN_NUMBER, FACE_VALUE, PRICE_BAND
 *
 * <p>PRICE_BAND raw values: "2%", "5%", "10%", "20%", "No Band", "NA"
 * The raw string is normalised and preserved as bandLabel so Pine displays
 * exactly what NSE publishes.
 */
public class PriceBandDownloader {

    private static final Logger log = LoggerFactory.getLogger(PriceBandDownloader.class);

    private final NseClient client;

    public PriceBandDownloader(NseClient client) {
        this.client = client;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<PriceBandRecord> downloadFull(LocalDate date) throws IOException {
        log.info("[PriceBand] FULL download (sec_list) for: {}", date);
        String csv = client.downloadSecList(date);
        if (csv == null) {
            log.warn("[PriceBand] sec_list not available — holiday / non-trading day.");
            return Collections.emptyList();
        }
        List<PriceBandRecord> records = parseSecListCsv(csv);
        log.info("[PriceBand] FULL: {} records loaded.", records.size());
        return records;
    }

    // ── CSV Parser ────────────────────────────────────────────────────────────

    List<PriceBandRecord> parseSecListCsv(String csv) throws IOException {
        List<PriceBandRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return records;

            int idxSymbol = findCol(header, "SYMBOL", "SCRIP_CD", "SCRIP");
            int idxSeries = findCol(header, "SERIES");
            int idxBand   = findCol(header,
                    "PRICE_BAND", "PBAND", "BAND",
                    "PRICE BAND", "PRICEBAND", "BAND_PERCENT", "BAND%", "PCT_BAND");

            if (idxSymbol < 0)
                throw new IOException(
                        "[PriceBand] SYMBOL column not found. Header: "
                        + String.join(",", header));
            if (idxBand < 0)
                log.warn("[PriceBand] PRICE_BAND column not found. Columns: {}",
                        String.join(",", header));

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series    = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    String rawCell   = (idxBand >= 0 && idxBand < row.length)
                                       ? row[idxBand].trim() : "";
                    String bandLabel = normaliseBandLabel(rawCell);
                    double bandPct   = parseBandPct(rawCell);
                    records.add(new PriceBandRecord(
                            symbol, series, 0.0, 0.0, 0.0, bandPct, bandLabel));
                } catch (Exception e) {
                    log.debug("[PriceBand] Skipping malformed row: {}",
                            String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[PriceBand] CSV parse error: " + e.getMessage(), e);
        }
        return records;
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    /**
     * Normalises the raw PRICE_BAND cell to a clean display string.
     * <pre>
     *   "20"      → "20%"
     *   "20%"     → "20%"
     *   "No Band" → "No Band"
     *   ""        → "No Band"
     *   "NA"      → "No Band"
     * </pre>
     */
    static String normaliseBandLabel(String raw) {
        if (raw == null || raw.isBlank()) return "No Band";
        String t = raw.trim();
        if (t.equalsIgnoreCase("NA")
                || t.equals("-")
                || t.equalsIgnoreCase("NO BAND")
                || t.equalsIgnoreCase("NONE")
                || t.equals("0")) return "No Band";
        if (t.endsWith("%")) return t;          // already has suffix
        try {
            double v = Double.parseDouble(t.replace(",", ""));
            if (v <= 0) return "No Band";
            return (v == Math.floor(v)) ? (int) v + "%" : v + "%";
        } catch (NumberFormatException e) {
            return t;   // unknown string — return as-is
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    int findCol(String[] header, String... names) {
        for (String name : names)
            for (int i = 0; i < header.length; i++)
                if (header[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    String clean(String s) { return s == null ? "" : s.trim().toUpperCase(); }

    double parseBandPct(String raw) {
        if (raw == null) return 0.0;
        String val = raw.trim().replace("%", "").replace(",", "").trim();
        if (val.isEmpty() || val.equalsIgnoreCase("-")
                || val.equalsIgnoreCase("NA")
                || val.equalsIgnoreCase("NO BAND")
                || val.equalsIgnoreCase("NONE")) return 0.0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
