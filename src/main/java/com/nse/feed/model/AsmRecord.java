package com.nse.feed.model;

/**
 * Immutable record representing one NSE ASM / STASM / LTASM / GSM entry.
 *
 * @param symbol    NSE ticker
 * @param isin      ISIN (may be empty string when derived from REMARKS)
 * @param type      Canonical type: "STASM" | "LTASM" | "GSM" | "ASM"
 * @param stage     Raw stage text from NSE: "I" | "II" | "III"
 * @param stageCode Encoded integer — see {@link AsmStageCode} for all constants
 */
public record AsmRecord(
        String symbol,
        String isin,
        String type,
        String stage,
        int    stageCode
) {
    /** Convenience factory — encodes stageCode from type+stage automatically. */
    public static AsmRecord of(String symbol, String isin, String type, String stage) {
        return new AsmRecord(symbol, isin, type, stage, AsmStageCode.encode(type, stage));
    }

    /** Label suitable for Pine Script / UI display. */
    public String label() { return AsmStageCode.label(stageCode); }
}
