package com.nse.feed.model;

/**
 * Immutable record representing one row from the NSE Daily Price Band report.
 *
 * @param symbol      NSE ticker symbol (e.g. RELIANCE)
 * @param series      NSE series (EQ, BE, SM, etc.)
 * @param prevClose   Previous day closing price
 * @param lowerBand   Absolute lower circuit price level
 * @param upperBand   Absolute upper circuit price level
 * @param bandPercent Percentage band (2, 5, 10, 20; 0 = no static band)
 */
public record PriceBandRecord(
        String symbol,
        String series,
        double prevClose,
        double lowerBand,
        double upperBand,
        double bandPercent
) {}
