package com.vedank.crawler;

import com.vedank.crawler.report.CrawlReport;
import com.vedank.crawler.report.LinkResult;
import com.vedank.crawler.report.LinkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WebCrawlerTest {

    private TestSite site;

    @BeforeEach
    void startSite() throws Exception {
        site = new TestSite();
    }

    @AfterEach
    void stopSite() {
        site.close();
    }

    /** Fast defaults for tests: no politeness delay to speak of, short timeouts and backoff. */
    private CrawlerConfig.Builder config(String path) {
        return CrawlerConfig.builder(site.url(path))
                .threads(4)
                .requestsPerSecondPerHost(1000)
                .requestTimeout(Duration.ofSeconds(5))
                .retryBackoff(Duration.ofMillis(10));
    }

    private CrawlReport crawl(CrawlerConfig config) throws InterruptedException {
        return new WebCrawler(config).crawl();
    }

    @Test
    void crawlsEveryReachablePageExactlyOnceDespiteCycles() throws Exception {
        site.page("/", "/a", "/b", "/#top", "/a?")
                .page("/a", "/", "/b", "/c")
                .page("/b", "/a", "/c", "./a", "../a")
                .page("/c", "/", "/a", "/b");

        CrawlReport report = crawl(config("/").maxDepth(10).build());

        for (String path : List.of("/", "/a", "/b", "/c")) {
            assertEquals(1, site.hits(path), path + " should be fetched exactly once");
            assertEquals(LinkStatus.OK, report.result(site.url(path)).orElseThrow().status());
        }
        assertFalse(report.hasBrokenLinks());
        assertFalse(report.truncated());
    }

    @Test
    void reportsBrokenLinksWithThePagesThatLinkToThem() throws Exception {
        site.page("/", "/ok", "/missing")
                .page("/ok", "/missing", "/gone")
                .route("/gone", n -> TestSite.Response.status(410));

        CrawlReport report = crawl(config("/").build());

        LinkResult missing = report.result(site.url("/missing")).orElseThrow();
        assertEquals(LinkStatus.CLIENT_ERROR, missing.status());
        assertEquals(404, missing.httpStatus());
        assertEquals(Set.of(site.url("/"), site.url("/ok")), report.referrersOf(site.url("/missing")));

        assertEquals(List.of(site.url("/missing"), site.url("/gone")),
                report.brokenLinks().stream().map(LinkResult::url).sorted((a, b) -> b.compareTo(a)).toList());
        assertTrue(report.toText().contains("linked from " + site.url("/ok")));
    }

    @Test
    void stopsFollowingLinksAtMaxDepthButStillChecksTheLastLevel() throws Exception {
        site.page("/", "/d1").page("/d1", "/d2").page("/d2", "/d3").page("/d3", "/d4");

        CrawlReport report = crawl(config("/").maxDepth(2).build());

        assertEquals(1, site.hits("/d2"), "depth-2 page is checked");
        assertEquals(0, site.hits("/d3"), "its links are not followed");
        assertEquals(0, report.result(site.url("/d2")).orElseThrow().linksFound());
        assertTrue(site.methods().contains("HEAD /d2"), "leaf pages only need a status, so HEAD is used");
    }

    @Test
    void stopsAtMaxPagesAndSaysSo() throws Exception {
        String[] links = new String[50];
        for (int i = 0; i < links.length; i++) {
            links[i] = "/p" + i;
            site.page(links[i]);
        }
        site.page("/", links);

        CrawlReport report = crawl(config("/").maxPages(10).build());

        assertEquals(10, report.results().size());
        assertTrue(report.truncated());
        assertEquals(10, site.requestedPaths().stream().filter(p -> !p.equals("/robots.txt")).count());
    }

    @Test
    void retriesTransientServerErrorsWithBackoff() throws Exception {
        site.page("/", "/flaky", "/down")
                .route("/flaky", n -> n < 3 ? TestSite.Response.status(503) : TestSite.Response.html("<p>back</p>"))
                .route("/down", n -> TestSite.Response.status(500));

        CrawlReport report = crawl(config("/").maxRetries(2).build());

        LinkResult flaky = report.result(site.url("/flaky")).orElseThrow();
        assertEquals(LinkStatus.OK, flaky.status());
        assertEquals(3, flaky.attempts());

        LinkResult down = report.result(site.url("/down")).orElseThrow();
        assertEquals(LinkStatus.SERVER_ERROR, down.status());
        assertEquals(3, down.attempts(), "1 try + 2 retries");
        assertEquals(3, site.hits("/down"));
    }

    @Test
    void timesOutHungPagesInsteadOfBlockingAWorker() throws Exception {
        site.page("/", "/slow", "/fast")
                .page("/fast")
                .route("/slow", n -> {
                    Thread.sleep(3_000);
                    return TestSite.Response.html("too late");
                });

        long start = System.nanoTime();
        CrawlReport report = crawl(config("/").requestTimeout(Duration.ofMillis(300)).maxRetries(0).build());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        LinkResult slow = report.result(site.url("/slow")).orElseThrow();
        assertEquals(LinkStatus.NETWORK_ERROR, slow.status());
        assertNotNull(slow.error());
        assertEquals(LinkStatus.OK, report.result(site.url("/fast")).orElseThrow().status());
        assertTrue(elapsedMs < 2_500, "crawl must not wait for the hung page, took " + elapsedMs + "ms");
    }

    @Test
    void honorsRobotsTxt() throws Exception {
        site.page("/", "/public", "/private/secret", "/private/allowed")
                .page("/public")
                .page("/private/secret")
                .page("/private/allowed")
                .route("/robots.txt", n -> new TestSite.Response(200, "text/plain",
                        "User-agent: *\nDisallow: /private/\nAllow: /private/allowed\n"));

        CrawlReport report = crawl(config("/").build());

        assertEquals(0, site.hits("/private/secret"));
        assertEquals(LinkStatus.BLOCKED_BY_ROBOTS, report.result(site.url("/private/secret")).orElseThrow().status());
        assertEquals(1, site.hits("/private/allowed"));
        assertEquals(1, site.hits("/robots.txt"), "robots.txt fetched once per origin, not per page");
        assertFalse(report.hasBrokenLinks(), "blocked is not broken");
    }

    @Test
    void robotsTxtCanBeIgnored() throws Exception {
        site.page("/", "/private/x").page("/private/x")
                .route("/robots.txt", n -> new TestSite.Response(200, "text/plain", "User-agent: *\nDisallow: /\n"));

        crawl(config("/").respectRobotsTxt(false).build());

        assertEquals(1, site.hits("/private/x"));
        assertEquals(0, site.hits("/robots.txt"));
    }

    @Test
    void externalLinksAreCheckedButNeverCrawled() throws Exception {
        try (TestSite external = new TestSite()) {
            external.page("/landing", "/deeper").page("/deeper");
            site.page("/", external.url("/landing"), external.url("/dead"));

            CrawlReport report = crawl(config("/").build());

            LinkResult landing = report.result(external.url("/landing")).orElseThrow();
            assertFalse(landing.internal());
            assertEquals(LinkStatus.OK, landing.status());
            assertEquals(List.of("HEAD /landing"), external.methods().stream().filter(m -> m.contains("/landing")).toList());
            assertEquals(0, external.hits("/deeper"), "links on external pages are not followed");
            assertEquals(LinkStatus.CLIENT_ERROR, report.result(external.url("/dead")).orElseThrow().status());
        }
    }

    @Test
    void externalLinksCanBeSkippedEntirely() throws Exception {
        try (TestSite external = new TestSite()) {
            external.page("/landing");
            site.page("/", external.url("/landing"));

            CrawlReport report = crawl(config("/").checkExternalLinks(false).build());

            assertEquals(0, external.hits("/landing"));
            assertTrue(report.result(external.url("/landing")).isEmpty());
        }
    }

    @Test
    void fallsBackToGetWhenHeadIsNotAllowed() throws Exception {
        // A server that rejects HEAD with 405 but serves GET fine.
        site.page("/", "/no-head")
                .route("/no-head", n -> n == 1 ? TestSite.Response.status(405) : TestSite.Response.html("ok"));

        // maxDepth 1: /no-head is a leaf, so it is checked with HEAD first.
        CrawlReport report = crawl(config("/").maxDepth(1).build());

        LinkResult noHead = report.result(site.url("/no-head")).orElseThrow();
        assertEquals(LinkStatus.OK, noHead.status());
        assertEquals(1, noHead.attempts(), "the 405 -> GET fallback is not counted as a retry");
        assertEquals(List.of("HEAD /no-head", "GET /no-head"),
                site.methods().stream().filter(m -> m.endsWith("/no-head")).toList());
    }

    @Test
    void followsRedirectsAndRecordsTheFinalUrl() throws Exception {
        site.page("/", "/old")
                .route("/old", n -> new TestSite.Response(301, "text/html", site.url("/new")))
                .page("/new", "/after-redirect")
                .page("/after-redirect");

        CrawlReport report = crawl(config("/").build());

        LinkResult old = report.result(site.url("/old")).orElseThrow();
        assertEquals(LinkStatus.OK, old.status());
        assertTrue(old.wasRedirected());
        assertEquals(site.url("/new"), old.finalUrl());
        assertEquals(1, site.hits("/after-redirect"), "links on the redirect target are followed");
    }

    @Test
    void fetchesInParallelAndStillVisitsEachUrlOnce() throws Exception {
        // 10 hub pages x 20 leaves; every hub links to every leaf, so each leaf is discovered 10 times concurrently.
        List<String> leaves = new ArrayList<>();
        for (int i = 0; i < 20; i++) leaves.add("/leaf" + i);
        List<String> hubs = new ArrayList<>();
        for (int h = 0; h < 10; h++) {
            String hub = "/hub" + h;
            hubs.add(hub);
            site.page(hub, leaves.toArray(String[]::new));
        }
        for (String leaf : leaves) {
            site.route(leaf, n -> {
                Thread.sleep(50);
                return TestSite.Response.html("<p>leaf</p>");
            });
        }
        site.page("/", hubs.toArray(String[]::new));

        List<String> reported = Collections.synchronizedList(new ArrayList<>());
        CrawlReport report = new WebCrawler(config("/").threads(8).build(), r -> reported.add(r.url())).crawl();

        for (String leaf : leaves) assertEquals(1, site.hits(leaf), leaf);
        for (String hub : hubs) assertEquals(1, site.hits(hub), hub);
        assertEquals(31, report.results().size());
        assertEquals(31, reported.size(), "listener called once per URL");
        assertTrue(site.maxConcurrentRequests() > 1, "workers should overlap, saw " + site.maxConcurrentRequests());
    }

    @Test
    void rateLimitsRequestsPerHost() throws Exception {
        String[] links = new String[9];
        for (int i = 0; i < links.length; i++) {
            links[i] = "/r" + i;
            site.page(links[i]);
        }
        site.page("/", links);

        crawl(config("/").threads(8).requestsPerSecondPerHost(20).respectRobotsTxt(false).build());

        List<Long> starts = site.requestStartNanos();
        Collections.sort(starts);
        assertEquals(10, starts.size());
        long spanMs = (starts.get(starts.size() - 1) - starts.get(0)) / 1_000_000;
        // 10 requests at 20/s => the last starts >= 9 intervals (450ms) after the first, despite 8 threads.
        assertTrue(spanMs >= 400, "requests were not spaced out: span " + spanMs + "ms");
    }

    @Test
    void rejectsNonHttpSeed() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebCrawler(CrawlerConfig.builder("ftp://example.com").build()).crawl());
    }

    @Test
    void unreachableSeedIsReportedNotThrown() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort(); // a free port, closed again so connections are refused
        }
        CrawlReport report = crawl(CrawlerConfig.builder("http://127.0.0.1:" + port + "/")
                .requestTimeout(Duration.ofMillis(500)).maxRetries(0).build());

        assertEquals(1, report.results().size());
        assertEquals(LinkStatus.NETWORK_ERROR, report.results().get(0).status());
    }
}
