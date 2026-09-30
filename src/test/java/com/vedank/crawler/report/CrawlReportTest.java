package com.vedank.crawler.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrawlReportTest {

    private static LinkResult result(String url, LinkStatus status, int http, String error) {
        return new LinkResult(url, 1, true, status, http, url, "text/html", 1, 5, 0, error);
    }

    private final CrawlReport report = new CrawlReport("http://x.test/",
            List.of(result("http://x.test/ok", LinkStatus.OK, 200, null),
                    result("http://x.test/404", LinkStatus.CLIENT_ERROR, 404, null),
                    result("http://x.test/down", LinkStatus.NETWORK_ERROR, -1, "HttpTimeoutException: request timed out")),
            Map.of("http://x.test/404", Set.of("http://x.test/", "http://x.test/ok")),
            Duration.ofMillis(1500), false, 4);

    @Test
    void summarizesAndListsBrokenLinksWithReferrers() {
        String text = report.toText();

        assertTrue(text.contains("3 URLs checked"));
        assertTrue(text.contains("[HTTP 404] http://x.test/404"));
        assertTrue(text.contains("linked from http://x.test/ok"));
        assertTrue(text.contains("[HttpTimeoutException: request timed out] http://x.test/down"));
        assertEquals(2, report.brokenLinks().size());
        assertEquals(1L, report.countByStatus().get(LinkStatus.OK));
    }

    @Test
    void writesCsvWithQuotingWhereNeeded(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("report.csv");
        report.writeCsv(csv);

        List<String> lines = Files.readAllLines(csv);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("url,status,http_status"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("http://x.test/404,CLIENT_ERROR,404,")
                && l.contains("http://x.test/ http://x.test/ok")));
    }
}
