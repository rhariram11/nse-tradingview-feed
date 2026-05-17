package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.MergedRecord;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Joins price band data with ASM surveillance data by NSE symbol.
 *
 * Two modes:
 *
 * 1. FULL merge  – called after a cold-start / Monday full download.
 *    Takes the complete List<PriceBandRecord> (~2000 rows) and the ASM map,
 *    produces a MergedRecord for every EQ symbol.
 *
 * 2. DELTA merge – called on regular trading days after cold-start is done.
 *    Takes Map<symbol, PriceBandRecord> containing ONLY the ~10-50 symbols
 *    whose band changed today, plus the ASM map.
 *    Only those changed symbols get new MergedRecord rows — all other symbols'
 *    seed files remain untouched (no I/O, no duplicate rows).
 *
 * SeedWriter receives the resulting list and appends one new date-row per
 * symbol.  On delta mode the list is small, so the write loop is fast.
 */
public class DataMerger {

    private static final Logger log = LoggerFactory.getLogger(DataMerger.class);

    // ── Full merge (cold-start / Monday) ────────────────────────────────────

    /**
     * Full merge: all price band records × ASM map.
     * Use after PriceBandDownloader.downloadFull().
     */
    public List<MergedRecord> mergeFull(
            List<PriceBandRecord> priceBands,
            Map<String, AsmRecord> asmMap) {

        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>(priceBands.size());

        for (PriceBandRecord pb : priceBands) {
            merged.add(buildRecord(today, pb, asmMap));
        }

        // Include ASM-only symbols not present in circuit file (edge case)
        for (Map.Entry<String, AsmRecord> entry : asmMap.entrySet()) {
            boolean present = merged.stream().anyMatch(r -> r.symbol().equals(entry.getKey()));
            if (!present) {
                AsmRecord asm = entry.getValue();
                log.debug("[Merge] ASM-only symbol (not in circuit file): {}", asm.symbol());
                merged.add(new MergedRecord(
                        today, asm.symbol(), "EQ",
                        0.0, 0.0, 0.0, 0.0,
                        asm.stageCode(), asm.type(), asm.stage()));
            }
        }

        log.info("[Merge] FULL merge complete: {} records.", merged.size());
        return merged;
    }

    // ── Delta merge (normal daily run) ──────────────────────────────────────

    /**
     * Delta merge: only symbols that had a band change today.
     * Use after DeltaBandUpdater.downloadAndParse().
     *
     * @param deltaMap  symbol → PriceBandRecord for ONLY changed symbols
     * @param asmMap    full current ASM map (always refreshed daily)
     * @return small list (~10-50 records) to be appended by SeedWriter
     */
    public List<MergedRecord> mergeDelta(
            Map<String, PriceBandRecord> deltaMap,
            Map<String, AsmRecord> asmMap) {

        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>(deltaMap.size());

        for (PriceBandRecord pb : deltaMap.values()) {
            merged.add(buildRecord(today, pb, asmMap));
        }

        // Also emit updated rows for ASM-only symbols that changed status today
        // These would NOT appear in the delta band file but their ASM flag changed.
        // We emit a row with bandPct=0 to record the new ASM code in the seed.
        for (Map.Entry<String, AsmRecord> entry : asmMap.entrySet()) {
            String symbol = entry.getKey();
            if (!deltaMap.containsKey(symbol)) {
                AsmRecord asm = entry.getValue();
                log.debug("[Merge] ASM-status row for (no band change): {}", symbol);
                merged.add(new MergedRecord(
                        today, symbol, "EQ",
                        0.0, 0.0, 0.0, 0.0,
                        asm.stageCode(), asm.type(), asm.stage()));
            }
        }

        log.info("[Merge] DELTA merge complete: {} records to write.", merged.size());
        return merged;
    }

    // ── Backward-compat shim ─────────────────────────────────────────────────

    /**
     * Legacy method alias kept for any external callers.
     * Delegates to mergeFull().
     */
    public List<MergedRecord> merge(
            List<PriceBandRecord> priceBands,
            Map<String, AsmRecord> asmMap) {
        return mergeFull(priceBands, asmMap);
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private MergedRecord buildRecord(
            LocalDate date,
            PriceBandRecord pb,
            Map<String, AsmRecord> asmMap) {

        // PriceBandRecord is a POJO — all accessors use get* prefix
        AsmRecord asm   = asmMap.getOrDefault(pb.getSymbol(), null);
        int    asmCode  = asm != null ? asm.stageCode() : 0;
        String asmType  = asm != null ? asm.type()      : "NONE";
        String asmStage = asm != null ? asm.stage()     : "";

        return new MergedRecord(
                date,
                pb.getSymbol(),      // was pb.symbol()
                pb.getSeries(),      // was pb.series()
                pb.getLastPrice(),   // was pb.prevClose() — field is lastPrice
                pb.getLowerBand(),   // was pb.lowerBand()
                pb.getUpperBand(),   // was pb.upperBand()
                pb.getBandPct(),     // was pb.bandPercent() — field is bandPct
                asmCode,
                asmType,
                asmStage);
    }
}
