package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Small deterministic codec micro-benchmark shared by the CLI harness and /mds bench. */
public final class CodecBench {

    public record Result(int iterations, int payloadBytes, long p50Nanos, long p95Nanos, long avgNanos) {
    }

    private CodecBench() {
    }

    public static Result run(int warmupIterations, int measureIterations) {
        return run(warmupIterations, measureIterations, () -> {
        });
    }

    private static Result run(int warmupIterations, int measureIterations, Runnable tick) {
        SnapshotEnvelope envelope = sampleEnvelope();
        try {
            for (int i = 0; i < warmupIterations; i++) {
                SnapshotEnvelope.decode(envelope.encode());
                tick.run();
            }
            int payloadBytes = envelope.encode().length;
            long[] samples = new long[measureIterations];
            for (int i = 0; i < measureIterations; i++) {
                long start = System.nanoTime();
                SnapshotEnvelope.decode(envelope.encode());
                samples[i] = System.nanoTime() - start;
                tick.run();
            }
            Arrays.sort(samples);
            long p50 = samples[measureIterations / 2];
            long p95 = samples[Math.min(measureIterations - 1, (int) (measureIterations * 0.95))];
            long sum = 0L;
            for (long sample : samples) {
                sum += sample;
            }
            return new Result(measureIterations, payloadBytes, p50, p95, sum / measureIterations);
        } catch (ProtocolException e) {
            throw new IllegalStateException("sample envelope is invalid", e);
        }
    }

    public static String describe(Result result) {
        return String.format(Locale.ROOT,
                "codec: iterations=%d payload=%dB p50=%.1fus p95=%.1fus avg=%.1fus",
                result.iterations(), result.payloadBytes(),
                result.p50Nanos() / 1000.0, result.p95Nanos() / 1000.0, result.avgNanos() / 1000.0);
    }

    public static SnapshotEnvelope sampleEnvelope() {
        Random random = new Random(42L);
        List<EffectList.Entry> effects = new ArrayList<>();
        effects.add(new EffectList.Entry("minecraft:speed", 600, 1, true, true, true));
        effects.add(new EffectList.Entry("minecraft:regeneration", 200, 0, false, true, true));

        List<AttributeList.Entry> attributes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attributes.add(new AttributeList.Entry("minecraft:attribute_" + i, 1.0 + i));
        }

        List<StatisticsList.Entry> statistics = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            statistics.add(new StatisticsList.Entry("minecraft:custom", "minecraft:stat_" + i, i * 7));
        }

        List<AdvancementList.Entry> advancements = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            advancements.add(new AdvancementList.Entry("minecraft:story/advancement_" + i,
                    List.of("criteria_a", "criteria_b")));
        }

        CoreData core = new CoreData(19.5, 0.0, 18, 4.5f, 0.25f, 30, 0.5f, 12000,
                "SURVIVAL", true, false, 0.05f, 0.1f, 3, 0, 0, 300, 0.0f);

        byte[] inventory = new byte[8192];
        byte[] enderChest = new byte[2048];
        byte[] pdc = new byte[2048];
        random.nextBytes(inventory);
        random.nextBytes(enderChest);
        random.nextBytes(pdc);

        try {
            return new SnapshotEnvelope(Protocol.SCHEMA_VERSION, 4321, java.util.Map.of(
                    Segment.INVENTORY, inventory,
                    Segment.ENDER_CHEST, enderChest,
                    Segment.CORE, core.encode(),
                    Segment.EFFECTS, EffectList.encode(effects),
                    Segment.ATTRIBUTES, AttributeList.encode(attributes),
                    Segment.STATISTICS, StatisticsList.encode(statistics),
                    Segment.ADVANCEMENTS, AdvancementList.encode(advancements),
                    Segment.PDC, pdc,
                    Segment.MISC, MiscData.EMPTY.encode()
            ));
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }
}
