package com.junxiex.mikudatasync.benchmark;

import com.junxiex.mikudatasync.common.Crc32c;
import com.junxiex.mikudatasync.common.MapDataList;
import com.junxiex.mikudatasync.common.Protocol;
import com.junxiex.mikudatasync.common.Segment;
import com.junxiex.mikudatasync.common.SnapshotEnvelope;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.velocity.BackupService;
import com.junxiex.mikudatasync.velocity.JournalStore;
import com.junxiex.mikudatasync.velocity.VelocityConfig;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Benchmarks the six async-candidate hot paths; executed by the benchmark workflow. */
public final class AsyncBenchmark {

    private static final List<String> LINES = new ArrayList<>();

    private AsyncBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        line("environment java=" + System.getProperty("java.version")
                + " cores=" + Runtime.getRuntime().availableProcessors()
                + " os=" + System.getProperty("os.name"));

        byte[] inventory = random(6144);
        byte[] pdc = random(1024);
        byte[] stats = random(2048);
        byte[] misc = random(200);
        byte[] colors = new byte[MapDataList.COLORS_LENGTH];
        java.util.Arrays.fill(colors, (byte) 7);
        byte[] maps = MapDataList.encode(List.of(new MapDataList.Entry(1, (byte) 1, 0, 0, false, colors)));
        SnapshotEnvelope envelope = new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 5000,
                Map.of(Segment.CORE, random(64), Segment.INVENTORY, inventory, Segment.PDC, pdc,
                        Segment.STATISTICS, stats, Segment.MISC, misc, Segment.MAPS, maps));
        byte[] encoded = envelope.encode();
        line("envelope bytes=" + encoded.length + " raw="
                + (64 + inventory.length + pdc.length + stats.length + misc.length + maps.length));

        run("encode_gzip", 100, 500, () -> {
            try {
                envelope.encode();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        run("decode_gunzip", 100, 500, () -> {
            try {
                SnapshotEnvelope.decode(encoded);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        byte[] megabyte = random(1024 * 1024);
        run("crc32c_1mb", 50, 500, () -> Crc32c.compute(megabyte, 0, megabyte.length));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        run("sha256_1mb", 50, 500, () -> sha.digest(megabyte));

        List<MapDataList.Entry> fourMaps = List.of(
                new MapDataList.Entry(1, (byte) 1, 0, 0, false, colors),
                new MapDataList.Entry(2, (byte) 2, 10, 20, false, colors),
                new MapDataList.Entry(3, (byte) 3, -10, -20, true, colors),
                new MapDataList.Entry(4, (byte) 4, 100, 200, false, colors));
        byte[] mapsBytes = MapDataList.encode(fourMaps);
        run("map_encode_4", 100, 1000, () -> MapDataList.encode(fourMaps));
        run("map_decode_4", 100, 1000, () -> {
            try {
                MapDataList.decode(mapsBytes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Path dir = Files.createTempDirectory("mds-bench");
        byte[] payload = random(2048);
        SnapshotMessage message = new SnapshotMessage(1, 1, "quit", "survival-1", payload);
        UUID uuid = UUID.randomUUID();
        try (JournalStore journal = new JournalStore(dir.resolve("never.mds"),
                VelocityConfig.FsyncMode.NEVER, 50, Long.MAX_VALUE, LoggerFactory.getLogger("bench"))) {
            run("journal_append_no_fsync", 200, 2000, () -> {
                try {
                    journal.append(uuid, message, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        try (JournalStore journal = new JournalStore(dir.resolve("interval.mds"),
                VelocityConfig.FsyncMode.INTERVAL, 50, Long.MAX_VALUE, LoggerFactory.getLogger("bench"))) {
            run("journal_append_interval", 50, 500, () -> {
                try {
                    journal.append(uuid, message, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        try (JournalStore journal = new JournalStore(dir.resolve("always.mds"),
                VelocityConfig.FsyncMode.ALWAYS, 50, Long.MAX_VALUE, LoggerFactory.getLogger("bench"))) {
            run("journal_append_always_fsync", 5, 50, () -> {
                try {
                    journal.append(uuid, message, true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        BackupService backups = new BackupService(dir.resolve("backups"), 100,
                LoggerFactory.getLogger("bench"));
        Path first = backups.write(uuid, "BenchPlayer", message);
        String fileName = first.getFileName().toString();
        run("backup_write_2kb", 20, 200, () -> {
            try {
                backups.write(uuid, "BenchPlayer", message);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        run("backup_read_2kb", 20, 200, () -> {
            try {
                backups.read(fileName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Path output = Path.of("build", "benchmark-results.txt").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
        System.out.println("results: " + output);
    }

    private static void run(String name, int warmup, int iterations, Runnable action) {
        for (int i = 0; i < warmup; i++) {
            action.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
        long elapsed = System.nanoTime() - start;
        double avgUs = elapsed / 1000.0 / iterations;
        line(String.format(Locale.ROOT, "BENCH %s iters=%d avg_us=%.3f ops_per_sec=%.0f",
                name, iterations, avgUs, iterations * 1_000_000_000.0 / elapsed));
    }

    private static void line(String text) {
        LINES.add(text);
        System.out.println(text);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(42).nextBytes(bytes);
        return bytes;
    }
}