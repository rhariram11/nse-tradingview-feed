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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Handles all HTTP communication with NSE India.
 *
 * NSE Anti-Bot Protocol (MUST follow in order):
 *   1. GET https://www.nseindia.com                    → seeds nsit + nseappid cookies
 *   2. GET https://www.nseindia.com/market-data/       → seeds additional session cookies
 *   3. All subsequent API / file download calls must carry:
 *        - Cookie header  (auto-managed by BasicCookieStore)
 *        - Referer        https://www.nseindia.com/
 *        - X-Requested-With: XMLHttpRequest
 *
 * Non-trading days (weekends, holidays):
 *   NSE returns HTTP 404 for date-specific report files.
 *   Use getOrNull() instead of get() when a missing file is acceptable.
 *   get() throws IOException on 404; getOrNull() returns null silently.
 */
public class NseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NseClient.class);

    public static final String BASE_URL = "https://www.nseindia.com";

    /** Date format NSE expects in report API query params: 17-May-2026 */
    public static final DateTimeFormatter NSE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/124.0.0.0 Safari/537.36";

    // ── NSE archive JSON templates ────────────────────────────────────────────
    //
    // Full price band (circuit file) – all EQ symbols with current band %
    // File: circuit_DDMMYYYY.csv
    private static final String ARCHIVE_FULL_BAND =
            "[{\"name\":\"Security Wise Daily Price Band\","
          + "\"type\":\"daily\","
          + "\"category\":\"capital-market\","
          + "\"section\":\"equity\"}]";

    // Delta band changes – only symbols whose band % changed today
    // File: eq_band_changes_DDMMYYYY.csv
    // Columns: Symbol, Series, New Band, Old Band, Effective Date
    private static final String ARCHIVE_DELTA_BAND =
            "[{\"name\":\"Equity Band Change\","
          + "\"type\":\"daily\","
          + "\"category\":\"capital-market\","
          + "\"section\":\"equity\"}]";

    // ASM / STASM / LTASM surveillance list
    // File: asm_DDMMYYYY.csv
    // NSE uses two slightly different name strings — we try primary first,
    // then fall back to secondary if we get a 404.
    public static final String ARCHIVE_ASM_PRIMARY =
            "[{\"name\":\"Additional Surveillance Measure (ASM)\","
          + "\"type\":\"daily\","
          + "\"category\":\"surveillance\","
          + "\"section\":\"equity\"}]";

    public static final String ARCHIVE_ASM_FALLBACK =
            "[{\"name\":\"ASM\","
          + "\"type\":\"daily\","
          + "\"category\":\"surveillance\","
          + "\"section\":\"equity\"}]";

    private static final String REPORT_API = BASE_URL + "/api/reports";

    // ── HTTP client ───────────────────────────────────────────────────────────
    private final BasicCookieStore cookieStore;
    private final CloseableHttpClient http;

    public NseClient() {
        cookieStore = new BasicCookieStore();
        http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();
    }

    // ── Session warm-up ───────────────────────────────────────────────────────

    /**
     * Two-step warm-up required by NSE to receive session cookies.
     * Must be called once per JVM run before any download.
     */
    public void warmUpSession() throws IOException {
        log.info("[NSE] Warm-up 1/2 – homepage...");
        doGet(BASE_URL);
        sleep(1500);

        log.info("[NSE] Warm-up 2/2 – market-data page...");
        doGet(BASE_URL + "/market-data/securities-available-for-trading");
        sleep(1500);

        log.info("[NSE] Session ready. Cookies: {}", cookieStore.getCookies().size());
    }

    // ── Typed download methods ────────────────────────────────────────────────

    /**
     * Full price band circuit file for given date.
     * File: circuit_DDMMYYYY.csv (~2000 rows)
     * Returns null if NSE returns 404 (non-trading day / holiday).
     */
    public byte[] downloadFullBandCsv(LocalDate date) throws IOException {
        String url = buildReportUrl(ARCHIVE_FULL_BAND, date);
        log.info("[NSE] Downloading FULL band file: {}", url);
        return getOrNull(url);
    }

    /**
     * Delta band-change file for given date.
     * File: eq_band_changes_DDMMYYYY.csv (~10-50 rows, changed symbols only)
     * Returns null if NSE returns 404 (non-trading day / no changes).
     */
    public byte[] downloadDeltaBandCsv(LocalDate date) throws IOException {
        String url = buildReportUrl(ARCHIVE_DELTA_BAND, date);
        log.info("[NSE] Downloading DELTA band file: {}", url);
        return getOrNull(url);
    }

    /**
     * ASM/STASM/LTASM surveillance list for given date.
     * File: asm_DDMMYYYY.csv
     * Tries primary archive name first; falls back to secondary on 404.
     * Returns null if both return 404 (non-trading day).
     */
    public byte[] downloadAsmCsv(LocalDate date) throws IOException {
        // Try primary archive name
        String url = buildReportUrl(ARCHIVE_ASM_PRIMARY, date);
        log.info("[NSE] Downloading ASM file (primary): {}", url);
        byte[] bytes = getOrNull(url);

        if (bytes == null) {
            // 404 on primary → try fallback archive name
            String fallbackUrl = buildReportUrl(ARCHIVE_ASM_FALLBACK, date);
            log.info("[NSE] ASM primary 404 — trying fallback: {}", fallbackUrl);
            bytes = getOrNull(fallbackUrl);
        }

        if (bytes == null) {
            log.warn("[NSE] ASM file not available for date {} (non-trading day or holiday).", date);
        }
        return bytes; // null = not a trading day, caller handles gracefully
    }

    // ── Core HTTP methods ─────────────────────────────────────────────────────

    /**
     * GET url → bytes. Throws IOException on any non-200 status (including 404).
     * Use this when the file MUST exist.
     */
    public byte[] get(String url) throws IOException {
        try (CloseableHttpResponse resp = http.execute(buildGet(url))) {
            int status = resp.getCode();
            if (status != 200) {
                throw new IOException("NSE HTTP " + status + " for: " + url);
            }
            return EntityUtils.toByteArray(resp.getEntity());
        }
    }

    /**
     * GET url → bytes, or null if NSE returns 404 / 403.
     * Use this for date-specific report files that may not exist on holidays.
     */
    public byte[] getOrNull(String url) throws IOException {
        try (CloseableHttpResponse resp = http.execute(buildGet(url))) {
            int status = resp.getCode();
            if (status == 404 || status == 403) {
                log.debug("[NSE] HTTP {} for {} — treating as no-data (non-trading day)", status, url);
                EntityUtils.consume(resp.getEntity());
                return null;
            }
            if (status != 200) {
                throw new IOException("NSE HTTP " + status + " for: " + url);
            }
            return EntityUtils.toByteArray(resp.getEntity());
        }
    }

    // ── URL builder ───────────────────────────────────────────────────────────

    /**
     * Builds a verified NSE report download URL.
     * Pattern: /api/reports?archives=<encoded-json>&date=DD-MMM-YYYY&type=daily&mode=single
     */
    public String buildReportUrl(String archiveJson, LocalDate date) {
        String encodedArchive = URLEncoder.encode(archiveJson, StandardCharsets.UTF_8);
        String dateStr = date.format(NSE_DATE_FMT); // e.g. 17-May-2026
        return REPORT_API
                + "?archives=" + encodedArchive
                + "&date=" + dateStr
                + "&type=daily"
                + "&mode=single";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void doGet(String url) throws IOException {
        try (CloseableHttpResponse resp = http.execute(buildGet(url))) {
            EntityUtils.consume(resp.getEntity());
        }
    }

    private HttpGet buildGet(String url) {
        HttpGet req = new HttpGet(url);
        req.setHeader("User-Agent",       UA);
        req.setHeader("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        req.setHeader("Accept-Language",  "en-US,en;q=0.9");
        req.setHeader("Referer",          BASE_URL + "/");
        req.setHeader("X-Requested-With", "XMLHttpRequest");
        req.setHeader("Connection",       "keep-alive");
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
