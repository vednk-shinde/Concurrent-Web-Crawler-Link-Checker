package com.vedank.crawler.fetch;

import com.vedank.crawler.CrawlerConfig;
import com.vedank.crawler.parse.UrlNormalizer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP fetching with the failure handling a crawler needs:
 * <ul>
 *   <li>per-request timeout (a hung server can't stall a worker forever)</li>
 *   <li>retries with exponential backoff for network errors, 429 and 5xx</li>
 *   <li>HEAD for pure link checks, falling back to GET when HEAD is unsupported</li>
 *   <li>per-origin rate limiting before every attempt, retries included</li>
 *   <li>a response-size cap so one huge file can't exhaust memory</li>
 * </ul>
 * Thread-safe: one instance (and one HttpClient connection pool) is shared by all workers.
 */
public final class PageFetcher {

    private final HttpClient client;
    private final HostRateLimiter rateLimiter;
    private final CrawlerConfig config;

    public PageFetcher(CrawlerConfig config, HostRateLimiter rateLimiter) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param needBody true to GET and read the body (pages to parse); false to
     *                 only learn the status (leaf pages, external links)
     */
    public FetchResult fetch(String url, boolean needBody) {
        String origin = UrlNormalizer.origin(url);
        long started = System.nanoTime();
        int attempts = 0;
        boolean useHead = !needBody;
        String lastError = null;

        while (true) {
            attempts++;
            try {
                rateLimiter.acquire(origin);
                HttpResponse<InputStream> response = client.send(request(url, useHead), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (useHead && (status == 405 || status == 501)) {
                    // Server doesn't support HEAD; ask again with GET. Not counted as a failure.
                    response.body().close();
                    useHead = false;
                    attempts--;
                    continue;
                }
                if (isRetryable(status) && attempts <= config.maxRetries()) {
                    response.body().close();
                    backoff(attempts);
                    continue;
                }

                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                String body = "";
                try (InputStream in = response.body()) {
                    if (!useHead && needBody && isText(contentType)) {
                        byte[] bytes = in.readNBytes(config.maxBodyBytes());
                        body = new String(bytes, charsetOf(contentType));
                    }
                }
                return new FetchResult(status, UrlNormalizer.normalize(response.uri().toString()).orElse(url),
                        contentType, body, attempts, elapsedMillis(started), null);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult(-1, url, null, "", attempts, elapsedMillis(started), "interrupted");
            } catch (IOException e) {
                lastError = describe(e);
                if (attempts > config.maxRetries()) {
                    return new FetchResult(-1, url, null, "", attempts, elapsedMillis(started), lastError);
                }
                try {
                    backoff(attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new FetchResult(-1, url, null, "", attempts, elapsedMillis(started), lastError);
                }
            }
        }
    }

    private HttpRequest request(String url, boolean head) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .header("User-Agent", config.userAgent())
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
        return head ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /** Exponential backoff: base, 2x base, 4x base, ... */
    private void backoff(int attempt) throws InterruptedException {
        long delay = config.retryBackoff().toMillis() << Math.min(attempt - 1, 10);
        Thread.sleep(delay);
    }

    private static boolean isText(String contentType) {
        if (contentType == null) return true;
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("text/") || ct.contains("html") || ct.contains("xml");
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (RuntimeException ignored) {
                        // unknown charset: fall through to UTF-8
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String describe(IOException e) {
        String name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
