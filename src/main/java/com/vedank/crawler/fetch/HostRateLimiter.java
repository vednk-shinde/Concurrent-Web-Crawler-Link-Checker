package com.vedank.crawler.fetch;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politeness: at most {@code requestsPerSecond} request starts per origin,
 * no matter how many worker threads are crawling it. Different origins are
 * limited independently, so one slow site doesn't throttle the others.
 *
 * Lock-free slot reservation: each caller CASes the origin's "next free
 * slot" forward by one interval, then sleeps (outside any lock) until its
 * reserved slot arrives. Threads never block each other while waiting.
 */
public final class HostRateLimiter {

    private final long intervalNanos;
    private final ConcurrentMap<String, AtomicLong> nextSlot = new ConcurrentHashMap<>();

    public HostRateLimiter(double requestsPerSecond) {
        if (requestsPerSecond <= 0) throw new IllegalArgumentException("requestsPerSecond must be > 0");
        this.intervalNanos = (long) (1_000_000_000L / requestsPerSecond);
    }

    public void acquire(String origin) throws InterruptedException {
        AtomicLong slot = nextSlot.computeIfAbsent(origin, o -> new AtomicLong(Long.MIN_VALUE));
        long reserved;
        while (true) {
            long current = slot.get();
            long now = System.nanoTime();
            long start = current == Long.MIN_VALUE ? now : Math.max(current, now);
            if (slot.compareAndSet(current, start + intervalNanos)) {
                reserved = start;
                break;
            }
        }
        long wait = reserved - System.nanoTime();
        if (wait > 0) {
            TimeUnit.NANOSECONDS.sleep(wait);
        }
    }
}
