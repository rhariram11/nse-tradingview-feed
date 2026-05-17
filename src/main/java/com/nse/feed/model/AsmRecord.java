package com.nse.feed.model;

/**
 * Immutable record for one NSE ASM / STASM / LTASM / GSM entry.
 *
 * @param symbol    NSE ticker (upper-case)
 * @param isin      ISIN (may be empty when derived from REMARKS)
 * @param type      Canonical type: "STASM" | "LTASM" | "GSM" | "ASM"
 * @param stage     Stage text from NSE: "I" | "II" | "III"
 * @param stageCode Encoded enum — see {@link AsmStageCode}
 */
public record AsmRecord(
        String       symbol,
        String       isin,
        String       type,
        String       stage,
        AsmStageCode stageCode
) {
    /** Convenience factory — encodes stageCode from type+stage automatically. */
    public static AsmRecord of(String symbol, String isin, String type, String stage) {
        return new AsmRecord(symbol, isin, type, stage, AsmStageCode.encode(type, stage));
    }

    /** Label suitable for Pine Script / logs (delegates to AsmStageCode). */
    public String label() { return stageCode.label(); }
}
