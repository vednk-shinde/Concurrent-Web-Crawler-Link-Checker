package com.vedank.crawler.cli;

import com.vedank.crawler.CrawlListener;
import com.vedank.crawler.CrawlerConfig;
import com.vedank.crawler.WebCrawler;
import com.vedank.crawler.report.CrawlReport;
import com.vedank.crawler.report.LinkResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Command-line entry point.
 *
 * <pre>
 * java -jar web-crawler.jar https://example.com --depth 2 --threads 16 --csv report.csv
 * </pre>
 *
 * Exit codes: 0 = no broken links, 1 = broken links found, 2 = usage error.
 * The non-zero exit on broken links makes it usable as a CI gate for a docs site.
 */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar web-crawler.jar <seed-url> [options]

              --depth N          max link depth from the seed to crawl (default 3)
              --max-pages N      stop after checking N URLs (default 500)
              --threads N        worker threads (default 8)
              --rps N            max requests per second per host (default 5)
              --timeout-ms N     per-request timeout (default 10000)
              --retries N        retries for timeouts, 429 and 5xx (default 2)
              --no-external      don't check links to other sites
              --ignore-robots    don't honor robots.txt
              --csv FILE         also write every result to a CSV file
              --quiet            no per-URL progress output
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }

        CrawlerConfig.Builder builder = CrawlerConfig.builder(args[0]);
        Path csv = null;
        boolean quiet = false;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--depth" -> builder.maxDepth(Integer.parseInt(args[++i]));
                    case "--max-pages" -> builder.maxPages(Integer.parseInt(args[++i]));
                    case "--threads" -> builder.threads(Integer.parseInt(args[++i]));
                    case "--rps" -> builder.requestsPerSecondPerHost(Double.parseDouble(args[++i]));
                    case "--timeout-ms" -> builder.requestTimeout(Duration.ofMillis(Long.parseLong(args[++i])));
                    case "--retries" -> builder.maxRetries(Integer.parseInt(args[++i]));
                    case "--no-external" -> builder.checkExternalLinks(false);
                    case "--ignore-robots" -> builder.respectRobotsTxt(false);
                    case "--csv" -> csv = Path.of(args[++i]);
                    case "--quiet" -> quiet = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            err.println("Missing value for " + args[args.length - 1]);
            err.print(USAGE);
            return 2;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.print(USAGE);
            return 2;
        }

        CrawlListener progress = quiet ? CrawlListener.NONE : result -> printProgress(out, result);
        try {
            CrawlReport report = new WebCrawler(builder.build(), progress).crawl();
            out.println();
            out.print(report.toText());
            if (csv != null) {
                report.writeCsv(csv);
                out.println("\nCSV written to " + csv.toAbsolutePath());
            }
            return report.hasBrokenLinks() ? 1 : 0;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 2;
        } catch (Exception e) {
            err.println("Crawl failed: " + e);
            return 2;
        }
    }

    private static void printProgress(PrintStream out, LinkResult r) {
        String code = r.httpStatus() > 0 ? Integer.toString(r.httpStatus()) : "---";
        String flag = r.isBroken() ? "BROKEN" : r.status().name();
        synchronized (out) {
            out.printf("%-6s %-17s d=%d %s%n", code, flag, r.depth(), r.url());
        }
    }
}
