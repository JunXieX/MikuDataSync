package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ByteWriter;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Journal/backup IO benchmarks; only executed when MDS_BENCH is set. */
class IoBenchmarkTest {

    @Test
    void runBenchmarks() throws Exception {
        assumeTrue(System.getenv("MDS_BENCH") != null, "benchmark only");
        Path dir = Files.createTempDirectory("mds-bench");
        byte[] envelope = new byte[2048];
        new java.util.Random(7).nextBytes(envelope);
        SnapshotMessage message = new SnapshotMessage(1, 1, "quit", "survival-1", envelope);
        UUID uuid = UUID.randomUUID();

        Path neverPath = dir.resolve("never.mds");
        try (JournalStore journal = new JournalStore(neverPath, VelocityConfig.FsyncMode.NEVER, 50, Long.MAX_VALUE,
                LoggerFactory.getLogger("bench"))) {
            measure("journal_append_no_fsync", 100, 1000, () -> {
                try {
                    journal.append(uuid, message, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        Path intervalPath = dir.resolve("interval.mds");
        try (JournalStore journal = new JournalStore(intervalPath, VelocityConfig.FsyncMode.INTERVAL, 50, Long.MAX_VALUE,
                LoggerFactory.getLogger("bench"))) {
            measure("journal_append_interval", 50, 500, () -> {
                try {
                    journal.append(uuid, message, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        Path alwaysPath = dir.resolve("always.mds");
        try (JournalStore journal = new JournalStore(alwaysPath, VelocityConfig.FsyncMode.ALWAYS, 50, Long.MAX_VALUE,
                LoggerFactory.getLogger("bench"))) {
            measure("journal_append_always_fsync", 5, 40, () -> {
                try {
                    journal.append(uuid, message, true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        BackupService backups = new BackupService(dir.resolve("backups"), 100,
                LoggerFactory.getLogger("bench"));
        Path last = backups.write(uuid, "BenchPlayer", message);
        String fileName = last.getFileName().toString();
        measure("backup_write_2kb", 10, 100, () -> {
            try {
                backups.write(uuid, "BenchPlayer", message);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        measure("backup_read_2kb", 10, 100, () -> {
            try {
                backups.read(fileName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void measure(String name, int warmup, int iterations, Runnable action) {
        for (int i = 0; i < warmup; i++) {
            action.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "BENCH %s iters=%d avg_us=%.2f ops_per_sec=%.0f%n",
                name, iterations, elapsed / 1000.0 / iterations,
                iterations * 1_000_000_000.0 / elapsed);
    }
}