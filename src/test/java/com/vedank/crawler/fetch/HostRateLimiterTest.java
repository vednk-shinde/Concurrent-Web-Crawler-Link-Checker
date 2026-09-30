package com.vedank.crawler.fetch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class HostRateLimiterTest {

    @Test
    void spacesRequestsToOneHostEvenAcrossThreads() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(50); // one slot every 20ms
        List<Long> grants = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                limiter.acquire("http://a.test");
                grants.add(System.nanoTime());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        Collections.sort(grants);
        long spanMs = (grants.get(grants.size() - 1) - grants.get(0)) / 1_000_000;
        assertTrue(spanMs >= 180, "11 grants at 50/s need >= 200ms (allowing jitter), got " + spanMs);
    }

    @Test
    void differentHostsDoNotWaitForEachOther() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(1); // 1 per second per host
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            limiter.acquire("http://host" + i + ".test");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "first request to each host should be immediate, took " + elapsedMs);
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new HostRateLimiter(0));
    }
}
