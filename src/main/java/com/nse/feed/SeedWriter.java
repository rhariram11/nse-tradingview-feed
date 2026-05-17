package com.nse.feed;

import com.nse.feed.model.MergedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Writes NSE metadata in TradingView {@code request.seed()} format.
 *
 * <h3>Two output strategies (both run simultaneously)</h3>
 * <ol>
 *   <li><b>NSE_METADATA.csv</b> — consolidated file: one row per symbol per date.
 *       Load in Pine via a fixed anchor ticker.  1 file, no 2000+ file explosion.</li>
 *   <li><b>data/{SYMBOL}.csv</b> — per-symbol files.
 *       Works directly with {@code request.seed(repo, syminfo.ticker, ...)}.</li>
 * </ol>
 *
 * <h3>Seed CSV column mapping</h3>
 * <pre>
 *   date   = trade date (yyyy-MM-dd)
 *   open   = previous close
 *   high   = upper circuit price level  (0 = none)
 *   low    = lower circuit price level  (0 = none)
 *   close  = band %  (5 / 10 / 20; 0 = no static band / F&O)
 *   volume = ASM stage code (see AsmStageCode)
 * </pre>
 */
public class SeedWriter {

    private static final Logger log = LoggerFactory.getLogger(SeedWriter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static final String CONSOLIDATED_FILENAME = "NSE_METADATA.csv";
    private static final String CONSOLIDATED_HEADER  = "date,symbol,series,open,high,low,close,volume";
    private static final String PER_SYMBOL_HEADER    = "date,open,high,low,close,volume";

    private final Path    dataDir;
    private final boolean writePerSymbol;

    public SeedWriter(String dataDirPath, boolean writePerSymbol) throws IOException {
        this.dataDir        = Paths.get(dataDirPath);
        this.writePerSymbol = writePerSymbol;
        Files.createDirectories(dataDir);
    }

    public SeedWriter(String dataDirPath) throws IOException {
        this(dataDirPath, true);
    }

    public void write(List<MergedRecord> records) throws IOException {
        if (records.isEmpty()) { log.info("[SeedWriter] Nothing to write."); return; }
        writeConsolidated(records);
        if (writePerSymbol) writePerSymbol(records);
    }

    // ── Consolidated ──────────────────────────────────────────────────────────

    private void writeConsolidated(List<MergedRecord> records) throws IOException {
        Path file    = dataDir.resolve(CONSOLIDATED_FILENAME);
        boolean isNew = !Files.exists(file);
        Set<String> existing = isNew ? Collections.emptySet() : loadExistingKeys(file);

        int written = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(
                file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (isNew) { bw.write(CONSOLIDATED_HEADER); bw.newLine(); }
            for (MergedRecord r : records) {
                String key = r.date().format(DATE_FMT) + "|" + r.symbol();
                if (existing.contains(key)) continue;
                bw.write(String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%d",
                    r.date().format(DATE_FMT), r.symbol(), r.series(),
                    r.prevClose(), r.upperBand(), r.lowerBand(),
                    r.bandPct(), r.asmCode()));
                bw.newLine();
                written++;
            }
        }
        log.info("[SeedWriter] Consolidated: {} rows.", written);
    }

    private Set<String> loadExistingKeys(Path file) throws IOException {
        Set<String> keys = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(file)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int c1 = line.indexOf(','), c2 = line.indexOf(',', c1 + 1);
                if (c1 < 0 || c2 < 0) continue;
                keys.add(line.substring(0, c1) + "|" + line.substring(c1 + 1, c2));
            }
        }
        return keys;
    }

    // ── Per-symbol ────────────────────────────────────────────────────────────

    private void writePerSymbol(List<MergedRecord> records) throws IOException {
        int written = 0;
        for (MergedRecord r : records) {
            Path file  = dataDir.resolve(r.symbol() + ".csv");
            boolean isNew = !Files.exists(file);
            if (!isNew && dateAlreadyExists(file, r.date().format(DATE_FMT))) continue;
            try (BufferedWriter bw = Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (isNew) { bw.write(PER_SYMBOL_HEADER); bw.newLine(); }
                bw.write(String.format("%s,%.2f,%.2f,%.2f,%.2f,%d",
                    r.date().format(DATE_FMT),
                    r.prevClose(), r.upperBand(), r.lowerBand(),
                    r.bandPct(), r.asmCode()));
                bw.newLine();
                written++;
            }
        }
        log.info("[SeedWriter] Per-symbol: {} files.", written);
    }

    private boolean dateAlreadyExists(Path file, String dateStr) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int start = Math.max(0, lines.size() - 5);
        for (int i = start; i < lines.size(); i++)
            if (lines.get(i).startsWith(dateStr)) return true;
        return false;
    }
}
