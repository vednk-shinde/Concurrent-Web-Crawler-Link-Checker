package com.vedank.crawler;

import com.vedank.crawler.fetch.FetchResult;
import com.vedank.crawler.fetch.HostRateLimiter;
import com.vedank.crawler.fetch.PageFetcher;
import com.vedank.crawler.parse.LinkExtractor;
import com.vedank.crawler.parse.UrlNormalizer;
import com.vedank.crawler.report.CrawlReport;
import com.vedank.crawler.report.LinkResult;
import com.vedank.crawler.report.LinkStatus;
import com.vedank.crawler.robots.RobotsCache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded crawler and link checker.
 *
 * <h2>Concurrency design</h2>
 * <ul>
 *   <li><b>Frontier</b>: a {@link LinkedBlockingQueue} of {@link CrawlTask}s shared by N worker threads.</li>
 *   <li><b>Dedup</b>: a concurrent hash set of normalized URLs. {@code Set.add} is atomic, so when
 *       two workers discover the same link at the same moment exactly one of them enqueues it.</li>
 *   <li><b>Termination</b>: {@code pending} counts tasks that are queued or being processed. A
 *       worker increments it for every child <i>before</i> decrementing it for the parent, so it
 *       can only reach zero when the queue is empty and no worker can still add work.</li>
 *   <li><b>Politeness</b>: {@link HostRateLimiter} caps request rate per origin across all workers.</li>
 * </ul>
 * Pages on the seed's origin are crawled (fetched, parsed, followed) up to {@code maxDepth}.
 * Links to other origins are only checked for a working status, never crawled.
 */
public final class WebCrawler {

    private final CrawlerConfig config;
    private final CrawlListener listener;
    private final PageFetcher fetcher;
    private final RobotsCache robots;

    public WebCrawler(CrawlerConfig config) {
        this(config, CrawlListener.NONE);
    }

    public WebCrawler(CrawlerConfig config, CrawlListener listener) {
        this.config = config;
        this.listener = listener;
        this.fetcher = new PageFetcher(config, new HostRateLimiter(config.requestsPerSecondPerHost()));
        this.robots = new RobotsCache(fetcher, config.agentToken());
    }

    public CrawlReport crawl() throws InterruptedException {
        String seed = UrlNormalizer.normalize(config.seedUrl())
                .orElseThrow(() -> new IllegalArgumentException("Seed must be an absolute http(s) URL: " + config.seedUrl()));
        return new Run(seed).execute();
    }

    /** State for a single crawl; a WebCrawler can be run more than once. */
    private final class Run {
        private final String seed;
        private final String seedOrigin;

        private final BlockingQueue<CrawlTask> frontier = new LinkedBlockingQueue<>();
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        private final Map<String, LinkResult> results = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> referrers = new ConcurrentHashMap<>();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicBoolean truncated = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);

        Run(String seed) {
            this.seed = seed;
            this.seedOrigin = UrlNormalizer.origin(seed);
        }

        CrawlReport execute() throws InterruptedException {
            Instant started = Instant.now();
            enqueue(seed, 0, null);

            ExecutorService workers = Executors.newFixedThreadPool(config.threads(), new WorkerThreadFactory());
            try {
                for (int i = 0; i < config.threads(); i++) {
                    workers.submit(this::workerLoop);
                }
                finished.await();
            } finally {
                workers.shutdownNow();
                workers.awaitTermination(30, TimeUnit.SECONDS);
            }
            return new CrawlReport(seed, results.values(), referrers, Duration.between(started, Instant.now()),
                    truncated.get(), config.threads());
        }

        private void workerLoop() {
            try {
                while (finished.getCount() > 0) {
                    CrawlTask task = frontier.poll(50, TimeUnit.MILLISECONDS);
                    if (task == null) continue;
                    try {
                        process(task);
                    } catch (RuntimeException e) {
                        // e.g. a pathological page the parser chokes on: record it, keep the worker alive
                        record(new LinkResult(task.url(), task.depth(),
                                UrlNormalizer.origin(task.url()).equals(seedOrigin), LinkStatus.NETWORK_ERROR,
                                -1, task.url(), null, 0, 0, 0, "internal error: " + e));
                    } finally {
                        if (pending.decrementAndGet() == 0) {
                            finished.countDown();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void process(CrawlTask task) {
            boolean internal = UrlNormalizer.origin(task.url()).equals(seedOrigin);

            if (internal && config.respectRobotsTxt()
                    && !robots.rulesFor(seedOrigin).isAllowed(UrlNormalizer.pathAndQuery(task.url()))) {
                record(LinkResult.blockedByRobots(task.url(), task.depth()));
                return;
            }

            boolean expand = internal && task.depth() < config.maxDepth();
            FetchResult fetch = fetcher.fetch(task.url(), expand);

            int linksFound = 0;
            // Only follow if we're still on our origin after redirects.
            if (expand && fetch.isSuccess() && fetch.isHtml()
                    && UrlNormalizer.origin(fetch.finalUrl()).equals(seedOrigin)) {
                Set<String> links = LinkExtractor.extractLinks(fetch.body(), fetch.finalUrl());
                linksFound = links.size();
                for (String link : links) {
                    referrers.computeIfAbsent(link, k -> ConcurrentHashMap.newKeySet()).add(task.url());
                    enqueue(link, task.depth() + 1, task.url());
                }
            }
            record(LinkResult.fromFetch(task.url(), task.depth(), internal, fetch, linksFound));
        }

        private void enqueue(String url, int depth, String parent) {
            boolean internal = UrlNormalizer.origin(url).equals(seedOrigin);
            if (!internal && !config.checkExternalLinks()) return;
            if (!seen.add(url)) return; // someone else already scheduled it

            if (scheduled.incrementAndGet() > config.maxPages()) {
                truncated.set(true);
                return;
            }
            pending.incrementAndGet(); // before the parent's decrement, so pending can't hit 0 early
            frontier.add(new CrawlTask(url, depth, parent));
        }

        private void record(LinkResult result) {
            results.put(result.url(), result);
            try {
                listener.onResult(result);
            } catch (RuntimeException ignored) {
                // a faulty listener must not kill a worker
            }
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "crawler-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
