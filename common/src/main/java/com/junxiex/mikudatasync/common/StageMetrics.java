package com.junxiex.mikudatasync.common;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight per-stage timing counters (nanoseconds), exposed via /mdsbackend timings. */
public final class StageMetrics {

    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();

    private StageMetrics() {
    }

    public static void record(String stage, long nanos) {
        Counter counter = COUNTERS.computeIfAbsent(stage, key -> new Counter());
        counter.count.increment();
        counter.total.add(nanos);
        long value = nanos;
        counter.max.accumulateAndGet(value, Math::max);
    }

    public static String summary() {
        StringBuilder builder = new StringBuilder("stage timings (avg/max us, count):");
        COUNTERS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Counter counter = entry.getValue();
                    long count = counter.count.sum();
                    if (count == 0) {
                        return;
                    }
                    builder.append(' ').append(entry.getKey())
                            .append('=').append(String.format(Locale.ROOT, "%.1f/%.1f us x%d",
                                    counter.total.sum() / 1000.0 / count,
                                    counter.max.get() / 1000.0, count));
                });
        return builder.toString();
    }

    public static void reset() {
        COUNTERS.clear();
    }

    private static final class Counter {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final java.util.concurrent.atomic.AtomicLong max = new java.util.concurrent.atomic.AtomicLong();
    }
}
