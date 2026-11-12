package dev.hyperion.renderer;

import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.util.MpscQueue;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Асинхронный движок occlusion-culling сущностей.
 *
 * <p>Схема (именно так, как это должно быть устроено в многопоточном рендере):
 * <pre>
 *  render thread                     worker threads
 *  -------------                     --------------
 *  shouldRender(entity)
 *    ├─ есть свежий результат? ── да ──► применяем (спрятать/показать)
 *    └─ нет ─► query в MPSC-очередь ──► DDA-рейкаст по immutable-снапшотам
 *              и рисуем (fail-open) ◄── публикация результата в ConcurrentHashMap
 * </pre>
 *
 * <p>Рендер-поток никогда не ждёт воркеров. Устаревший/неизвестный результат всегда
 * трактуется как «рисовать», поэтому артефактов «пропавших мобов» не бывает.
 */
public final class AsyncOcclusionEngine {

    private static final AsyncOcclusionEngine INSTANCE = new AsyncOcclusionEngine();

    private final MpscQueue<Query> queue = new MpscQueue<>(4096);
    private final Map<Integer, Result> results = new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile int queueCapacity = 4096;

    private AsyncOcclusionEngine() {
    }

    public static AsyncOcclusionEngine get() {
        return INSTANCE;
    }

    public void configure(HyperionConfig cfg) {
        this.queueCapacity = cfg.occlusionQueueCapacity;
    }

    public Result result(int entityId) {
        return results.get(entityId);
    }

    public void submit(Query query) {
        if (queue.size() >= queueCapacity) return;
        if (!queue.offer(query)) return;
        HyperionMetrics.CULL_QUERIES.increment();
        schedule();
    }

    private void schedule() {
        if (!HyperionExecutors.isRunning()) return;
        if (draining.compareAndSet(false, true)) {
            try {
                HyperionExecutors.cpu().execute(this::drain);
            } catch (Throwable t) {
                draining.set(false);
            }
        }
    }

    private void drain() {
        try {
            SectionLookup snapshots = OcclusionSnapshots.get();
            int processed = 0;
            Query query;
            while (processed < 1024 && (query = queue.poll()) != null) {
                processed++;
                int state = evaluate(snapshots, query);
                results.put(query.entityId(), new Result(state, System.currentTimeMillis(), query.ex(), query.ey(), query.ez()));
                if (state == VoxelRaycaster.UNKNOWN) {
                    HyperionMetrics.CULL_UNKNOWN.increment();
                }
            }
        } finally {
            draining.set(false);
            // Защита от потерянного wakeup: если очередь наполнилась между poll и сбросом флага.
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }

    private static int evaluate(SectionLookup snapshots, Query q) {
        double midY = q.ey() + q.height() * 0.5;
        int first = VoxelRaycaster.trace(snapshots, q.cx(), q.cy(), q.cz(), q.ex(), midY, q.ez());
        if (q.rays() <= 1 || first != VoxelRaycaster.BLOCKED) {
            return first;
        }
        int second = VoxelRaycaster.trace(snapshots, q.cx(), q.cy(), q.cz(), q.ex(), q.ey() + q.height() * 0.9, q.ez());
        if (second != VoxelRaycaster.BLOCKED) return second;
        if (q.rays() == 2) return VoxelRaycaster.BLOCKED;
        int third = VoxelRaycaster.trace(snapshots, q.cx(), q.cy(), q.cz(),
                q.ex() + q.halfWidth() * 0.8, midY, q.ez() + q.halfWidth() * 0.8);
        return third == VoxelRaycaster.BLOCKED ? VoxelRaycaster.BLOCKED : third;
    }

    /** Главный поток: чистим протухшие результаты, чтобы карта не росла бесконечно. */
    public void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Result>> it = results.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().time() > 2000) {
                it.remove();
            }
        }
    }

    public int pending() {
        return queue.size();
    }

    public int tracked() {
        return results.size();
    }

    public void clear() {
        queue.clear();
        results.clear();
    }

    public record Query(int entityId, double cx, double cy, double cz,
                        double ex, double ey, double ez,
                        double halfWidth, double height, int rays) {
    }

    public record Result(int state, long time, double ex, double ey, double ez) {
        public boolean hidden() {
            return state == VoxelRaycaster.BLOCKED;
        }
    }
}
