package com.junxiex.mikudatasync.common;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight process-wide timing counters exposed through /mds status commands. */
public final class Metrics {

    private static final AtomicLong CAPTURES = new AtomicLong();
    private static final AtomicLong CAPTURE_LAST = new AtomicLong();
    private static final AtomicLong CAPTURE_MAX = new AtomicLong();
    private static final AtomicLong APPLIES = new AtomicLong();
    private static final AtomicLong APPLY_LAST = new AtomicLong();
    private static final AtomicLong APPLY_MAX = new AtomicLong();
    private static final AtomicLong GATES = new AtomicLong();
    private static final AtomicLong GATE_LAST = new AtomicLong();
    private static final AtomicLong GATE_MAX = new AtomicLong();

    private Metrics() {
    }

    public static void recordCapture(long nanos) {
        record(CAPTURES, CAPTURE_LAST, CAPTURE_MAX, nanos);
    }

    public static void recordApply(long nanos) {
        record(APPLIES, APPLY_LAST, APPLY_MAX, nanos);
    }

    public static void recordGate(long nanos) {
        record(GATES, GATE_LAST, GATE_MAX, nanos);
    }

    private static void record(AtomicLong count, AtomicLong last, AtomicLong max, long nanos) {
        if (nanos < 0) {
            return;
        }
        count.incrementAndGet();
        last.set(nanos);
        max.accumulateAndGet(nanos, Math::max);
    }

    public static String captureSummary() {
        return String.format(Locale.ROOT, "capture n=%d last=%.2fms max=%.2fms",
                CAPTURES.get(), CAPTURE_LAST.get() / 1_000_000.0, CAPTURE_MAX.get() / 1_000_000.0);
    }

    public static String applySummary() {
        return String.format(Locale.ROOT, "apply n=%d last=%.2fms max=%.2fms",
                APPLIES.get(), APPLY_LAST.get() / 1_000_000.0, APPLY_MAX.get() / 1_000_000.0);
    }

    public static String gateSummary() {
        return String.format(Locale.ROOT, "gate n=%d last=%.2fms max=%.2fms",
                GATES.get(), GATE_LAST.get() / 1_000_000.0, GATE_MAX.get() / 1_000_000.0);
    }
}
