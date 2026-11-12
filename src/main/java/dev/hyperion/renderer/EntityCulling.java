package dev.hyperion.renderer;

import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.compat.IrisCompat;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionGuard;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

/**
 * Фасад culling для миксина {@code EntityRenderDispatcher#shouldRender}.
 *
 * <p>Консервативные правила: игроки, светящиеся (glowing), с видимым именем,
 * сущности с пассажирами или в транспорте, а также всё, что ближе минимальной
 * дистанции, рисуются всегда.
 *
 * <p>Порядок проверок совместимости (сверху вниз, каждая дешёвая):
 * <ol>
 *   <li>{@link HyperionGuard} — предохранитель: после серии ошибок подсистема
 *       отключается сама, игра продолжает работать без неё;</li>
 *   <li>план совместимости — подсистема могла быть уступлена (VulkanMod, OptiFine,
 *       режим YIELD) или выключена в конфиге;</li>
 *   <li>{@link IrisCompat#cullingAllowed()} — шейдеры: в shadow pass не отсекаем
 *       никогда, при {@code irisCulling=OFF} не отсекаем вообще;</li>
 *   <li>дальность ванильной прорисовки, если в сборке Distant Horizons/Bobby:
 *       их ландшафт в снапшоты не входит, за горизонтом решение не принимаем.</li>
 * </ol>
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

    /**
     * Единственная точка входа из миксина. Никогда не бросает исключений:
     * при сбое возвращает false («рисовать»), а не роняет кадр.
     */
    public static boolean shouldCull(Entity entity, double camX, double camY, double camZ) {
        return HyperionGuard.test(Feature.ENTITY_CULLING,
                () -> decide(entity, camX, camY, camZ), false);
    }

    private static boolean decide(Entity entity, double camX, double camY, double camZ) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.entityCulling) return false;
        if (!HyperionCompat.works(Feature.ENTITY_CULLING)) return false;
        if (!IrisCompat.cullingAllowed()) return false;

        if (entity instanceof PlayerEntity) return false;
        if (entity.isGlowing() || entity.isCustomNameVisible()) return false;
        if (entity.hasPassengers() || entity.hasVehicle()) return false;

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
        if (beyondRenderedWorld(distSq)) return false;

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
            HyperionStatus.work(Feature.ENTITY_CULLING);
            frameCulled++;
            return true;
        }
        return false;
    }

    /**
     * Distant Horizons и Bobby дорисовывают мир за пределами ванильной дальности.
     * Их геометрии нет в {@link OcclusionSnapshots}: рейкаст вернул бы UNKNOWN
     * (fail-open), но секции на границе прорисовки могут быть заполнены частично —
     * и тогда «стена» из LOD-ландшафта скрыла бы видимого моба. За горизонтом
     * ванильной прорисовки решение не принимаем вовсе.
     */
    private static boolean beyondRenderedWorld(double distSq) {
        if (!HyperionCompat.hasLodTerrain()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return false;
        Integer viewDistance = client.options.getViewDistance().getValue();
        if (viewDistance == null || viewDistance <= 0) return false;
        double limit = viewDistance * 16.0;
        return distSq > limit * limit;
    }

    private static boolean moved(AsyncOcclusionEngine.Result result, double x, double y, double z) {
        double dx = result.ex() - x;
        double dy = result.ey() - y;
        double dz = result.ez() - z;
        return dx * dx + dy * dy + dz * dz > 1.0; // сдвинулся больше чем на блок — пересчитываем
    }
}
