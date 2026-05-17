package com.nse.feed;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Handles all HTTP communication with NSE India.
 *
 * ── URL Strategy (verified 2026-05-17) ────────────────────────────────────────
 *
 * FULL price band master (all EQ symbols):
 *   https://nsearchives.nseindia.com/content/equities/sec_list_{ddmmyyyy}.csv
 *   - Published on the CURRENT trading day
 *   - No session/cookie needed — direct public URL
 *   - ~2800 rows: SYMBOL, NAME_OF_COMPANY, SERIES, FACE_VALUE, PRICE_BAND ...
 *
 * DELTA band changes (changed symbols only):
 *   https://nsearchives.nseindia.com/content/equities/eq_band_changes_{ddmmyyyy}.csv
 *   - IMPORTANT: filename carries NEXT trading day's date
 *     e.g. changes effective Monday are in file eq_band_changes_19052026.csv
 *          published after Friday market close (16-May-2026 run)
 *   - No session/cookie needed
 *   - ~5-50 rows: SYMBOL, SERIES, PRICE_BAND_NEW, PRICE_BAND_OLD, EFFECTIVE_DATE, REMARKS
 *
 * ASM surveillance list:
 *   https://www.nseindia.com/api/asm-securities?asmType=shortterm
 *   https://www.nseindia.com/api/asm-securities?asmType=longterm
 *   - Requires session warm-up (homepage GET → cookie capture → API call)
 *   - Fallback: derive from REMARKS column in eq_band_changes (contains "ASM Stage N")
 *
 * ── What NOT to use ────────────────────────────────────────────────────────────
 *   https://www.nseindia.com/api/reports?archives=...  ← requires live browser session
 *   Returns 404 for all files from headless Java HTTP clients.
 */
public class NseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NseClient.class);

    // ── Base URLs ─────────────────────────────────────────────────────────────
    /** Public archive — no session needed. Direct file downloads. */
    public static final String ARCHIVE_BASE =
            "https://nsearchives.nseindia.com/content/equities/";

    /** Session-gated API base — requires cookie warm-up first. */
    public static final String NSE_BASE = "https://www.nseindia.com";

    /** ASM securities API endpoints (session required). */
    public static final String ASM_SHORTTERM =
            NSE_BASE + "/api/asm-securities?asmType=shortterm";
    public static final String ASM_LONGTERM  =
            NSE_BASE + "/api/asm-securities?asmType=longterm";

    /**
     * Date format for nsearchives filenames: ddmmyyyy (no separators).
     * Example: 16 May 2026 → "16052026"
     */
    public static final DateTimeFormatter FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("ddMMuuuu");

    /** Friendly display format for log messages. */
    public static final DateTimeFormatter DISPLAY_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/124.0.0.0 Safari/537.36";

    // ── HTTP client (Apache HC5, cookie-aware) ────────────────────────────────
    private final BasicCookieStore cookieStore;
    private final CloseableHttpClient http;

    /** Tracks whether warmUpSession() has been called this JVM run. */
    private boolean sessionWarmed = false;

    public NseClient() {
        cookieStore = new BasicCookieStore();
        http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();
    }

    // ── Session warm-up (for session-gated API calls only) ────────────────────

    /**
     * Warms up the NSE session by fetching the homepage and a secondary page.
     * This seeds the nsit + nseappid cookies required by /api/* endpoints.
     *
     * NOT needed for nsearchives.nseindia.com downloads.
     */
    public void warmUpSession() throws IOException {
        if (sessionWarmed) {
            log.debug("[NSE] Session already warmed — skipping.");
            return;
        }
        log.info("[NSE] Warm-up 1/2 — homepage...");
        doGet(NSE_BASE);
        sleep(1500);

        log.info("[NSE] Warm-up 2/2 — reports page...");
        doGet(NSE_BASE + "/reports/asm");
        sleep(1500);

        log.info("[NSE] Session ready. Cookies captured: {}", cookieStore.getCookies().size());
        sessionWarmed = true;
    }

    // ── Public download methods ───────────────────────────────────────────────

    /**
     * Downloads sec_list_{ddmmyyyy}.csv — full price band master for all EQ symbols.
     *
     * Tries up to 7 calendar days backwards from {@code forDate} to find the
     * latest available trading day file (handles bank holidays gracefully).
     *
     * @return CSV text, or null if no file found in 7-day window
     */
    public String downloadSecList(LocalDate forDate) throws IOException {
        for (int i = 0; i <= 7; i++) {
            LocalDate candidate = forDate.minusDays(i);
            if (isWeekend(candidate)) continue;
            String url = ARCHIVE_BASE + "sec_list_" + candidate.format(FILE_DATE_FMT) + ".csv";
            log.info("[NSE] Trying sec_list: {}", url);
            String body = getArchiveText(url);
            if (body != null) {
                log.info("[NSE] sec_list found for trading day: {}",
                        candidate.format(DISPLAY_DATE_FMT));
                return body;
            }
        }
        log.warn("[NSE] sec_list not found in last 7 days (forDate={}).", forDate);
        return null;
    }

    /**
     * Downloads eq_band_changes_{ddmmyyyy}.csv — delta band changes.
     *
     * IMPORTANT: NSE names this file with the NEXT trading day's date.
     * Strategy: try nextTradingDay(runDate) first, then runDate, then prevTradingDay.
     * This handles runs at different times (pre-publish, post-publish, next-morning).
     *
     * @return CSV text, or null if no delta file published yet for this cycle
     */
    public String downloadBandChanges(LocalDate runDate) throws IOException {
        LocalDate[] candidates = {
            nextTradingDay(runDate),  // primary: file named with next trading day
            runDate,                  // fallback 1: sometimes same-day naming
            prevTradingDay(runDate)   // fallback 2: already-published from yesterday run
        };

        for (LocalDate candidate : candidates) {
            String url = ARCHIVE_BASE + "eq_band_changes_" + candidate.format(FILE_DATE_FMT) + ".csv";
            log.info("[NSE] Trying eq_band_changes: {}", url);
            String body = getArchiveText(url);
            if (body != null) {
                log.info("[NSE] eq_band_changes found (file date-label: {}).",
                        candidate.format(DISPLAY_DATE_FMT));
                return body;
            }
        }
        log.info("[NSE] eq_band_changes not available for runDate={}.", runDate);
        return null;
    }

    /**
     * Downloads ASM securities via session-gated API.
     * Calls warmUpSession() automatically if not already done.
     *
     * Returns combined shortterm + longterm JSON string, or null on failure.
     * On HTTP error the caller should fall back to deriving ASM from band-change REMARKS.
     */
    public String downloadAsmJson() throws IOException {
        warmUpSession(); // idempotent
        sleep(500);

        String shortTerm = getWithSession(ASM_SHORTTERM, "reports/asm");
        String longTerm  = getWithSession(ASM_LONGTERM,  "reports/asm");

        if (shortTerm == null && longTerm == null) {
            log.warn("[NSE] ASM session API returned null for both endpoints.");
            return null;
        }
        // Return whichever succeeded — AsmDownloader merges both
        if (shortTerm != null && longTerm != null) {
            // Combine: strip trailing ] from shortTerm, strip leading [ from longTerm
            String st = shortTerm.trim();
            String lt = longTerm.trim();
            // Both may be wrapped in {"data":[...]} — caller handles parsing
            return "{\"shortterm\":" + st + ",\"longterm\":" + lt + "}";
        }
        return shortTerm != null ? shortTerm : longTerm;
    }

    // ── Core HTTP helpers ─────────────────────────────────────────────────────

    /**
     * GET from nsearchives (no session needed).
     * Returns response body as UTF-8 String, or null on 404/403.
     * Throws IOException on other non-200 statuses.
     */
    public String getArchiveText(String url) throws IOException {
        try (CloseableHttpResponse resp = http.execute(buildGet(url, null))) {
            int status = resp.getCode();
            if (status == 200) {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                // Guard against empty or HTML error page
                if (body == null || body.isBlank() || body.stripLeading().startsWith("<")) {
                    log.debug("[NSE] 200 but empty/HTML body for: {}", url);
                    return null;
                }
                return body;
            }
            if (status == 404 || status == 403) {
                log.debug("[NSE] HTTP {} (not available): {}", status, url);
                EntityUtils.consume(resp.getEntity());
                return null;
            }
            EntityUtils.consume(resp.getEntity());
            throw new IOException("NSE archive HTTP " + status + " for: " + url);
        }
    }

    /**
     * GET with session cookies (for nseindia.com /api/* endpoints).
     * Returns response body, or null on 404/403/401.
     */
    public String getWithSession(String url, String refererPath) throws IOException {
        HttpGet req = buildGet(url, refererPath);
        req.setHeader("Accept", "application/json, text/plain, */*");
        req.setHeader("X-Requested-With", "XMLHttpRequest");
        try (CloseableHttpResponse resp = http.execute(req)) {
            int status = resp.getCode();
            log.info("[NSE] {} → HTTP {}", url, status);
            if (status == 200) {
                return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            }
            EntityUtils.consume(resp.getEntity());
            log.warn("[NSE] Session API HTTP {} for: {}", status, url);
            return null;
        }
    }

    // ── Date utilities (static, reusable across the package) ─────────────────

    public static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY
            || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** Next calendar day that is not Saturday or Sunday. */
    public static LocalDate nextTradingDay(LocalDate d) {
        LocalDate next = d.plusDays(1);
        while (isWeekend(next)) next = next.plusDays(1);
        return next;
    }

    /** Previous calendar day that is not Saturday or Sunday. */
    public static LocalDate prevTradingDay(LocalDate d) {
        LocalDate prev = d.minusDays(1);
        while (isWeekend(prev)) prev = prev.minusDays(1);
        return prev;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void doGet(String url) throws IOException {
        try (CloseableHttpResponse resp = http.execute(buildGet(url, null))) {
            EntityUtils.consume(resp.getEntity());
        }
    }

    private HttpGet buildGet(String url, String refererPath) {
        HttpGet req = new HttpGet(url);
        req.setHeader("User-Agent", UA);
        req.setHeader("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        req.setHeader("Accept-Language", "en-US,en;q=0.9");
        req.setHeader("Referer",
                refererPath != null ? NSE_BASE + "/" + refererPath : NSE_BASE + "/");
        req.setHeader("Connection", "keep-alive");
        return req;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() throws IOException {
        http.close();
    }
}
