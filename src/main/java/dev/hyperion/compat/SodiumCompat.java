package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Мост к Sodium.
 *
 * <p>Что важно понять про совместимость: Sodium полностью заменяет рендер чанков
 * (RenderSectionManager, вершинные форматы, occlusion). Hyperion туда не лезет —
 * это единственный способ не сломать ни Sodium, ни Iris, который построен поверх
 * Sodium. Зато у Sodium 0.6 есть собственное отсечение сущностей —
 * {@code SodiumWorldRenderer.isEntityVisible(EntityRenderer, Entity)} — и вот оно
 * пересекается с Hyperion напрямую.
 *
 * <p><b>Порядок решений.</b> Sodium вызывает {@code isEntityVisible} из своего миксина
 * в {@code EntityRenderer.shouldRender} (обёртка вокруг {@code Frustum.isVisible}),
 * а ванильный {@code EntityRenderDispatcher.shouldRender} вызывает
 * {@code EntityRenderer.shouldRender}. Значит проверка Sodium всегда происходит
 * раньше хука Hyperion, и порядок детерминированный:
 * <pre>
 *   EntityRenderDispatcher.shouldRender
 *     └─ EntityRenderer.shouldRender
 *          └─ Sodium.isEntityVisible  ← переопределяется в режиме HYPERION
 *     └─ хук Hyperion (RETURN)        ← окончательное решение по occlusion
 * </pre>
 *
 * <p><b>Когда переопределять.</b> Только если Hyperion действительно принимает решения
 * в этом кадре. Если подсистема на паузе (шейдеры Iris в режиме {@code irisCulling=OFF}),
 * уступлена, выключена или отключена предохранителем — управление возвращается Sodium,
 * иначе в сборке не отсекал бы никто. Это и есть {@link #forceVisible()}.
 *
 * <p><b>Shadow pass.</b> В проходе теней Iris видимость нужно считать от солнца, а не
 * от камеры: и Hyperion, и Sodium в этот момент отсекать не должны. Поэтому в shadow
 * pass метод тоже возвращает «видима» — иначе у мобов пропадают тени.
 *
 * <p>Проверено на Sodium 0.6.13+mc1.21.4 (пакет {@code net.caffeinemc.mods.sodium});
 * для старых сборок поддерживается пакет {@code me.jellysquid.mods.sodium}.
 */
public final class SodiumCompat {

    private static final String RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static final String RENDERER_OLD = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer";

    private static volatile boolean resolved;
    private static volatile Class<?> rendererClass;
    private static volatile Method instanceNullable;
    private static volatile Field useEntityCulling;

    /** Выставляется планировщиком совместимости; читается миксином Sodium. */
    private static volatile boolean bypassEntityCulling;
    private static volatile boolean bypassApplied;

    private SodiumCompat() {
    }

    public static boolean present() {
        resolve();
        return rendererClass != null;
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (SodiumCompat.class) {
            if (resolved) return;
            rendererClass = CompatBridge.find(RENDERER, RENDERER_OLD);
            if (rendererClass != null) {
                instanceNullable = CompatBridge.method(rendererClass, "instanceNullable");
                if (instanceNullable == null) instanceNullable = CompatBridge.method(rendererClass, "instance");
                useEntityCulling = CompatBridge.field(rendererClass, "useEntityCulling");
            }
            resolved = true;
        }
    }

    /** Включён ли у Sodium собственный entity-culling (по факту, а не по конфигу на диске). */
    public static boolean entityCullingActive() {
        if (!present()) return false;
        Object renderer = CompatBridge.invoke(instanceNullable, null);
        if (renderer == null) {
            // Мир ещё не загружен — считаем, что включён (стандартное значение Sodium).
            return true;
        }
        return CompatBridge.readBoolean(useEntityCulling, renderer, true);
    }

    public static void requestBypass(boolean value) {
        bypassEntityCulling = value;
        if (value && !bypassApplied) {
            bypassApplied = true;
            Hyperion.LOGGER.info("[Hyperion][compat] Sodium: isEntityVisible переопределён — видимость сущностей решает Hyperion");
        }
    }

    /** Читается из миксина Sodium: true → вернуть управление Hyperion. */
    public static boolean bypassEntityCulling() {
        return bypassEntityCulling;
    }

    public static boolean bypassActive() {
        return bypassEntityCulling && present();
    }

    /**
     * Главная точка решения для миксина {@code SodiumEntityVisibilityMixin}:
     * вернуть ли {@code true} («сущность видима, дальше решает Hyperion»).
     *
     * <p>Вызывается на каждую сущность в кадре, поэтому здесь только volatile-чтения
     * и один дешёвый вызов в Iris — никакой рефлексии по чужим полям.
     */
    public static boolean forceVisible() {
        if (!bypassEntityCulling) return false;
        // Shadow pass: отдаём все сущности, иначе тени пропадают (и у Sodium тоже).
        if (IrisCompat.shadowPass()) return true;
        // Hyperion не работает (пауза/уступлено/предохранитель) — пусть решает Sodium.
        return HyperionStatus.usable(Feature.ENTITY_CULLING)
                && HyperionCompat.works(Feature.ENTITY_CULLING)
                && IrisCompat.cullingAllowed();
    }

    public static String describe() {
        if (!present()) return "Sodium не установлен";
        return "Sodium: рендер чанков не трогаем, entity-culling "
                + (entityCullingActive() ? "включён" : "выключен")
                + (bypassActive() ? " (переопределён Hyperion)" : "");
    }
}
