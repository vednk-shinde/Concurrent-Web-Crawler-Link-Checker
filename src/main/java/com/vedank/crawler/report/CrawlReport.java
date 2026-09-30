package com.vedank.crawler.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable summary of a finished crawl. */
public final class CrawlReport {

    private final String seedUrl;
    private final List<LinkResult> results;
    private final Map<String, Set<String>> referrers;
    private final Duration duration;
    private final boolean truncated;
    private final int threads;

    public CrawlReport(String seedUrl, Collection<LinkResult> results, Map<String, Set<String>> referrers,
                       Duration duration, boolean truncated, int threads) {
        this.seedUrl = seedUrl;
        this.results = results.stream()
                .sorted(Comparator.comparingInt(LinkResult::depth).thenComparing(LinkResult::url))
                .toList();
        Map<String, Set<String>> copy = new HashMap<>();
        referrers.forEach((url, from) -> copy.put(url, Collections.unmodifiableSet(new TreeSet<>(from))));
        this.referrers = Collections.unmodifiableMap(copy);
        this.duration = duration;
        this.truncated = truncated;
        this.threads = threads;
    }

    public String seedUrl() { return seedUrl; }
    public List<LinkResult> results() { return results; }
    public Duration duration() { return duration; }
    /** True if maxPages was hit and some discovered URLs were not checked. */
    public boolean truncated() { return truncated; }

    public Optional<LinkResult> result(String url) {
        return results.stream().filter(r -> r.url().equals(url)).findFirst();
    }

    /** Pages that link to {@code url}. */
    public Set<String> referrersOf(String url) {
        return referrers.getOrDefault(url, Set.of());
    }

    public List<LinkResult> brokenLinks() {
        return results.stream().filter(LinkResult::isBroken).toList();
    }

    public boolean hasBrokenLinks() {
        return results.stream().anyMatch(LinkResult::isBroken);
    }

    public Map<LinkStatus, Long> countByStatus() {
        Map<LinkStatus, Long> counts = new EnumMap<>(LinkStatus.class);
        for (LinkStatus s : LinkStatus.values()) counts.put(s, 0L);
        results.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
        return counts;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        long checked = results.stream().filter(r -> r.status() != LinkStatus.BLOCKED_BY_ROBOTS).count();
        double seconds = Math.max(duration.toMillis(), 1) / 1000.0;

        sb.append("Crawl of ").append(seedUrl).append('\n');
        sb.append(String.format(Locale.ROOT, "  %d URLs checked (%d internal, %d external) in %.2fs with %d threads (%.1f URLs/s)%n",
                checked,
                results.stream().filter(LinkResult::internal).count(),
                results.stream().filter(r -> !r.internal()).count(),
                seconds, threads, checked / seconds));
        countByStatus().forEach((status, n) -> {
            if (n > 0) sb.append(String.format(Locale.ROOT, "  %-18s %d%n", status, n));
        });
        if (truncated) {
            sb.append("  (stopped at maxPages; some discovered URLs were not checked)\n");
        }

        List<LinkResult> broken = brokenLinks();
        if (broken.isEmpty()) {
            sb.append("\nNo broken links found.\n");
        } else {
            sb.append("\nBroken links (").append(broken.size()).append("):\n");
            for (LinkResult r : broken) {
                String what = r.httpStatus() > 0 ? "HTTP " + r.httpStatus() : r.error();
                sb.append("  [").append(what).append("] ").append(r.url()).append('\n');
                for (String from : referrersOf(r.url())) {
                    sb.append("      linked from ").append(from).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** One row per URL: url,status,http_status,depth,internal,attempts,elapsed_ms,final_url,linked_from,error */
    public void writeCsv(Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("url,status,http_status,depth,internal,attempts,elapsed_ms,final_url,linked_from,error\n");
            for (LinkResult r : results) {
                w.write(String.join(",",
                        csv(r.url()),
                        r.status().name(),
                        Integer.toString(r.httpStatus()),
                        Integer.toString(r.depth()),
                        Boolean.toString(r.internal()),
                        Integer.toString(r.attempts()),
                        Long.toString(r.elapsedMillis()),
                        csv(r.finalUrl()),
                        csv(referrersOf(r.url()).stream().collect(Collectors.joining(" "))),
                        csv(r.error())));
                w.write('\n');
            }
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
