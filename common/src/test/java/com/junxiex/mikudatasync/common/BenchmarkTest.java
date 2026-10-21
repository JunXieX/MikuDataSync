package com.junxiex.mikudatasync.common;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Micro benchmarks; only executed when MDS_BENCH is set (benchmark workflow). */
class BenchmarkTest {

    @Test
    void runBenchmarks() throws Exception {
        assumeTrue(System.getenv("MDS_BENCH") != null, "benchmark only");

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
        System.out.printf(Locale.ROOT, "BENCH envelope_size bytes=%d raw_segments=%d%n",
                encoded.length, inventory.length + pdc.length + stats.length + misc.length + maps.length);

        measure("encode+gzip", 50, 200, () -> {
            try {
                envelope.encode();
            } catch (ProtocolException e) {
                throw new RuntimeException(e);
            }
        });
        measure("decode+gunzip", 50, 200, () -> {
            try {
                SnapshotEnvelope.decode(encoded);
            } catch (ProtocolException e) {
                throw new RuntimeException(e);
            }
        });

        byte[] megabyte = random(1024 * 1024);
        measure("crc32c_1mb", 20, 200, () -> Crc32c.compute(megabyte, 0, megabyte.length));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        measure("sha256_1mb", 20, 200, () -> sha.digest(megabyte));

        List<MapDataList.Entry> fourMaps = List.of(
                new MapDataList.Entry(1, (byte) 1, 0, 0, false, colors),
                new MapDataList.Entry(2, (byte) 2, 10, 20, false, colors),
                new MapDataList.Entry(3, (byte) 3, -10, -20, true, colors),
                new MapDataList.Entry(4, (byte) 4, 100, 200, false, colors));
        byte[] mapsBytes = MapDataList.encode(fourMaps);
        measure("map_encode_4", 50, 300, () -> MapDataList.encode(fourMaps));
        measure("map_decode_4", 50, 300, () -> {
            try {
                MapDataList.decode(mapsBytes);
            } catch (ProtocolException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(encoded.length > 0);
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
        double avgUs = elapsed / 1000.0 / iterations;
        System.out.printf(Locale.ROOT, "BENCH %s iters=%d avg_us=%.2f ops_per_sec=%.0f%n",
                name, iterations, avgUs, iterations * 1_000_000_000.0 / elapsed);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(42).nextBytes(bytes);
        return bytes;
    }
}
