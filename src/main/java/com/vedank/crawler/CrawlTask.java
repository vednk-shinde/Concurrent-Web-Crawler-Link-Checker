package com.vedank.crawler;

/**
 * One unit of work on the frontier queue.
 *
 * @param url       normalized absolute URL
 * @param depth     link distance from the seed (seed = 0)
 * @param parentUrl page this URL was first discovered on; null for the seed
 */
public record CrawlTask(String url, int depth, String parentUrl) {
}
