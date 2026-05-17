package com.nse.feed;

import com.nse.feed.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Joins price-band records with ASM surveillance data by NSE symbol.
 *
 * <h3>Delta merge change-sets</h3>
 * <ol>
 *   <li>Band-changed symbols (from eq_band_changes)</li>
 *   <li>Newly added to ASM (present today, absent yesterday)</li>
 *   <li>ASM stage changed (same symbol, different stageCode today vs yesterday)</li>
 *   <li>Removed from ASM (in yesterday's map, absent today — only when API returned full list)</li>
 * </ol>
 *
 * <p>Outputs MergedRecord with both bandLabel ("20%") and asmLabel ("STASM-I")
 * as dedicated fields for direct Pine consumption via nse_metadata.csv.
 */
public class DataMerger {

    private static final Logger log = LoggerFactory.getLogger(DataMerger.class);

    // ── Full merge ────────────────────────────────────────────────────────────

    public List<MergedRecord> mergeFull(List<PriceBandRecord> priceBands,
                                        Map<String, AsmRecord> asmMap) {
        LocalDate today  = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>(priceBands.size());

        for (PriceBandRecord pb : priceBands)
            merged.add(buildRecord(today, pb, asmMap));

        // ASM-only symbols absent from the circuit file
        for (Map.Entry<String, AsmRecord> e : asmMap.entrySet()) {
            String sym = e.getKey();
            boolean seen = merged.stream().anyMatch(r -> r.symbol().equals(sym));
            if (!seen) merged.add(asmOnlyRecord(today, e.getValue()));
        }

        log.info("[Merge] FULL: {} records.", merged.size());
        return merged;
    }

    // ── Delta merge ───────────────────────────────────────────────────────────

    public List<MergedRecord> mergeDelta(Map<String, PriceBandRecord> deltaMap,
                                         Map<String, AsmRecord>       asmMap,
                                         Map<String, AsmRecord>       prevAsmMap) {
        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>();
        // Effective ASM: use today's full response; fall back to yesterday's if empty
        Map<String, AsmRecord> eff = asmMap.isEmpty() ? prevAsmMap : asmMap;

        // 1. Band-changed symbols
        for (PriceBandRecord pb : deltaMap.values())
            merged.add(buildRecord(today, pb, eff));

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
            String sym      = e.getKey();
            AsmRecord prev  = prevAsmMap.get(sym);
            if (prev != null
                    && prev.stageCode() != e.getValue().stageCode()
                    && !deltaMap.containsKey(sym)) {
                log.info("[Merge] ASM stage changed: {} {} → {}",
                        sym, prev.label(), e.getValue().label());
                merged.add(asmOnlyRecord(today, e.getValue()));
            }
        }

        // 4. Removed from ASM (only when today's list is non-empty = confirmed full response)
        if (!asmMap.isEmpty()) {
            for (String sym : prevAsmMap.keySet()) {
                if (!asmMap.containsKey(sym) && !deltaMap.containsKey(sym)) {
                    log.info("[Merge] ASM REMOVED: {} → code 0", sym);
                    merged.add(MergedRecord.of(
                            today, sym, "EQ", 0, 0, 0, 0, "No Band",
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
        return MergedRecord.of(
                date, pb.getSymbol(), pb.getSeries(),
                pb.getLastPrice(), pb.getLowerBand(), pb.getUpperBand(),
                pb.getBandPct(), pb.getBandLabel(),
                asm != null ? asm.stageCode() : AsmStageCode.NONE,
                asm != null ? asm.type()      : "NONE",
                asm != null ? asm.stage()     : "");
    }

    private MergedRecord asmOnlyRecord(LocalDate date, AsmRecord asm) {
        return MergedRecord.of(
                date, asm.symbol(), "EQ",
                0, 0, 0, 0, "No Band",
                asm.stageCode(), asm.type(), asm.stage());
    }
}
