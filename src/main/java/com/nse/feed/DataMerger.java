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
 * Joins PriceBand records with ASM records by NSE symbol.
 * Produces a flat MergedRecord list ready for SeedWriter.
 */
public class DataMerger {

    private static final Logger log = LoggerFactory.getLogger(DataMerger.class);

    public List<MergedRecord> merge(
            List<PriceBandRecord> priceBands,
            Map<String, AsmRecord> asmMap) {

        LocalDate today = LocalDate.now();
        List<MergedRecord> merged = new ArrayList<>(priceBands.size());

        for (PriceBandRecord pb : priceBands) {
            AsmRecord asm = asmMap.getOrDefault(pb.symbol(), null);
            int asmCode   = asm != null ? asm.stageCode() : 0;
            String asmType  = asm != null ? asm.type()  : "NONE";
            String asmStage = asm != null ? asm.stage() : "";

            merged.add(new MergedRecord(
                    today,
                    pb.symbol(),
                    pb.series(),
                    pb.prevClose(),
                    pb.lowerBand(),
                    pb.upperBand(),
                    pb.bandPercent(),
                    asmCode,
                    asmType,
                    asmStage
            ));
        }

        // Also add ASM-only symbols not present in price band file (edge case)
        for (Map.Entry<String, AsmRecord> entry : asmMap.entrySet()) {
            boolean alreadyPresent = merged.stream()
                    .anyMatch(r -> r.symbol().equals(entry.getKey()));
            if (!alreadyPresent) {
                AsmRecord asm = entry.getValue();
                log.debug("ASM-only symbol (not in price band file): {}", asm.symbol());
                merged.add(new MergedRecord(
                        today,
                        asm.symbol(),
                        "EQ",
                        0, 0, 0, 0,
                        asm.stageCode(),
                        asm.type(),
                        asm.stage()
                ));
            }
        }

        log.info("Merge complete: {} total records.", merged.size());
        return merged;
    }
}
