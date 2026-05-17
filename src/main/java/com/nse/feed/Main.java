package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.MergedRecord;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Entry point for the NSE → TradingView seed ETL.
 *
 * Flow:
 *   1. Warm-up NSE session (cookie / CSRF header acquisition).
 *   2. Download and parse Daily Price Band CSV.
 *   3. Download and parse ASM / LTASM / STASM CSV.
 *   4. Merge both datasets by NSE symbol.
 *   5. Write per-symbol CSV files to data/ directory in TradingView seed format.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {

        log.info("=== NSE TradingView Feed ETL started ===");

        // Step 1: Create shared HTTP client and warm NSE session
        NseClient client = new NseClient();
        client.warmUpSession();

        // Step 2: Price Band data
        PriceBandDownloader pbDownloader = new PriceBandDownloader(client);
        List<PriceBandRecord> priceBands = pbDownloader.download();
        log.info("Price Band records fetched: {}", priceBands.size());

        // Step 3: ASM / LTASM / STASM data
        AsmDownloader asmDownloader = new AsmDownloader(client);
        Map<String, AsmRecord> asmMap = asmDownloader.download();
        log.info("ASM records fetched: {}", asmMap.size());

        // Step 4: Merge
        DataMerger merger = new DataMerger();
        List<MergedRecord> merged = merger.merge(priceBands, asmMap);
        log.info("Merged records: {}", merged.size());

        // Step 5: Write seed files
        SeedWriter writer = new SeedWriter("data");
        writer.write(merged);
        log.info("=== ETL complete. Seed files written to data/ ===");
    }
}
