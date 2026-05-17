package com.nse.feed;

import com.nse.feed.model.MergedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes per-symbol seed CSV files in TradingView request.seed() format.
 *
 * Output directory structure:
 *   data/<SYMBOL>.csv
 *
 * TradingView seed CSV format (append mode: new date rows added daily):
 *   date,open,high,low,close,volume
 *
 * Field mapping:
 *   date   = trade date (YYYY-MM-DD)
 *   open   = previous close price
 *   high   = upper circuit level (0 if no static band)
 *   low    = lower circuit level (0 if no static band)
 *   close  = band percent  (5 / 10 / 20; 0 = no static band / F&O stock)
 *   volume = ASM stage code (0=none, 11=STASM-I, 12=STASM-II, 13=LTASM-I,
 *                            14=LTASM-II, 15=LTASM-III, 20=GSM)
 */
public class SeedWriter {

    private static final Logger log = LoggerFactory.getLogger(SeedWriter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String HEADER = "date,open,high,low,close,volume";

    private final Path dataDir;

    public SeedWriter(String dataDirPath) throws IOException {
        this.dataDir = Paths.get(dataDirPath);
        Files.createDirectories(dataDir);
    }

    public void write(List<MergedRecord> records) throws IOException {
        int written = 0;
        for (MergedRecord rec : records) {
            Path file = dataDir.resolve(rec.symbol() + ".csv");
            boolean isNew = !Files.exists(file);

            try (BufferedWriter bw = Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                // Write header only for new files
                if (isNew) {
                    bw.write(HEADER);
                    bw.newLine();
                } else {
                    // Avoid duplicate date entries on re-run
                    if (dateAlreadyExists(file, rec.date().format(DATE_FMT))) {
                        log.debug("Skipping duplicate date {} for {}", rec.date(), rec.symbol());
                        continue;
                    }
                }

                String line = String.format("%s,%.2f,%.2f,%.2f,%.2f,%d",
                        rec.date().format(DATE_FMT),
                        rec.prevClose(),
                        rec.upperBand(),
                        rec.lowerBand(),
                        rec.bandPercent(),
                        rec.asmCode());
                bw.write(line);
                bw.newLine();
                written++;
            }
        }
        log.info("Seed files written/updated: {}", written);
    }

    /** Quick check: scan last few lines of existing file to detect duplicate date. */
    private boolean dateAlreadyExists(Path file, String dateStr) throws IOException {
        List<String> lines = Files.readAllLines(file);
        // Check last 5 lines for efficiency
        int start = Math.max(0, lines.size() - 5);
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).startsWith(dateStr)) return true;
        }
        return false;
    }
}
