package com.nse.feed.model;

import java.time.LocalDate;

/**
 * Combined record after joining PriceBand and ASM data for one NSE symbol.
 *
 * @param date        Trade date
 * @param symbol      NSE ticker
 * @param series      NSE series
 * @param prevClose   Previous close price
 * @param lowerBand   Lower circuit level (0 if no static band)
 * @param upperBand   Upper circuit level (0 if no static band)
 * @param bandPercent Circuit band % (0 if no static band / F&O)
 * @param asmCode     Encoded ASM stage (0=none, 11–15=STASM/LTASM, 20=GSM)
 * @param asmType     Raw ASM type text
 * @param asmStage    Raw ASM stage text
 */
public record MergedRecord(
        LocalDate date,
        String    symbol,
        String    series,
        double    prevClose,
        double    lowerBand,
        double    upperBand,
        double    bandPercent,
        int       asmCode,
        String    asmType,
        String    asmStage
) {}
