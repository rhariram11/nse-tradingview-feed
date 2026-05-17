package com.nse.feed;

import com.nse.feed.model.AsmRecord;
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
 * Downloads and parses the NSE ASM / STASM / LTASM surveillance list.
 *
 * ── Strategy ──────────────────────────────────────────────────────────────────
 *
 * PRIMARY  : Session-based JSON API
 *   GET /api/asm-securities?asmType=shortterm   → STASM list
 *   GET /api/asm-securities?asmType=longterm    → LTASM list
 *   Requires warmUpSession() in NseClient before calling.
 *
 * FALLBACK : Derive from eq_band_changes REMARKS column
 *   NSE includes strings like "ASM Stage 1", "LTASM Stage 2" in the REMARKS
 *   column of the delta band-change file. When the session API fails or
 *   returns empty data, Main calls deriveFromRemarks() to extract ASM symbols.
 *
 * Returns EMPTY map (not an exception) in all failure cases.
 * Main handles empty ASM map gracefully.
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Attempts to download ASM list via session-based JSON API.
     *
     * @return symbol → AsmRecord map; EMPTY map on failure / non-trading day
     */
    public Map<String, AsmRecord> download(LocalDate date) throws IOException {
        log.info("[ASM] Attempting session-based ASM download for: {}", date);

        String json = null;
        try {
            json = client.downloadAsmJson();
        } catch (IOException e) {
            log.warn("[ASM] Session API failed: {}. Will use REMARKS fallback.", e.getMessage());
            return new HashMap<>();
        }

        if (json == null || json.isBlank()) {
            log.warn("[ASM] Session API returned empty. Will use REMARKS fallback.");
            return new HashMap<>();
        }

        Map<String, AsmRecord> result = parseAsmJson(json);
        log.info("[ASM] Session API: {} ASM symbols parsed.", result.size());
        return result;
    }

    /**
     * Derives ASM-flagged symbols from the REMARKS column of eq_band_changes.
     * NSE includes text like "ASM Stage 1", "STASM Stage I", "LTASM Stage II"
     * in the REMARKS field when a stock enters or changes ASM stage.
     *
     * This is the reliable fallback when the session API is unavailable.
     *
     * @param deltaMap symbol → PriceBandRecord from DeltaBandUpdater
     * @return symbol → AsmRecord derived from REMARKS text
     */
    public Map<String, AsmRecord> deriveFromRemarks(
            Map<String, com.nse.feed.model.PriceBandRecord> deltaMap) {

        Map<String, AsmRecord> result = new HashMap<>();
        for (Map.Entry<String, com.nse.feed.model.PriceBandRecord> entry : deltaMap.entrySet()) {
            String symbol  = entry.getKey();
            String remarks = entry.getValue().getRemarks();
            if (remarks == null) continue;
            String upper = remarks.toUpperCase();
            if (!upper.contains("ASM")) continue;

            String type  = deriveType(upper);
            String stage = deriveStage(upper);
            int    code  = toStageCode(type, stage);
            result.put(symbol, new AsmRecord(symbol, "", type, stage, code));
            log.info("[ASM] Derived from REMARKS: {} → {} {}", symbol, type, stage);
        }
        log.info("[ASM] REMARKS fallback: {} ASM symbols found.", result.size());
        return result;
    }

    // ── JSON Parser ───────────────────────────────────────────────────────────
    //
    // NSE API returns one of:
    //   a) { "data": [ { "symbol": "X", ... }, ... ] }
    //   b) [ { "symbol": "X", ... }, ... ]
    //   c) { "shortterm": {...}, "longterm": {...} }  ← combined wrapper from NseClient
    //
    // We use a simple substring scan (no external JSON lib needed for this).

    private Map<String, AsmRecord> parseAsmJson(String json) {
        Map<String, AsmRecord> map = new HashMap<>();
        int idx = 0;
        while (true) {
            // Find "symbol" key
            int keyIdx = json.indexOf("\"symbol\"", idx);
            if (keyIdx < 0) break;
            int colon  = json.indexOf(':', keyIdx);
            int openQ  = json.indexOf('"', colon + 1);
            int closeQ = json.indexOf('"', openQ + 1);
            if (openQ < 0 || closeQ < 0) break;
            String symbol = json.substring(openQ + 1, closeQ).trim().toUpperCase();
            idx = closeQ + 1;
            if (symbol.isEmpty()) continue;

            // Look for asmType / stage near this symbol entry (within next 300 chars)
            String nearby = json.substring(closeQ, Math.min(closeQ + 300, json.length()));
            String type  = extractField(nearby, "asmType", "type");
            String stage = extractField(nearby, "asmStage", "stage");
            if (type.isEmpty())  type  = deriveType(nearby.toUpperCase());
            if (stage.isEmpty()) stage = deriveStage(nearby.toUpperCase());
            int code = toStageCode(type.toUpperCase(), stage.toUpperCase());
            map.put(symbol, new AsmRecord(symbol, "", type, stage, code));
        }
        return map;
    }

    /** Extract value of first matching field name from a JSON snippet. */
    private String extractField(String json, String... fieldNames) {
        for (String field : fieldNames) {
            String key = "\"" + field + "\"";
            int ki = json.indexOf(key);
            if (ki < 0) continue;
            int colon  = json.indexOf(':', ki);
            int openQ  = json.indexOf('"', colon + 1);
            int closeQ = json.indexOf('"', openQ + 1);
            if (openQ < 0 || closeQ < 0) continue;
            return json.substring(openQ + 1, closeQ).trim();
        }
        return "";
    }

    // ── Stage / type derivation ───────────────────────────────────────────────

    static String deriveType(String upper) {
        if (upper.contains("LTASM")) return "LTASM";
        if (upper.contains("STASM")) return "STASM";
        if (upper.contains("SHORTTERM") || upper.contains("SHORT")) return "STASM";
        if (upper.contains("LONGTERM")  || upper.contains("LONG"))  return "LTASM";
        if (upper.contains("GSM"))   return "GSM";
        if (upper.contains("ASM"))   return "ASM";
        return "ASM";
    }

    static String deriveStage(String upper) {
        if (upper.contains("III") || upper.contains("STAGE 3") || upper.contains("STAGE3")) return "III";
        if (upper.contains("II")  || upper.contains("STAGE 2") || upper.contains("STAGE2")) return "II";
        if (upper.contains("I")   || upper.contains("STAGE 1") || upper.contains("STAGE1")) return "I";
        return "I";
    }

    static int toStageCode(String type, String stage) {
        int n = stageNum(stage);
        if (type.contains("LTASM") || type.contains("LONG"))  return 12 + n;
        if (type.contains("STASM") || type.contains("SHORT")) return 10 + n;
        if (type.contains("GSM"))                             return 20;
        if (type.contains("ASM"))                             return n > 0 ? (10 + n) : 11;
        return 0;
    }

    private static int stageNum(String stage) {
        String u = stage.trim().toUpperCase();
        if (u.contains("III") || u.equals("3")) return 3;
        if (u.contains("II")  || u.equals("2")) return 2;
        return 1;
    }

    // ── Legacy CSV parser (kept for if NSE re-enables CSV ASM download) ───────

    private Map<String, AsmRecord> parseCsv(String csv) throws IOException {
        Map<String, AsmRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            int idxSymbol = findCol(header, "SYMBOL");
            int idxIsin   = findCol(header, "ISIN");
            int idxStage  = findCol(header,
                    "ASM STAGE", "LTASM STAGE", "STASM STAGE", "STAGE",
                    "ASMSTAGE", "ASM_STAGE", "SURVEILLANCE STAGE");
            int idxType   = findCol(header,
                    "ASM TYPE", "TYPE", "ASM_TYPE", "SURVEILLANCE TYPE", "SURV TYPE");

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String isin  = idxIsin  >= 0 ? clean(row[idxIsin])  : "";
                    String stage = idxStage >= 0 ? clean(row[idxStage]) : "";
                    String type  = idxType  >= 0 ? clean(row[idxType])  : deriveType(stage);
                    int    code  = toStageCode(type, stage);
                    map.put(symbol, new AsmRecord(symbol, isin, type, stage, code));
                } catch (Exception e) {
                    log.debug("[ASM] Skipping row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[ASM] CSV parse error: " + e.getMessage(), e);
        }
        return map;
    }

    private int findCol(String[] header, String... names) {
        for (String name : names)
            for (int i = 0; i < header.length; i++)
                if (header[i].trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private String clean(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
