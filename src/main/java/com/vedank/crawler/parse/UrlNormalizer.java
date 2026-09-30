package com.vedank.crawler.parse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Canonicalizes URLs so that equivalent spellings map to one key in the
 * crawler's visited set: {@code HTTP://Example.com:80/a/./b/../c#top} and
 * {@code http://example.com/a/c} must dedupe to the same entry, or the
 * crawler fetches the same page repeatedly.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Normalizes an absolute URL. Empty for non-http(s) schemes or unparseable input. */
    public static Optional<String> normalize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.trim().replace(" ", "%20")).normalize();
            String scheme = uri.getScheme();
            if (scheme == null) return Optional.empty();
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return Optional.empty();

            String host = uri.getHost();
            if (host == null || host.isEmpty()) return Optional.empty();
            host = host.toLowerCase(Locale.ROOT);

            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // normalize() leaves leading ".." segments in place for paths like "/../a"; they resolve to root.
            while (path.equals("/..") || path.startsWith("/../")) {
                path = path.length() == 3 ? "/" : path.substring(3);
            }

            StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
            if (!defaultPort) sb.append(':').append(port);
            sb.append(path);
            // "/a?" and "/a" are the same resource; only keep a non-empty query.
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) sb.append('?').append(uri.getRawQuery());
            // Fragment dropped: #section is the same document.
            return Optional.of(sb.toString());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code scheme://host[:port]} of an already-normalized URL; the unit of politeness and robots.txt. */
    public static String origin(String normalizedUrl) {
        URI uri = URI.create(normalizedUrl);
        String origin = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    }

    /** Path plus query, as matched against robots.txt rules. */
    public static String pathAndQuery(String normalizedUrl) {
        URI uri = URI.create(normalizedUrl);
        return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
    }
}
