# nse-tradingview-feed

Java ETL that pulls NSE daily price band and ASM/LTASM data, formats it for
TradingView `request.seed()`, and auto-publishes via GitHub Actions.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    GitHub Actions (daily cron)                   │
│                          ↓                                      │
│                       Main.java                                 │
│                          │                                      │
│          ┌───────────────┴──────────────┐                       │
│          │                              │                       │
│   COLD-START mode               DELTA mode                      │
│   (first run / Monday           (every other trading day)       │
│    / --force-full)                                              │
│          │                              │                       │
│  PriceBandDownloader          DeltaBandUpdater                  │
│  circuit_DDMMYYYY.csv    eq_band_changes_DDMMYYYY.csv           │
│  (~2000 rows, ALL EQ)    (~10-50 rows, CHANGED only)            │
│          │                              │                       │
│          └──────────┬───────────────────┘                       │
│                     │                                           │
│              AsmDownloader (always full refresh)                │
│              asm_DDMMYYYY.csv (~100-300 rows)                   │
│                     │                                           │
│                  DataMerger                                     │
│           mergeFull() / mergeDelta()                            │
│                     │                                           │
│                  SeedWriter                                     │
│              data/<SYMBOL>.csv                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Run Modes

### Cold-Start (Full Extract)

Triggered automatically when **any** of the following is true:

| Condition | Reason |
|---|---|
| `data/cold_start.done` sentinel missing | First-ever run on fresh checkout |
| Today is **Monday** | Weekly re-baseline to prevent drift |
| `--force-full` CLI flag passed | Manual override |

Downloads `circuit_DDMMYYYY.csv` — the full NSE price band file containing
**all ~2000 EQ symbols** with their current band %. After a successful
cold-start the sentinel file `data/cold_start.done` is written and committed
so that the next daily run knows the baseline exists.

### Delta (Incremental Daily)

Every other trading day (Tue–Fri, no forced flag):

Downloads `eq_band_changes_DDMMYYYY.csv` — **only symbols whose price band
changed today** (~10–50 rows). Only those symbols' seed files are updated;
all others are untouched. This makes the daily run extremely fast.

---

## NSE API Endpoints (Verified May 2026)

All downloads use the NSE reports API:
```
GET https://www.nseindia.com/api/reports
    ?archives=<url-encoded-json>
    &date=DD-MMM-YYYY
    &type=daily
    &mode=single
```

| File | archives name | When used |
|---|---|---|
| `circuit_DDMMYYYY.csv` | Security Wise Daily Price Band | Cold-start |
| `eq_band_changes_DDMMYYYY.csv` | Equity Band Change | Daily delta |
| `asm_DDMMYYYY.csv` | Additional Surveillance Measure (ASM) | Every run |

**Session warm-up (mandatory before any download):**
```
1. GET https://www.nseindia.com
2. GET https://www.nseindia.com/market-data/securities-available-for-trading
   (1.5s pause between each)
```
Without the two-step warm-up NSE returns 401 or an empty response.

---

## TradingView Seed Format

Each symbol gets a file `data/<SYMBOL>.csv` appended with one row per trading day:

```
date,open,high,low,close,volume
2026-05-16,0.00,0.00,0.00,5.00,0
2026-05-17,0.00,0.00,0.00,10.00,13
```

| Column | Contains | Example |
|---|---|---|
| `date` | Trade date | 2026-05-17 |
| `open` | Previous close (0 if from delta/circuit) | 245.30 |
| `high` | Upper circuit level (0 if no static band) | 0 |
| `low` | Lower circuit level (0 if no static band) | 0 |
| `close` | Band % (5 / 10 / 20 / 0 = no band) | 5 |
| `volume` | ASM stage code (see below) | 13 |

**ASM stage codes:**

| Code | Meaning |
|---|---|
| 0 | Not under surveillance |
| 11 | STASM Stage I |
| 12 | STASM Stage II |
| 13 | LTASM Stage I |
| 14 | LTASM Stage II |
| 15 | LTASM Stage III |
| 20 | GSM |

---

## Building & Running

```bash
# Build
mvn clean package -q

# Run (delta mode — normal daily)
java -jar target/nse-tradingview-feed.jar

# Run cold-start manually
java -jar target/nse-tradingview-feed.jar --force-full

# Override trade date (e.g. backfill a missed day)
java -jar target/nse-tradingview-feed.jar --date 2026-05-16

# Force full extract for a specific date
java -jar target/nse-tradingview-feed.jar --force-full --date 2026-05-16
```

---

## GitHub Actions

The workflow runs daily at **04:30 IST (23:00 UTC)** — after NSE publishes
end-of-day files. Monday runs automatically use cold-start mode.

---

## Sentinel File

`data/cold_start.done` — created after every successful cold-start run.
Contents:
```
Last full extract: 2026-05-12
Next full extract: next Monday or --force-full
```
This file is committed by GitHub Actions so subsequent runs on the same
runner (or a fresh checkout) know whether a baseline already exists.
