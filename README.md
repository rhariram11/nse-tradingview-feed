# NSE → TradingView Feed

A Java batch ETL that:
1. Downloads **Daily Price Band** and **ASM / LTASM / STASM** CSVs from NSE India EOD.
2. Merges and formats them into per-symbol daily OHLCV-like seed files.
3. Commits the output to the `data/` folder so TradingView's `request.seed()` can consume them.

---

## Project Structure

```
nse-tradingview-feed/
├── src/main/java/com/nse/feed/
│   ├── Main.java                  # Entry point
│   ├── NseClient.java             # HTTP client with NSE cookie/header handling
│   ├── PriceBandDownloader.java   # Downloads & parses price band CSV
│   ├── AsmDownloader.java         # Downloads & parses ASM/LTASM/STASM CSV
│   ├── DataMerger.java            # Joins both datasets by symbol
│   ├── SeedWriter.java            # Writes per-symbol CSV in TradingView seed format
│   └── model/
│       ├── PriceBandRecord.java
│       ├── AsmRecord.java
│       └── MergedRecord.java
├── data/                          # Auto-generated output (committed by Actions)
│   └── <SYMBOL>.csv               # One file per NSE symbol
├── pom.xml
└── .github/workflows/nse-etl.yml  # Daily GitHub Actions workflow
```

---

## How it works

### NSE Data Sources
| Data | NSE URL | Frequency |
|------|---------|-----------|
| Daily Price Bands | `https://nseindia.com/api/equity-stockIndices?index=SECURITIES%20IN%20F%26O` + band report | EOD |
| ASM / LTASM / STASM | `https://nseindia.com/reports/asm` (CSV download) | EOD |

### TradingView Seed Format
Each `data/<SYMBOL>.csv` follows TradingView's `request.seed` column layout:
```
date,open,high,low,close,volume
2025-01-01,10,1250.5,1100.2,0,1
```
Field mapping:
| TV Field | Our Data |
|----------|----------|
| `close`  | Band % (e.g. 5, 10, 20; 0 = no static band / F&O) |
| `high`   | Upper circuit price level |
| `low`    | Lower circuit price level |
| `open`   | Previous close |
| `volume` | ASM stage code (0=none, 11=STASM-I, 12=STASM-II, 13=LTASM-I, 14=LTASM-II, 15=LTASM-III) |

### Pine Script Usage
```pine
//@version=6
indicator("NSE Band + ASM", overlay=true)

[bc, bh, bl, bo, bv] = request.seed("rhariram11/nse-tradingview-feed", syminfo.ticker, [close, high, low, open, volume])

bandPct  = bc   // circuit band %
upper    = bh   // upper circuit level
lower    = bl   // lower circuit level
asmStage = bv   // ASM code

// Display
plot(upper, "Upper Circuit", color.red,   2, plot.style_circles)
plot(lower, "Lower Circuit", color.green, 2, plot.style_circles)
```

---

## Running Locally

### Prerequisites
- Java 17+
- Maven 3.8+

```bash
mvn clean package
java -jar target/nse-feed-1.0.0.jar
```

Output CSVs will be written to `data/`.

---

## GitHub Actions
The workflow `.github/workflows/nse-etl.yml` runs **Monday–Saturday at 4:30 PM IST (11:00 UTC)** to capture EOD data after markets close at 3:30 PM IST.

The workflow:
1. Builds the JAR.
2. Runs the ETL.
3. Commits any changed `data/*.csv` files back to `main`.
