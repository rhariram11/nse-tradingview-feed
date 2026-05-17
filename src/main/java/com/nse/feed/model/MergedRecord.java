package com.nse.feed.model;

import java.time.LocalDate;

/**
 * Combined record after joining PriceBand + ASM data for one NSE symbol.
 *
 * Mapped to the TradingView seed CSV columns:
 * <pre>
 *   date   = trade date
 *   open   = previous close price (0 when unknown)
 *   high   = upper circuit price level  (0 = no static band / F&O)
 *   low    = lower circuit price level  (0 = no static band / F&O)
 *   close  = band % (5 / 10 / 20; 0 = no static band / F&O)
 *   volume = ASM stage code — see {@link AsmStageCode}
 * </pre>
 *
 * @param date      Trade date (IST)
 * @param symbol    NSE ticker
 * @param series    NSE series (EQ, BE, etc.)
 * @param prevClose Previous close price
 * @param lowerBand Lower circuit price level (0 if none)
 * @param upperBand Upper circuit price level (0 if none)
 * @param bandPct   Circuit band percentage (0 if no static band)
 * @param asmCode   Encoded ASM stage — see {@link AsmStageCode}
 * @param asmType   Raw ASM type text (STASM / LTASM / GSM / NONE)
 * @param asmStage  Raw ASM stage text (I / II / III / empty)
 */
public record MergedRecord(
        LocalDate date,
        String    symbol,
        String    series,
        double    prevClose,
        double    lowerBand,
        double    upperBand,
        double    bandPct,
        int       asmCode,
        String    asmType,
        String    asmStage
) {}
