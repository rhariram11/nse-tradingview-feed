package com.nse.feed;

import com.nse.feed.model.AsmRecord;
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
 * Downloads and parses the NSE ASM / LTASM / STASM surveillance report.
 *
 * Always a full refresh (not delta) — ASM list is small (~100-300 rows).
 *
 * Returns an EMPTY map (not an exception) when:
 *   - NSE returns 404 (non-trading day / public holiday)
 *   - Response is empty or malformed
 * Main handles empty ASM map gracefully.
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads and parses the ASM list for the given trade date.
     *
     * @param date trade date
     * @return symbol → AsmRecord map; EMPTY map if not a trading day / NSE 404
     */
    public Map<String, AsmRecord> download(LocalDate date) throws IOException {
        log.info("[ASM] Downloading surveillance list for: {}", date);

        // NseClient.downloadAsmCsv() already tries primary + fallback archive names
        // and returns null on 404 (non-trading day)
        byte[] raw = client.downloadAsmCsv(date);

        if (raw == null) {
            log.warn("[ASM] No data for {} — not a trading day or NSE holiday. Returning empty map.", date);
            return new HashMap<>();
        }

        String csv = new String(raw, StandardCharsets.UTF_8).trim();
        if (csv.isEmpty() || csv.startsWith("<")) {
            log.warn("[ASM] Empty or HTML response for {}. Returning empty map.", date);
            return new HashMap<>();
        }

        Map<String, AsmRecord> result = parseCsv(csv);
        log.info("[ASM] Parsed {} ASM/surveillance records.", result.size());
        return result;
    }

    // ── CSV Parser ────────────────────────────────────────────────────────────

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

    // ── Stage code mapping ────────────────────────────────────────────────────

    static int toStageCode(String type, String stage) {
        int n = extractStageNumber(stage);
        if (type.contains("LTASM") || type.contains("LONG"))  return 12 + n; // 13,14,15
        if (type.contains("STASM") || type.contains("SHORT")) return 10 + n; // 11,12
        if (type.contains("GSM"))                             return 20;
        if (type.contains("ASM"))                             return n > 0 ? (10 + n) : 11;
        return 0;
    }

    private static int extractStageNumber(String stage) {
        stage = stage.trim().toUpperCase();
        if (stage.contains("III") || stage.equals("3")) return 3;
        if (stage.contains("II")  || stage.equals("2")) return 2;
        if (stage.contains("I")   || stage.equals("1")) return 1;
        return 1;
    }

    private static String deriveType(String stage) {
        String u = stage.toUpperCase();
        if (u.contains("LTASM")) return "LTASM";
        if (u.contains("STASM")) return "STASM";
        if (u.contains("GSM"))   return "GSM";
        return "ASM";
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
