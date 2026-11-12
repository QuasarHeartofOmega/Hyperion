package dev.hyperion.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hyperion.core.util.MpscQueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Конфиг мода. Читается один раз при старте (в том числе из mixin-плагина, до onInitialize),
 * перезагружается командой /hyperion reload.
 */
public final class HyperionConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile HyperionConfig instance;
    private static Path file;

    // ------------------------------------------------------------------ общее
    /** Главный выключатель: false — мод не делает ничего, кроме логирования. */
    public boolean enabled = true;
    /** Подробные логи (сколько потоков, что отключено совместимостью и т.д.). */
    public boolean verboseLogging = false;

    // ------------------------------------------------------------------ потоки
    /** Потоки дискового I/O. 0 = авто (min(2, cores)). */
    public int ioThreads = 0;
    /** Потоки CPU-задач. 0 = авто (max(1, cores - 2)). */
    public int cpuThreads = 0;

    // ------------------------------------------------------- chunk-system
    /** Асинхронный read-ahead NBT чанков с диска. */
    public boolean chunkPrefetch = true;
    /** Радиус read-ahead в чанках (0 — только сам чанк, 1 — 3x3, 2 — 5x5). */
    public int chunkPrefetchRadius = 1;
    /** Максимум одновременных задач prefetch. */
    public int chunkPrefetchInFlight = 32;
    /** Лимит кэша распакованного NBT, МБ. */
    public int chunkCacheSizeMb = 96;
    /** Лимит числа закэшированных чанков. */
    public int chunkCacheMaxEntries = 2048;
    /** Через сколько мс запись кэша считается устаревшей. */
    public int chunkCacheTtlMs = 15_000;

    // ------------------------------------------------------ entity-system
    /** Параллельный анализ активности сущностей + throttling ИИ далёких мобов. */
    public boolean entityThrottling = true;
    /** Дальше этого расстояния (в блоках) от любого игрока ИИ моба тикается реже. */
    public double entityThrottleDistance = 48.0;
    /** Тиковать ИИ каждого N-го тика (2 = каждый второй). 1 = выключено. */
    public int entityThrottleFactor = 2;
    /** Ниже этого числа сущностей анализ идёт в главном потоке (накладные расходы выше выигрыша). */
    public int entityParallelThreshold = 192;
    /** Размер партии для параллельного анализа. */
    public int entityParallelBatch = 256;
    /** Никогда не троттлить мобов с целью/агрессией. */
    public boolean entityThrottleSkipAggressive = true;

    // ----------------------------------------------------------- renderer
    /** Асинхронное occlusion-culling сущностей (клиент). */
    public boolean entityCulling = true;
    /** Ближе этого расстояния сущности рисуются всегда. */
    public double entityCullingMinDistance = 6.0;
    /** Дальше этого расстояния culling не применяется (там уже работает ванильный frustum/distance). */
    public double entityCullingMaxDistance = 96.0;
    /** Сколько секций 16^3 главный поток строит за тик (бюджет снапшотов). */
    public int occlusionSectionBudgetPerTick = 6;
    /** TTL снапшота секции, мс. */
    public int occlusionSnapshotTtlMs = 400;
    /** Сколько лучей на сущность (1 — центр, 3 — центр + углы бокса). */
    public int occlusionRays = 3;
    /** Максимальный возраст результата culling, мс; старше — рисуем (fail-open). */
    public int occlusionResultTtlMs = 120;
    /** Ёмкость очереди запросов culling. */
    public int occlusionQueueCapacity = 4096;

    // -------------------------------------------------------------- память
    /** Плоские предрасчитанные массивы свойств blockstate. */
    public boolean blockStateCache = true;
    /** Пул курсоров BlockPos (thread-local ring). */
    public boolean blockPosPool = true;
    /** Размер thread-local ring курсоров. */
    public int blockPosPoolSize = 16;

    // ------------------------------------------------------------- прочее
    /** HUD с метриками (включается и командой /hyperion hud). */
    public boolean hud = false;
    /** Строить бенчмарк A* по команде /hyperion bench. */
    public boolean benchmarks = true;

    public static HyperionConfig get() {
        HyperionConfig local = instance;
        if (local == null) {
            synchronized (HyperionConfig.class) {
                local = instance;
                if (local == null) {
                    local = load(null);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void init(Path configDir) {
        synchronized (HyperionConfig.class) {
            file = configDir.resolve("hyperion.json");
            instance = load(file);
            save();
        }
    }

    public static void reload() {
        synchronized (HyperionConfig.class) {
            instance = load(file);
            save();
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Hyperion.LOGGER.warn("[Hyperion] Не удалось сохранить конфиг: {}", e.toString());
        }
    }

    private static HyperionConfig load(Path path) {
        if (path != null && Files.exists(path)) {
            try {
                HyperionConfig parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), HyperionConfig.class);
                if (parsed != null) {
                    parsed.sanitize();
                    return parsed;
                }
            } catch (Exception e) {
                Hyperion.LOGGER.warn("[Hyperion] Конфиг повреждён, использую значения по умолчанию: {}", e.toString());
            }
        }
        HyperionConfig cfg = new HyperionConfig();
        cfg.sanitize();
        return cfg;
    }

    /** Защита от значений, которые ломают пулы потоков (в спеке было cores-4 без клампа). */
    public void sanitize() {
        ioThreads = clamp(ioThreads, 0, 16);
        cpuThreads = clamp(cpuThreads, 0, 64);
        chunkPrefetchRadius = clamp(chunkPrefetchRadius, 0, 4);
        chunkPrefetchInFlight = clamp(chunkPrefetchInFlight, 1, 512);
        chunkCacheSizeMb = clamp(chunkCacheSizeMb, 8, 2048);
        chunkCacheMaxEntries = clamp(chunkCacheMaxEntries, 64, 65536);
        chunkCacheTtlMs = clamp(chunkCacheTtlMs, 500, 600_000);
        entityThrottleDistance = clamp(entityThrottleDistance, 8.0, 256.0);
        entityThrottleFactor = clamp(entityThrottleFactor, 1, 8);
        entityParallelThreshold = clamp(entityParallelThreshold, 16, 100_000);
        entityParallelBatch = clamp(entityParallelBatch, 32, 8192);
        entityCullingMinDistance = clamp(entityCullingMinDistance, 0.0, 32.0);
        entityCullingMaxDistance = clamp(entityCullingMaxDistance, 16.0, 512.0);
        occlusionSectionBudgetPerTick = clamp(occlusionSectionBudgetPerTick, 1, 64);
        occlusionSnapshotTtlMs = clamp(occlusionSnapshotTtlMs, 50, 5000);
        occlusionRays = clamp(occlusionRays, 1, 3);
        occlusionResultTtlMs = clamp(occlusionResultTtlMs, 16, 2000);
        occlusionQueueCapacity = clamp(occlusionQueueCapacity, 128, 65536);
        blockPosPoolSize = clamp(blockPosPoolSize, 4, 256);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Очередь для отложенных сообщений в лог из рабочих потоков. */
    public static final MpscQueue<String> LOG_QUEUE = new MpscQueue<>(256);
}
