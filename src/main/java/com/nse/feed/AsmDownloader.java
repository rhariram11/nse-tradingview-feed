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

/**
 * Downloads and parses the NSE ASM surveillance list.
 *
 * <h3>Strategy (ordered by reliability)</h3>
 * <ol>
 *   <li><b>Session JSON API</b> — {@code /api/asm-securities?asmType=shortterm|longterm}<br>
 *       Parsed with Jackson ObjectMapper (replaces fragile regex scanner).</li>
 *   <li><b>REMARKS fallback</b> — derives ASM flags from the REMARKS column of
 *       eq_band_changes. Only covers stocks whose band changed that day;
 *       remaining symbols are forward-filled from the persisted asm_state.csv
 *       via {@link AsmStateStore} in Main.</li>
 * </ol>
 *
 * <h3>Stage code contract</h3>
 * All codes are canonical {@link AsmStageCode} enum values.
 * Non-overlapping ranges: STASM 11-13, LTASM 21-23, GSM 30.
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Downloads STASM + LTASM lists from the NSE session API.
     *
     * @return symbol → AsmRecord (EMPTY, never null, on any failure)
     */
    public Map<String, AsmRecord> download(LocalDate date) throws IOException {
        log.info("[ASM] Session API download for: {}", date);

        String json;
        try {
            json = client.downloadAsmJson();
        } catch (IOException e) {
            log.warn("[ASM] Session API I/O failure: {}. Using empty map.", e.getMessage());
            return Collections.emptyMap();
        }

        if (json == null || json.isBlank()) {
            log.warn("[ASM] Session API returned empty response.");
            return Collections.emptyMap();
        }

        Map<String, AsmRecord> result = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);

            // Combined wrapper: {"shortterm": [...], "longterm": [...]}
            if (root.has("shortterm") || root.has("longterm")) {
                parseHalf(root.path("shortterm"), "STASM", result);
                parseHalf(root.path("longterm"),  "LTASM", result);
            }
            // Direct array: [{...}, ...]
            else if (root.isArray()) {
                parseArray(root, "ASM", result);
            }
            // {"data": [{...}, ...]}
            else if (root.has("data")) {
                JsonNode data = root.get("data");
                if (data.isArray()) parseArray(data, "ASM", result);
            }
            // Single-key object whose value is an array
            else {
                root.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isArray()) {
                        String keyType = entry.getKey().toUpperCase().contains("LONG") ? "LTASM" : "STASM";
                        parseArray(entry.getValue(), keyType, result);
                    }
                });
            }
        } catch (Exception e) {
            log.error("[ASM] Jackson parse error: {}. Raw snippet: {}",
                    e.getMessage(), json.substring(0, Math.min(200, json.length())));
            return Collections.emptyMap();
        }

        log.info("[ASM] Session API: {} ASM symbols.", result.size());
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
            String stage = deriveStage(upper);
            result.put(symbol, AsmRecord.of(symbol, "", type, stage));
            log.info("[ASM] Derived from REMARKS: {} → {} {}", symbol, type, stage);
        }
        log.info("[ASM] REMARKS fallback: {} symbols.", result.size());
        return Collections.unmodifiableMap(result);
    }

    // ── Jackson parsing helpers ───────────────────────────────────────────────

    /**
     * Parses one half of the combined JSON response (shortterm or longterm array).
     * Handles both wrapped ({"data":[...]}) and bare array formats.
     */
    private void parseHalf(JsonNode node, String defaultType, Map<String, AsmRecord> out) {
        if (node.isMissingNode()) return;
        // Node might be array directly, or {"data":[...]}
        JsonNode arr = node.isArray() ? node
                     : node.has("data") && node.get("data").isArray() ? node.get("data")
                     : node;
        if (arr.isArray()) parseArray(arr, defaultType, out);
    }

    /**
     * Iterates a JSON array of ASM objects, extracting symbol + stage.
     * Field name variants handled: NSE's API has changed field names across years.
     */
    private void parseArray(JsonNode arr, String defaultType, Map<String, AsmRecord> out) {
        if (!arr.isArray()) return;
        for (JsonNode obj : arr) {
            String symbol = firstNonBlank(obj,
                    "symbol", "SYMBOL", "scrip", "scripCode", "SCRIP");
            if (symbol == null || symbol.isBlank()) continue;
            symbol = symbol.trim().toUpperCase();

            String isin     = firstNonBlankOrEmpty(obj, "isin", "ISIN");
            String rawType  = firstNonBlankOrEmpty(obj,
                    "asmType", "asmtype", "surveillanceType", "type", "TYPE");
            String rawStage = firstNonBlankOrEmpty(obj,
                    "asmStage", "asmstage", "stage", "STAGE", "surveillanceStage");

            // Derive type from field content when explicit field is absent
            if (rawType.isBlank())  rawType  = defaultType;
            if (rawStage.isBlank()) rawStage = deriveStage(obj.toString().toUpperCase());

            out.put(symbol, AsmRecord.of(symbol, isin, rawType, rawStage));
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

    // ── Type / Stage derivation from text ─────────────────────────────────────

    static String deriveType(String upper) {
        if (upper.contains("LTASM"))                                      return "LTASM";
        if (upper.contains("STASM"))                                      return "STASM";
        if (upper.contains("SHORTTERM") || upper.contains("SHORT TERM")) return "STASM";
        if (upper.contains("LONGTERM")  || upper.contains("LONG TERM"))  return "LTASM";
        if (upper.contains("GSM"))                                        return "GSM";
        if (upper.contains("ASM"))                                        return "STASM";
        return "";
    }

    static String deriveStage(String upper) {
        if (upper.contains("III") || upper.contains("STAGE 3") || upper.contains("STAGE3")) return "III";
        if (upper.contains("II")  || upper.contains("STAGE 2") || upper.contains("STAGE2")) return "II";
        if (upper.contains("I")   || upper.contains("STAGE 1") || upper.contains("STAGE1")) return "I";
        return "I";
    }
}
