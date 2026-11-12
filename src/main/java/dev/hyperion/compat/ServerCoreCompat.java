package dev.hyperion.compat;

import java.lang.reflect.Method;

/**
 * Мост к ServerCore (activation range).
 *
 * <p>ServerCore троттлит мобов по «дистанции активации» — это прямое пересечение
 * с throttling ИИ Hyperion. Два троттлинга подряд означают, что мобы начинают
 * «teleport-иться» и терять цели, поэтому Hyperion не трогает тех мобов, которых
 * ServerCore уже пометил неактивными: {@code ActivationEntity.servercore$isInactive()}.
 *
 * <p>Итог: ServerCore работает на ближней/средней дистанции, Hyperion добивает
 * дальние регионы, двойного пропуска тиков нет. Проверено на ServerCore 1.5.8+1.21.4.
 */
public final class ServerCoreCompat {

    private static final String CONFIG = "me.wesley1808.servercore.common.config.Config";
    private static final String MAIN_CONFIG = "me.wesley1808.servercore.common.config.MainConfig";
    private static final String ACTIVATION_CONFIG =
            "me.wesley1808.servercore.common.config.data.activation_range.ActivationRangeConfig";
    private static final String ACTIVATION_ENTITY =
            "me.wesley1808.servercore.common.interfaces.activation_range.ActivationEntity";

    private static volatile boolean resolved;
    private static volatile Class<?> activationEntity;
    private static volatile Method isInactive;
    private static volatile Method configGet;
    private static volatile Method activationRange;
    private static volatile Method enabled;
    private static volatile boolean defer;

    private ServerCoreCompat() {
    }

    public static boolean present() {
        resolve();
        return activationEntity != null || configGet != null;
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (ServerCoreCompat.class) {
            if (resolved) return;
            Class<?> entity = CompatBridge.find(ACTIVATION_ENTITY);
            if (entity != null) {
                activationEntity = entity;
                isInactive = CompatBridge.method(entity, "servercore$isInactive");
            }
            Class<?> config = CompatBridge.find(CONFIG);
            if (config != null) {
                configGet = CompatBridge.method(config, "getSafe");
                if (configGet == null) configGet = CompatBridge.method(config, "get");
            }
            Class<?> main = CompatBridge.find(MAIN_CONFIG);
            if (main != null) activationRange = CompatBridge.method(main, "activationRange");
            Class<?> activation = CompatBridge.find(ACTIVATION_CONFIG);
            if (activation != null) enabled = CompatBridge.method(activation, "enabled");
            resolved = true;
        }
    }

    /** Включён ли activation range (если прочитать не удалось — считаем включённым). */
    public static boolean activationRangeEnabled() {
        if (!present()) return false;
        Object main = CompatBridge.invoke(configGet, null);
        Object range = CompatBridge.invoke(activationRange, main);
        return CompatBridge.invokeBoolean(enabled, range, true);
    }

    public static void requestDefer(boolean value) {
        defer = value;
    }

    /** true → этот моб уже троттлится ServerCore, Hyperion его не трогает. */
    public static boolean alreadyThrottled(Object entity) {
        if (!defer || activationEntity == null || isInactive == null || entity == null) return false;
        if (!activationEntity.isInstance(entity)) return false;
        return CompatBridge.invokeBoolean(isInactive, entity, false);
    }

    public static String describe() {
        if (!present()) return "ServerCore не установлен";
        return "ServerCore: activation range " + (activationRangeEnabled() ? "включён" : "выключен")
                + (defer ? " — Hyperion троттлит только остальных мобов" : "");
    }
}
