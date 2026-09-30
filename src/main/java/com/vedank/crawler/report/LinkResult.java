package com.vedank.crawler.report;

import com.vedank.crawler.fetch.FetchResult;

/**
 * What the crawler learned about one URL.
 *
 * @param internal   same origin as the seed (crawled), as opposed to an external link (only checked)
 * @param linksFound number of outgoing links extracted, 0 if the page wasn't parsed
 */
public record LinkResult(
        String url,
        int depth,
        boolean internal,
        LinkStatus status,
        int httpStatus,
        String finalUrl,
        String contentType,
        int attempts,
        long elapsedMillis,
        int linksFound,
        String error) {

    public static LinkResult fromFetch(String url, int depth, boolean internal, FetchResult fetch, int linksFound) {
        return new LinkResult(url, depth, internal, LinkStatus.fromHttpStatus(fetch.statusCode()), fetch.statusCode(),
                fetch.finalUrl(), fetch.contentType(), fetch.attempts(), fetch.elapsedMillis(), linksFound, fetch.error());
    }

    public static LinkResult blockedByRobots(String url, int depth) {
        return new LinkResult(url, depth, true, LinkStatus.BLOCKED_BY_ROBOTS, 0, url, null, 0, 0, 0, null);
    }

    public boolean isBroken() {
        return status.isBroken();
    }

    public boolean wasRedirected() {
        return finalUrl != null && !finalUrl.equals(url);
    }
}
