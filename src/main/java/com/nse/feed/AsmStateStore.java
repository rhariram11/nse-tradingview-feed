package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.AsmStageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists the full daily ASM state to {@code data/asm_state.csv}.
 *
 * <p>Stocks under long-term ASM surveillance may not change their circuit band
 * for weeks.  Without this store, the REMARKS fallback cannot see them on days
 * the NSE session API is blocked, causing silent code-0 downgrades in seed files.
 *
 * <h3>File format — {@code data/asm_state.csv}</h3>
 * <pre>
 *   symbol,isin,type,stage,stageCode
 *   AARTIIND,,STASM,I,11
 *   RELIANCE,,LTASM,II,22
 * </pre>
 */
public class AsmStateStore {

    private static final Logger log = LoggerFactory.getLogger(AsmStateStore.class);
    private static final String FILENAME = "asm_state.csv";
    private static final String HEADER   = "symbol,isin,type,stage,stageCode";

    private final Path file;

    public AsmStateStore(String dataDir) {
        this.file = Paths.get(dataDir, FILENAME);
    }

    /** Loads persisted ASM state. Returns EMPTY map if file does not exist. */
    public Map<String, AsmRecord> load() {
        if (!Files.exists(file)) {
            log.info("[AsmState] No asm_state.csv — first run.");
            return Collections.emptyMap();
        }
        Map<String, AsmRecord> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file)) {
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
                int code;
                try { code = Integer.parseInt(p[4].trim()); }
                catch (NumberFormatException e) { code = AsmStageCode.encode(type, stage); }
                map.put(symbol, new AsmRecord(symbol, isin, type, stage, code));
            }
            log.info("[AsmState] Loaded {} ASM symbols.", map.size());
        } catch (IOException e) {
            log.warn("[AsmState] Load failed: {}. Using empty map.", e.getMessage());
        }
        return Collections.unmodifiableMap(map);
    }

    /** Saves the full current ASM map (overwrites previous snapshot). */
    public void save(Map<String, AsmRecord> asmMap) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(HEADER); bw.newLine();
            for (AsmRecord r : asmMap.values()) {
                bw.write(String.join(",",
                    r.symbol(),
                    r.isin()  != null ? r.isin()  : "",
                    r.type()  != null ? r.type()  : "",
                    r.stage() != null ? r.stage() : "",
                    String.valueOf(r.stageCode())));
                bw.newLine();
            }
        }
        log.info("[AsmState] Saved {} ASM symbols.", asmMap.size());
    }
}
