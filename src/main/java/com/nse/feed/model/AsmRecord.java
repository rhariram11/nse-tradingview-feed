package com.nse.feed.model;

/**
 * Immutable record for one NSE ASM / STASM / LTASM / GSM entry.
 *
 * <p>Fields sourced from /api/reportASM response:
 * <ul>
 *   <li>{@code symbol}       — NSE ticker (upper-case)
 *   <li>{@code companyName}  — Company name from NSE
 *   <li>{@code isin}         — ISIN (may be empty when derived from REMARKS)
 *   <li>{@code type}         — Canonical: "STASM" | "LTASM" | "GSM" | "ASM"
 *   <li>{@code stage}        — Stage text: "I" | "II" | "III" | "IV"
 *   <li>{@code asOfDate}     — Effective date string from NSE (e.g. "15-May-2026")
 *   <li>{@code circRevision} — List revision number from survCode brackets
 *                              (e.g. 13 from "LTASM - I (13)"). Not a stage number.
 *   <li>{@code stageCode}    — Encoded enum — see {@link AsmStageCode}
 * </ul>
 */
public record AsmRecord(
        String       symbol,
        String       companyName,
        String       isin,
        String       type,
        String       stage,
        String       asOfDate,
        int          circRevision,
        AsmStageCode stageCode
) {
    /**
     * Convenience factory — encodes stageCode from type+stage automatically.
     *
     * @param symbol       NSE ticker
     * @param companyName  Company name (may be empty)
     * @param isin         ISIN (may be empty)
     * @param type         "LTASM" | "STASM" | "GSM"
     * @param stage        "I" | "II" | "III" | "IV"
     * @param asOfDate     Effective date from asmTime field ("15-May-2026")
     * @param circRevision List revision number extracted from survCode brackets
     */
    public static AsmRecord of(String symbol, String companyName, String isin,
                               String type, String stage,
                               String asOfDate, int circRevision) {
        return new AsmRecord(
                symbol, companyName, isin, type, stage,
                asOfDate, circRevision,
                AsmStageCode.encode(type, stage));
    }

    /** Label suitable for Pine Script / logs (delegates to AsmStageCode). */
    public String label() { return stageCode.label(); }
}
