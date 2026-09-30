package com.vedank.crawler;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable crawl settings. Build with {@link #builder(String)}.
 */
public final class CrawlerConfig {

    private final String seedUrl;
    private final int threads;
    private final int maxDepth;
    private final int maxPages;
    private final double requestsPerSecondPerHost;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final boolean checkExternalLinks;
    private final boolean respectRobotsTxt;
    private final String userAgent;
    private final int maxBodyBytes;

    private CrawlerConfig(Builder b) {
        this.seedUrl = Objects.requireNonNull(b.seedUrl, "seedUrl");
        this.threads = b.threads;
        this.maxDepth = b.maxDepth;
        this.maxPages = b.maxPages;
        this.requestsPerSecondPerHost = b.requestsPerSecondPerHost;
        this.requestTimeout = b.requestTimeout;
        this.maxRetries = b.maxRetries;
        this.retryBackoff = b.retryBackoff;
        this.checkExternalLinks = b.checkExternalLinks;
        this.respectRobotsTxt = b.respectRobotsTxt;
        this.userAgent = b.userAgent;
        this.maxBodyBytes = b.maxBodyBytes;
    }

    public static Builder builder(String seedUrl) {
        return new Builder(seedUrl);
    }

    public String seedUrl() { return seedUrl; }
    public int threads() { return threads; }
    public int maxDepth() { return maxDepth; }
    public int maxPages() { return maxPages; }
    public double requestsPerSecondPerHost() { return requestsPerSecondPerHost; }
    public Duration requestTimeout() { return requestTimeout; }
    public int maxRetries() { return maxRetries; }
    public Duration retryBackoff() { return retryBackoff; }
    public boolean checkExternalLinks() { return checkExternalLinks; }
    public boolean respectRobotsTxt() { return respectRobotsTxt; }
    public String userAgent() { return userAgent; }
    public int maxBodyBytes() { return maxBodyBytes; }

    /** Product token used for robots.txt group matching, e.g. "LinkCheckerBot" from "LinkCheckerBot/1.0 (...)". */
    public String agentToken() {
        String token = userAgent.split("[/\\s]", 2)[0];
        return token.isEmpty() ? "*" : token;
    }

    public static final class Builder {
        private final String seedUrl;
        private int threads = 8;
        private int maxDepth = 3;
        private int maxPages = 500;
        private double requestsPerSecondPerHost = 5;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private int maxRetries = 2;
        private Duration retryBackoff = Duration.ofMillis(500);
        private boolean checkExternalLinks = true;
        private boolean respectRobotsTxt = true;
        private String userAgent = "LinkCheckerBot/1.0 (+https://github.com/vednk-shinde/Concurrent-Web-Crawler-Link-Checker)";
        private int maxBodyBytes = 5 * 1024 * 1024;

        private Builder(String seedUrl) {
            this.seedUrl = seedUrl;
        }

        public Builder threads(int threads) { this.threads = positive(threads, "threads"); return this; }
        public Builder maxDepth(int maxDepth) { this.maxDepth = nonNegative(maxDepth, "maxDepth"); return this; }
        public Builder maxPages(int maxPages) { this.maxPages = positive(maxPages, "maxPages"); return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = nonNegative(maxRetries, "maxRetries"); return this; }
        public Builder checkExternalLinks(boolean check) { this.checkExternalLinks = check; return this; }
        public Builder respectRobotsTxt(boolean respect) { this.respectRobotsTxt = respect; return this; }
        public Builder userAgent(String userAgent) { this.userAgent = Objects.requireNonNull(userAgent); return this; }
        public Builder maxBodyBytes(int bytes) { this.maxBodyBytes = positive(bytes, "maxBodyBytes"); return this; }

        public Builder requestsPerSecondPerHost(double rps) {
            if (rps <= 0) throw new IllegalArgumentException("requestsPerSecondPerHost must be > 0");
            this.requestsPerSecondPerHost = rps;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("requestTimeout must be > 0");
            this.requestTimeout = timeout;
            return this;
        }

        public Builder retryBackoff(Duration backoff) {
            if (backoff.isNegative()) throw new IllegalArgumentException("retryBackoff must be >= 0");
            this.retryBackoff = backoff;
            return this;
        }

        public CrawlerConfig build() {
            return new CrawlerConfig(this);
        }

        private static int positive(int v, String name) {
            if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
            return v;
        }

        private static int nonNegative(int v, String name) {
            if (v < 0) throw new IllegalArgumentException(name + " must be >= 0");
            return v;
        }
    }
}
