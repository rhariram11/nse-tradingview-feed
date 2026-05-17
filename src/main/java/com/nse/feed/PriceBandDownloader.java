package com.nse.feed;

import com.nse.feed.model.PriceBandRecord;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and parses the NSE Daily Price Band report.
 *
 * NSE publishes a CSV daily at:
 *   https://nseindia.com/api/equity-market?type=daily_price_bands
 *
 * Typical CSV columns (NSE format, may vary slightly):
 *   SYMBOL, SERIES, PREV_CLOSE, LOWER_BAND, UPPER_BAND, BAND_PERCENT
 *
 * Symbols on the F&O segment have NO static band (dynamic ±10% intraday).
 * The parser sets bandPercent=0 for those.
 */
public class PriceBandDownloader {

    private static final Logger log = LoggerFactory.getLogger(PriceBandDownloader.class);

    // Primary endpoint for daily security-wise price band
    private static final String PRICE_BAND_API =
            "https://www.nseindia.com/api/equity-market?type=daily_price_bands";

    // Fallback: the bhavcopy-style CSV (security-wise) which also carries band info
    private static final String BHAVCOPY_API =
            "https://www.nseindia.com/api/reports?archives=%5B%7B%22name%22%3A%22Security+Wise+Daily+Price+Band%22%2C%22type%22%3A%22daily%22%2C%22category%22%3A%22capital_market%22%2C%22section%22%3A%22equity%22%7D%5D&date=" +
            LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) + "&type=daily&mode=single";

    private final NseClient client;

    public PriceBandDownloader(NseClient client) {
        this.client = client;
    }

    public List<PriceBandRecord> download() throws IOException {
        log.info("Downloading Daily Price Band data...");
        byte[] raw;
        try {
            raw = client.get(PRICE_BAND_API);
        } catch (IOException e) {
            log.warn("Primary price band API failed ({}), trying bhavcopy fallback...", e.getMessage());
            raw = client.get(BHAVCOPY_API);
        }
        return parseCsv(new String(raw));
    }

    private List<PriceBandRecord> parseCsv(String csv) throws IOException {
        List<PriceBandRecord> records = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            String[] header = reader.readNext(); // skip / map header
            if (header == null) return records;

            // Detect column indices flexibly
            int idxSymbol    = findCol(header, "SYMBOL");
            int idxSeries    = findCol(header, "SERIES");
            int idxPrevClose = findCol(header, "PREV_CLOSE", "PREVCLOSE", "PREV CLOSE");
            int idxLower     = findCol(header, "LOWER_BAND", "LOWERBAND", "LOWER BAND", "LB");
            int idxUpper     = findCol(header, "UPPER_BAND", "UPPERBAND", "UPPER BAND", "UB");
            int idxPct       = findCol(header, "BAND_PERCENT", "BANDPCT", "BAND%", "PBAND");

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                try {
                    String symbol    = clean(row[idxSymbol]);
                    String series    = idxSeries >= 0 ? clean(row[idxSeries]) : "EQ";
                    double prevClose = parseDouble(row, idxPrevClose);
                    double lower     = parseDouble(row, idxLower);
                    double upper     = parseDouble(row, idxUpper);
                    double bandPct   = parseDouble(row, idxPct);

                    // 0 band = no static circuit (F&O stocks)
                    if (bandPct == 0 && lower == 0 && upper == 0) {
                        bandPct = 0; // explicitly no static band
                    }

                    records.add(new PriceBandRecord(symbol, series, prevClose, lower, upper, bandPct));
                } catch (Exception e) {
                    log.debug("Skipping malformed row: {}", String.join(",", row));
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("CSV parse error: " + e.getMessage(), e);
        }
        log.info("Parsed {} price band records.", records.size());
        return records;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private int findCol(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    private String clean(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private double parseDouble(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) return 0.0;
        String val = row[idx].trim().replace(",", "");
        if (val.isEmpty() || val.equals("-")) return 0.0;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0.0; }
    }
}
