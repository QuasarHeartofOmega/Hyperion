package dev.hyperion.entity.path;

import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.memory.BlockPosPool;
import dev.hyperion.memory.BlockStateCache;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Асинхронный поиск пути для наземных мобов — <b>opt-in</b> ({@code asyncPathfinding}).
 *
 * <p>Схема ровно та же, что в остальных подсистемах мода: главный поток снимает
 * immutable-снапшот, воркер считает, результат применяется главным потоком позже.
 *
 * <pre>
 *  server thread                            worker threads
 *  -------------                            --------------
 *  MobNavigation.findPathTo(pos)
 *    ├─ есть свежий путь в кэше? ── да ─► перепроверка по живому миру ─► отдаём Path
 *    └─ нет ─► снапшот области (бюджет) ──► A* по boolean[] ─► PathCache
 *              и возвращаем null ◄──────────────────────────┘
 *              ( vanilla считает сама )
 * </pre>
 *
 * <p><b>Почему это безопасно.</b> Подменяется только тот вызов, для которого найден
 * и перепроверен путь; во всех остальных случаях возвращается null, и ванильный
 * A* работает как обычно. Ничего не блокируется: главный поток никогда не ждёт
 * воркера. Пути строятся по «клеткам, где можно стоять» (сплошной куб под ногами,
 * свободно над ногами на всю высоту моба), а шаги — только на 1 блок по одной оси:
 * вверх на 1 моб подпрыгивает, вниз на 1 спадает. Ни диагоналей, ни провалов,
 * ни воды/лавы/дверей — такое просто не находится и уходит в ваниль.
 *
 * <p><b>Чего здесь нет.</b> Ванильный {@code PathNodeMaker} не потокобезопасен и
 * мутирует кэш чанков — он остаётся в главном потоке и вызывается ванилью. Мы не
 * переносим ванильный ИИ в другой поток, а добавляем свой дешёвый расчёт рядом.
 *
 * <p>Выключено по умолчанию: это самая вмешивающаяся в поведение мобов часть мода,
 * включать её должен пользователь осознанно. Миксин ставится только при
 * {@code asyncPathfinding=true} (см. {@code HyperionMixinPlugin}).
 */
public final class HyperionPathSystem {

    private static final HyperionPathSystem INSTANCE = new HyperionPathSystem();

    /** Дальше этого числа узлов путь не отдаём: ваниль умеет лучше на длинных дистанциях. */
    private static final int MAX_PATH_NODES = 512;
    /** Запас вокруг старта/цели, чтобы путь мог обойти препятствие сбоку. */
    private static final int MARGIN = 3;
    /** Сколько клеток воздуха над ногами требуем минимум (моб ростом 1.8 — две). */
    private static final int MIN_HEAD_CELLS = 2;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** Только главный поток: анти-спам запросов по мобам. */
    private final Map<Integer, Long> lastRequest = new HashMap<>();

    private volatile PathCache cache;
    private volatile AsyncPathfinder pathfinder;
    private long budgetTick = Long.MIN_VALUE;
    private int snapshotsThisTick;

    private HyperionPathSystem() {
    }

    public static HyperionPathSystem get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ API для миксина

    /**
     * Главный поток, HEAD миксина в {@code MobNavigation#findPathTo}.
     *
     * @return готовый путь или {@code null} — «пусть считает ваниль».
     */
    public Path findPath(MobEntity mob, BlockPos target, int distance) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.enabled || !cfg.asyncPathfinding) return null;
        if (!HyperionExecutors.isRunning()) return null;
        if (!HyperionStatus.usable(Feature.ENTITY_AI)) return null;
        if (!HyperionCompat.works(Feature.ENTITY_AI)) return null;
        if (mob == null || target == null) return null;
        if (!(mob.getWorld() instanceof ServerWorld world)) return null;
        if (!eligible(mob)) return null;

        BlockPos start = mob.getBlockPos();
        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        if (sx == tx && sy == ty && sz == tz) return null;

        // Область снапшота обязана вместить и старт, и цель, иначе путь не найдётся
        // и мы просто зря потратим бюджет главного потока.
        int minX = Math.min(sx, tx) - MARGIN;
        int maxX = Math.max(sx, tx) + MARGIN;
        int minZ = Math.min(sz, tz) - MARGIN;
        int maxZ = Math.max(sz, tz) + MARGIN;
        int minY = Math.max(world.getBottomY(), Math.min(sy, ty) - MARGIN);
        int maxY = Math.max(sy, ty) + MARGIN;
        if (maxX - minX + 1 > cfg.pathSnapshotRadius * 2 || maxZ - minZ + 1 > cfg.pathSnapshotRadius * 2) return null;
        if (maxY - minY + 1 > cfg.pathSnapshotHeight) return null;

        int headCells = headCells(mob);
        HyperionMetrics.PATH_REQUESTS.increment();

        PathCache cache = cache(cfg);
        AsyncPathfinder.Result cached = cache.get(sx, sy, sz, tx, ty, tz);
        if (cached != null) {
            if (!cached.found()) {
                // Отрицательный результат тоже кэшируется: незачем считать A* заново
                // каждый раз, когда цель перепланирует путь к недостижимой точке.
                HyperionMetrics.PATH_NEGATIVE_HITS.increment();
                return null;
            }
            if (!PathValidator.validate(cached.path(), liveSampler(world, headCells),
                    sx, sy, sz, tx, ty, tz, MAX_PATH_NODES)) {
                cache.remove(sx, sy, sz, tx, ty, tz);
                HyperionMetrics.PATH_INVALID.increment();
                return null;
            }
            HyperionMetrics.PATH_USED.increment();
            HyperionStatus.work(Feature.ENTITY_AI);
            return toPath(cached.path(), target);
        }

        // Промах: считаем в фоне, а этот вызов отдаём ванили.
        long now = world.getTime();
        Long last = lastRequest.get(mob.getId());
        if (last != null && now - last < cfg.pathRequestCooldownTicks) return null;
        if (inFlight.size() >= cfg.pathMaxInFlight) return null;
        if (now != budgetTick) {
            budgetTick = now;
            snapshotsThisTick = 0;
        }
        if (snapshotsThisTick >= cfg.pathSnapshotsPerTick) return null;

        long key = requestKey(sx, sy, sz, tx, ty, tz);
        if (!inFlight.add(key)) return null;

        PathSnapshot snapshot;
        try {
            snapshot = capture(world, minX, minY, minZ,
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, headCells);
        } catch (Throwable t) {
            inFlight.remove(key);
            throw t;
        }
        if (snapshot == null) {
            inFlight.remove(key); // часть области не загружена — считать не по чему
            return null;
        }
        snapshotsThisTick++;
        if (lastRequest.size() > 4096) lastRequest.clear(); // мобы приходят и уходят
        lastRequest.put(mob.getId(), now);
        HyperionMetrics.PATH_SNAPSHOTS.increment();

        try {
            pathfinder(cfg).findAsync(snapshot.sampler(), sx, sy, sz, tx, ty, tz)
                    .whenComplete((result, error) -> {
                        try {
                            if (error != null || result == null) {
                                HyperionMetrics.PATH_FAILED.increment();
                                return;
                            }
                            cache.put(sx, sy, sz, tx, ty, tz, result);
                            HyperionMetrics.PATH_SEARCH_NANOS.add(result.nanos());
                            if (result.found()) {
                                HyperionMetrics.PATH_FOUND.increment();
                            } else {
                                HyperionMetrics.PATH_NOT_FOUND.increment();
                            }
                        } finally {
                            inFlight.remove(key);
                        }
                    });
        } catch (Throwable t) {
            inFlight.remove(key); // пул остановлен — просто отдаём ванили
        }
        return null;
    }

    // ------------------------------------------------------------------ применимость

    /**
     * Только наземные мобы «в один блок шириной», стоящие на земле и не в воде/лаве.
     * Всё остальное (плавающие, летающие, пауки, рейдеры-широкие, мобы в лодке)
     * уходит в ваниль: там наша модель проходимости неприменима.
     */
    private static boolean eligible(MobEntity mob) {
        return mob.isOnGround()
                && !mob.isTouchingWater()
                && !mob.isInLava()
                && !mob.hasVehicle()
                && !mob.hasPassengers()
                && !mob.isAiDisabled()
                && mob.getWidth() < 1.0f
                && mob.getHeight() <= 3.0f
                && mob.getNavigation() instanceof MobNavigation;
    }

    private static int headCells(MobEntity mob) {
        return Math.max(MIN_HEAD_CELLS, (int) Math.ceil(mob.getHeight()));
    }

    // ------------------------------------------------------------------ снапшот

    /**
     * Снятие снапшота главным потоком. Курсоры BlockPos берутся из пула
     * ({@link BlockPosPool}) — на область 32x12x32 это ~12k обращений к миру
     * без единой аллокации BlockPos.
     *
     * @return null, если хотя бы один нужный чанк не загружен (снапшот был бы неполным)
     */
    private static PathSnapshot capture(ServerWorld world, int minX, int minY, int minZ,
                                        int sizeX, int sizeY, int sizeZ, int headCells) {
        for (int cx = minX >> 4; cx <= (minX + sizeX - 1) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= (minZ + sizeZ - 1) >> 4; cz++) {
                if (!(world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk)) {
                    return null;
                }
            }
        }
        boolean[] grid = new boolean[sizeX * sizeY * sizeZ];
        BlockSampler sampler = liveSampler(world, headCells);
        int i = 0;
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int x = minX; x < minX + sizeX; x++) {
                    grid[i++] = sampler.passable(x, y, z);
                }
            }
        }
        return new PathSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, grid);
    }

    /**
     * «Здесь можно стоять»: под ногами сплошной непрозрачный куб, над ногами
     * {@code headCells} клеток воздуха. Сознательно строго: плиты, ступени, заборы,
     * вода, трава, двери — не наш случай, такие пути считает ваниль.
     *
     * <p>Используется и при снятии снапшота, и при перепроверке готового пути,
     * поэтому семантика у обеих стадий одинаковая.
     */
    private static BlockSampler liveSampler(World world, int headCells) {
        return (x, y, z) -> {
            BlockState ground = world.getBlockState(BlockPosPool.at(x, y - 1, z));
            if (!BlockStateCache.isOpaqueFullCube(ground)) return false;
            for (int i = 0; i < headCells; i++) {
                if (!BlockStateCache.isAir(world.getBlockState(BlockPosPool.at(x, y + i, z)))) return false;
            }
            return true;
        };
    }

    // ------------------------------------------------------------------ сборка Path

    /**
     * Узлы — клетки на уровне ног (ваниль понимает node.y именно так, см.
     * {@code Path#getNodePosition}). Тип ставим WALKABLE с нулевым штрафом:
     * это «безопасная земля» для ванильных проверок ({@code canWalkOnPath}
     * отсекает WATER/LAVA/OPEN, WALKABLE проходит).
     */
    private static Path toPath(long[] cells, BlockPos target) {
        List<PathNode> nodes = new ArrayList<>(cells.length);
        PathNode previous = null;
        for (int i = 0; i < cells.length; i++) {
            long cell = cells[i];
            PathNode node = new PathNode(AsyncPathfinder.absoluteX(cell),
                    AsyncPathfinder.absoluteY(cell), AsyncPathfinder.absoluteZ(cell));
            node.type = PathNodeType.WALKABLE;
            node.penalty = 0.0f;
            node.pathLength = i;
            node.penalizedPathLength = i;
            node.distanceToNearestTarget = cells.length - 1 - i;
            node.previous = previous;
            previous = node;
            nodes.add(node);
        }
        return new Path(nodes, target, true);
    }

    // ------------------------------------------------------------------ сервис

    private PathCache cache(HyperionConfig cfg) {
        PathCache local = cache;
        if (local == null) {
            synchronized (this) {
                local = cache;
                if (local == null) {
                    local = new PathCache(cfg.pathCacheEntries, cfg.pathCacheTtlMs);
                    cache = local;
                }
            }
        }
        return local;
    }

    private AsyncPathfinder pathfinder(HyperionConfig cfg) {
        AsyncPathfinder local = pathfinder;
        if (local == null) {
            synchronized (this) {
                local = pathfinder;
                if (local == null) {
                    local = new AsyncPathfinder(cfg.pathMaxNodes);
                    pathfinder = local;
                }
            }
        }
        return local;
    }

    private static long requestKey(int sx, int sy, int sz, int tx, int ty, int tz) {
        long start = AsyncPathfinder.packAbsolute(sx, sy, sz);
        long goal = AsyncPathfinder.packAbsolute(tx, ty, tz);
        return start * 31 + goal;
    }

    public int cachedPaths() {
        PathCache local = cache;
        return local == null ? 0 : local.size();
    }

    public int inFlight() {
        return inFlight.size();
    }

    public double hitRate() {
        PathCache local = cache;
        return local == null ? 0.0 : local.hitRate();
    }

    public void clear() {
        PathCache local = cache;
        if (local != null) local.clear();
        inFlight.clear();
        lastRequest.clear();
    }

    public void shutdown() {
        clear();
    }
}
