package com.nse.feed.model;

/**
 * Represents one row from either:
 *   - sec_list_{ddmmyyyy}.csv    (full band master)
 *   - eq_band_changes_{ddmmyyyy}.csv  (delta changes)
 *
 * The {@code remarks} field is populated from eq_band_changes REMARKS column.
 * It carries ASM context (e.g. "ASM Stage 1", "LTASM Stage II") used by
 * AsmDownloader.deriveFromRemarks() as a fallback when the session API is unavailable.
 */
public class PriceBandRecord {

    private final String symbol;
    private final String series;
    private final double lastPrice;
    private final double lowerBand;
    private final double upperBand;
    private final double bandPct;

    /** From REMARKS column in eq_band_changes — may be null for full sec_list records. */
    private String remarks;

    public PriceBandRecord(String symbol, String series,
                           double lastPrice, double lowerBand,
                           double upperBand, double bandPct) {
        this.symbol    = symbol;
        this.series    = series;
        this.lastPrice = lastPrice;
        this.lowerBand = lowerBand;
        this.upperBand = upperBand;
        this.bandPct   = bandPct;
    }

    public String getSymbol()    { return symbol; }
    public String getSeries()    { return series; }
    public double getLastPrice() { return lastPrice; }
    public double getLowerBand() { return lowerBand; }
    public double getUpperBand() { return upperBand; }
    public double getBandPct()   { return bandPct; }

    public String getRemarks()           { return remarks; }
    public void   setRemarks(String r)   { this.remarks = r; }

    @Override
    public String toString() {
        return symbol + "|" + series + "|band=" + bandPct + "%"
             + (remarks != null && !remarks.isEmpty() ? "|" + remarks : "");
    }
}
