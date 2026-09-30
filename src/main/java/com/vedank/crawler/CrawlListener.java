package com.vedank.crawler;

import com.vedank.crawler.report.LinkResult;

/**
 * Progress callback. Called from worker threads, possibly concurrently,
 * so implementations must be thread-safe.
 */
@FunctionalInterface
public interface CrawlListener {

    CrawlListener NONE = result -> { };

    void onResult(LinkResult result);
}
