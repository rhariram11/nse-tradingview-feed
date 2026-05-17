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
 *   <li>ASM (fallback)  : REMARKS column in eq_band_changes + persisted data/asm_state.csv</li>
 * </ul>
 *
 * <h3>Run modes</h3>
 * <ul>
 *   <li>COLD-START: Monday / first run / {@code --force-full}<br>
 *       Downloads full sec_list + full ASM. Writes complete nse_metadata.csv.</li>
 *   <li>DELTA: Tuesday–Friday<br>
 *       Downloads eq_band_changes + ASM. Forward-fills ASM from asm_state.csv
 *       when the session API is blocked. Writes changed rows only (upsert).</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * <ul>
 *   <li>{@code data/nse_metadata.csv}  — single consolidated Pine seed file (UNCHANGED)</li>
 *   <li>{@code data/nse_udf_ohlc.csv}  — OHLC-packed feed for UDF server push</li>
 *   <li>{@code data/asm_state.csv}     — persisted ASM forward-fill store</li>
 *   <li>{@code data/nse_asm_full.csv}  — full daily ASM audit snapshot</li>
 *   <li>{@code data/cold_start.done}   — sentinel for run-mode detection</li>
 * </ul>
 *
 * <h3>OHLC field mapping (nse_udf_ohlc.csv)</h3>
 * <pre>
 *   open   = BAND_PCT
 *   high   = LOWER_BAND
 *   low    = UPPER_BAND
 *   close  = ASM_CODE
 *   volume = LAST_PRICE
 * </pre>
 *
 * <h3>UDF push</h3>
 * After writing nse_udf_ohlc.csv, the ETL POSTs it to the UDF server at
 * {@code UDF_SERVER_URL/udf/bars/bulk}.  Set env var {@code UDF_SERVER_URL}
 * to enable; leave unset for local runs without a server.
 *
 * <h3>CLI flags</h3>
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

            DataMerger       merger      = new DataMerger();
            SeedWriter       seedWriter  = new SeedWriter(DATA_DIR);
            OhlcExportWriter ohlcWriter  = new OhlcExportWriter(DATA_DIR);
            UdfPusher        udfPusher   = new UdfPusher(DATA_DIR);
            AsmStateStore    asmStore    = new AsmStateStore(DATA_DIR);
            AsmDownloader    asmDl       = new AsmDownloader(client);
            AsmFullWriter    asmFullWr   = new AsmFullWriter(DATA_DIR);

            // ── COLD-START ──────────────────────────────────────────────────
            if (coldStart) {

                PriceBandDownloader pbDl = new PriceBandDownloader(client);
                List<PriceBandRecord> priceBands = pbDl.downloadFull(tradeDate);
                if (priceBands.isEmpty()) {
                    log.warn("sec_list empty — public holiday? Exiting.");
                    return;
                }
                log.info("Price band records loaded: {}", priceBands.size());

                Map<String, AsmRecord> asmMap = downloadAsmSafe(
                        asmDl, null, tradeDate);
                log.info("ASM records: {}", asmMap.size());

                // Persist ASM state and write audit snapshot
                asmStore.save(asmMap);
                asmFullWr.write(tradeDate, asmMap);

                // Write consolidated seed file (nse_metadata.csv — never altered below)
                List<MergedRecord> merged = merger.mergeFull(priceBands, asmMap);
                seedWriter.write(merged);

                // Write OHLC export CSV + push to UDF server
                ohlcWriter.write(merged);
                udfPusher.push();

                writeSentinel(tradeDate);

            } else {

            // ── DELTA ───────────────────────────────────────────────────────

                // Load yesterday's persisted ASM state for forward-fill
                Map<String, AsmRecord> prevAsmMap = asmStore.load();

                DeltaBandUpdater delta   = new DeltaBandUpdater(client);
                Map<String, PriceBandRecord> deltaMap =
                        delta.downloadAndParse(tradeDate);
                log.info("Band changes: {}", deltaMap.size());

                Map<String, AsmRecord> freshAsmMap =
                        downloadAsmSafe(asmDl, deltaMap, tradeDate);

                /*
                 * FORWARD-FILL RULE:
                 * If the session API returned nothing (blocked/maintenance),
                 * keep ALL symbols from the previous day's persisted state.
                 */
                Map<String, AsmRecord> effectiveAsm =
                        freshAsmMap.isEmpty() ? prevAsmMap : freshAsmMap;
                log.info("Effective ASM (after forward-fill): {}", effectiveAsm.size());

                // Persist updated ASM state + write audit snapshot
                asmStore.save(effectiveAsm);
                asmFullWr.write(tradeDate, effectiveAsm);

                if (!deltaMap.isEmpty() || !effectiveAsm.isEmpty()) {
                    List<MergedRecord> deltaRecords =
                            merger.mergeDelta(deltaMap, effectiveAsm, prevAsmMap);

                    // nse_metadata.csv — full upsert (unchanged contract)
                    seedWriter.write(deltaRecords);

                    // Write OHLC export CSV + push to UDF server
                    ohlcWriter.write(deltaRecords);
                    udfPusher.push();

                } else {
                    log.info("No changes detected — nothing to write.");
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
        try {
            map = asmDl.download(date);
        } catch (IOException e) {
            log.warn("[ASM] Download error: {}. Using empty.", e.getMessage());
            map = Collections.emptyMap();
        }
        if (map.isEmpty() && deltaMap != null && !deltaMap.isEmpty()) {
            log.info("[ASM] Session API empty — supplementing with REMARKS fallback.");
            map = asmDl.deriveFromRemarks(deltaMap);
        }
        return map;
    }

    private static boolean sentinelExists() {
        return java.nio.file.Files.exists(java.nio.file.Paths.get(SENTINEL_FILE));
    }

    private static void writeSentinel(LocalDate date) throws IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(SENTINEL_FILE);
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
            catch (Exception e) { log.warn("Bad --date value — using today IST."); }
        }
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }
}
