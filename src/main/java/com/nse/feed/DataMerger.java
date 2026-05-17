package com.nse.feed;

import com.nse.feed.model.AsmRecord;
import com.nse.feed.model.AsmStageCode;
import com.nse.feed.model.MergedRecord;
import com.nse.feed.model.PriceBandRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Joins price-band data with ASM surveillance data by NSE symbol.
 *
 * <h3>Delta merge — three change-sets handled</h3>
 * <ol>
 *   <li>Band-changed symbols (from eq_band_changes)</li>
 *   <li>Newly added to ASM (present in today's map, absent yesterday)</li>
 *   <li>ASM stage change (same symbol, different code today vs yesterday)</li>
 *   <li>Removed from ASM (in yesterday's map, absent today's non-empty map)</li>
 * </ol>
 */
public class DataMerger {

    private static final Logger log = LoggerFactory.getLogger(DataMerger.class);

    // ── Full merge ────────────────────────────────────────────────────────────

    public List<MergedRecord> mergeFull(
            List<PriceBandRecord> priceBands,
            Map<String, AsmRecord> asmMap) {

        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>(priceBands.size());
        for (PriceBandRecord pb : priceBands) merged.add(buildRecord(today, pb, asmMap));

        // ASM-only symbols absent from circuit file
        for (Map.Entry<String, AsmRecord> e : asmMap.entrySet()) {
            if (merged.stream().noneMatch(r -> r.symbol().equals(e.getKey()))) {
                merged.add(asmOnlyRecord(today, e.getValue()));
            }
        }
        log.info("[Merge] FULL: {} records.", merged.size());
        return merged;
    }

    // ── Delta merge ───────────────────────────────────────────────────────────

    public List<MergedRecord> mergeDelta(
            Map<String, PriceBandRecord> deltaMap,
            Map<String, AsmRecord>       asmMap,
            Map<String, AsmRecord>       prevAsmMap) {

        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>();

        // 1. Band-changed symbols
        for (PriceBandRecord pb : deltaMap.values()) {
            Map<String, AsmRecord> eff = asmMap.isEmpty() ? prevAsmMap : asmMap;
            merged.add(buildRecord(today, pb, eff));
        }

        // 2. Newly added to ASM
        for (Map.Entry<String, AsmRecord> e : asmMap.entrySet()) {
            String sym = e.getKey();
            if (!deltaMap.containsKey(sym) && !prevAsmMap.containsKey(sym)) {
                log.info("[Merge] NEW ASM: {} {}", sym, e.getValue().label());
                merged.add(asmOnlyRecord(today, e.getValue()));
            }
        }

        // 3. ASM stage changed
        for (Map.Entry<String, AsmRecord> e : asmMap.entrySet()) {
            String sym  = e.getKey();
            AsmRecord prev = prevAsmMap.get(sym);
            if (prev != null && prev.stageCode() != e.getValue().stageCode()
                    && !deltaMap.containsKey(sym)) {
                log.info("[Merge] ASM stage changed: {} {} → {}",
                        sym, prev.label(), e.getValue().label());
                merged.add(asmOnlyRecord(today, e.getValue()));
            }
        }

        // 4. Removed from ASM (only when today's list is non-empty = full response)
        if (!asmMap.isEmpty()) {
            for (String sym : prevAsmMap.keySet()) {
                if (!asmMap.containsKey(sym) && !deltaMap.containsKey(sym)) {
                    log.info("[Merge] ASM REMOVED: {} → code 0", sym);
                    merged.add(new MergedRecord(
                        today, sym, "EQ", 0, 0, 0, 0,
                        AsmStageCode.NONE, "NONE", ""));
                }
            }
        }

        log.info("[Merge] DELTA: {} records.", merged.size());
        return merged;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MergedRecord buildRecord(LocalDate date, PriceBandRecord pb,
                                     Map<String, AsmRecord> asmMap) {
        AsmRecord asm = asmMap.get(pb.getSymbol());
        return new MergedRecord(
                date, pb.getSymbol(), pb.getSeries(),
                pb.getLastPrice(), pb.getLowerBand(), pb.getUpperBand(), pb.getBandPct(),
                asm != null ? asm.stageCode() : AsmStageCode.NONE,
                asm != null ? asm.type()      : "NONE",
                asm != null ? asm.stage()     : "");
    }

    private MergedRecord asmOnlyRecord(LocalDate date, AsmRecord asm) {
        return new MergedRecord(date, asm.symbol(), "EQ",
                0, 0, 0, 0, asm.stageCode(), asm.type(), asm.stage());
    }
}
