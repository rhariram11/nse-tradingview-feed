package com.nse.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.AsmStageCode;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads and parses the NSE ASM surveillance list from {@code /api/reportASM}.
 *
 * <h3>Actual JSON structure (verified 2026-05-17)</h3>
 * <pre>
 * {
 *   "longterm": {
 *     "data": [
 *       {
 *         "symbol":           "21STCENMGM",
 *         "series":           null,
 *         "companyName":      "21st Century Management Services Limited",
 *         "isin":             "INE253B01015",
 *         "asmSurvIndicator": "Stage I",       ← stage text
 *         "asmTime":          "15-May-2026",   ← effective date
 *         "survCode":         "LTASM - I (13)", ← (13) = NSE list revision #
 *         "survDesc":         "Long Term Additional Surveillance Measure (LTASM) - Stage I",
 *         "srno":             1
 *       }, ...
 *     ]
 *   },
 *   "shortterm": {
 *     "data": [
 *       {
 *         "symbol":           "63MOONS",
 *         "asmSurvIndicator": "Stage I",
 *         "asmTime":          "15-May-2026",
 *         "survCode":         "STASM - I (11)", ← (11) = NSE list revision #
 *         ...
 *       }, ...
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h3>survCode bracket number significance</h3>
 * The number in brackets (e.g. 13 in "LTASM - I (13)") is NSE's internal
 * list revision counter — it increments each time NSE updates/republishes that
 * surveillance list. It is NOT a stage number. It is captured as
 * {@code circRevision} for audit/tracking but has no trading significance.
 *
 * <h3>Fallback strategy</h3>
 * If the API call fails, {@link #deriveFromRemarks} derives ASM flags from
 * the REMARKS column of eq_band_changes. This only covers stocks whose band
 * changed that day; remaining symbols must be forward-filled from asm_state.csv
 * via {@link AsmStateStore} in Main.
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Extracts the revision number from survCode, e.g. "LTASM - I (13)" → 13 */
    private static final Pattern CIRC_REVISION_PATTERN = Pattern.compile("\\((\\d+)\\)");

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Downloads STASM + LTASM lists from {@code /api/reportASM}.
     *
     * @return symbol → AsmRecord (EMPTY map, never null, on any failure)
     */
    public Map<String, AsmRecord> download(LocalDate date) throws IOException {
        log.info("[ASM] Fetching /api/reportASM for: {}", date);

        String json;
        try {
            json = client.downloadAsmJson();
        } catch (IOException e) {
            log.warn("[ASM] API I/O failure: {}. Using empty map.", e.getMessage());
            return Collections.emptyMap();
        }

        if (json == null || json.isBlank()) {
            log.warn("[ASM] /api/reportASM returned empty response.");
            return Collections.emptyMap();
        }

        Map<String, AsmRecord> result = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);

            // Primary path: combined {"longterm":{"data":[...]}, "shortterm":{"data":[...]}}
            if (root.has("longterm") || root.has("shortterm")) {
                parseSection(root.path("longterm"),  "LTASM", result);
                parseSection(root.path("shortterm"), "STASM", result);
            }
            // Direct array fallback
            else if (root.isArray()) {
                parseArray(root, "ASM", result);
            }
            // {"data": [...]} wrapper fallback
            else if (root.has("data") && root.get("data").isArray()) {
                parseArray(root.get("data"), "ASM", result);
            }
            // Unknown structure — iterate top-level keys that hold arrays
            else {
                root.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isArray()) {
                        String keyType = entry.getKey().toUpperCase().contains("LONG") ? "LTASM" : "STASM";
                        parseArray(entry.getValue(), keyType, result);
                    }
                });
            }
        } catch (Exception e) {
            log.error("[ASM] Parse error: {}. Raw snippet: {}",
                    e.getMessage(), json.substring(0, Math.min(300, json.length())));
            return Collections.emptyMap();
        }

        log.info("[ASM] Parsed {} ASM symbols (LT+ST combined).", result.size());
        return Collections.unmodifiableMap(result);
    }

    /**
     * Derives ASM flags from REMARKS text in the eq_band_changes delta file.
     * Only covers stocks whose band changed that day — all other symbols
     * must be forward-filled from asm_state.csv (handled in Main).
     */
    public Map<String, AsmRecord> deriveFromRemarks(Map<String, PriceBandRecord> deltaMap) {
        Map<String, AsmRecord> result = new HashMap<>();
        for (Map.Entry<String, PriceBandRecord> entry : deltaMap.entrySet()) {
            String symbol  = entry.getKey();
            String remarks = entry.getValue().getRemarks();
            if (remarks == null || !remarks.toUpperCase().contains("ASM")) continue;
            String upper = remarks.toUpperCase();
            String type  = deriveType(upper);
            String stage = deriveStageFromText(upper);
            result.put(symbol, AsmRecord.of(symbol, "", "", type, stage, "", 0));
            log.info("[ASM] Derived from REMARKS: {} → {} {}", symbol, type, stage);
        }
        log.info("[ASM] REMARKS fallback: {} symbols.", result.size());
        return Collections.unmodifiableMap(result);
    }

    // ── Jackson parsing helpers ───────────────────────────────────────────────

    /**
     * Parses one section of the reportASM response.
     * Handles both {"data":[...]} wrapper and bare array formats.
     */
    private void parseSection(JsonNode sectionNode, String defaultType, Map<String, AsmRecord> out) {
        if (sectionNode == null || sectionNode.isMissingNode()) return;
        // Section is {"data":[...]} — primary path from reportASM
        if (sectionNode.has("data") && sectionNode.get("data").isArray()) {
            parseArray(sectionNode.get("data"), defaultType, out);
        }
        // Bare array fallback
        else if (sectionNode.isArray()) {
            parseArray(sectionNode, defaultType, out);
        }
    }

    /**
     * Iterates a JSON array of ASM objects, extracting all fields from the
     * actual /api/reportASM response structure.
     *
     * <p>Field mapping from reportASM response:
     * <ul>
     *   <li>{@code symbol}           → AsmRecord.symbol
     *   <li>{@code companyName}      → AsmRecord.companyName
     *   <li>{@code isin}             → AsmRecord.isin
     *   <li>{@code asmSurvIndicator} → stage text ("Stage I" … "Stage IV")
     *   <li>{@code asmTime}          → asOfDate ("15-May-2026")
     *   <li>{@code survCode}         → survCode ("LTASM - I (13)")
     *   <li>extracted from survCode  → circRevision (13)
     * </ul>
     */
    private void parseArray(JsonNode arr, String defaultType, Map<String, AsmRecord> out) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode obj : arr) {
            if (!obj.isObject()) continue;

            // Symbol — try multiple field name variants
            String symbol = firstNonBlank(obj, "symbol", "SYMBOL", "scrip", "scripCode", "SCRIP");
            if (symbol == null || symbol.isBlank()) continue;
            symbol = symbol.trim().toUpperCase();

            // Core fields from actual reportASM response
            String companyName = firstNonBlankOrEmpty(obj, "companyName", "company_name", "name");
            String isin        = firstNonBlankOrEmpty(obj, "isin", "ISIN");
            String asOfDate    = firstNonBlankOrEmpty(obj, "asmTime", "asmtime", "date");
            String survCode    = firstNonBlankOrEmpty(obj, "survCode", "survcode", "surveillance_code");

            // Stage: asmSurvIndicator holds "Stage I", "Stage II" etc.
            String stageRaw    = firstNonBlankOrEmpty(obj,
                    "asmSurvIndicator", "asmSurvindicator",
                    "asmStage", "asmstage", "stage", "STAGE",
                    "surveillanceStage");

            // Type: if not explicit, use the section's defaultType (LTASM / STASM)
            String typeRaw = firstNonBlankOrEmpty(obj,
                    "asmType", "asmtype", "surveillanceType", "type", "TYPE");
            if (typeRaw.isBlank()) typeRaw = defaultType;

            // Derive canonical stage string from indicator text or survCode
            String stage = deriveStageFromText(stageRaw.toUpperCase());
            if (stage.isBlank() && !survCode.isBlank()) {
                stage = deriveStageFromText(survCode.toUpperCase());
            }
            if (stage.isBlank()) stage = "I";  // safe default

            // Extract list revision number from survCode brackets: "LTASM - I (13)" → 13
            int circRevision = extractCircRevision(survCode);

            out.put(symbol, AsmRecord.of(symbol, companyName, isin, typeRaw, stage, asOfDate, circRevision));
        }
    }

    // ── Field extraction helpers ──────────────────────────────────────────────

    private String firstNonBlank(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode n = obj.get(k);
            if (n != null && !n.isNull() && !n.asText().isBlank()) return n.asText();
        }
        return null;
    }

    private String firstNonBlankOrEmpty(JsonNode obj, String... keys) {
        String v = firstNonBlank(obj, keys);
        return v == null ? "" : v;
    }

    /**
     * Extracts revision number from survCode brackets.
     * "LTASM - I (13)" → 13
     * "STASM - I (11)" → 11
     * ""               → 0
     */
    static int extractCircRevision(String survCode) {
        if (survCode == null || survCode.isBlank()) return 0;
        Matcher m = CIRC_REVISION_PATTERN.matcher(survCode);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ── Type / Stage derivation from text ─────────────────────────────────────

    static String deriveType(String upper) {
        if (upper.contains("LTASM"))                                      return "LTASM";
        if (upper.contains("STASM"))                                      return "STASM";
        if (upper.contains("SHORTTERM") || upper.contains("SHORT TERM") ||
            upper.contains("SHORT-TERM"))                                 return "STASM";
        if (upper.contains("LONGTERM")  || upper.contains("LONG TERM")  ||
            upper.contains("LONG-TERM"))                                  return "LTASM";
        if (upper.contains("GSM"))                                        return "GSM";
        if (upper.contains("ASM"))                                        return "STASM";
        return "";
    }

    /**
     * Derives canonical stage string ("I", "II", "III", "IV") from any text
     * that may contain "Stage I", "Stage 1", "STAGE III", roman numerals, etc.
     */
    static String deriveStageFromText(String upper) {
        if (upper == null) return "I";
        // Check IV before III/II to avoid partial matches
        if (upper.contains("IV")    || upper.contains("STAGE 4") || upper.contains("STAGE4")) return "IV";
        if (upper.contains("III")   || upper.contains("STAGE 3") || upper.contains("STAGE3")) return "III";
        if (upper.contains(" II ")  || upper.contains("STAGE 2") || upper.contains("STAGE2") ||
            upper.endsWith(" II")   || upper.contains("- II"))                               return "II";
        if (upper.contains(" I ")   || upper.contains("STAGE 1") || upper.contains("STAGE1") ||
            upper.endsWith(" I")    || upper.contains("- I"))                                return "I";
        return "I";  // default
    }
}
