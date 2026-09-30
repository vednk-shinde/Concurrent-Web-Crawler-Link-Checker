package com.vedank.crawler.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UrlNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "HTTP://Example.COM,                    http://example.com/",
            "http://example.com:80/a,               http://example.com/a",
            "https://example.com:443/a,             https://example.com/a",
            "http://example.com:8080/a,             http://example.com:8080/a",
            "http://example.com/a/./b/../c,         http://example.com/a/c",
            "http://example.com/../a,               http://example.com/a",
            "http://example.com/..foo,              http://example.com/..foo",
            "http://example.com/page#section,       http://example.com/page",
            "http://example.com/search?q=a%20b,     http://example.com/search?q=a%20b",
            "http://example.com/a?,                 http://example.com/a",
            "http://example.com/a b,                http://example.com/a%20b",
            "http://example.com/Case/Sensitive,     http://example.com/Case/Sensitive"
    })
    void normalizesEquivalentSpellings(String input, String expected) {
        assertEquals(Optional.of(expected), UrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:a@b.com", "javascript:void(0)", "ftp://example.com/x", "tel:+123", "/relative", "", "http://"})
    void rejectsNonCrawlableUrls(String input) {
        assertTrue(UrlNormalizer.normalize(input).isEmpty(), input);
    }

    @Test
    void originAndPath() {
        assertEquals("http://example.com", UrlNormalizer.origin("http://example.com/a/b?x=1"));
        assertEquals("http://example.com:8080", UrlNormalizer.origin("http://example.com:8080/"));
        assertEquals("/a/b?x=1", UrlNormalizer.pathAndQuery("http://example.com/a/b?x=1"));
    }
}
