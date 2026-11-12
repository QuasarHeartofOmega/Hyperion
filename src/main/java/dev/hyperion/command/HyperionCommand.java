package dev.hyperion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.hyperion.chunk.HyperionChunkSystem;
import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.entity.HyperionEntitySystem;
import dev.hyperion.entity.path.AsyncPathfinder;
import dev.hyperion.entity.path.BlockSampler;
import dev.hyperion.entity.path.PathCache;
import dev.hyperion.memory.BlockStateCache;
import dev.hyperion.renderer.AsyncOcclusionEngine;
import dev.hyperion.renderer.GpuCapabilities;
import dev.hyperion.renderer.OcclusionSnapshots;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/** /hyperion — диагностика, конфиг и бенчмарк. Регистрируется миксином, без Fabric API. */
public final class HyperionCommand {

    private HyperionCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("hyperion")
                .then(CommandManager.literal("stats").executes(HyperionCommand::stats))
                .then(CommandManager.literal("compat").executes(HyperionCommand::compat))
                .then(CommandManager.literal("gpu").executes(HyperionCommand::gpu))
                .then(CommandManager.literal("hud").executes(HyperionCommand::hud))
                .then(CommandManager.literal("reload")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(HyperionCommand::reload))
                .then(CommandManager.literal("cache")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("clear").executes(HyperionCommand::clearCaches)))
                .then(CommandManager.literal("bench")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("path").executes(HyperionCommand::benchPath)))
                .executes(HyperionCommand::stats));
    }

    private static int stats(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        HyperionConfig cfg = HyperionConfig.get();
        HyperionMetrics.Percentiles tick = HyperionMetrics.tickPercentiles();

        send(source, "Hyperion " + Hyperion.VERSION + " — статус", true);
        send(source, "enabled=" + cfg.enabled + ", " + HyperionExecutors.describe(), false);
        send(source, String.format("Тик сервера: %s", tick.format()), false);
        send(source, String.format("Чанки: hit-rate %.1f%%, в кэше %d чанков / %d МБ, prefetch в работе %d, ошибок %d",
                HyperionMetrics.hitRate(),
                HyperionChunkSystem.get().cachedChunks(),
                HyperionChunkSystem.get().cachedMegabytes(),
                HyperionChunkSystem.get().inFlight(),
                HyperionMetrics.CHUNK_PREFETCH_FAILED.sum()), false);
        send(source, String.format("Сущности: %d в тике, троттлится %d, пропущено тиков ИИ %d (%.1f%%)",
                HyperionMetrics.LAST_ENTITY_COUNT,
                HyperionEntitySystem.get().publishedThrottleable(),
                HyperionMetrics.ENTITY_AI_TICKS_SKIPPED.sum(),
                HyperionMetrics.aiSavedPercent()), false);
        long analyses = HyperionMetrics.ENTITY_ANALYSIS_PARALLEL.sum();
        if (analyses > 0) {
            send(source, String.format("Параллельных анализов: %d, среднее время %.3f мс",
                    analyses, HyperionMetrics.ENTITY_ANALYSIS_NANOS.sum() / 1e6 / analyses), false);
        }
        send(source, String.format("Culling: запросов %d, скрыто всего %d, UNKNOWN %d, снапшотов %d (в очереди %d)",
                HyperionMetrics.CULL_QUERIES.sum(),
                HyperionMetrics.CULL_HIDDEN.sum(),
                HyperionMetrics.CULL_UNKNOWN.sum(),
                OcclusionSnapshots.get().size(),
                AsyncOcclusionEngine.get().pending()), false);
        send(source, String.format("BlockStateCache: %d состояний, инициализация %.1f мс",
                BlockStateCache.entries(), HyperionMetrics.BLOCKSTATE_CACHE_INIT_NANOS / 1e6), false);
        return 1;
    }

    private static int compat(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        List<String> findings = HyperionCompat.findings();
        send(source, "Совместимость: найдено правил " + findings.size(), true);
        if (findings.isEmpty()) {
            send(source, "Известных взаимодействий с другими модами нет.", false);
        }
        for (String finding : findings) {
            send(source, finding, false);
        }
        return 1;
    }

    private static int gpu(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        send(source, "Диагностика рендера", true);
        // Класс клиента трогаем только на клиенте — на выделенном сервере LWJGL OpenGL нет.
        if (!source.getServer().isDedicated()) {
            send(source, GpuCapabilities.describe(), false);
        } else {
            send(source, "Выделенный сервер: GPU не используется.", false);
        }
        send(source, "Vulkan: " + GpuCapabilities.vulkanStatus(), false);
        send(source, "Рендер чанков Hyperion не заменяет: это работа Sodium/Iris.", false);
        return 1;
    }

    private static int hud(CommandContext<ServerCommandSource> ctx) {
        HyperionConfig cfg = HyperionConfig.get();
        cfg.hud = !cfg.hud;
        HyperionConfig.save();
        send(ctx.getSource(), "HUD Hyperion: " + (cfg.hud ? "включён" : "выключен"), true);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        HyperionConfig.reload();
        HyperionCompat.logReport();
        send(ctx.getSource(), "Конфиг перезагружен. Часть настроек (миксины) применяется только при перезапуске игры.", true);
        return 1;
    }

    private static int clearCaches(CommandContext<ServerCommandSource> ctx) {
        OcclusionSnapshots.get().clear();
        AsyncOcclusionEngine.get().clear();
        send(ctx.getSource(), "Кэши снапшотов и culling очищены.", true);
        return 1;
    }

    /**
     * Бенчмарк асинхронного A* на синтетическом снапшоте мира.
     * Демонстрирует корректную схему: воркер считает, результат возвращается в главный поток.
     */
    private static int benchPath(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!HyperionConfig.get().benchmarks) {
            send(source, "Бенчмарки выключены в конфиге.", false);
            return 0;
        }
        send(source, "Запускаю A* бенчмарк в пуле Hyperion-CPU...", true);

        final int sx = 48, sy = 24, sz = 48;
        boolean[] grid = new boolean[sx * sy * sz];
        Random random = new Random(12345L);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    grid[(y * sz + z) * sx + x] = y == 0 || random.nextInt(100) > 22;
                }
            }
        }
        BlockSampler sampler = BlockSampler.ofGrid(0, 0, 0, sx, sy, sz, grid);
        AsyncPathfinder pathfinder = new AsyncPathfinder(60_000);
        PathCache cache = new PathCache(256, 5_000);

        CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            int found = 0;
            int runs = 24;
            long nodes = 0;
            for (int i = 0; i < runs; i++) {
                int tx = 4 + i * 2 % (sx - 8);
                int tz = 4 + (i * 7) % (sz - 8);
                AsyncPathfinder.Result cached = cache.get(2, 1, 2, tx, 1, tz);
                AsyncPathfinder.Result result = cached != null ? cached
                        : pathfinder.find(sampler, 2, 1, 2, tx, 1, tz);
                if (cached == null) cache.put(2, 1, 2, tx, 1, tz, result);
                if (result.found()) found++;
                nodes += result.expandedNodes();
            }
            // Второй проход — целиком из кэша.
            for (int i = 0; i < runs; i++) {
                int tx = 4 + i * 2 % (sx - 8);
                int tz = 4 + (i * 7) % (sz - 8);
                cache.get(2, 1, 2, tx, 1, tz);
            }
            double ms = (System.nanoTime() - start) / 1e6;
            long finalNodes = nodes;
            int finalFound = found;
            source.getServer().executeSync(() -> {
                send(source, String.format("A* готово за %.2f мс: %d/%d путей, %d раскрытых узлов",
                        ms, finalFound, runs, finalNodes), true);
                send(source, String.format("PathCache: %d записей, hit-rate %.1f%%", cache.size(), cache.hitRate()), false);
                send(source, "Важно: это автономный движок. В ванильный PathNavigation он не внедряется — тот не потокобезопасен.", false);
            });
        }, HyperionExecutors.cpu());
        return 1;
    }

    private static void send(ServerCommandSource source, String message, boolean header) {
        source.sendFeedback(() -> Text.literal(message)
                .formatted(header ? Formatting.AQUA : Formatting.GRAY), false);
    }
}
