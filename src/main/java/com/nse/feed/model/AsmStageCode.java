package com.nse.feed.model;

/**
 * Canonical encoding of NSE ASM / STASM / LTASM / GSM stage codes.
 *
 * <pre>
 *  0        = no surveillance
 *  11-13    = STASM Stage I / II / III     (short-term)
 *  21-23    = LTASM Stage I / II / III     (long-term, NON-overlapping range)
 *  30       = GSM
 * </pre>
 *
 * Pine Script reads the ASM_CODE column from nse_metadata.csv:
 * <pre>
 *   int asm = asmCode
 *   bool inStasm = asm >= 11 and asm <= 13
 *   bool inLtasm = asm >= 21 and asm <= 23
 *   bool inGsm   = asm == 30
 *   bool inAsm   = asm > 0
 * </pre>
 */
public enum AsmStageCode {

    NONE      (0),
    STASM_I   (11),
    STASM_II  (12),
    STASM_III (13),
    LTASM_I   (21),
    LTASM_II  (22),
    LTASM_III (23),
    GSM       (30);

    /** The integer written to the CSV / used in Pine. */
    public final int code;

    AsmStageCode(int code) { this.code = code; }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Convenience alias for NONE (used as the "no ASM" sentinel). */
    public static final AsmStageCode NONE_ENUM = NONE;

    /**
     * Encodes ASM type + stage text into the canonical enum constant.
     *
     * @param type  e.g. "STASM", "LTASM", "GSM", "ASM", "shortterm", "longterm"
     * @param stage e.g. "I", "II", "III", "1", "2", "3", ""
     */
    public static AsmStageCode encode(String type, String stage) {
        int n  = stageNum(stage);
        String t = type == null ? "" : type.trim().toUpperCase();
        if (t.contains("LTASM") || t.contains("LONG"))  return ltasm(n);
        if (t.contains("STASM") || t.contains("SHORT")) return stasm(n);
        if (t.contains("GSM"))                          return GSM;
        if (t.contains("ASM"))                          return stasm(n);
        return NONE;
    }

    /** Returns true when this code represents any active surveillance. */
    public boolean isActive() { return this != NONE; }

    /** Human-readable label for Pine labels / logs. */
    public String label() {
        return switch (this) {
            case STASM_I   -> "STASM-I";
            case STASM_II  -> "STASM-II";
            case STASM_III -> "STASM-III";
            case LTASM_I   -> "LTASM-I";
            case LTASM_II  -> "LTASM-II";
            case LTASM_III -> "LTASM-III";
            case GSM       -> "GSM";
            default        -> "";
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static AsmStageCode stasm(int n) {
        return switch (n) { case 2 -> STASM_II; case 3 -> STASM_III; default -> STASM_I; };
    }

    private static AsmStageCode ltasm(int n) {
        return switch (n) { case 2 -> LTASM_II; case 3 -> LTASM_III; default -> LTASM_I; };
    }

    private static int stageNum(String stage) {
        if (stage == null) return 1;
        String u = stage.trim().toUpperCase();
        if (u.contains("IV") || u.equals("4")) return 4;  // future-proof
        if (u.contains("III") || u.equals("3")) return 3;
        if (u.contains("II")  || u.equals("2")) return 2;
        return 1;
    }
}
