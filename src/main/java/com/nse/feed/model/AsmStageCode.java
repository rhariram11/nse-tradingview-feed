package com.nse.feed.model;

/**
 * Canonical encoding of NSE ASM / STASM / LTASM / GSM stage codes.
 *
 * <pre>
 *  0        = no surveillance
 *  11-13    = STASM Stage I / II / III
 *  21-23    = LTASM Stage I / II / III      ← non-overlapping with STASM
 *  30       = GSM
 * </pre>
 *
 * Pine Script reads {@code volume} field from the seed CSV.
 * Match these constants in your Pine indicator:
 * <pre>
 *   STASM_I   = 11, STASM_II  = 12, STASM_III = 13
 *   LTASM_I   = 21, LTASM_II  = 22, LTASM_III = 23
 *   GSM       = 30
 * </pre>
 */
public final class AsmStageCode {

    public static final int NONE       = 0;

    // Short-Term ASM  (STASM)
    public static final int STASM_I    = 11;
    public static final int STASM_II   = 12;
    public static final int STASM_III  = 13;

    // Long-Term ASM (LTASM) — 20s range, NO overlap with STASM
    public static final int LTASM_I    = 21;
    public static final int LTASM_II   = 22;
    public static final int LTASM_III  = 23;

    // Graded Surveillance Measure
    public static final int GSM        = 30;

    private AsmStageCode() {}   // utility class — no instantiation

    /**
     * Encodes ASM type + stage text into a canonical integer code.
     *
     * @param type  e.g. "STASM", "LTASM", "GSM", "ASM"
     * @param stage e.g. "I", "II", "III", "1", "2", "3", ""
     * @return one of the constants above; 0 if unrecognised
     */
    public static int encode(String type, String stage) {
        int n = stageNum(stage);
        String t = type == null ? "" : type.trim().toUpperCase();
        if (t.contains("LTASM") || t.contains("LONG"))  return ltasmCode(n);
        if (t.contains("STASM") || t.contains("SHORT")) return stasmCode(n);
        if (t.contains("GSM"))                          return GSM;
        if (t.contains("ASM"))                          return stasmCode(n);
        return NONE;
    }

    /** Human-readable label for a stage code (useful for Pine labels). */
    public static String label(int code) {
        return switch (code) {
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

    private static int stasmCode(int n) {
        return switch (n) {
            case 2  -> STASM_II;
            case 3  -> STASM_III;
            default -> STASM_I;
        };
    }

    private static int ltasmCode(int n) {
        return switch (n) {
            case 2  -> LTASM_II;
            case 3  -> LTASM_III;
            default -> LTASM_I;
        };
    }

    private static int stageNum(String stage) {
        if (stage == null) return 1;
        String u = stage.trim().toUpperCase();
        if (u.contains("III") || u.equals("3")) return 3;
        if (u.contains("II")  || u.equals("2")) return 2;
        return 1;
    }
}
