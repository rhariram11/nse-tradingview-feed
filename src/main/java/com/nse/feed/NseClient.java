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
import java.io.InputStream;

/**
 * Handles all HTTP communication with NSE India.
 *
 * NSE requires:
 *   1. A prior GET to https://www.nseindia.com to receive cookies (nsit, nseappid, etc.).
 *   2. All subsequent data API calls must include those cookies + specific headers:
 *      - Referer: https://www.nseindia.com/
 *      - X-Requested-With: XMLHttpRequest
 *      - Accept: application/json, text/csv, * / *
 *
 * Without the warm-up step NSE returns 401/403 or empty responses.
 */
public class NseClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NseClient.class);

    private static final String BASE_URL  = "https://www.nseindia.com";
    private static final String UA        = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                          + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                          + "Chrome/124.0.0.0 Safari/537.36";

    private final BasicCookieStore cookieStore;
    private final CloseableHttpClient http;

    public NseClient() {
        cookieStore = new BasicCookieStore();
        http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .build();
    }

    /**
     * Performs an initial GET to the NSE homepage to acquire session cookies.
     * Must be called before any data downloads.
     */
    public void warmUpSession() throws IOException {
        log.info("Warming up NSE session (acquiring cookies)...");
        HttpGet req = buildGet(BASE_URL);
        try (CloseableHttpResponse resp = http.execute(req)) {
            EntityUtils.consume(resp.getEntity());
            log.info("Session warm-up complete. Cookies: {}", cookieStore.getCookies().size());
        }
        // Small pause to mimic human browser behaviour
        sleep(2000);
    }

    /**
     * Downloads the content of the given NSE URL as a raw InputStream.
     * Caller is responsible for closing the stream.
     */
    public byte[] get(String url) throws IOException {
        log.debug("GET {}", url);
        HttpGet req = buildGet(url);
        try (CloseableHttpResponse resp = http.execute(req)) {
            int status = resp.getCode();
            if (status != 200) {
                throw new IOException("NSE returned HTTP " + status + " for " + url);
            }
            return EntityUtils.toByteArray(resp.getEntity());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private HttpGet buildGet(String url) {
        HttpGet req = new HttpGet(url);
        req.setHeader("User-Agent",       UA);
        req.setHeader("Accept",           "text/html,application/xhtml+xml,application/xml;"
                                        + "q=0.9,image/webp,*/*;q=0.8");
        req.setHeader("Accept-Language",  "en-US,en;q=0.9");
        req.setHeader("Referer",          BASE_URL + "/");
        req.setHeader("X-Requested-With", "XMLHttpRequest");
        req.setHeader("Connection",       "keep-alive");
        return req;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() throws IOException {
        http.close();
    }
}
