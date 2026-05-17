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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the NSE → TradingView seed ETL.
 *
 * ─── Run Modes ────────────────────────────────────────────────────────────────
 *
 *  COLD-START (full extract) — triggered when ANY of:
 *    a) data/cold_start.done sentinel missing   (first-ever run)
 *    b) today is MONDAY                         (weekly re-baseline)
 *    c) --force-full CLI flag
 *
 *  DELTA (incremental) — every other trading day (Tue–Fri).
 *
 *  NON-TRADING DAY GUARD:
 *    If NSE returns no data (404 / empty) for the given date the ETL logs
 *    a clear message and exits with code 0 (success). This prevents GitHub
 *    Actions from marking Sunday / public holiday runs as failures.
 *
 * ─── CLI args ─────────────────────────────────────────────────────────────────
 *   --force-full            force cold-start regardless of sentinel
 *   --date YYYY-MM-DD       override trade date (default: today)
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
        boolean forceFull    = argList.contains("--force-full");
        LocalDate tradeDate  = parseDate(argList);

        log.info("Trade date : {}", tradeDate);
        log.info("Day of week: {}", tradeDate.getDayOfWeek());
        log.info("Force-full : {}", forceFull);

        // ── Weekend / non-trading day fast-exit ───────────────────────────────
        // Skip Sundays proactively to avoid unnecessary NSE warm-up calls.
        // Saturday is kept because NSE occasionally publishes Saturday data.
        if (tradeDate.getDayOfWeek() == DayOfWeek.SUNDAY && !forceFull) {
            log.info("Today is Sunday — NSE markets closed. Nothing to do. Exiting cleanly.");
            return;
        }

        // ── Determine run mode ────────────────────────────────────────────────
        boolean coldStart = forceFull
                || !sentinelExists()
                || tradeDate.getDayOfWeek() == DayOfWeek.MONDAY;

        log.info("Run mode   : {}",
                coldStart ? "COLD-START (full extract)" : "DELTA (incremental)");

        // ── Shared HTTP client + NSE session ──────────────────────────────────
        try (NseClient client = new NseClient()) {
            client.warmUpSession();

            // ── ASM: always full refresh (small file, ~100-300 rows) ──────────
            AsmDownloader asmDownloader = new AsmDownloader(client);
            Map<String, AsmRecord> asmMap = asmDownloader.download(tradeDate);

            // ── Non-trading day guard: ASM 404 means no market data today ─────
            // If ASM returned empty AND we did not force a run, it's a holiday.
            if (asmMap.isEmpty() && !forceFull) {
                log.info("ASM list empty for {} — likely a public holiday or non-trading day.", tradeDate);
                log.info("Attempting price band download to confirm before skipping...");

                // Double-check: try downloading the price band file too.
                // If both are 404, we're certain it's a non-trading day.
                byte[] bandCheck = client.downloadFullBandCsv(tradeDate);
                if (bandCheck == null) {
                    log.info("Price band also unavailable for {}. Confirmed non-trading day.", tradeDate);
                    log.info("======================================================");
                    log.info(" ETL skipped — non-trading day. Exit code 0.");
                    log.info("======================================================");
                    return; // Clean exit — GitHub Actions will show green ✓
                }
                log.info("Price band available — proceeding with empty ASM map.");
            }

            log.info("ASM records: {}", asmMap.size());

            DataMerger merger = new DataMerger();
            SeedWriter  writer = new SeedWriter(DATA_DIR);

            if (coldStart) {
                // ── COLD-START: download full circuit file ────────────────────
                PriceBandDownloader pbDownloader = new PriceBandDownloader(client);
                List<PriceBandRecord> priceBands = pbDownloader.downloadFull(tradeDate);

                if (priceBands.isEmpty()) {
                    log.warn("[Cold-Start] Price band file empty for {}. Non-trading day?", tradeDate);
                    log.info("ETL skipped — no data. Exit code 0.");
                    return;
                }

                log.info("Price band records (full): {}", priceBands.size());
                List<MergedRecord> merged = merger.mergeFull(priceBands, asmMap);
                writer.write(merged);
                writeSentinel(tradeDate);
                log.info("Sentinel written: {}", SENTINEL_FILE);

            } else {
                // ── DELTA: only changed symbols ───────────────────────────────
                DeltaBandUpdater deltaUpdater = new DeltaBandUpdater(client);
                Map<String, PriceBandRecord> deltaMap =
                        deltaUpdater.downloadAndParse(tradeDate);
                log.info("Band changes today: {}", deltaMap.size());

                if (deltaMap.isEmpty() && asmMap.isEmpty()) {
                    log.info("No changes and no ASM data — nothing to write.");
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
                log.warn("Invalid --date '{}', using today.", args.get(idx + 1));
            }
        }
        return LocalDate.now();
    }
}
