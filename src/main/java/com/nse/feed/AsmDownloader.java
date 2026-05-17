package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.AsmStageCode;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads and parses the NSE ASM surveillance list.
 *
 * <h3>Strategy (ordered by reliability)</h3>
 * <ol>
 *   <li><b>Session JSON API</b> — {@code /api/asm-securities?asmType=shortterm|longterm}
 *       Parsed with regex object extraction — no external JSON lib dependency.</li>
 *   <li><b>REMARKS fallback</b> — derives ASM flags from the REMARKS column of
 *       eq_band_changes. Only covers stocks whose band changed that day.</li>
 * </ol>
 *
 * <h3>Stage code encoding</h3>
 * All codes delegate to {@link AsmStageCode} — the single source of truth.
 * <pre>
 *   STASM: 11 / 12 / 13
 *   LTASM: 21 / 22 / 23  (non-overlapping range)
 *   GSM  : 30
 * </pre>
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    // Captures: "fieldName":"value" from a JSON object snippet
    private static final Pattern FIELD_PAT =
        Pattern.compile("\\\"([^\"]+)\\\"\\s*:\\s*\\\"([^\"]*)\\\"");

    // Matches a single-level JSON object  { ... }
    private static final Pattern OBJECT_PAT =
        Pattern.compile("\\{[^{}]*\\}");

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Downloads both STASM and LTASM lists from the session API.
     *
     * @return symbol → AsmRecord; EMPTY (never null) on any failure
     */
    public Map<String, AsmRecord> download(java.time.LocalDate date) throws IOException {
        log.info("[ASM] Session API download for: {}", date);

        String json;
        try {
            json = client.downloadAsmJson();
        } catch (IOException e) {
            log.warn("[ASM] Session API I/O failure: {}. Using empty map.", e.getMessage());
            return Collections.emptyMap();
        }

        if (json == null || json.isBlank()) {
            log.warn("[ASM] Session API returned empty. Using empty map.");
            return Collections.emptyMap();
        }

        Map<String, AsmRecord> result = new HashMap<>();
        result.putAll(parseAsmHalf(json, "shortterm", "STASM"));
        result.putAll(parseAsmHalf(json, "longterm",  "LTASM"));
        if (result.isEmpty()) {
            result.putAll(parseAsmObjectArray(json, "ASM"));
        }

        log.info("[ASM] Session API: {} ASM symbols.", result.size());
        return Collections.unmodifiableMap(result);
    }

    /**
     * Derives ASM flags from REMARKS text in the eq_band_changes delta file.
     * Only covers stocks whose band changed that day.
     */
    public Map<String, AsmRecord> deriveFromRemarks(
            Map<String, PriceBandRecord> deltaMap) {

        Map<String, AsmRecord> result = new HashMap<>();
        for (Map.Entry<String, PriceBandRecord> entry : deltaMap.entrySet()) {
            String symbol  = entry.getKey();
            String remarks = entry.getValue().getRemarks();
            if (remarks == null || !remarks.toUpperCase().contains("ASM")) continue;
            String upper = remarks.toUpperCase();
            String type  = deriveType(upper);
            String stage = deriveStage(upper);
            result.put(symbol, AsmRecord.of(symbol, "", type, stage));
            log.info("[ASM] Derived from REMARKS: {} → {} {}", symbol, type, stage);
        }
        log.info("[ASM] REMARKS fallback: {} symbols.", result.size());
        return Collections.unmodifiableMap(result);
    }

    // ── JSON Parsing ──────────────────────────────────────────────────────────

    private Map<String, AsmRecord> parseAsmHalf(String json, String key, String defaultType) {
        String keyToken = "\"" + key + "\"";
        int keyIdx = json.indexOf(keyToken);
        if (keyIdx < 0) return Collections.emptyMap();
        int arrStart = json.indexOf('[', keyIdx);
        if (arrStart < 0) return Collections.emptyMap();
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return Collections.emptyMap();
        return parseAsmObjectArray(json.substring(arrStart, arrEnd + 1), defaultType);
    }

    private Map<String, AsmRecord> parseAsmObjectArray(String json, String defaultType) {
        Map<String, AsmRecord> map = new HashMap<>();
        String payload = unwrapDataArray(json);
        Matcher objM = OBJECT_PAT.matcher(payload);
        while (objM.find()) {
            String obj    = objM.group();
            Map<String, String> fields = extractAllFields(obj);
            String symbol = coalesce(fields, "symbol", "SYMBOL", "scrip", "SCRIP");
            if (symbol == null || symbol.isBlank()) continue;
            symbol = symbol.trim().toUpperCase();
            String isin     = coalesce(fields, "isin", "ISIN");
            String rawType  = coalesce(fields, "asmType", "asmtype", "type", "TYPE");
            String rawStage = coalesce(fields, "asmStage", "asmstage", "stage", "STAGE");
            if (rawType.isBlank())  rawType  = deriveType(obj.toUpperCase());
            if (rawStage.isBlank()) rawStage = deriveStage(obj.toUpperCase());
            if (rawType.isBlank())  rawType  = defaultType;
            map.put(symbol, AsmRecord.of(symbol, isin == null ? "" : isin, rawType, rawStage));
        }
        return map;
    }

    private Map<String, String> extractAllFields(String obj) {
        Map<String, String> fields = new HashMap<>();
        Matcher m = FIELD_PAT.matcher(obj);
        while (m.find()) fields.put(m.group(1), m.group(2));
        return fields;
    }

    private String coalesce(Map<String, String> fields, String... keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private String unwrapDataArray(String json) {
        for (String key : new String[]{"data", "records", "Data", "Records"}) {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) continue;
            int arrStart = json.indexOf('[', ki);
            if (arrStart < 0) continue;
            int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
            if (arrEnd >= 0) return json.substring(arrStart, arrEnd + 1);
        }
        return json;
    }

    private int findMatchingBracket(String s, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == open)  depth++;
            if (s.charAt(i) == close) depth--;
            if (depth == 0) return i;
        }
        return -1;
    }

    // ── Type / Stage derivation ───────────────────────────────────────────────

    static String deriveType(String upper) {
        if (upper.contains("LTASM"))                                 return "LTASM";
        if (upper.contains("STASM"))                                 return "STASM";
        if (upper.contains("SHORTTERM") || upper.contains("SHORT TERM")) return "STASM";
        if (upper.contains("LONGTERM")  || upper.contains("LONG TERM"))  return "LTASM";
        if (upper.contains("GSM"))                                   return "GSM";
        if (upper.contains("ASM"))                                   return "ASM";
        return "";
    }

    static String deriveStage(String upper) {
        if (upper.contains("III") || upper.contains("STAGE 3") || upper.contains("STAGE3")) return "III";
        if (upper.contains("II")  || upper.contains("STAGE 2") || upper.contains("STAGE2")) return "II";
        if (upper.contains("I")   || upper.contains("STAGE 1") || upper.contains("STAGE1")) return "I";
        return "I";
    }
}
