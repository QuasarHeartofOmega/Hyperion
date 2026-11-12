package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.core.HyperionStatus.State;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Мост к Iris (шейдеры).
 *
 * <p>Зачем это нужно. Iris изначально писался под Sodium и полностью управляет
 * конвейером кадра: сначала shadow pass (кадр со стороны солнца), потом основной
 * проход, потом composite. Если в этот момент отсечь сущность по видимости из
 * камеры, тень от неё исчезнет — типичный баг «мобы без теней с шейдерами».
 *
 * <p>Поэтому Hyperion спрашивает у Iris официальное API:
 * <ul>
 *   <li>{@code IrisApi.isShaderPackInUse()} — активен ли шейдерпак;</li>
 *   <li>{@code IrisApi.isRenderingShadowPass()} — идёт ли сейчас shadow pass.</li>
 * </ul>
 * Во время shadow pass culling Hyperion не применяется вообще: все сущности,
 * которые попали в кадр, рисуются и отбрасывают тени. В основном проходе culling
 * работает как обычно (режим {@code SHADOW_SAFE}).
 *
 * <p>Состояние «шейдеры включены» кэшируется раз в кадр ({@link #refresh()} из
 * {@code HyperionCompat.clientFrame()}), потому что шейдерпак можно переключить
 * на ходу, а дёргать рефлексию на каждую сущность незачем. Состояние shadow pass,
 * наоборот, читается вживую: оно меняется внутри одного кадра.
 *
 * <p>Все обращения — через рефлексию: без Iris в сборке класс просто «недоступен»,
 * никаких NoClassDefFoundError. Проверено на Iris 1.8.x для 1.21.4
 * ({@code net.irisshaders.iris.api.v0.IrisApi}, ревизия API 2).
 */
public final class IrisCompat {

    /** Как вести culling сущностей при активном шейдерпаке. */
    public enum ShaderCulling {
        /** Полностью выключить culling, пока включены шейдеры. */
        OFF,
        /** Culling в основном проходе, никогда — в shadow pass (по умолчанию). */
        SHADOW_SAFE,
        /** Culling всегда (быстрее, но возможны артефакты теней). */
        FORCE;

        public static ShaderCulling parse(String value, ShaderCulling fallback) {
            if (value == null) return fallback;
            for (ShaderCulling mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) return mode;
            }
            return fallback;
        }
    }

    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String LEGACY_API_CLASS = "net.coderbot.iris.api.v0.IrisApi";
    private static final String SHADOW_STATE_CLASS = "net.irisshaders.iris.shadows.ShadowRenderingState";
    private static final String IRIS_CLASS = "net.irisshaders.iris.Iris";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile Object api;
    private static volatile Method isShaderPackInUse;
    private static volatile Method isRenderingShadowPass;
    private static volatile Method areShadowsRendered;
    private static volatile Method getCurrentPackName;
    private static volatile Field currentPackName;
    private static volatile boolean shadersInUse;
    private static volatile ShaderCulling mode = ShaderCulling.SHADOW_SAFE;
    private static volatile long lastRefresh;

    private IrisCompat() {
    }

    /** Есть ли Iris в сборке (по классу API — не зависит от id мода). */
    public static boolean present() {
        resolve();
        return available;
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (IrisCompat.class) {
            if (resolved) return;
            try {
                Class<?> apiClass = CompatBridge.find(API_CLASS, LEGACY_API_CLASS);
                if (apiClass != null) {
                    Method getInstance = CompatBridge.method(apiClass, "getInstance");
                    Object instance = CompatBridge.invoke(getInstance, null);
                    if (instance != null) {
                        api = instance;
                        isShaderPackInUse = CompatBridge.method(apiClass, "isShaderPackInUse");
                        isRenderingShadowPass = CompatBridge.method(apiClass, "isRenderingShadowPass");
                        available = isShaderPackInUse != null;
                    }
                }
                if (isRenderingShadowPass == null) {
                    // Старые версии Iris: состояние тени есть только во внутреннем классе.
                    Class<?> shadowState = CompatBridge.find(SHADOW_STATE_CLASS);
                    areShadowsRendered = CompatBridge.method(shadowState, "areShadowsCurrentlyBeingRendered");
                }
                Class<?> iris = CompatBridge.find(IRIS_CLASS);
                if (iris != null) {
                    getCurrentPackName = CompatBridge.method(iris, "getCurrentPackName");
                    if (getCurrentPackName == null) currentPackName = CompatBridge.field(iris, "currentPackName");
                }
                mode = ShaderCulling.parse(HyperionConfig.get().irisCulling, ShaderCulling.SHADOW_SAFE);
                if (available) {
                    Hyperion.LOGGER.info("[Hyperion][compat] Iris API найден (shadow pass {})",
                            isRenderingShadowPass != null ? "определяется" : "определяется по ShadowRenderingState");
                }
            } catch (Throwable t) {
                available = false;
                Hyperion.LOGGER.warn("[Hyperion][compat] Iris API недоступен: {}", String.valueOf(t));
            }
            resolved = true;
        }
    }

    /**
     * Обновить кэш «шейдеры включены» и режим culling. Вызывается раз в кадр из
     * render thread. Возвращает true, если состояние изменилось — тогда планировщик
     * совместимости пересобирает план (и индикатор сразу показывает паузу/работу).
     */
    public static boolean refresh() {
        if (!present()) {
            boolean was = shadersInUse;
            shadersInUse = false;
            return was;
        }
        lastRefresh = System.currentTimeMillis();
        boolean now = CompatBridge.invokeBoolean(isShaderPackInUse, api, false);
        ShaderCulling parsed = ShaderCulling.parse(HyperionConfig.get().irisCulling, ShaderCulling.SHADOW_SAFE);
        boolean changed = now != shadersInUse || parsed != mode;
        shadersInUse = now;
        mode = parsed;
        return changed;
    }

    public static boolean shadersInUse() {
        return available && shadersInUse;
    }

    /** Идёт ли shadow pass — спрашивается вживую, состояние меняется внутри кадра. */
    public static boolean shadowPass() {
        if (!available || !shadersInUse) return false;
        if (isRenderingShadowPass != null) {
            return CompatBridge.invokeBoolean(isRenderingShadowPass, api, false);
        }
        return CompatBridge.invokeBoolean(areShadowsRendered, null, false);
    }

    /**
     * Главное правило совместимости с Iris: можно ли сейчас отсеять сущность.
     * Вызывается из {@code EntityCulling} перед применением результата рейкаста
     * и из миксина Sodium — поэтому только volatile-чтения, без рефлексии,
     * кроме случая «шейдеры действительно включены».
     */
    public static boolean cullingAllowed() {
        if (!available || !shadersInUse) return true;
        return switch (mode) {
            case OFF -> false;
            case FORCE -> true;
            case SHADOW_SAFE -> !shadowPass();
        };
    }

    /** Режим из конфига (кэш обновляется раз в кадр). */
    public static ShaderCulling mode() {
        return mode;
    }

    /** Причина паузы для индикатора (пустая строка, если паузы нет). */
    public static String pauseReason() {
        if (!available || !shadersInUse) return "";
        return switch (mode) {
            case OFF -> "шейдеры Iris: culling на паузе";
            case SHADOW_SAFE -> "шейдеры Iris: culling только в основном проходе";
            case FORCE -> "шейдеры Iris: culling принудительно";
        };
    }

    /** Имя активного шейдерпака (для HUD/статуса), либо «—». */
    public static String packName() {
        if (!present() || !shadersInUse) return "—";
        String name = CompatBridge.invokeString(getCurrentPackName, null, null);
        if (name == null) name = CompatBridge.readString(currentPackName, null, null);
        return name == null || name.isEmpty() ? "—" : name;
    }

    public static String describe() {
        if (!present()) return "Iris не установлен";
        StringBuilder sb = new StringBuilder("Iris: ");
        sb.append(shadersInUse ? "шейдеры включены" : "шейдеры выключены");
        if (shadersInUse) {
            sb.append(" (").append(packName()).append(')');
            sb.append(", режим culling=").append(mode);
            sb.append(", shadow pass=").append(shadowPass() ? "да" : "нет");
        }
        return sb.toString();
    }

    /**
     * Отметить в статусе, что culling живёт в режиме шейдеров — и снять паузу,
     * когда шейдеры выключили. Паузу ставим только поверх ACTIVE/BOOT: уступленную
     * или сломанную подсистему «оживлять» нельзя, это решает планировщик.
     */
    public static void applyStatus() {
        if (!available) return;
        State current = HyperionStatus.state(Feature.ENTITY_CULLING);
        if (current == State.YIELDED || current == State.OFF || current == State.ERROR) return;
        if (shadersInUse && mode == ShaderCulling.OFF) {
            HyperionStatus.set(Feature.ENTITY_CULLING, State.PAUSED,
                    "шейдеры Iris (" + packName() + ")", "iris");
        } else if (current == State.PAUSED) {
            HyperionStatus.set(Feature.ENTITY_CULLING, State.ACTIVE,
                    shadersInUse ? "шейдеры Iris: shadow pass уважается" : "", "");
        }
    }

    public static long lastRefreshMillis() {
        return lastRefresh;
    }
}
