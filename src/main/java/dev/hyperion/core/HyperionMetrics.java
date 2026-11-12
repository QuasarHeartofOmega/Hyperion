package dev.hyperion.core;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Метрики без блокировок: счётчики на LongAdder, кольцевой буфер времён кадра/тика
 * на AtomicLongArray. Перцентили считаются только по запросу (HUD/команда).
 */
public final class HyperionMetrics {

    private HyperionMetrics() {
    }

    // ------------------------------------------------------------- чанки
    public static final LongAdder CHUNK_CACHE_HITS = new LongAdder();
    public static final LongAdder CHUNK_CACHE_MISSES = new LongAdder();
    public static final LongAdder CHUNK_PREFETCH_QUEUED = new LongAdder();
    public static final LongAdder CHUNK_PREFETCH_DONE = new LongAdder();
    public static final LongAdder CHUNK_PREFETCH_FAILED = new LongAdder();
    public static final LongAdder CHUNK_BYTES_SAVED = new LongAdder();

    // ---------------------------------------------------------- сущности
    public static final LongAdder ENTITY_TICKS_TOTAL = new LongAdder();
    public static final LongAdder ENTITY_AI_TICKS_SKIPPED = new LongAdder();
    public static final LongAdder ENTITY_ANALYSIS_PARALLEL = new LongAdder();
    public static final LongAdder ENTITY_ANALYSIS_NANOS = new LongAdder();
    public static volatile int LAST_ENTITY_COUNT;
    public static volatile int LAST_THROTTLED_COUNT;

    // ---------------------------------------------------------- рендер
    public static final LongAdder CULL_QUERIES = new LongAdder();
    public static final LongAdder CULL_HIDDEN = new LongAdder();
    public static final LongAdder CULL_UNKNOWN = new LongAdder();
    public static final LongAdder CULL_SNAPSHOTS_BUILT = new LongAdder();
    public static volatile int LAST_CULLED_ENTITIES;

    // ------------------------------------------------------------ память
    public static volatile int BLOCKSTATE_CACHE_ENTRIES;
    public static volatile long BLOCKSTATE_CACHE_INIT_NANOS;

    // -------------------------------------------------- кольцевые буферы
    private static final int RING = 512;
    private static final AtomicLongArray FRAME_NANOS = new AtomicLongArray(RING);
    private static final AtomicInteger FRAME_IDX = new AtomicInteger();
    private static final AtomicLongArray TICK_NANOS = new AtomicLongArray(RING);
    private static final AtomicInteger TICK_IDX = new AtomicInteger();

    public static void recordFrame(long nanos) {
        FRAME_NANOS.set(FRAME_IDX.getAndIncrement() & (RING - 1), nanos);
    }

    public static void recordTick(long nanos) {
        TICK_NANOS.set(TICK_IDX.getAndIncrement() & (RING - 1), nanos);
    }

    public static Percentiles framePercentiles() {
        return percentiles(FRAME_NANOS);
    }

    public static Percentiles tickPercentiles() {
        return percentiles(TICK_NANOS);
    }

    private static Percentiles percentiles(AtomicLongArray ring) {
        long[] copy = new long[RING];
        int n = 0;
        for (int i = 0; i < RING; i++) {
            long v = ring.get(i);
            if (v > 0) copy[n++] = v;
        }
        if (n == 0) return new Percentiles(0, 0, 0, 0);
        long[] sorted = Arrays.copyOf(copy, n);
        Arrays.sort(sorted);
        return new Percentiles(
                sorted[(int) (n * 0.50)] / 1_000_000.0,
                sorted[(int) (n * 0.95)] / 1_000_000.0,
                sorted[Math.min(n - 1, (int) (n * 0.99))] / 1_000_000.0,
                sorted[n - 1] / 1_000_000.0);
    }

    public record Percentiles(double p50, double p95, double p99, double max) {
        public String format() {
            return String.format("p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms", p50, p95, p99, max);
        }
    }

    public static double hitRate() {
        long hits = CHUNK_CACHE_HITS.sum();
        long misses = CHUNK_CACHE_MISSES.sum();
        long total = hits + misses;
        return total == 0 ? 0.0 : (100.0 * hits / total);
    }

    public static double aiSavedPercent() {
        long total = ENTITY_TICKS_TOTAL.sum();
        return total == 0 ? 0.0 : (100.0 * ENTITY_AI_TICKS_SKIPPED.sum() / total);
    }
}
