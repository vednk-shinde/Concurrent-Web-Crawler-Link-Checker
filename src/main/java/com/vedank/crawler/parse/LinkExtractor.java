package com.vedank.crawler.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pulls every followable {@code <a href>} out of an HTML page as normalized absolute URLs. */
public final class LinkExtractor {

    private LinkExtractor() {
    }

    /**
     * @param html    page body
     * @param pageUrl the URL the body was actually served from (after redirects);
     *                relative links and {@code <base href>} resolve against it
     */
    public static Set<String> extractLinks(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        Set<String> links = new LinkedHashSet<>(); // dedupe within the page, keep document order
        for (Element a : doc.select("a[href]")) {
            if (a.attr("rel").toLowerCase().contains("nofollow")) continue;
            String absolute = a.absUrl("href");
            UrlNormalizer.normalize(absolute).ifPresent(links::add);
        }
        return links;
    }
}
