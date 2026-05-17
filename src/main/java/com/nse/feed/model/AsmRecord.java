package com.nse.feed.model;

/**
 * Immutable record representing one row from the NSE ASM / LTASM / STASM surveillance report.
 *
 * @param symbol    NSE ticker symbol
 * @param isin      ISIN code
 * @param type      Surveillance type text (STASM / LTASM / GSM / ASM)
 * @param stage     Raw stage text from NSE (e.g. "I", "II", "III")
 * @param stageCode Encoded integer: 0=none, 11=STASM-I, 12=STASM-II,
 *                  13=LTASM-I, 14=LTASM-II, 15=LTASM-III, 20=GSM
 */
public record AsmRecord(
        String symbol,
        String isin,
        String type,
        String stage,
        int    stageCode
) {}
