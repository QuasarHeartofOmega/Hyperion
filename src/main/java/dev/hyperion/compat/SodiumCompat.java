package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;

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
 * <p>В режиме приоритета HYPERION это метод переопределяется нашим миксином
 * {@code SodiumEntityVisibilityMixin}: он возвращает true, а окончательное решение
 * принимает асинхронный движок Hyperion в {@code EntityRenderDispatcher.shouldRender}.
 * Двойной работы нет, порядок решений детерминированный.
 *
 * <p>Проверено на Sodium 0.6.13+mc1.21.4 (пакет net.caffeinemc.mods.sodium);
 * для старых сборок поддерживается пакет me.jellysquid.mods.sodium.
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

    public static String describe() {
        if (!present()) return "Sodium не установлен";
        return "Sodium: рендер чанков не трогаем, entity-culling "
                + (entityCullingActive() ? "включён" : "выключен")
                + (bypassActive() ? " (переопределён Hyperion)" : "");
    }
}
