package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Map;

/**
 * Writes the complete ASM list snapshot to {@code data/nse_asm_full.csv}.
 *
 * <h3>Purpose</h3>
 * Audit / debug: compare this file against the NSE surveillance page or
 * nse_metadata.csv to verify that no ASM flag was silently dropped.
 *
 * <h3>File format (updated to include all reportASM fields)</h3>
 * <pre>
 *   DATE,SYMBOL,COMPANY_NAME,ISIN,TYPE,STAGE,STAGE_CODE,LABEL,AS_OF_DATE,CIRC_REVISION
 *   2026-05-15,21STCENMGM,21st Century Management Services Limited,INE253B01015,LTASM,I,21,LTASM-I,15-May-2026,13
 *   2026-05-15,63MOONS,63 moons technologies limited,INE111B01023,STASM,I,11,STASM-I,15-May-2026,11
 * </pre>
 *
 * <h3>Column notes</h3>
 * <ul>
 *   <li>{@code STAGE_CODE}     — Integer for Pine Script: 11-14=STASM, 21-24=LTASM, 30=GSM
 *   <li>{@code CIRC_REVISION}  — NSE's internal list revision counter from survCode brackets
 *                                (e.g. 13 from "LTASM - I (13)"). Not a stage number.
 *   <li>{@code AS_OF_DATE}     — Effective date string from NSE's asmTime field
 * </ul>
 *
 * Written atomically (.tmp → rename) so the file is never partially visible.
 */
public class AsmFullWriter {

    private static final Logger log = LoggerFactory.getLogger(AsmFullWriter.class);
    private static final String FILENAME     = "nse_asm_full.csv";
    private static final String FILENAME_TMP = "nse_asm_full.csv.tmp";
    private static final String HEADER =
            "DATE,SYMBOL,COMPANY_NAME,ISIN,TYPE,STAGE,STAGE_CODE,LABEL,AS_OF_DATE,CIRC_REVISION";

    private final Path dataDir;

    public AsmFullWriter(String dataDir) {
        this.dataDir = Paths.get(dataDir);
    }

    /**
     * Writes the full ASM snapshot for the given run-date.
     * Rows are sorted by STAGE_CODE (ascending) then SYMBOL.
     */
    public void write(LocalDate date, Map<String, AsmRecord> asmMap) throws IOException {
        Files.createDirectories(dataDir);
        Path tmp    = dataDir.resolve(FILENAME_TMP);
        Path final_ = dataDir.resolve(FILENAME);

        try (BufferedWriter bw = Files.newBufferedWriter(
                tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            bw.write(HEADER);
            bw.newLine();

            asmMap.values().stream()
                  .sorted((a, b) -> {
                      int cmp = Integer.compare(a.stageCode().code, b.stageCode().code);
                      return cmp != 0 ? cmp : a.symbol().compareTo(b.symbol());
                  })
                  .forEach(r -> {
                      try {
                          bw.write(String.join(",",
                                  date.toString(),
                                  safe(r.symbol()),
                                  safeCsv(r.companyName()),   // quoted — may contain commas
                                  safe(r.isin()),
                                  safe(r.type()),
                                  safe(r.stage()),
                                  String.valueOf(r.stageCode().code),
                                  safe(r.stageCode().label()),
                                  safe(r.asOfDate()),
                                  String.valueOf(r.circRevision())));
                          bw.newLine();
                      } catch (IOException e) {
                          throw new RuntimeException(e);
                      }
                  });
        }

        Files.move(tmp, final_,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        log.info("[AsmFullWriter] {} ASM records → {}", asmMap.size(), final_.toAbsolutePath());
    }

    /** Trims and returns empty string for null. */
    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * CSV-safe: wraps value in double quotes and escapes internal quotes.
     * Used for companyName which may contain commas (e.g. "Jain Irrigation Systems, Ltd.").
     */
    private String safeCsv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.trim().replace("\"", "\"\"") + "\"";
    }
}
