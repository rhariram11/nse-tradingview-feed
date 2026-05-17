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
 * Downloads and parses the NSE ASM / LTASM / STASM surveillance report.
 *
 * NSE source: https://www.nseindia.com/reports/asm
 *
 * Verified API (May 2026):
 *   /api/reports?archives=[{"name":"Additional Surveillance Measure (ASM)",
 *                           "type":"daily","category":"surveillance","section":"equity"}]
 *               &date=DD-MMM-YYYY&type=daily&mode=single
 * Returns: asm_DDMMYYYY.csv
 *
 * CSV columns:
 *   SR NO, SYMBOL, SECURITY NAME, ISIN, SERIES, ASM STAGE / LTASM STAGE / STASM STAGE
 *
 * ASM stage codes emitted to seed:
 *   0  = Not under surveillance
 *   11 = STASM Stage I
 *   12 = STASM Stage II
 *   13 = LTASM Stage I
 *   14 = LTASM Stage II
 *   15 = LTASM Stage III
 *   20 = GSM (Graded Surveillance Measure)
 *
 * ASM is always downloaded fresh (full list) regardless of cold-start / delta
 * mode because the list can change significantly day to day.
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Downloads and parses the ASM list for the given trade date.
     * Always a full refresh (not delta) — ASM list is small (~100-300 rows).
     *
     * @param date trade date
     * @return symbol → AsmRecord map for all symbols currently under surveillance
     */
    public Map<String, AsmRecord> download(LocalDate date) throws IOException {
        log.info("[ASM] Downloading surveillance list for date: {}", date);
        byte[] raw = client.downloadAsmCsv(date);
        String csv = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();

        if (csv.isEmpty() || csv.startsWith("<")) {
            log.warn("[ASM] Empty or HTML response for date {}. Using empty ASM map.", date);
            return new HashMap<>();
        }

        Map<String, AsmRecord> result = parseCsv(csv);
        log.info("[ASM] Parsed {} ASM/surveillance records.", result.size());
        return result;
    }

    // ── CSV Parser ───────────────────────────────────────────────────────────

    private Map<String, AsmRecord> parseCsv(String csv) throws IOException {
        Map<String, AsmRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            int idxSymbol = findCol(header, "SYMBOL");
            int idxIsin   = findCol(header, "ISIN");
            int idxStage  = findCol(header,
                    "ASM STAGE", "LTASM STAGE", "STASM STAGE", "STAGE", "ASMSTAGE", "ASM_STAGE");
            int idxType   = findCol(header, "ASM TYPE", "TYPE", "ASM_TYPE", "SURVEILLANCE TYPE");
            int idxSeries = findCol(header, "SERIES");

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
                    log.debug("[ASM] Skipping malformed row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("[ASM] CSV parse error: " + e.getMessage(), e);
        }
        return map;
    }

    // ── Stage code mapping ───────────────────────────────────────────────────

    static int toStageCode(String type, String stage) {
        int stageNum = extractStageNumber(stage);
        if (type.contains("LTASM") || type.contains("LONG")) {
            return 12 + stageNum; // 13, 14, 15
        } else if (type.contains("STASM") || type.contains("SHORT")) {
            return 10 + stageNum; // 11, 12
        } else if (type.contains("GSM")) {
            return 20;
        } else if (type.contains("ASM")) {
            return stageNum > 0 ? (10 + stageNum) : 11;
        }
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
}
