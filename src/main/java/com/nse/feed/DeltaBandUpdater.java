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
 * Downloads and parses NSE delta band-change file (eq_band_changes_{ddmmyyyy}.csv).
 *
 * <h3>Verified CSV columns (live file)</h3>
 * SYMBOL, SERIES, PRICE_BAND_NEW, PRICE_BAND_OLD, EFFECTIVE_DATE, REMARKS
 *
 * <h3>CRITICAL DATE RULE</h3>
 * NSE labels this file with the NEXT trading day's date.
 * {@link NseClient#downloadBandChanges} tries nextTradingDay(runDate) first.
 *
 * <p>bandLabel is preserved from PRICE_BAND_NEW for exact Pine display.
 * REMARKS is retained for the ASM fallback derivation in {@link AsmDownloader}.
 */
public class DeltaBandUpdater {

    private static final Logger log = LoggerFactory.getLogger(DeltaBandUpdater.class);

    private final NseClient client;

    public DeltaBandUpdater(NseClient client) {
        this.client = client;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Map<String, PriceBandRecord> downloadAndParse(LocalDate runDate) throws IOException {
        String csv = client.downloadBandChanges(runDate);
        if (csv == null) {
            log.info("[Delta] No eq_band_changes available for runDate={}.", runDate);
            return new HashMap<>();
        }
        Map<String, PriceBandRecord> changes = parseDeltaCsv(csv);
        log.info("[Delta] {} band change(s) for runDate={}.", changes.size(), runDate);
        return changes;
    }

    // ── CSV Parser ────────────────────────────────────────────────────────────

    Map<String, PriceBandRecord> parseDeltaCsv(String csv) throws IOException {
        Map<String, PriceBandRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;
            log.debug("[Delta] Header: {}", String.join(",", header));

            int idxSymbol  = findCol(header, "SYMBOL", "SCRIP");
            int idxSeries  = findCol(header, "SERIES");
            int idxNewBand = findCol(header,
                    "PRICE_BAND_NEW", "NEW_PRICE_BAND",
                    "NEW_BAND", "NEW BAND", "NEWBAND",
                    "PRICE BAND", "PRICEBAND", "PBAND");
            int idxOldBand = findCol(header,
                    "PRICE_BAND_OLD", "OLD_PRICE_BAND",
                    "OLD_BAND", "OLD BAND", "OLDBAND");
            int idxRemarks = findCol(header, "REMARKS", "REMARK", "REASON", "DESCRIPTION");

            if (idxSymbol < 0) {
                log.warn("[Delta] SYMBOL column not found. Header: {}", String.join(",", header));
                return map;
            }
            if (idxNewBand < 0)
                log.warn("[Delta] New-band column not found. Header: {}", String.join(",", header));

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol  = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String series  = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    String rawNew  = (idxNewBand >= 0 && idxNewBand < row.length)
                                     ? row[idxNewBand].trim() : "";
                    String rawOld  = (idxOldBand >= 0 && idxOldBand < row.length)
                                     ? row[idxOldBand].trim() : "";
                    String newLabel = PriceBandDownloader.normaliseBandLabel(rawNew);
                    String oldLabel = PriceBandDownloader.normaliseBandLabel(rawOld);
                    double newPct   = parseBandPct(rawNew);
                    String remarks  = (idxRemarks >= 0) ? row[idxRemarks].trim() : "";

                    log.info("[Delta] {} : {} → {}  [{}]", symbol, oldLabel, newLabel, remarks);

                    PriceBandRecord rec =
                            new PriceBandRecord(symbol, series, 0.0, 0.0, 0.0, newPct, newLabel);
                    rec.setRemarks(remarks);
                    map.put(symbol, rec);
                } catch (Exception e) {
                    log.debug("[Delta] Skipping malformed row: {}", String.join(",", row));
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

    private double parseBandPct(String raw) {
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
