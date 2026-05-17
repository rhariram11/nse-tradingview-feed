package com.nse.feed;

import com.nse.feed.model.MergedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Writes all MergedRecords to the single consolidated seed file:
 * {@code data/nse_metadata.csv}
 *
 * <h3>Why a single file (not per-symbol)</h3>
 * Per-symbol files would create 2 000+ small files — slow CI checkout,
 * expensive file-system operations, messy git history.  A single CSV is
 * read once by Pine's webhook receiver or a lookup service.
 *
 * <h3>CSV column contract (Pine reads by column name — order is the contract)</h3>
 * <pre>
 *   DATE, SYMBOL, SERIES, LAST_PRICE, LOWER_BAND, UPPER_BAND,
 *   BAND_PCT, BAND_LABEL, ASM_CODE, ASM_TYPE, ASM_STAGE, ASM_LABEL
 * </pre>
 *
 * <ul>
 *   <li>BAND_LABEL  — "20%", "5%", "No Band" (exact NSE string)</li>
 *   <li>ASM_CODE    — 0 / 11 / 12 / 13 / 21 / 22 / 23 / 30</li>
 *   <li>ASM_LABEL   — "STASM-I", "LTASM-II", "GSM", "" (empty = not in ASM)</li>
 * </ul>
 *
 * Written atomically (.tmp → rename) — never partially visible.
 */
public class SeedWriter {

    private static final Logger log = LoggerFactory.getLogger(SeedWriter.class);

    private static final String FEED_FILE     = "nse_metadata.csv";
    private static final String FEED_FILE_TMP = "nse_metadata.csv.tmp";

    private static final String HEADER =
            "DATE,SYMBOL,SERIES,LAST_PRICE,LOWER_BAND,UPPER_BAND," +
            "BAND_PCT,BAND_LABEL,ASM_CODE,ASM_TYPE,ASM_STAGE,ASM_LABEL";

    private final Path dataDir;

    public SeedWriter(String dataDir) {
        this.dataDir = Paths.get(dataDir);
    }

    /**
     * Atomically writes all records to {@code data/nse_metadata.csv}.
     * On DELTA runs the caller is responsible for merging the delta into
     * the full set before calling this method.
     */
    public void write(List<MergedRecord> records) throws IOException {
        Files.createDirectories(dataDir);
        Path tmp    = dataDir.resolve(FEED_FILE_TMP);
        Path output = dataDir.resolve(FEED_FILE);

        try (BufferedWriter bw = Files.newBufferedWriter(
                tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            bw.write(HEADER);
            bw.newLine();

            for (MergedRecord r : records) {
                bw.write(toRow(r));
                bw.newLine();
            }
        }

        Files.move(tmp, output,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        log.info("[SeedWriter] {} records → {}", records.size(), output.toAbsolutePath());
    }

    // ── Row formatting ────────────────────────────────────────────────────────

    private String toRow(MergedRecord r) {
        return String.join(",",
                r.date().toString(),
                esc(r.symbol()),
                esc(r.series()),
                fmt(r.lastPrice()),
                fmt(r.lowerBand()),
                fmt(r.upperBand()),
                fmt(r.bandPct()),
                esc(r.bandLabel()),
                String.valueOf(r.asmCode().code),
                esc(r.asmType()),
                esc(r.asmStage()),
                esc(r.asmLabel()));
    }

    /** Formats a double: whole numbers as integers, others to 2dp. */
    private String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
               ? String.valueOf((long) v)
               : String.format("%.2f", v);
    }

    /** CSV-escapes a string only when it contains commas or double-quotes. */
    private String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\""))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
