package com.vedank.crawler.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkExtractorTest {

    @Test
    void resolvesRelativeLinksAndDropsNonHttpOnes() {
        String html = """
                <a href="/abs">1</a>
                <a href="rel">2</a>
                <a href="../up">3</a>
                <a href="https://other.org/x#frag">4</a>
                <a href="mailto:me@example.com">5</a>
                <a href="javascript:alert(1)">6</a>
                <a href="/abs">duplicate</a>
                <a name="no-href">7</a>
                """;

        List<String> links = List.copyOf(LinkExtractor.extractLinks(html, "http://example.com/dir/page"));

        assertEquals(List.of(
                "http://example.com/abs",
                "http://example.com/dir/rel",
                "http://example.com/up",
                "https://other.org/x"), links);
    }

    @Test
    void honorsBaseHrefAndNofollow() {
        String html = """
                <html><head><base href="http://cdn.example.com/docs/"></head><body>
                <a href="guide">guide</a>
                <a href="/private" rel="nofollow">skip</a>
                </body></html>
                """;

        assertEquals(List.of("http://cdn.example.com/docs/guide"),
                List.copyOf(LinkExtractor.extractLinks(html, "http://example.com/")));
    }
}
