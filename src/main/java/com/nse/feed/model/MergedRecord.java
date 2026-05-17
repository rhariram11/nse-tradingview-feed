package com.nse.feed.model;

import java.time.LocalDate;

/**
 * One row in the consolidated Pine-consumable seed file (data/nse_metadata.csv).
 *
 * <h3>CSV column contract (SeedWriter writes in this exact order)</h3>
 * <pre>
 *   DATE, SYMBOL, SERIES, LAST_PRICE, LOWER_BAND, UPPER_BAND,
 *   BAND_PCT, BAND_LABEL, ASM_CODE, ASM_TYPE, ASM_STAGE, ASM_LABEL
 * </pre>
 *
 * <h3>Display fields</h3>
 * <ul>
 *   <li>BAND_LABEL — "20%" / "5%" / "No Band" (exactly as NSE publishes)</li>
 *   <li>ASM_LABEL  — "STASM-I" / "LTASM-II" / "GSM" / "" (empty = not in ASM)</li>
 * </ul>
 */
public record MergedRecord(
        LocalDate    date,
        String       symbol,
        String       series,
        double       lastPrice,
        double       lowerBand,
        double       upperBand,
        double       bandPct,
        String       bandLabel,
        AsmStageCode asmCode,
        String       asmType,
        String       asmStage,
        String       asmLabel
) {
    // ── Convenience factory ───────────────────────────────────────────────────

    /**
     * Builds a MergedRecord from raw components, auto-deriving asmLabel.
     */
    public static MergedRecord of(
            LocalDate date, String symbol, String series,
            double lastPrice, double lowerBand, double upperBand,
            double bandPct, String bandLabel,
            AsmStageCode asmCode, String asmType, String asmStage) {

        String safeLabel = (bandLabel == null || bandLabel.isBlank())
                           ? PriceBandRecord.deriveLabelFromPct(bandPct) : bandLabel;
        String asmLabel  = deriveAsmLabel(asmType, asmStage, asmCode);
        return new MergedRecord(
                date, symbol, series,
                lastPrice, lowerBand, upperBand,
                bandPct, safeLabel,
                asmCode, asmType, asmStage, asmLabel);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String deriveAsmLabel(String type, String stage, AsmStageCode code) {
        if (code == AsmStageCode.NONE_ENUM
                || type == null || type.equalsIgnoreCase("NONE"))
            return "";
        String t = type.trim().toUpperCase();
        String s = stage == null ? "" : stage.trim().toUpperCase();
        if (t.contains("GSM"))   return "GSM";
        String prefix = t.contains("LTASM") ? "LTASM"
                      : t.contains("STASM") ? "STASM"
                      : t.contains("LONG")  ? "LTASM"
                      : t.contains("SHORT") ? "STASM"
                      : "ASM";
        String roman = normaliseStage(s);
        return roman.isEmpty() ? prefix : prefix + "-" + roman;
    }

    private static String normaliseStage(String s) {
        if (s.contains("IV") || s.equals("4")) return "IV";
        if (s.contains("III") || s.equals("3")) return "III";
        if (s.contains("II")  || s.equals("2")) return "II";
        if (s.contains("I")   || s.equals("1")) return "I";
        return s;
    }
}
