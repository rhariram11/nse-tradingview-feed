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
 * ─── Run Modes ───────────────────────────────────────────────────────────────
 *
 *  COLD-START (full extract)  — triggered when ANY of these is true:
 *    a) data/cold_start.done sentinel file does not exist
 *       (first ever run on a fresh checkout / fresh Actions runner)
 *    b) today is MONDAY  (weekly re-baseline to prevent drift)
 *    c) --force-full flag is passed as CLI argument
 *
 *    Action:
 *      ① Download full circuit_DDMMYYYY.csv (~2000 rows, ALL EQ symbols)
 *      ② Parse & full-merge with ASM list
 *      ③ Write seed rows for every symbol
 *      ④ Write / touch data/cold_start.done sentinel
 *
 *  DELTA (normal daily run)   — every other trading day:
 *      ① Download eq_band_changes_DDMMYYYY.csv (~10-50 rows, ONLY changed symbols)
 *      ② Parse & delta-merge with ASM list
 *      ③ Write seed rows ONLY for changed symbols (unchanged files untouched)
 *
 *  ASM list is ALWAYS downloaded fresh (full list, small ~100-300 rows).
 *
 * ─── CLI arguments (optional) ────────────────────────────────────────────────
 *   --force-full     force a cold-start full extract regardless of sentinel
 *   --date YYYY-MM-DD  override trade date (default: today)
 *
 * ─── Sentinel file ───────────────────────────────────────────────────────────
 *   data/cold_start.done  — plain text file containing the date of last full run.
 *   Created/updated by this job after every successful cold-start run.
 *   Committed to the repo by GitHub Actions so the next daily run knows
 *   a baseline already exists.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String DATA_DIR       = "data";
    private static final String SENTINEL_FILE  = DATA_DIR + "/cold_start.done";

    public static void main(String[] args) throws Exception {
        log.info("======================================================");
        log.info(" NSE TradingView Feed ETL — started");
        log.info("======================================================");

        // ── Parse CLI args ──────────────────────────────────────────────────
        List<String> argList   = Arrays.asList(args);
        boolean forceFull      = argList.contains("--force-full");
        LocalDate tradeDate    = parseDate(argList);
        log.info("Trade date   : {}", tradeDate);
        log.info("Force-full   : {}", forceFull);

        // ── Determine run mode ──────────────────────────────────────────────
        boolean coldStart = forceFull
                || !sentinelExists()
                || tradeDate.getDayOfWeek() == DayOfWeek.MONDAY;

        log.info("Run mode     : {}", coldStart ? "COLD-START (full extract)" : "DELTA (incremental)");
        if (!coldStart && tradeDate.getDayOfWeek() == DayOfWeek.MONDAY) {
            log.info("  → Monday detected: forcing full extract for weekly re-baseline.");
        }
        if (!sentinelExists() && !forceFull) {
            log.info("  → Sentinel data/cold_start.done not found: first-ever run, doing full extract.");
        }

        // ── Shared HTTP client + NSE session warm-up ────────────────────────
        try (NseClient client = new NseClient()) {
            client.warmUpSession();

            // ── ASM: always a full refresh (list is small) ──────────────────
            AsmDownloader asmDownloader = new AsmDownloader(client);
            Map<String, AsmRecord> asmMap = asmDownloader.download(tradeDate);
            log.info("ASM records  : {}", asmMap.size());

            DataMerger  merger = new DataMerger();
            SeedWriter  writer = new SeedWriter(DATA_DIR);

            if (coldStart) {
                // ── COLD-START: full price band extract ─────────────────────
                PriceBandDownloader pbDownloader = new PriceBandDownloader(client);
                List<PriceBandRecord> priceBands = pbDownloader.downloadFull(tradeDate);
                log.info("Price band records (full): {}", priceBands.size());

                List<MergedRecord> merged = merger.mergeFull(priceBands, asmMap);
                writer.write(merged);

                // Write sentinel so next run knows baseline is in place
                writeSentinel(tradeDate);
                log.info("Sentinel written: {}", SENTINEL_FILE);

            } else {
                // ── DELTA: only changed symbols ─────────────────────────────
                DeltaBandUpdater deltaUpdater = new DeltaBandUpdater(client);
                Map<String, PriceBandRecord> deltaMap =
                        deltaUpdater.downloadAndParse(tradeDate);
                log.info("Band changes today: {}", deltaMap.size());

                if (deltaMap.isEmpty() && asmMap.isEmpty()) {
                    log.info("No changes today — seed files already up to date.");
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

    // ── Sentinel helpers ────────────────────────────────────────────────────

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

    // ── CLI helpers ─────────────────────────────────────────────────────────

    private static LocalDate parseDate(List<String> args) {
        int idx = args.indexOf("--date");
        if (idx >= 0 && idx + 1 < args.size()) {
            try {
                return LocalDate.parse(args.get(idx + 1));
            } catch (Exception e) {
                log.warn("Invalid --date value '{}', using today.", args.get(idx + 1));
            }
        }
        return LocalDate.now();
    }
}
