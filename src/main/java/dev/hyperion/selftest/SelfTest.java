package dev.hyperion.selftest;

import dev.hyperion.core.util.MpscQueue;
import dev.hyperion.core.util.PackedPos;
import dev.hyperion.entity.path.AsyncPathfinder;
import dev.hyperion.entity.path.BlockSampler;
import dev.hyperion.entity.path.PathCache;
import dev.hyperion.renderer.SectionLookup;
import dev.hyperion.renderer.SectionSnapshot;
import dev.hyperion.renderer.VoxelRaycaster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Автономные тесты чистых компонентов мода — запускаются без Minecraft:
 * <pre>java -cp &lt;classpath&gt; dev.hyperion.selftest.SelfTest</pre>
 * Проверяют именно те места, где в исходной спеке были ошибки: lock-free очередь,
 * упаковка координат, кэш путей и рейкаст.
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testMpscFifo();
        testMpscCapacity();
        testMpscMultiProducerStress();
        testPackedPositions();
        testPathfinderPacking();
        testAstarOpenGrid();
        testAstarWall();
        testPathCache();
        testSectionSnapshotBits();
        testRaycastClear();
        testRaycastBlocked();
        testRaycastUnknown();

        System.out.println();
        System.out.println("=== Hyperion self-test: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ очереди

    private static void testMpscFifo() {
        MpscQueue<Integer> queue = new MpscQueue<>();
        for (int i = 0; i < 1000; i++) queue.offer(i);
        boolean ok = true;
        for (int i = 0; i < 1000; i++) {
            Integer value = queue.poll();
            if (value == null || value != i) ok = false;
        }
        check("MPSC FIFO порядок", ok && queue.poll() == null && queue.isEmpty());
    }

    private static void testMpscCapacity() {
        MpscQueue<Integer> queue = new MpscQueue<>(4);
        boolean ok = queue.offer(1) && queue.offer(2) && queue.offer(3) && queue.offer(4);
        ok &= !queue.offer(5);
        queue.poll();
        ok &= queue.offer(5);
        check("MPSC мягкий лимит ёмкости", ok);
    }

    private static void testMpscMultiProducerStress() throws Exception {
        final int producers = 4;
        final int perProducer = 25_000;
        MpscQueue<Integer> queue = new MpscQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    while (!queue.offer(base + i)) {
                        Thread.onSpinWait();
                    }
                }
                done.countDown();
            }, "producer-" + p);
            threads.add(t);
            t.start();
        }

        Set<Integer> seen = new HashSet<>(producers * perProducer);
        AtomicInteger duplicates = new AtomicInteger();
        start.countDown();
        int expected = producers * perProducer;
        long deadline = System.currentTimeMillis() + 20_000;
        int received = 0;
        while (received < expected && System.currentTimeMillis() < deadline) {
            Integer value = queue.poll();
            if (value == null) {
                Thread.onSpinWait();
                continue;
            }
            if (!seen.add(value)) duplicates.incrementAndGet();
            received++;
        }
        done.await();
        for (Thread t : threads) t.join();
        // Добираем остаток после завершения производителей.
        Integer value;
        while ((value = queue.poll()) != null) {
            if (!seen.add(value)) duplicates.incrementAndGet();
            received++;
        }
        check("MPSC 4 производителя x 25000: без потерь и дублей",
                received == expected && seen.size() == expected && duplicates.get() == 0);
    }

    // ------------------------------------------------------------------ координаты

    private static void testPackedPositions() {
        boolean ok = true;
        int[] coords = {0, 1, -1, 12345, -12345, Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2};
        for (int x : coords) {
            for (int z : coords) {
                long packed = PackedPos.chunk(x, z);
                ok &= PackedPos.x(packed) == x && PackedPos.z(packed) == z;
            }
        }
        for (int x : new int[]{0, 15, 16, -1, -16, -17, 100000, -100000}) {
            for (int z : new int[]{0, 15, 16, -1, -16, -17, 100000, -100000}) {
                long region = PackedPos.region(x, z);
                ok &= PackedPos.x(region) == (x >> PackedPos.REGION_SHIFT);
                ok &= PackedPos.z(region) == (z >> PackedPos.REGION_SHIFT);
            }
        }
        for (int sx : new int[]{0, 1, -1, 1000, -1000}) {
            for (int sy : new int[]{-4, 0, 4, 19, -20}) {
                for (int sz : new int[]{0, 1, -1, 1000, -1000}) {
                    long key = PackedPos.section(sx, sy, sz);
                    ok &= PackedPos.sectionX(key) == sx
                            && PackedPos.sectionY(key) == sy
                            && PackedPos.sectionZ(key) == sz;
                }
            }
        }
        check("Упаковка chunk/region/section (включая отрицательные координаты)", ok);
    }

    private static void testPathfinderPacking() {
        boolean ok = true;
        for (int x : new int[]{0, 1, -1, 510, -511}) {
            for (int y : new int[]{0, 1, -1, 254, -255}) {
                for (int z : new int[]{0, 1, -1, 510, -511}) {
                    int key = invokePack(x, y, z);
                    ok &= unpackX(key) == x && unpackY(key) == y && unpackZ(key) == z;
                }
            }
        }
        check("A* упаковка относительных координат", ok);
    }

    // reflection: методы пакетно-приватные, тест живёт в другом пакете
    private static int invokePack(int x, int y, int z) {
        try {
            var m = AsyncPathfinder.class.getDeclaredMethod("pack", int.class, int.class, int.class);
            m.setAccessible(true);
            return (int) m.invoke(null, x, y, z);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int unpackX(int key) {
        return invokeUnpack("unpackX", key);
    }

    private static int unpackY(int key) {
        return invokeUnpack("unpackY", key);
    }

    private static int unpackZ(int key) {
        return invokeUnpack("unpackZ", key);
    }

    private static int invokeUnpack(String name, int key) {
        try {
            var m = AsyncPathfinder.class.getDeclaredMethod(name, int.class);
            m.setAccessible(true);
            return (int) m.invoke(null, key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ A*

    private static void testAstarOpenGrid() {
        BlockSampler open = (x, y, z) -> x >= 0 && x < 32 && y >= 0 && y < 8 && z >= 0 && z < 32;
        AsyncPathfinder pathfinder = new AsyncPathfinder(50_000);
        AsyncPathfinder.Result result = pathfinder.find(open, 0, 1, 0, 10, 1, 7);
        boolean ok = result.found() && result.path().length == 18; // 10 + 7 шагов + стартовый узел
        long last = result.path()[result.path().length - 1];
        ok &= AsyncPathfinder.absoluteX(last) == 10 && AsyncPathfinder.absoluteZ(last) == 7;
        check("A* на открытом поле: кратчайший путь", ok);
    }

    private static void testAstarWall() {
        // Стена x=5 на всю высоту, кроме прохода в y=3.
        BlockSampler sampler = (x, y, z) -> {
            if (x < 0 || x > 12 || y < 0 || y > 6 || z < 0 || z > 12) return false;
            return x != 5 || y == 3;
        };
        AsyncPathfinder pathfinder = new AsyncPathfinder(50_000);
        AsyncPathfinder.Result result = pathfinder.find(sampler, 0, 1, 0, 10, 1, 0);
        boolean ok = result.found();
        boolean usesGap = false;
        for (long node : result.path()) {
            if (AsyncPathfinder.absoluteX(node) == 5 && AsyncPathfinder.absoluteY(node) == 3) usesGap = true;
        }
        check("A* обходит стену через единственный проход", ok && usesGap);

        BlockSampler sealed = (x, y, z) -> x >= 0 && x <= 12 && y >= 0 && y <= 6 && z >= 0 && z <= 12 && x != 5;
        AsyncPathfinder.Result blocked = pathfinder.find(sealed, 0, 1, 0, 10, 1, 0);
        check("A* корректно сообщает «пути нет»", !blocked.found());
    }

    private static void testPathCache() {
        PathCache cache = new PathCache(8, 10_000);
        AsyncPathfinder.Result fake = new AsyncPathfinder.Result(new long[0], 1, 1000, true);
        boolean ok = cache.get(0, 0, 0, 1, 1, 1) == null;
        cache.put(0, 0, 0, 1, 1, 1, fake);
        ok &= cache.get(0, 0, 0, 1, 1, 1) == fake;
        ok &= cache.get(0, 0, 0, 2, 2, 2) == null;
        ok &= cache.hits() == 1 && cache.misses() == 2;
        check("PathCache: попадания/промахи", ok);

        PathCache shortLived = new PathCache(8, 50);
        shortLived.put(0, 0, 0, 1, 1, 1, fake);
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        check("PathCache: TTL истекает", shortLived.get(0, 0, 0, 1, 1, 1) == null);
    }

    // ------------------------------------------------------------------ рейкаст

    private static void testSectionSnapshotBits() {
        long[] bits = new long[64];
        int[] indices = {0, 1, 63, 64, 127, 4095};
        for (int i : indices) SectionSnapshot.set(bits, i);
        SectionSnapshot snapshot = new SectionSnapshot(bits, System.currentTimeMillis(), false);
        boolean ok = true;
        for (int i : indices) ok &= snapshot.isOpaque(i);
        ok &= !snapshot.isOpaque(2) && !snapshot.isOpaque(4094);
        check("SectionSnapshot: битовые операции", ok);
    }

    private static void testRaycastClear() {
        Map<Long, SectionSnapshot> map = new HashMap<>();
        long key = PackedPos.section(0, 4, 0);
        map.put(key, new SectionSnapshot(new long[64], System.currentTimeMillis(), true));
        SectionLookup lookup = map::get;
        int result = VoxelRaycaster.trace(lookup, 0.5, 64.5, 0.5, 10.5, 64.5, 0.5);
        check("Рейкаст: свободный луч", result == VoxelRaycaster.CLEAR);
    }

    private static void testRaycastBlocked() {
        Map<Long, SectionSnapshot> map = new HashMap<>();
        long[] bits = new long[64];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                SectionSnapshot.set(bits, PackedPos.sectionIndex(5, y, z));
            }
        }
        map.put(PackedPos.section(0, 4, 0), new SectionSnapshot(bits, System.currentTimeMillis(), false));
        SectionLookup lookup = map::get;
        int result = VoxelRaycaster.trace(lookup, 0.5, 64.5, 0.5, 10.5, 64.5, 0.5);
        check("Рейкаст: стена блокирует", result == VoxelRaycaster.BLOCKED);

        // Луч, идущий над стеной (стена только до y=66).
        long[] lowWall = new long[64];
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 16; z++) {
                SectionSnapshot.set(lowWall, PackedPos.sectionIndex(5, y, z));
            }
        }
        map.put(PackedPos.section(0, 4, 0), new SectionSnapshot(lowWall, System.currentTimeMillis(), false));
        int above = VoxelRaycaster.trace(lookup, 0.5, 70.5, 0.5, 10.5, 70.5, 0.5);
        check("Рейкаст: луч над стеной свободен", above == VoxelRaycaster.CLEAR);
    }

    private static void testRaycastUnknown() {
        SectionLookup lookup = key -> null;
        int result = VoxelRaycaster.trace(lookup, 0.5, 64.5, 0.5, 10.5, 64.5, 0.5);
        check("Рейкаст: неизвестная секция -> UNKNOWN (fail-open)", result == VoxelRaycaster.UNKNOWN);
    }

    // ------------------------------------------------------------------ утилита

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
