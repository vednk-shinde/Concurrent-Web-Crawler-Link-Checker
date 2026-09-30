# Concurrent Web Crawler & Link Checker

![CI](https://github.com/vednk-shinde/Concurrent-Web-Crawler-Link-Checker/actions/workflows/ci.yml/badge.svg)

A multi-threaded web crawler in Java 17 that discovers every page of a site and validates every link on it, internal and external, concurrently. It's built on a worker thread pool over a shared work queue, hash-based URL deduplication, lock-free per-host rate limiting, retries with exponential backoff, timeouts and robots.txt support. It ends with a report of every broken link and the pages that link to it.

Example output (illustrative):

```
$ java -jar web-crawler.jar https://docs.example.com --depth 3 --threads 16

200    OK                d=0 https://docs.example.com/
200    OK                d=1 https://docs.example.com/guide
404    BROKEN            d=2 https://docs.example.com/guide/old-page
200    OK                d=1 https://github.com/example/repo
...

Crawl of https://docs.example.com/
  214 URLs checked (187 internal, 27 external) in 9.84s with 16 threads (21.7 URLs/s)
  OK                 211
  CLIENT_ERROR       2
  NETWORK_ERROR      1

Broken links (3):
  [HTTP 404] https://docs.example.com/guide/old-page
      linked from https://docs.example.com/guide
      linked from https://docs.example.com/faq
  ...
```

Exit code is `1` when broken links are found, so it works as a CI gate for a documentation site.

## Architecture

```
                         ┌────────────────────────────────────────────┐
  seed ──▶ enqueue() ──▶ │ Frontier: LinkedBlockingQueue<CrawlTask>   │
             ▲           └───────────────┬────────────────────────────┘
             │                           │ poll()
             │        ┌──────────────────┼──────────────────┐
             │        ▼                  ▼                  ▼
             │   worker-1           worker-2    ...    worker-N        (fixed thread pool)
             │        │                  │                  │
             │        └────────┬─────────┴──────────────────┘
             │                 ▼
             │        robots.txt check  (RobotsCache: one fetch per origin, shared future)
             │                 ▼
             │        HostRateLimiter.acquire(origin)   (lock-free slot reservation)
             │                 ▼
             │        PageFetcher: GET or HEAD, timeout, retry 429/5xx/IO with backoff
             │                 ▼
             │        LinkExtractor (jsoup) ──▶ UrlNormalizer
             │                 │
             └──── new links ──┘   seen: ConcurrentHashMap.newKeySet()  (atomic dedup)
                                   referrers: ConcurrentHashMap<url, Set<page>>
                                   results:   ConcurrentHashMap<url, LinkResult>
```

## How the concurrency works

| Problem | Solution | Where |
|---|---|---|
| Fetch many pages at once | Fixed pool of N worker threads pulling `CrawlTask`s from one `LinkedBlockingQueue` | `WebCrawler.Run.workerLoop` |
| Never fetch a URL twice, even when several workers discover it at the same instant | URLs are normalized, then added to a concurrent hash set; `Set.add` is atomic, so exactly one worker wins and enqueues it | `WebCrawler.Run.enqueue`, `UrlNormalizer` |
| Know when the crawl is finished | `pending` counts tasks queued *or* in progress. Children are counted **before** the parent is un-counted, so it can only reach 0 when there is truly no work left. That releases a `CountDownLatch` | `WebCrawler.Run` |
| Don't hammer a site | Per-origin rate limit shared by all workers. Each caller CASes the origin's next free time slot forward, then sleeps until its slot (outside any lock). Different origins never wait on each other | `HostRateLimiter` |
| Fetch robots.txt once per origin, not once per worker | First thread installs a `CompletableFuture` with `putIfAbsent` and fetches; others `join()` the same future | `RobotsCache` |
| Record who links to a broken URL | `ConcurrentHashMap<url, concurrent Set<referrer>>`, updated with `computeIfAbsent` | `WebCrawler.Run.process` |
| One bad page shouldn't kill a worker | Per-task exception handling records the failure and the worker continues | `WebCrawler.Run.workerLoop` |

## Failure handling

- **Timeouts:** connect and request timeouts on every call, so a hung server costs one timeout, not a stuck worker.
- **Retries:** network errors, `429` and `5xx` are retried with exponential backoff (`500ms`, `1s`, `2s`, …). `4xx` is final.
- **HEAD first:** leaf pages and external links only need a status, so they get a `HEAD`. If the server answers `405`/`501`, the crawler falls back to `GET`, and that isn't counted as a retry.
- **Redirects** are followed, and the final URL is recorded. Links are only followed if the redirect stays on the seed's origin.
- **Body cap:** at most 5 MB of any response is read.
- **robots.txt:** `Allow`/`Disallow` with longest-match precedence, and a group naming our agent overrides `*`. Blocked URLs are reported as `BLOCKED_BY_ROBOTS`, not broken.

## Usage

Requires JDK 17+.

```bash
mvn package
java -jar target/web-crawler.jar https://example.com --depth 2 --csv report.csv
```

| Option | Default | |
|---|---|---|
| `--depth N` | 3 | Max link depth from the seed to crawl. Pages at the last level are checked but their links aren't followed |
| `--max-pages N` | 500 | Stop after checking N URLs (the report says if it stopped early) |
| `--threads N` | 8 | Worker threads |
| `--rps N` | 5 | Max requests per second per host |
| `--timeout-ms N` | 10000 | Per-request timeout |
| `--retries N` | 2 | Retries for timeouts, 429 and 5xx |
| `--no-external` | | Don't check links to other sites |
| `--ignore-robots` | | Don't honor robots.txt |
| `--csv FILE` | | Write every result (status, attempts, latency, referrers) to CSV |
| `--quiet` | | No per-URL progress lines |

Exit codes: `0` no broken links, `1` broken links found, `2` usage error.

### As a library

```java
CrawlerConfig config = CrawlerConfig.builder("https://example.com")
        .threads(16)
        .maxDepth(2)
        .requestsPerSecondPerHost(10)
        .build();

CrawlReport report = new WebCrawler(config, result -> System.out.println(result.url())).crawl();
report.brokenLinks().forEach(r -> System.out.println(r.url() + " <- " + report.referrersOf(r.url())));
```

### In GitHub Actions

```yaml
- name: Check docs for broken links
  run: java -jar web-crawler.jar https://docs.example.com --depth 4 --csv links.csv
```

## Tests

`mvn test` runs 51 tests. The crawler tests run against a real in-process HTTP server (the JDK's `HttpServer`), not mocks, so they exercise real sockets, timeouts and concurrency:

- every page fetched **exactly once** despite link cycles, fragments and `./`/`../` variants
- 10 hub pages × 20 leaves with 8 threads: each leaf is discovered 10 times concurrently but fetched once, and the server sees overlapping requests (real parallelism)
- broken links reported with every referring page
- `maxDepth` and `maxPages` limits
- flaky `503` recovers on retry; permanent `500` gives up after `1 + retries` attempts
- a hung page times out without delaying the rest of the crawl
- robots.txt `Allow`/`Disallow`, fetched once per origin
- external links checked with `HEAD`, never crawled; `405` → `GET` fallback
- redirects followed, final URL recorded
- per-host rate limit actually spaces requests out across 8 threads
- CLI exit codes and CSV output

## Project layout

```
src/main/java/com/vedank/crawler/
  WebCrawler.java        thread pool, frontier, dedup, termination
  CrawlerConfig.java     immutable settings + builder
  CrawlTask.java         unit of work (url, depth, parent)
  CrawlListener.java     progress callback
  fetch/                 PageFetcher (timeouts, retries, HEAD/GET), HostRateLimiter, FetchResult
  parse/                 UrlNormalizer, LinkExtractor (jsoup)
  robots/                RobotsRules (parser/matcher), RobotsCache (once per origin)
  report/                LinkResult, LinkStatus, CrawlReport (text + CSV)
  cli/Main.java          command-line interface
```

## Scope notes

- Single process. Distributing the crawl across machines would move the frontier and `seen` set to a shared store (e.g. Redis or Kafka partitioned by host).
- Only `<a href>` links are followed. Images, scripts and stylesheets aren't checked.
- JavaScript isn't executed, so links that only exist after client-side rendering aren't seen.
- robots.txt `Crawl-delay` and wildcard (`*`, `$`) path patterns aren't implemented. Use `--rps` to set politeness.
