package dev.hyperion.core;

import dev.hyperion.chunk.HyperionChunkSystem;
import dev.hyperion.entity.HyperionEntitySystem;
import dev.hyperion.memory.BlockStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Пулы потоков мода.
 *
 * <p>Отличие от спеки: размеры пулов клампятся снизу единицей. На 4-ядерной машине
 * {@code cores - 4 == 0}, а {@code Executors.newFixedThreadPool(0)} бросает
 * IllegalArgumentException — ванильный запуск просто падал бы.
 *
 * <p>Все потоки — daemon, с приоритетом ниже основного, чтобы не отбирать кванты
 * у render/server thread.
 */
public final class HyperionExecutors {

    private static volatile ExecutorService io;
    private static volatile ExecutorService cpu;
    private static volatile boolean started;
    private static volatile boolean stopped;

    private HyperionExecutors() {
    }

    public static synchronized void start() {
        if (started || stopped) return;
        HyperionConfig cfg = HyperionConfig.get();
        int cores = Runtime.getRuntime().availableProcessors();
        int ioCount = cfg.ioThreads > 0 ? cfg.ioThreads : Math.max(1, Math.min(2, cores));
        int cpuCount = cfg.cpuThreads > 0 ? cfg.cpuThreads : Math.max(1, cores - 2);

        io = Executors.newFixedThreadPool(ioCount, factory("Hyperion-IO", Thread.NORM_PRIORITY - 2));
        cpu = Executors.newFixedThreadPool(cpuCount, factory("Hyperion-CPU", Thread.NORM_PRIORITY - 1));
        started = true;

        Hyperion.LOGGER.info("[Hyperion] Пулы потоков: I/O={}, CPU={} (доступно ядер: {})", ioCount, cpuCount, cores);
        Runtime.getRuntime().addShutdownHook(new Thread(HyperionExecutors::shutdown, "Hyperion-Shutdown"));
    }

    public static ExecutorService io() {
        if (!started) start();
        return io;
    }

    public static ExecutorService cpu() {
        if (!started) start();
        return cpu;
    }

    public static boolean isRunning() {
        return started && !stopped;
    }

    public static synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        HyperionChunkSystem.get().shutdown();
        HyperionEntitySystem.get().shutdown();
        BlockStateCache.logStats();
        stopPool(io, "I/O");
        stopPool(cpu, "CPU");
        Hyperion.LOGGER.info("[Hyperion] Пулы потоков остановлены.");
    }

    private static void stopPool(ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory factory(String prefix, int priority) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, priority));
            t.setUncaughtExceptionHandler((thread, ex) ->
                    Hyperion.LOGGER.error("[Hyperion] Необработанное исключение в {}: {}", thread.getName(), ex.toString(), ex));
            return t;
        };
    }

    /** Для логов и /hyperion stats. */
    public static String describe() {
        if (!started) return "пулы не запущены";
        int cores = Runtime.getRuntime().availableProcessors();
        return "cores=" + cores + ", io=" + (io != null ? io.toString() : "?") + ", cpu=" + (cpu != null ? cpu.toString() : "?");
    }

    static final Logger LOG = LoggerFactory.getLogger("Hyperion-Exec");
}
