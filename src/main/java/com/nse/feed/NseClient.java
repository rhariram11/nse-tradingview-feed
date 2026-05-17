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
 *        - Accept         text/html,*\/*
 *        - User-Agent     Chrome-like UA string
 *
 * Without the two-step warm-up NSE returns 401 / empty body.
 *
 * Verified live endpoints (May 2026):
 *   Full circuit file  : /api/reports?archives=...&date=DD-MMM-YYYY  → circuit_DDMMYYYY.csv
 *   Delta band changes : /api/reports?archives=...&date=DD-MMM-YYYY  → eq_band_changes_DDMMYYYY.csv
 *   ASM list           : /api/reports?archives=...&date=DD-MMM-YYYY  → asm_DDMMYYYY.csv
 */
public class NseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NseClient.class);

    public static final String BASE_URL = "https://www.nseindia.com";

    // Date format NSE expects in its report API query params
    private static final DateTimeFormatter NSE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"); // e.g. 17-May-2026

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/124.0.0.0 Safari/537.36";

    // ── NSE Report archive param templates ──────────────────────────────────
    //
    // NSE's report download API uses a JSON array in the 'archives' query param.
    // Each object has: name, type, category, section.
    //
    // Full price band (circuit file) – all EQ symbols with their current band %
    //   File name: circuit_DDMMYYYY.csv
    private static final String ARCHIVE_FULL_BAND =
            "[{\"name\":\"Security Wise Daily Price Band\","
          + "\"type\":\"daily\","
          + "\"category\":\"capital-market\","
          + "\"section\":\"equity\"}]";

    // Delta band changes – only symbols whose band % changed today
    //   File name: eq_band_changes_DDMMYYYY.csv
    //   Columns  : Symbol, Series, New Band, Old Band, Effective Date
    private static final String ARCHIVE_DELTA_BAND =
            "[{\"name\":\"Equity Band Change\","
          + "\"type\":\"daily\","
          + "\"category\":\"capital-market\","
          + "\"section\":\"equity\"}]";

    // ASM / STASM / LTASM surveillance list
    //   File name: asm_DDMMYYYY.csv
    private static final String ARCHIVE_ASM =
            "[{\"name\":\"Additional Surveillance Measure (ASM)\","
          + "\"type\":\"daily\","
          + "\"category\":\"surveillance\","
          + "\"section\":\"equity\"}]";

    private static final String REPORT_API = BASE_URL + "/api/reports";

    // ── HTTP client ──────────────────────────────────────────────────────────
    private final BasicCookieStore cookieStore;
    private final CloseableHttpClient http;

    public NseClient() {
        cookieStore = new BasicCookieStore();
        http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();
    }

    // ── Session warm-up ──────────────────────────────────────────────────────

    /**
     * Two-step warm-up required by NSE to receive session cookies before any
     * data download. Must be called once per JVM run before any get() call.
     */
    public void warmUpSession() throws IOException {
        log.info("[NSE] Warm-up step 1/2 – homepage...");
        doGet(BASE_URL);
        sleep(1500);

        log.info("[NSE] Warm-up step 2/2 – market-data page...");
        doGet(BASE_URL + "/market-data/securities-available-for-trading");
        sleep(1500);

        log.info("[NSE] Session ready. Cookies acquired: {}", cookieStore.getCookies().size());
    }

    // ── Public download methods ──────────────────────────────────────────────

    /**
     * Downloads the FULL price band circuit file for the given date.
     * File: circuit_DDMMYYYY.csv  (~2000 rows, all EQ symbols)
     * Use on: first run (cold-start), Mondays, or --force-full.
     *
     * @param date trade date (usually today or last trading day)
     * @return raw CSV bytes
     */
    public byte[] downloadFullBandCsv(LocalDate date) throws IOException {
        String url = buildReportUrl(ARCHIVE_FULL_BAND, date);
        log.info("[NSE] Downloading FULL band file: {}", url);
        return get(url);
    }

    /**
     * Downloads the DELTA band-change file for the given date.
     * File: eq_band_changes_DDMMYYYY.csv  (~10-50 rows, only changed symbols)
     * Use on: every regular trading day after cold-start is complete.
     *
     * Columns: Symbol, Series, New Band, Old Band, Effective Date
     *
     * @param date trade date
     * @return raw CSV bytes (may be empty / single header line if no changes today)
     */
    public byte[] downloadDeltaBandCsv(LocalDate date) throws IOException {
        String url = buildReportUrl(ARCHIVE_DELTA_BAND, date);
        log.info("[NSE] Downloading DELTA band-change file: {}", url);
        return get(url);
    }

    /**
     * Downloads the ASM/STASM/LTASM surveillance list for the given date.
     * File: asm_DDMMYYYY.csv
     *
     * @param date trade date
     * @return raw CSV bytes
     */
    public byte[] downloadAsmCsv(LocalDate date) throws IOException {
        String url = buildReportUrl(ARCHIVE_ASM, date);
        log.info("[NSE] Downloading ASM file: {}", url);
        return get(url);
    }

    // ── Core HTTP GET ────────────────────────────────────────────────────────

    /** Generic GET – returns response bytes. Throws IOException on non-200. */
    public byte[] get(String url) throws IOException {
        log.debug("GET {}", url);
        try (CloseableHttpResponse resp = http.execute(buildGet(url))) {
            int status = resp.getCode();
            if (status != 200) {
                throw new IOException("NSE HTTP " + status + " for: " + url);
            }
            return EntityUtils.toByteArray(resp.getEntity());
        }
    }

    // ── URL builder ─────────────────────────────────────────────────────────

    /**
     * Builds a verified NSE report download URL.
     * Pattern: /api/reports?archives=<encoded-json>&date=DD-MMM-YYYY&type=daily&mode=single
     */
    private String buildReportUrl(String archiveJson, LocalDate date) {
        String encodedArchive = URLEncoder.encode(archiveJson, StandardCharsets.UTF_8);
        String dateStr = date.format(NSE_DATE_FMT); // e.g. 17-May-2026
        return REPORT_API
                + "?archives=" + encodedArchive
                + "&date=" + dateStr
                + "&type=daily"
                + "&mode=single";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Execute a GET and discard the response body (used for warm-up). */
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
