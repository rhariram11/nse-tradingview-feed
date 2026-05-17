package com.nse.feed.model;

/**
 * Immutable price-band record for one NSE symbol.
 *
 * <p>bandLabel : human-readable band string exactly as NSE publishes it
 *              e.g. "20%", "5%", "No Band"
 * <p>bandPct   : numeric equivalent (0.0 when label is "No Band")
 * <p>remarks   : REMARKS column from eq_band_changes (delta path only, mutable)
 */
public class PriceBandRecord {

    private final String symbol;
    private final String series;
    private final double lastPrice;
    private final double lowerBand;
    private final double upperBand;
    private final double bandPct;
    private final String bandLabel;
    private String       remarks = "";

    /** Full constructor — preserves raw bandLabel from the CSV cell. */
    public PriceBandRecord(String symbol, String series,
                           double lastPrice, double lowerBand, double upperBand,
                           double bandPct, String bandLabel) {
        this.symbol    = symbol;
        this.series    = series;
        this.lastPrice = lastPrice;
        this.lowerBand = lowerBand;
        this.upperBand = upperBand;
        this.bandPct   = bandPct;
        this.bandLabel = (bandLabel == null || bandLabel.isBlank())
                         ? deriveLabelFromPct(bandPct) : bandLabel;
    }

    /** Legacy constructor — derives bandLabel from bandPct (backward compat). */
    public PriceBandRecord(String symbol, String series,
                           double lastPrice, double lowerBand, double upperBand,
                           double bandPct) {
        this(symbol, series, lastPrice, lowerBand, upperBand,
             bandPct, deriveLabelFromPct(bandPct));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getSymbol()    { return symbol; }
    public String getSeries()    { return series; }
    public double getLastPrice() { return lastPrice; }
    public double getLowerBand() { return lowerBand; }
    public double getUpperBand() { return upperBand; }
    public double getBandPct()   { return bandPct; }
    public String getBandLabel() { return bandLabel; }
    public String getRemarks()   { return remarks; }
    public void   setRemarks(String r) { this.remarks = r == null ? "" : r.trim(); }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Derives a display label from the numeric band %.
     *  0.0  → "No Band"
     * 20.0  → "20%"
     *  5.5  → "5.5%"
     */
    public static String deriveLabelFromPct(double pct) {
        if (pct <= 0.0) return "No Band";
        return (pct == Math.floor(pct)) ? (int) pct + "%" : pct + "%";
    }

    @Override
    public String toString() {
        return symbol + "/" + series
             + " band=" + bandLabel
             + " last=" + lastPrice
             + " lo=" + lowerBand + " hi=" + upperBand;
    }
}
