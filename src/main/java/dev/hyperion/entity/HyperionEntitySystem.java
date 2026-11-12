package dev.hyperion.entity;

import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.util.PackedPos;
import it.unimi.dsi.fastutil.ints.Int2ByteMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Региональное партиционирование сущностей + параллельный анализ активности.
 *
 * <p><b>Важное отличие от спеки.</b> В документе предлагалось тикать сами сущности
 * в {@code parallelStream().forEach(...)} — так делать нельзя: {@code Entity.tick()}
 * пишет в мир (block changes, спавн, урон, глобальные списки сущностей, реестры тиков).
 * Параллельный вызов даёт гонки, порчу чанков и краши; {@code synchronized(entities)}
 * тут не спасает, потому что защищает не тот монитор — мир один на всех.
 *
 * <p>Здесь параллелится только <b>чистая функция</b>: по снапшоту координат, снятому
 * главным потоком, считается расстояние до ближайшего игрока и решение «троттлить ИИ
 * или нет». Воркеры не трогают объекты Minecraft вообще — только свои массивы.
 * Результат публикуется одной volatile-записью и применяется главным потоком на
 * следующем тике. Это даёт честный выигрыш на серверах с тысячами мобов и нулевой риск.
 */
public final class HyperionEntitySystem {

    private static final HyperionEntitySystem INSTANCE = new HyperionEntitySystem();

    /** Буферы снапшота на мир. Доступ только из главного потока. */
    private final Map<ServerWorld, Snapshot> buffers = new IdentityHashMap<>();
    /** Опубликованное решение: entityId -> 1 (можно троттлить). Читает только главный поток. */
    private volatile Int2ByteMap throttleable = new Int2ByteOpenHashMap(0);
    private int tickCounter;

    private HyperionEntitySystem() {
    }

    public static HyperionEntitySystem get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------- сбор данных

    /** Миксин ServerWorld#tickEntity, HEAD, главный поток. */
    public void collect(ServerWorld world, Entity entity) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.entityThrottling) return;

        Snapshot snapshot = buffers.computeIfAbsent(world, w -> new Snapshot());
        int i = snapshot.size++;
        snapshot.ensure(i + 1);

        boolean canThrottle = false;
        if (entity instanceof MobEntity mob) {
            canThrottle = !mob.isPersistent()
                    && !mob.cannotDespawn()
                    && !mob.isAiDisabled()
                    && !mob.hasVehicle()
                    && !mob.hasPassengers()
                    && !(cfg.entityThrottleSkipAggressive && mob.getTarget() != null);
        }

        snapshot.ids[i] = entity.getId();
        snapshot.xs[i] = entity.getX();
        snapshot.ys[i] = entity.getY();
        snapshot.zs[i] = entity.getZ();
        snapshot.allowed[i] = canThrottle;
        // Регион 32x32: старшие 32 бита — «локальность» (Morton-подобная свёртка),
        // младшие — индекс сущности. Сортировка группирует соседние регионы в одну партию.
        int rx = ((int) entity.getX()) >> PackedPos.REGION_SHIFT;
        int rz = ((int) entity.getZ()) >> PackedPos.REGION_SHIFT;
        long locality = (((long) (rx & 0xFFFF)) << 16) | (rz & 0xFFFFL);
        snapshot.order[i] = (locality << 32) | (i & 0xFFFFFFFFL);
    }

    /** Миксин ServerWorld#tick, RETURN, главный поток. */
    public void finishTick(ServerWorld world) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.entityThrottling) return;

        Snapshot snapshot = buffers.get(world);
        tickCounter++;
        if (snapshot == null || snapshot.size == 0) {
            publishEmpty();
            return;
        }

        int n = snapshot.size;
        HyperionMetrics.LAST_ENTITY_COUNT = n;
        HyperionMetrics.ENTITY_TICKS_TOTAL.add(n);

        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            publishEmpty();
            snapshot.size = 0;
            return;
        }

        double[] px = new double[players.size()];
        double[] py = new double[players.size()];
        double[] pz = new double[players.size()];
        for (int i = 0; i < players.size(); i++) {
            ServerPlayerEntity player = players.get(i);
            px[i] = player.getX();
            py[i] = player.getY();
            pz[i] = player.getZ();
        }

        double thresholdSq = cfg.entityThrottleDistance * cfg.entityThrottleDistance;
        byte[] result = new byte[n]; // новая на каждый тик: просроченная задача не испортит буфер
        long start = System.nanoTime();

        int batch = cfg.entityParallelBatch;
        if (n < cfg.entityParallelThreshold || batch >= n || !HyperionExecutors.isRunning()) {
            analyze(snapshot, 0, n, px, py, pz, thresholdSq, result);
        } else {
            long[] order = Arrays.copyOf(snapshot.order, n);
            Arrays.sort(order); // региональная локальность -> партии не прыгают по миру
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int from = 0; from < n; from += batch) {
                int lo = from;
                int hi = Math.min(n, from + batch);
                futures.add(CompletableFuture.runAsync(
                        () -> analyzeOrdered(snapshot, order, lo, hi, px, py, pz, thresholdSq, result),
                        HyperionExecutors.cpu()));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(50, TimeUnit.MILLISECONDS);
                HyperionMetrics.ENTITY_ANALYSIS_PARALLEL.increment();
            } catch (Exception e) {
                // Не успели — оставляем прошлые решения, мир не блокируем.
                snapshot.size = 0;
                return;
            }
        }
        HyperionMetrics.ENTITY_ANALYSIS_NANOS.add(System.nanoTime() - start);

        Int2ByteOpenHashMap map = new Int2ByteOpenHashMap(Math.max(16, n / 4));
        int throttled = 0;
        for (int i = 0; i < n; i++) {
            if (result[i] != 0) {
                map.put(snapshot.ids[i], (byte) 1);
                throttled++;
            }
        }
        map.trim();
        this.throttleable = map; // единственная точка публикации
        HyperionMetrics.LAST_THROTTLED_COUNT = throttled;
        snapshot.size = 0;
    }

    private void publishEmpty() {
        if (!throttleable.isEmpty()) {
            throttleable = new Int2ByteOpenHashMap(0);
            HyperionMetrics.LAST_THROTTLED_COUNT = 0;
        }
    }

    /** Чистая функция: только свои массивы, никаких объектов Minecraft. */
    private static void analyze(Snapshot s, int from, int to,
                                double[] px, double[] py, double[] pz,
                                double thresholdSq, byte[] out) {
        for (int i = from; i < to; i++) {
            if (!s.allowed[i]) continue;
            if (minDistanceSq(s.xs[i], s.ys[i], s.zs[i], px, py, pz) > thresholdSq) {
                out[i] = 1;
            }
        }
    }

    private static void analyzeOrdered(Snapshot s, long[] order, int from, int to,
                                       double[] px, double[] py, double[] pz,
                                       double thresholdSq, byte[] out) {
        for (int k = from; k < to; k++) {
            int i = (int) order[k];
            if (!s.allowed[i]) continue;
            if (minDistanceSq(s.xs[i], s.ys[i], s.zs[i], px, py, pz) > thresholdSq) {
                out[i] = 1;
            }
        }
    }

    private static double minDistanceSq(double x, double y, double z,
                                        double[] px, double[] py, double[] pz) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < px.length; i++) {
            double dx = x - px[i];
            double dy = y - py[i];
            double dz = z - pz[i];
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    // -------------------------------------------------------------- применение

    /**
     * Главный поток, миксин MobEntity#tickNewAi.
     * Решение «пропустить этот тик ИИ» размазано по тикам через (id + tick),
     * чтобы мобы не дёргались синхронно всей толпой.
     */
    public boolean shouldSkipAi(int entityId) {
        HyperionConfig cfg = HyperionConfig.get();
        int factor = cfg.entityThrottleFactor;
        if (factor <= 1) return false;
        if (throttleable.get(entityId) != 1) return false;
        boolean skip = ((entityId + tickCounter) % factor) != 0;
        if (skip) HyperionMetrics.ENTITY_AI_TICKS_SKIPPED.increment();
        return skip;
    }

    public int publishedThrottleable() {
        return throttleable.size();
    }

    public void shutdown() {
        buffers.clear();
        throttleable = new Int2ByteOpenHashMap(0);
    }

    /** Переиспользуемые массивы снапшота. */
    private static final class Snapshot {
        int[] ids = new int[256];
        double[] xs = new double[256];
        double[] ys = new double[256];
        double[] zs = new double[256];
        boolean[] allowed = new boolean[256];
        long[] order = new long[256];
        int size;

        void ensure(int capacity) {
            if (capacity <= ids.length) return;
            int n = Math.max(capacity, ids.length * 2);
            ids = Arrays.copyOf(ids, n);
            xs = Arrays.copyOf(xs, n);
            ys = Arrays.copyOf(ys, n);
            zs = Arrays.copyOf(zs, n);
            allowed = Arrays.copyOf(allowed, n);
            order = Arrays.copyOf(order, n);
        }
    }
}
