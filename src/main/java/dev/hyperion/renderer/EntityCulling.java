package dev.hyperion.renderer;

import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

/**
 * Фасад culling для миксина {@code EntityRenderDispatcher#shouldRender}.
 *
 * <p>Консервативные правила: игроки, светящиеся (glowing), сущности с пассажирами
 * или в транспорте, а также всё, что ближе минимальной дистанции, рисуются всегда.
 */
public final class EntityCulling {

    private static int frameCulled;

    private EntityCulling() {
    }

    /** Render thread: начало кадра. */
    public static void beginFrame() {
        frameCulled = 0;
    }

    /** Render thread: конец кадра — публикуем счётчик для HUD. */
    public static void endFrame() {
        HyperionMetrics.LAST_CULLED_ENTITIES = frameCulled;
    }

    public static boolean shouldCull(Entity entity, double camX, double camY, double camZ) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.entityCulling) return false;
        if (entity instanceof PlayerEntity) return false;
        if (entity.isGlowing() || entity.hasPassengers() || entity.hasVehicle()) return false;

        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        double dx = ex - camX;
        double dy = ey - camY;
        double dz = ez - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        double min = cfg.entityCullingMinDistance;
        if (distSq < min * min) return false;
        double max = cfg.entityCullingMaxDistance;
        if (distSq > max * max) return false;

        AsyncOcclusionEngine engine = AsyncOcclusionEngine.get();
        AsyncOcclusionEngine.Result result = engine.result(entity.getId());
        long now = System.currentTimeMillis();

        boolean stale = result == null
                || now - result.time() > cfg.occlusionResultTtlMs
                || moved(result, ex, ey, ez);

        if (stale) {
            Box box = entity.getBoundingBox();
            double halfWidth = Math.max(box.getLengthX(), box.getLengthZ()) * 0.5;
            engine.submit(new AsyncOcclusionEngine.Query(
                    entity.getId(), camX, camY, camZ, ex, ey, ez,
                    halfWidth, Math.max(0.5, box.getLengthY()), cfg.occlusionRays));
            return false; // fail-open: пока не знаем — рисуем
        }

        if (result.hidden()) {
            HyperionMetrics.CULL_HIDDEN.increment();
            frameCulled++;
            return true;
        }
        return false;
    }

    private static boolean moved(AsyncOcclusionEngine.Result result, double x, double y, double z) {
        double dx = result.ex() - x;
        double dy = result.ey() - y;
        double dz = result.ez() - z;
        return dx * dx + dy * dy + dz * dz > 1.0; // сдвинулся больше чем на блок — пересчитываем
    }
}
