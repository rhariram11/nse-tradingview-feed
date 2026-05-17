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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the NSE → TradingView seed ETL.
 *
 * ─── Data Sources (verified 2026-05-17) ──────────────────────────────────────
 *
 *  FULL band master : nsearchives.nseindia.com/content/equities/sec_list_{ddmmyyyy}.csv
 *  DELTA changes    : nsearchives.nseindia.com/content/equities/eq_band_changes_{ddmmyyyy}.csv
 *                     NOTE: eq_band_changes is dated with NEXT trading day
 *  ASM list         : nseindia.com/api/asm-securities?asmType=shortterm|longterm
 *                     Requires session warm-up; falls back to REMARKS in delta CSV
 *
 * ─── Run Modes ────────────────────────────────────────────────────────────────
 *
 *  COLD-START (full extract) triggered when ANY of:
 *    a) data/cold_start.done sentinel missing   (first-ever run)
 *    b) today is MONDAY                         (weekly re-baseline)
 *    c) --force-full CLI flag
 *
 *  DELTA (incremental) — every other trading day (Tue–Fri).
 *
 *  NON-TRADING DAY GUARD:
 *    Weekend (Sat/Sun) → fast-exit code 0 before any HTTP calls.
 *    Holiday (Mon–Fri) → both sec_list and eq_band_changes return 404 → exit code 0.
 *
 * ─── CLI args ─────────────────────────────────────────────────────────────────
 *   --force-full            force cold-start regardless of sentinel
 *   --date YYYY-MM-DD       override trade date (default: today IST)
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String DATA_DIR      = "data";
    private static final String SENTINEL_FILE = DATA_DIR + "/cold_start.done";

    public static void main(String[] args) throws Exception {
        log.info("======================================================");
        log.info(" NSE TradingView Feed ETL — started");
        log.info("======================================================");

        // ── Parse CLI args ────────────────────────────────────────────────────
        List<String> argList = Arrays.asList(args);
        boolean forceFull   = argList.contains("--force-full");
        LocalDate tradeDate = parseDate(argList);

        log.info("Trade date : {}", tradeDate);
        log.info("Day of week: {}", tradeDate.getDayOfWeek());
        log.info("Force-full : {}", forceFull);

        // ── Weekend fast-exit ─────────────────────────────────────────────────
        if (NseClient.isWeekend(tradeDate) && !forceFull) {
            log.info("Today is {} — NSE markets closed. Nothing to do. Exiting cleanly.",
                    tradeDate.getDayOfWeek());
            return; // exit 0
        }

        // ── Determine run mode ────────────────────────────────────────────────
        boolean coldStart = forceFull
                || !sentinelExists()
                || tradeDate.getDayOfWeek() == DayOfWeek.MONDAY;

        log.info("Run mode   : {}",
                coldStart ? "COLD-START (full extract)" : "DELTA (incremental)");

        // ── Shared HTTP client ────────────────────────────────────────────────
        try (NseClient client = new NseClient()) {

            DataMerger  merger  = new DataMerger();
            SeedWriter  writer  = new SeedWriter(DATA_DIR);

            if (coldStart) {
                // ── COLD-START: download full sec_list ────────────────────────
                PriceBandDownloader pbDownloader = new PriceBandDownloader(client);
                List<PriceBandRecord> priceBands = pbDownloader.downloadFull(tradeDate);

                if (priceBands.isEmpty()) {
                    log.warn("[Cold-Start] sec_list empty for {}. Public holiday?", tradeDate);
                    log.info("ETL skipped — no price band data. Exit code 0.");
                    return;
                }
                log.info("Price band records (full): {}", priceBands.size());

                // ASM: try session API; fall back to empty map on holiday
                Map<String, AsmRecord> asmMap = downloadAsmWithFallback(
                        client, null, tradeDate, forceFull);
                log.info("ASM records: {}", asmMap.size());

                List<MergedRecord> merged = merger.mergeFull(priceBands, asmMap);
                writer.write(merged);
                writeSentinel(tradeDate);
                log.info("Sentinel written: {}", SENTINEL_FILE);

            } else {
                // ── DELTA: eq_band_changes (NEXT trading day filename) ─────────
                DeltaBandUpdater deltaUpdater = new DeltaBandUpdater(client);
                Map<String, PriceBandRecord> deltaMap =
                        deltaUpdater.downloadAndParse(tradeDate);
                log.info("Band changes today: {}", deltaMap.size());

                // ASM: try session API; fall back to REMARKS in deltaMap
                Map<String, AsmRecord> asmMap = downloadAsmWithFallback(
                        client, deltaMap, tradeDate, forceFull);
                log.info("ASM records: {}", asmMap.size());

                if (deltaMap.isEmpty() && asmMap.isEmpty()) {
                    log.info("No band changes and no ASM data — nothing to write.");
                } else {
                    List<MergedRecord> merged = merger.mergeDelta(deltaMap, asmMap);
                    writer.write(merged);
                }
            }
        }

        log.info("======================================================");
        log.info(" ETL complete.");
        log.info("======================================================");
    }

    // ── ASM download with REMARKS fallback ────────────────────────────────────

    /**
     * Tries the session-based ASM API first.
     * If it returns empty (session blocked, holiday, etc.), falls back to
     * deriving ASM symbols from REMARKS in the deltaMap (may be null for cold-start).
     */
    private static Map<String, AsmRecord> downloadAsmWithFallback(
            NseClient client,
            Map<String, PriceBandRecord> deltaMap,
            LocalDate date,
            boolean forceFull) {

        AsmDownloader asmDownloader = new AsmDownloader(client);
        Map<String, AsmRecord> asmMap;
        try {
            asmMap = asmDownloader.download(date);
        } catch (IOException e) {
            log.warn("[ASM] download threw: {}. Using empty map.", e.getMessage());
            asmMap = new java.util.HashMap<>();
        }

        // If session API gave us nothing and we have a delta map, use REMARKS
        if (asmMap.isEmpty() && deltaMap != null && !deltaMap.isEmpty()) {
            log.info("[ASM] Session API empty — deriving from band-change REMARKS...");
            asmMap = asmDownloader.deriveFromRemarks(deltaMap);
        }
        return asmMap;
    }

    // ── Sentinel helpers ──────────────────────────────────────────────────────

    private static boolean sentinelExists() {
        return Files.exists(Paths.get(SENTINEL_FILE));
    }

    private static void writeSentinel(LocalDate date) throws IOException {
        Path sentinel = Paths.get(SENTINEL_FILE);
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel,
                "Last full extract: " + date + "\n"
              + "Next full extract: next Monday or --force-full\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── CLI helpers ───────────────────────────────────────────────────────────

    private static LocalDate parseDate(List<String> args) {
        int idx = args.indexOf("--date");
        if (idx >= 0 && idx + 1 < args.size()) {
            try { return LocalDate.parse(args.get(idx + 1)); }
            catch (Exception e) {
                log.warn("Invalid --date '{}', using today IST.", args.get(idx + 1));
            }
        }
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }
}
