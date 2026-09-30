package com.vedank.crawler.report;

public enum LinkStatus {
    /** 2xx/3xx (redirects are followed; the final status is recorded). */
    OK,
    /** 4xx: missing page, forbidden, etc. */
    CLIENT_ERROR,
    /** 5xx after exhausting retries. */
    SERVER_ERROR,
    /** No response: timeout, DNS failure, connection refused, after retries. */
    NETWORK_ERROR,
    /** Not fetched because robots.txt disallows it. */
    BLOCKED_BY_ROBOTS;

    public boolean isBroken() {
        return this == CLIENT_ERROR || this == SERVER_ERROR || this == NETWORK_ERROR;
    }

    public static LinkStatus fromHttpStatus(int status) {
        if (status < 0) return NETWORK_ERROR;
        if (status >= 500) return SERVER_ERROR;
        if (status >= 400) return CLIENT_ERROR;
        return OK;
    }
}
