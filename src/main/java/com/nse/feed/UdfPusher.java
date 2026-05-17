package com.nse.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

/**
 * Pushes the contents of {@code data/nse_udf_ohlc.csv} to a self-hosted
 * TradingView-compatible UDF server.
 *
 * <h3>Configuration (environment variables)</h3>
 * <pre>
 *   UDF_SERVER_URL   Base URL of the UDF server, e.g. http://localhost:8080
 *                    If absent, this class is a no-op — useful for local runs
 *                    without a server.  Required in production.
 *
 *   UDF_AUTH_TOKEN   Optional Bearer token added as Authorization header.
 *                    Leave blank if the server has no auth.
 * </pre>
 *
 * <h3>UDF server contract</h3>
 * This pusher POSTs the raw CSV to:
 * <pre>
 *   POST {UDF_SERVER_URL}/udf/bars/bulk
 *   Content-Type: text/csv; charset=utf-8
 *   Authorization: Bearer {UDF_AUTH_TOKEN}   (if set)
 * </pre>
 *
 * The UDF server must parse the posted CSV and update its in-memory /
 * Redis / file-backed bar store.  A minimal Spring Boot reference
 * implementation is included in the {@code udf-server/} directory.
 *
 * <h3>Retry policy</h3>
 * Up to {@value MAX_ATTEMPTS} attempts with exponential back-off
 * (1 s, 2 s, 4 s).  If all attempts fail the exception is logged as a
 * warning — the ETL pipeline itself is not aborted (metadata CSV is
 * already written successfully).
 */
public class UdfPusher {

    private static final Logger log = LoggerFactory.getLogger(UdfPusher.class);

    private static final String ENV_SERVER_URL  = "UDF_SERVER_URL";
    private static final String ENV_AUTH_TOKEN  = "UDF_AUTH_TOKEN";
    private static final String BULK_ENDPOINT   = "/udf/bars/bulk";
    private static final int    MAX_ATTEMPTS    = 3;
    private static final long   BASE_DELAY_MS   = 1_000L;

    private final Path   ohlcFile;
    private final String serverUrl;
    private final String authToken;

    /**
     * @param dataDir directory containing {@code nse_udf_ohlc.csv}
     */
    public UdfPusher(String dataDir) {
        this.ohlcFile  = Paths.get(dataDir, "nse_udf_ohlc.csv");
        this.serverUrl = System.getenv(ENV_SERVER_URL);
        this.authToken = System.getenv(ENV_AUTH_TOKEN);
    }

    /**
     * Pushes the OHLC CSV to the configured UDF server.
     * <ul>
     *   <li>If {@code UDF_SERVER_URL} is not set → logs INFO and returns immediately.</li>
     *   <li>If the OHLC file does not exist → logs WARN and returns.</li>
     *   <li>On HTTP errors → retries with back-off; warns on final failure.</li>
     * </ul>
     */
    public void push() {
        if (serverUrl == null || serverUrl.isBlank()) {
            log.info("[UdfPusher] UDF_SERVER_URL not set — skipping UDF push.");
            return;
        }
        if (!Files.exists(ohlcFile)) {
            log.warn("[UdfPusher] OHLC file not found: {} — skipping.", ohlcFile);
            return;
        }

        String body;
        try {
            body = Files.readString(ohlcFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[UdfPusher] Cannot read OHLC file: {}", e.getMessage());
            return;
        }

        String endpoint = serverUrl.stripTrailing("/") + BULK_ENDPOINT;
        log.info("[UdfPusher] Pushing {} chars to {}", body.length(), endpoint);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "text/csv; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

                if (authToken != null && !authToken.isBlank())
                    reqBuilder.header("Authorization", "Bearer " + authToken);

                HttpResponse<String> resp = client.send(
                        reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info("[UdfPusher] Success — HTTP {} on attempt {}",
                             resp.statusCode(), attempt);
                    return;
                }

                log.warn("[UdfPusher] HTTP {} on attempt {}/{} — body: {}",
                         resp.statusCode(), attempt, MAX_ATTEMPTS,
                         resp.body().substring(0, Math.min(200, resp.body().length())));

            } catch (IOException | InterruptedException e) {
                log.warn("[UdfPusher] Network error on attempt {}/{}: {}",
                         attempt, MAX_ATTEMPTS, e.getMessage());
                if (e instanceof InterruptedException)
                    Thread.currentThread().interrupt();
            }

            if (attempt < MAX_ATTEMPTS) {
                long delay = BASE_DELAY_MS * (1L << (attempt - 1)); // 1s, 2s, 4s
                log.info("[UdfPusher] Retrying in {} ms…", delay);
                try { Thread.sleep(delay); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.warn("[UdfPusher] All {} attempts failed — UDF data may be stale.", MAX_ATTEMPTS);
    }
}
