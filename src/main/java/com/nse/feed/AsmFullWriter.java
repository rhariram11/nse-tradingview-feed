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
 * <h3>File format</h3>
 * <pre>
 *   DATE,SYMBOL,ISIN,TYPE,STAGE,STAGE_CODE,LABEL
 *   2026-05-19,AARTIIND,,STASM,I,11,STASM-I
 *   2026-05-19,YESBANK,,LTASM,II,22,LTASM-II
 * </pre>
 *
 * Written atomically (.tmp → rename) so the file is never partially visible.
 */
public class AsmFullWriter {

    private static final Logger log = LoggerFactory.getLogger(AsmFullWriter.class);
    private static final String FILENAME     = "nse_asm_full.csv";
    private static final String FILENAME_TMP = "nse_asm_full.csv.tmp";
    private static final String HEADER = "DATE,SYMBOL,ISIN,TYPE,STAGE,STAGE_CODE,LABEL";

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
        Path tmp   = dataDir.resolve(FILENAME_TMP);
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
                                  safe(r.isin()),
                                  safe(r.type()),
                                  safe(r.stage()),
                                  String.valueOf(r.stageCode().code),
                                  safe(r.stageCode().label())));
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

    private String safe(String s) { return s == null ? "" : s.trim(); }
}
