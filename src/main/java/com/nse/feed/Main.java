package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.MergedRecord;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Entry point for the NSE → TradingView seed ETL.
 *
 * <h3>Data sources</h3>
 * <ul>
 *   <li>Full band master : nsearchives.nseindia.com/content/equities/sec_list_{ddmmyyyy}.csv</li>
 *   <li>Delta changes    : nsearchives.nseindia.com/content/equities/eq_band_changes_{ddmmyyyy}.csv</li>
 *   <li>ASM (primary)   : nseindia.com/api/asm-securities?asmType=shortterm|longterm</li>
 *   <li>ASM (fallback)  : REMARKS column + persisted data/asm_state.csv</li>
 * </ul>
 *
 * <h3>Run modes</h3>
 * COLD-START (Monday / first run / --force-full) or DELTA (Tue–Fri).
 *
 * <h3>CLI</h3>
 * {@code --force-full}  {@code --date YYYY-MM-DD}
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String DATA_DIR      = "data";
    private static final String SENTINEL_FILE = DATA_DIR + "/cold_start.done";

    public static void main(String[] args) throws Exception {
        log.info("=====================================================");
        log.info(" NSE TradingView Feed ETL — started");
        log.info("=====================================================");

        List<String> argList   = Arrays.asList(args);
        boolean      forceFull = argList.contains("--force-full");
        LocalDate    tradeDate = parseDate(argList);

        log.info("Trade date : {}", tradeDate);
        log.info("Day of week: {}", tradeDate.getDayOfWeek());
        log.info("Force-full : {}", forceFull);

        if (NseClient.isWeekend(tradeDate) && !forceFull) {
            log.info("Weekend — NSE closed. Exiting.");
            return;
        }

        boolean coldStart = forceFull
                || !sentinelExists()
                || tradeDate.getDayOfWeek() == DayOfWeek.MONDAY;

        log.info("Run mode   : {}", coldStart ? "COLD-START" : "DELTA");

        try (NseClient client = new NseClient()) {

            DataMerger    merger   = new DataMerger();
            SeedWriter    writer   = new SeedWriter(DATA_DIR);
            AsmStateStore asmStore = new AsmStateStore(DATA_DIR);
            AsmDownloader asmDl    = new AsmDownloader(client);

            // Previous day's ASM state for forward-fill
            Map<String, AsmRecord> prevAsmMap = coldStart
                    ? Collections.emptyMap() : asmStore.load();

            if (coldStart) {

                PriceBandDownloader pbDl = new PriceBandDownloader(client);
                List<PriceBandRecord> priceBands = pbDl.downloadFull(tradeDate);
                if (priceBands.isEmpty()) {
                    log.warn("sec_list empty — public holiday? Exiting.");
                    return;
                }
                log.info("Price band records: {}", priceBands.size());

                Map<String, AsmRecord> asmMap = downloadAsmSafe(asmDl, null, tradeDate);
                log.info("ASM records: {}", asmMap.size());
                asmStore.save(asmMap);

                writer.write(merger.mergeFull(priceBands, asmMap));
                writeSentinel(tradeDate);

            } else {

                DeltaBandUpdater delta = new DeltaBandUpdater(client);
                Map<String, PriceBandRecord> deltaMap = delta.downloadAndParse(tradeDate);
                log.info("Band changes: {}", deltaMap.size());

                Map<String, AsmRecord> freshAsmMap = downloadAsmSafe(asmDl, deltaMap, tradeDate);

                // Forward-fill: if session API returned nothing, keep previous state
                Map<String, AsmRecord> effectiveAsm =
                        freshAsmMap.isEmpty() ? prevAsmMap : freshAsmMap;
                log.info("Effective ASM (after forward-fill): {}", effectiveAsm.size());

                asmStore.save(effectiveAsm);

                if (!deltaMap.isEmpty() || !effectiveAsm.isEmpty()) {
                    writer.write(merger.mergeDelta(deltaMap, effectiveAsm, prevAsmMap));
                } else {
                    log.info("No changes — nothing to write.");
                }
            }
        }

        log.info("=====================================================");
        log.info(" ETL complete.");
        log.info("=====================================================");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, AsmRecord> downloadAsmSafe(
            AsmDownloader asmDl,
            Map<String, PriceBandRecord> deltaMap,
            LocalDate date) {
        Map<String, AsmRecord> map;
        try { map = asmDl.download(date); }
        catch (IOException e) {
            log.warn("[ASM] Download error: {}. Using empty.", e.getMessage());
            map = Collections.emptyMap();
        }
        if (map.isEmpty() && deltaMap != null && !deltaMap.isEmpty()) {
            log.info("[ASM] Supplementing with REMARKS fallback.");
            map = asmDl.deriveFromRemarks(deltaMap);
        }
        return map;
    }

    private static boolean sentinelExists() {
        return Files.exists(Paths.get(SENTINEL_FILE));
    }

    private static void writeSentinel(LocalDate date) throws IOException {
        Path p = Paths.get(SENTINEL_FILE);
        Files.createDirectories(p.getParent());
        Files.writeString(p,
            "Last full extract: " + date + "\n" +
            "Next full extract: next Monday or --force-full\n",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static LocalDate parseDate(List<String> args) {
        int idx = args.indexOf("--date");
        if (idx >= 0 && idx + 1 < args.size()) {
            try { return LocalDate.parse(args.get(idx + 1)); }
            catch (Exception e) { log.warn("Bad --date — using today IST."); }
        }
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }
}
