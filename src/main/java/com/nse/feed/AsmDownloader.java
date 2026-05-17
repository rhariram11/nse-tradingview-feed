package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads and parses the NSE ASM / LTASM / STASM surveillance report.
 *
 * NSE publishes the list at:  https://www.nseindia.com/reports/asm
 * The CSV download URL follows the pattern:
 *   https://www.nseindia.com/api/reportfiles?archives=...&type=daily
 *
 * Typical CSV columns:
 *   SR NO, SYMBOL, SECURITY NAME, ISIN, SERIES, ASM STAGE / LTASM STAGE / STASM STAGE
 *
 * ASM stage codes used internally:
 *   0  = Not under ASM
 *   11 = STASM Stage I
 *   12 = STASM Stage II
 *   13 = LTASM Stage I
 *   14 = LTASM Stage II
 *   15 = LTASM Stage III
 */
public class AsmDownloader {

    private static final Logger log = LoggerFactory.getLogger(AsmDownloader.class);

    // NSE ASM CSV download API (returns current day's list)
    private static final String ASM_API =
            "https://www.nseindia.com/api/reportfiles?archives=%5B%7B%22name%22%3A%22ASM%22%2C%22type%22%3A%22daily%22%2C%22category%22%3A%22surveillance%22%2C%22section%22%3A%22equity%22%7D%5D&type=daily&mode=single";

    private final NseClient client;

    public AsmDownloader(NseClient client) {
        this.client = client;
    }

    /**
     * Returns a map of NSE symbol → AsmRecord for all symbols currently under surveillance.
     */
    public Map<String, AsmRecord> download() throws IOException {
        log.info("Downloading ASM/LTASM/STASM data...");
        byte[] raw = client.get(ASM_API);
        return parseCsv(new String(raw));
    }

    private Map<String, AsmRecord> parseCsv(String csv) throws IOException {
        Map<String, AsmRecord> map = new HashMap<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext();
            if (header == null) return map;

            int idxSymbol = findCol(header, "SYMBOL");
            int idxIsin   = findCol(header, "ISIN");
            int idxStage  = findCol(header, "ASM STAGE", "LTASM STAGE", "STASM STAGE", "STAGE", "ASMSTAGE");
            int idxType   = findCol(header, "ASM TYPE", "TYPE", "ASM_TYPE");
            int idxSeries = findCol(header, "SERIES");

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol = clean(row[idxSymbol]);
                    if (symbol.isEmpty()) continue;
                    String isin   = idxIsin >= 0   ? clean(row[idxIsin])  : "";
                    String stage  = idxStage >= 0  ? clean(row[idxStage]) : "";
                    String type   = idxType >= 0   ? clean(row[idxType])  : deriveType(stage);
                    int    code   = toStageCode(type, stage);
                    map.put(symbol, new AsmRecord(symbol, isin, type, stage, code));
                } catch (Exception e) {
                    log.debug("Skipping ASM row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("ASM CSV parse error: " + e.getMessage(), e);
        }
        log.info("Parsed {} ASM records.", map.size());
        return map;
    }

    // ── Stage code mapping ───────────────────────────────────────────────────

    /**
     * Converts type + stage text into a compact integer code for TradingView seed.
     * Encoding:
     *   0  = none
     *   11 = STASM Stage I
     *   12 = STASM Stage II
     *   13 = LTASM Stage I
     *   14 = LTASM Stage II
     *   15 = LTASM Stage III
     *   20 = GSM (Graded Surveillance; if encountered)
     */
    static int toStageCode(String type, String stage) {
        int stageNum = extractStageNumber(stage);
        if (type.contains("LTASM") || type.contains("LONG")) {
            return 12 + stageNum; // 13, 14, 15
        } else if (type.contains("STASM") || type.contains("SHORT")) {
            return 10 + stageNum; // 11, 12
        } else if (type.contains("GSM")) {
            return 20;
        } else if (type.contains("ASM")) {
            // Generic ASM — map by stage number
            return stageNum > 0 ? (10 + stageNum) : 11;
        }
        return 0;
    }

    private static int extractStageNumber(String stage) {
        // Stage text like "I", "II", "III", "1", "2", "3"
        stage = stage.trim().toUpperCase();
        if (stage.contains("III") || stage.equals("3")) return 3;
        if (stage.contains("II")  || stage.equals("2")) return 2;
        if (stage.contains("I")   || stage.equals("1")) return 1;
        return 1; // default
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
