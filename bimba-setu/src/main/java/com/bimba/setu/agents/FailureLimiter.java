package com.bimba.setu.agents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Counts failed sign-ins per key (client IP) in a sliding window; blocked keys get HTTP 429. */
public final class FailureLimiter {
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public boolean blocked(String key, int max, long windowMs) {
        Deque<Long> d = failures.get(key);
        if (d == null) {
            return false;
        }
        synchronized (d) {
            prune(d, windowMs);
            return d.size() >= max;
        }
    }

    public void fail(String key, long windowMs) {
        Deque<Long> d = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d, windowMs);
            d.addLast(System.currentTimeMillis());
        }
        if (failures.size() > 10_000) {          // keep memory bounded under a spray of addresses
            failures.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    public void clear(String key) {
        failures.remove(key);
    }

    private static void prune(Deque<Long> d, long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        while (!d.isEmpty() && d.peekFirst() < cutoff) {
            d.pollFirst();
        }
    }
}
