package com.nse.feed;

import com.nse.feed.model.MergedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Generates {@code data/nse_udf_ohlc.csv} from the same MergedRecord list
 * that SeedWriter uses, WITHOUT touching nse_metadata.csv.
 *
 * <h3>OHLC field mapping (for TradingView UDF transport)</h3>
 * <pre>
 *   SYMBOL  → UDF ticker symbol
 *   open    → BAND_PCT         (e.g. 5.0 / 10.0 / 20.0 / 0 = no band)
 *   high    → LOWER_BAND       (absolute ₹ lower circuit price)
 *   low     → UPPER_BAND       (absolute ₹ upper circuit price)
 *   close   → ASM_CODE         (0=none, 11=STASM-I … 30=GSM)
 *   volume  → LAST_PRICE       (last traded price from ETL)
 * </pre>
 *
 * Why OHLC packing?
 * TradingView's {@code request.security()} on a custom UDF feed can only
 * access standard OHLCV fields.  Packing metadata into those 5 slots is
 * the only way to push 5 independent numeric values through a Pine script
 * today.  When more custom-field support is needed, migrate to a proper
 * UDF {@code custom_fields} extension.
 *
 * Written atomically (.tmp → rename) — never partially visible to the UDF server.
 */
public class OhlcExportWriter {

    private static final Logger log = LoggerFactory.getLogger(OhlcExportWriter.class);

    /** Output file name — deliberately separate from nse_metadata.csv */
    private static final String OHLC_FILE     = "nse_udf_ohlc.csv";
    private static final String OHLC_FILE_TMP = "nse_udf_ohlc.csv.tmp";

    /**
     * Column header contract.  UdfPusher and Pine must agree on this order.
     * <pre>
     *   symbol, open, high, low, close, volume
     *   ↓
     *   symbol, BAND_PCT, LOWER_BAND, UPPER_BAND, ASM_CODE, LAST_PRICE
     * </pre>
     */
    private static final String HEADER =
            "symbol,open,high,low,close,volume";

    private final Path dataDir;

    public OhlcExportWriter(String dataDir) {
        this.dataDir = Paths.get(dataDir);
    }

    /**
     * Writes all records to {@code data/nse_udf_ohlc.csv}.
     * {@code nse_metadata.csv} is never read or modified by this class.
     *
     * @param records the same list passed to {@link SeedWriter#write(List)}
     * @throws IOException if the file cannot be written
     */
    public void write(List<MergedRecord> records) throws IOException {
        Files.createDirectories(dataDir);
        Path tmp    = dataDir.resolve(OHLC_FILE_TMP);
        Path output = dataDir.resolve(OHLC_FILE);

        try (BufferedWriter bw = Files.newBufferedWriter(
                tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            bw.write(HEADER);
            bw.newLine();

            for (MergedRecord r : records) {
                bw.write(toRow(r));
                bw.newLine();
            }
        }

        Files.move(tmp, output,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        log.info("[OhlcExportWriter] {} rows → {}", records.size(), output.toAbsolutePath());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Formats one MergedRecord as a CSV row using the OHLC packing convention.
     *
     * <ul>
     *   <li>open   = BAND_PCT</li>
     *   <li>high   = LOWER_BAND</li>
     *   <li>low    = UPPER_BAND</li>
     *   <li>close  = ASM_CODE  (integer code, never fractional)</li>
     *   <li>volume = LAST_PRICE</li>
     * </ul>
     */
    private String toRow(MergedRecord r) {
        return String.join(",",
                r.symbol(),                           // symbol
                fmt(r.bandPct()),                    // open  = BAND_PCT
                fmt(r.lowerBand()),                  // high  = LOWER_BAND
                fmt(r.upperBand()),                  // low   = UPPER_BAND
                String.valueOf(r.asmCode().code),    // close = ASM_CODE
                fmt(r.lastPrice()));                 // volume= LAST_PRICE
    }

    /** Formats a double: whole numbers as integers, others to 2dp. */
    private String fmt(double v) {
        return (v == Math.floor(v) && !Double.isInfinite(v))
               ? String.valueOf((long) v)
               : String.format("%.2f", v);
    }
}
