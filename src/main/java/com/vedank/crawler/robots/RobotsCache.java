package com.vedank.crawler.robots;

import com.vedank.crawler.fetch.FetchResult;
import com.vedank.crawler.fetch.PageFetcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fetches robots.txt at most once per origin, even when many worker
 * threads hit a new host at the same moment: the first thread installs a
 * future and does the fetch, the others wait on that same future instead
 * of issuing duplicate requests.
 */
public final class RobotsCache {

    private final PageFetcher fetcher;
    private final String agentToken;
    private final ConcurrentMap<String, CompletableFuture<RobotsRules>> byOrigin = new ConcurrentHashMap<>();

    public RobotsCache(PageFetcher fetcher, String agentToken) {
        this.fetcher = fetcher;
        this.agentToken = agentToken;
    }

    public RobotsRules rulesFor(String origin) {
        CompletableFuture<RobotsRules> mine = new CompletableFuture<>();
        CompletableFuture<RobotsRules> existing = byOrigin.putIfAbsent(origin, mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            FetchResult result = fetcher.fetch(origin + "/robots.txt", true);
            // Missing (4xx) or unreachable robots.txt: treat as no restrictions.
            RobotsRules rules = result.statusCode() == 200
                    ? RobotsRules.parse(result.body(), agentToken)
                    : RobotsRules.allowAll();
            mine.complete(rules);
            return rules;
        } catch (RuntimeException e) {
            mine.complete(RobotsRules.allowAll());
            return RobotsRules.allowAll();
        }
    }
}
