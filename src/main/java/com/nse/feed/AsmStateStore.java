package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.AsmStageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists the full daily ASM state to {@code data/asm_state.csv}.
 *
 * <h3>Why this exists</h3>
 * NSE's session API is occasionally blocked (rate-limit, maintenance, weekends).
 * Without this store, symbols under long-term ASM would silently lose their
 * flags on days the API is unavailable.  This file is loaded on every DELTA run
 * and used as a forward-fill base in Main — ensuring every symbol carries the
 * correct ASM code from the last successful API response.
 *
 * <h3>File format — {@code data/asm_state.csv}</h3>
 * <pre>
 *   symbol,isin,type,stage,stageCode
 *   AARTIIND,,STASM,I,11
 *   RELIANCE,,LTASM,II,22
 * </pre>
 *
 * Written atomically (.tmp → rename).
 */
public class AsmStateStore {

    private static final Logger log = LoggerFactory.getLogger(AsmStateStore.class);
    private static final String FILENAME     = "asm_state.csv";
    private static final String FILENAME_TMP = "asm_state.csv.tmp";
    private static final String HEADER       = "symbol,isin,type,stage,stageCode";

    private final Path file;
    private final Path tmpFile;

    public AsmStateStore(String dataDir) {
        this.file    = Paths.get(dataDir, FILENAME);
        this.tmpFile = Paths.get(dataDir, FILENAME_TMP);
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    /** Loads persisted ASM state. Returns EMPTY (never null) if file absent. */
    public Map<String, AsmRecord> load() {
        if (!Files.exists(file)) {
            log.info("[AsmState] No asm_state.csv — first run or cold start.");
            return Collections.emptyMap();
        }
        Map<String, AsmRecord> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length < 5) continue;
                String symbol = p[0].trim().toUpperCase();
                String isin   = p[1].trim();
                String type   = p[2].trim();
                String stage  = p[3].trim();
                AsmStageCode code;
                try {
                    int raw = Integer.parseInt(p[4].trim());
                    code = fromCode(raw);
                } catch (NumberFormatException e) {
                    code = AsmStageCode.encode(type, stage);
                }
                map.put(symbol, new AsmRecord(symbol, isin, type, stage, code));
            }
        } catch (IOException e) {
            log.warn("[AsmState] Load failed: {}. Using empty map.", e.getMessage());
            return Collections.emptyMap();
        }
        log.info("[AsmState] Loaded {} ASM symbols from state store.", map.size());
        return Collections.unmodifiableMap(map);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Saves the full current ASM map atomically (.tmp → rename).
     * Never leaves the state file in a partially written state.
     */
    public void save(Map<String, AsmRecord> asmMap) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(
                tmpFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(HEADER);
            bw.newLine();
            for (AsmRecord r : asmMap.values()) {
                bw.write(String.join(",",
                        safe(r.symbol()),
                        safe(r.isin()),
                        safe(r.type()),
                        safe(r.stage()),
                        String.valueOf(r.stageCode().code)));
                bw.newLine();
            }
        }
        Files.move(tmpFile, file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        log.info("[AsmState] Saved {} ASM symbols.", asmMap.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AsmStageCode fromCode(int raw) {
        for (AsmStageCode c : AsmStageCode.values())
            if (c.code == raw) return c;
        return AsmStageCode.NONE;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
