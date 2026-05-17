package com.nse.feed.model;

/**
 * Lightweight projection for writing the audit snapshot nse_asm_full.csv.
 *
 * <p>This file contains the complete ASM list exactly as downloaded from NSE
 * each day. It is NOT consumed by Pine — it exists purely for auditing
 * (compare vs the NSE surveillance page or nse_metadata.csv).
 *
 * @param symbol    NSE ticker
 * @param isin      ISIN
 * @param type      STASM / LTASM / GSM
 * @param stage     I / II / III
 * @param stageCode Integer code (11-13 / 21-23 / 30)
 * @param label     Human label e.g. "STASM-I"
 */
public record AsmExportRecord(
        String symbol,
        String isin,
        String type,
        String stage,
        int    stageCode,
        String label
) {
    public static AsmExportRecord from(AsmRecord r) {
        return new AsmExportRecord(
                r.symbol(), r.isin(), r.type(), r.stage(),
                r.stageCode().code, r.stageCode().label());
    }
}
