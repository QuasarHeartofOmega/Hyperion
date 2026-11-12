package dev.hyperion.entity.path;

import dev.hyperion.core.HyperionExecutors;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Асинхронный A* по immutable-снапшоту мира.
 *
 * <p>Где используется: бенчмарк {@code /hyperion bench path}, автономные тесты и
 * opt-in подсистема {@link HyperionPathSystem} ({@code asyncPathfinding=true}).
 * Ванильный {@code PathNavigation} не потокобезопасен и мутирует кэш чанков, поэтому
 * в другой поток переносится <b>только этот собственный расчёт</b> по immutable-снапшоту;
 * ванильный A* остаётся в главном потоке и вызывается всегда, когда наш путь не найден
 * или не прошёл перепроверку по живому миру.
 *
 * <p>Реализация: 6-связный A* с манхэттенской эвристикой, открытая очередь — собственная
 * бинарная куча на примитивных массивах. Координаты пакуются в int относительно старта
 * (±511 по X/Z, ±255 по Y), поэтому поиск ограничен радиусом 511 блоков и не аллоцирует
 * объекты узлов.
 */
public final class AsyncPathfinder {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private static final int X_BITS = 10; // ±511
    private static final int Y_BITS = 9;  // ±255
    private static final int Z_BITS = 10; // ±511
    private static final int X_LIMIT = 1 << (X_BITS - 1);
    private static final int Y_LIMIT = 1 << (Y_BITS - 1);
    private static final int Z_LIMIT = 1 << (Z_BITS - 1);

    private final int maxNodes;

    public AsyncPathfinder(int maxNodes) {
        this.maxNodes = Math.max(16, maxNodes);
    }

    public CompletableFuture<Result> findAsync(BlockSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz) {
        return CompletableFuture.supplyAsync(() -> find(sampler, sx, sy, sz, tx, ty, tz), HyperionExecutors.cpu());
    }

    public Result find(BlockSampler sampler, int sx, int sy, int sz, int tx, int ty, int tz) {
        long start = System.nanoTime();
        if (Math.abs(tx - sx) >= X_LIMIT || Math.abs(ty - sy) >= Y_LIMIT || Math.abs(tz - sz) >= Z_LIMIT) {
            return new Result(new long[0], 0, System.nanoTime() - start, false);
        }
        if (sx == tx && sy == ty && sz == tz) {
            return new Result(new long[]{packAbsolute(sx, sy, sz)}, 1, System.nanoTime() - start, true);
        }

        IntHeap open = new IntHeap(1024);
        // Открытый/закрытый списки на примитивных хеш-таблицах (открытая адресация).
        IntIntMap gScore = new IntIntMap(4096);
        IntIntMap cameFrom = new IntIntMap(4096);

        int startKey = pack(0, 0, 0);
        int goalKey = pack(tx - sx, ty - sy, tz - sz);
        gScore.put(startKey, 0);
        open.push(startKey, heuristic(tx - sx, ty - sy, tz - sz));

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            int current = open.pop();
            if (current == goalKey) {
                long[] path = reconstruct(cameFrom, current, sx, sy, sz);
                return new Result(path, expanded, System.nanoTime() - start, true);
            }
            int rx = unpackX(current);
            int ry = unpackY(current);
            int rz = unpackZ(current);
            int baseG = gScore.get(current, Integer.MAX_VALUE / 4);
            if (baseG == Integer.MAX_VALUE / 4) continue;
            expanded++;

            for (int d = 0; d < 6; d++) {
                int nx = rx + DX[d];
                int ny = ry + DY[d];
                int nz = rz + DZ[d];
                if (Math.abs(nx) >= X_LIMIT || Math.abs(ny) >= Y_LIMIT || Math.abs(nz) >= Z_LIMIT) continue;
                if (!sampler.passable(sx + nx, sy + ny, sz + nz)) continue;
                int key = pack(nx, ny, nz);
                int tentative = baseG + 1;
                if (tentative >= gScore.get(key, Integer.MAX_VALUE / 4)) continue;
                cameFrom.put(key, current);
                gScore.put(key, tentative);
                open.push(key, tentative + heuristic(tx - sx - nx, ty - sy - ny, tz - sz - nz));
            }
        }
        return new Result(new long[0], expanded, System.nanoTime() - start, false);
    }

    private static long[] reconstruct(IntIntMap cameFrom, int current, int sx, int sy, int sz) {
        int[] buffer = new int[1024];
        int n = 0;
        int node = current;
        while (n < buffer.length) {
            buffer[n++] = node;
            int prev = cameFrom.get(node, Integer.MIN_VALUE);
            if (prev == Integer.MIN_VALUE) break;
            node = prev;
        }
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            int key = buffer[n - 1 - i];
            out[i] = packAbsolute(sx + unpackX(key), sy + unpackY(key), sz + unpackZ(key));
        }
        return out;
    }

    private static int heuristic(int dx, int dy, int dz) {
        return Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
    }

    // ------------------------------------------------------------------ упаковка

    static int pack(int rx, int ry, int rz) {
        return ((rx & ((1 << X_BITS) - 1)) << (Y_BITS + Z_BITS))
                | ((ry & ((1 << Y_BITS) - 1)) << Z_BITS)
                | (rz & ((1 << Z_BITS) - 1));
    }

    static int unpackX(int key) {
        int v = key >>> (Y_BITS + Z_BITS);
        return v << (32 - X_BITS) >> (32 - X_BITS);
    }

    static int unpackY(int key) {
        int v = key >>> Z_BITS;
        return v << (32 - Y_BITS) >> (32 - Y_BITS);
    }

    static int unpackZ(int key) {
        return key << (32 - Z_BITS) >> (32 - Z_BITS);
    }

    /** Абсолютная упаковка координат узла пути в long: X (21) | Y (12) | Z (21), все знаковые. */
    public static long packAbsolute(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 43) | ((long) (y & 0xFFF) << 31) | (z & 0x1FFFFFL);
    }

    public static int absoluteX(long key) {
        return (int) (key >> 43);
    }

    /**
     * Y тоже знаковый: мир начинается с -64, и без знакового расширения узел
     * пути на y=-40 превращался бы в y=4056 (моб получал путь в небо).
     */
    public static int absoluteY(long key) {
        return (int) ((key >> 31) & 0xFFF) << 20 >> 20;
    }

    public static int absoluteZ(long key) {
        return (int) (key << 43 >> 43);
    }

    public record Result(long[] path, int expandedNodes, long nanos, boolean found) {
        public double millis() {
            return nanos / 1e6;
        }
    }

    // ------------------------------------------------------------------ контейнеры

    /** Бинарная куча (key, priority) на массивах. */
    static final class IntHeap {
        private int[] keys;
        private int[] priorities;
        private int size;

        IntHeap(int capacity) {
            keys = new int[capacity];
            priorities = new int[capacity];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int key, int priority) {
            if (size == keys.length) grow();
            int i = size++;
            keys[i] = key;
            priorities[i] = priority;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (priorities[parent] <= priorities[i]) break;
                swap(parent, i);
                i = parent;
            }
        }

        int pop() {
            int top = keys[0];
            size--;
            if (size > 0) {
                keys[0] = keys[size];
                priorities[0] = priorities[size];
                int i = 0;
                while (true) {
                    int left = i * 2 + 1;
                    int right = left + 1;
                    int smallest = i;
                    if (left < size && priorities[left] < priorities[smallest]) smallest = left;
                    if (right < size && priorities[right] < priorities[smallest]) smallest = right;
                    if (smallest == i) break;
                    swap(smallest, i);
                    i = smallest;
                }
            }
            return top;
        }

        private void swap(int a, int b) {
            int k = keys[a];
            keys[a] = keys[b];
            keys[b] = k;
            int p = priorities[a];
            priorities[a] = priorities[b];
            priorities[b] = p;
        }

        private void grow() {
            int n = keys.length * 2;
            keys = Arrays.copyOf(keys, n);
            priorities = Arrays.copyOf(priorities, n);
        }
    }

    /** Примитивная int->int хеш-таблица с открытой адресацией (без боксинга). */
    static final class IntIntMap {
        private int[] keys;
        private int[] values;
        private boolean[] used;
        private int size;
        private int mask;

        IntIntMap(int capacity) {
            int n = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
            keys = new int[n];
            values = new int[n];
            used = new boolean[n];
            mask = n - 1;
        }

        private static int hash(int key) {
            key *= 0x9E3779B1;
            key ^= key >>> 16;
            return key;
        }

        void put(int key, int value) {
            if (size * 2 >= keys.length) rehash();
            int i = hash(key) & mask;
            while (used[i] && keys[i] != key) {
                i = (i + 1) & mask;
            }
            if (!used[i]) {
                used[i] = true;
                keys[i] = key;
                size++;
            }
            values[i] = value;
        }

        int get(int key, int defaultValue) {
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) return values[i];
                i = (i + 1) & mask;
            }
            return defaultValue;
        }

        private void rehash() {
            int[] oldKeys = keys;
            int[] oldValues = values;
            boolean[] oldUsed = used;
            int n = keys.length * 2;
            keys = new int[n];
            values = new int[n];
            used = new boolean[n];
            mask = n - 1;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i]) put(oldKeys[i], oldValues[i]);
            }
        }
    }
}
