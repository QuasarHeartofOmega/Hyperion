package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;

import java.lang.reflect.Field;

/**
 * Мост к моду EntityCulling (tr7zw).
 *
 * <p>Этот мод делает ровно то же, что подсистема culling Hyperion, но своим
 * асинхронным рейкастом. Два движка = двойная работа и «мигающие» мобы на границе.
 *
 * <p>Хорошая новость: у мода есть штатный выключатель — публичное поле
 * {@code CullTask.disableEntityCulling}. Мы не патчим его байткод, а выставляем
 * этот флаг, то есть переопределяем поведение его же собственным механизмом.
 * Отсечение блок-сущностей (disableBlockEntityCulling) остаётся моду: Hyperion
 * блок-сущности не трогает, так что области работы не пересекаются.
 *
 * <p>Проверено на EntityCulling 1.11.3 для 1.21.4. Флаг восстанавливается при
 * выходе, чтобы не менять поведение сборки после удаления Hyperion.
 */
public final class EntityCullingCompat {

    private static final String MOD_BASE = "dev.tr7zw.entityculling.EntityCullingModBase";
    private static final String CULL_TASK = "dev.tr7zw.entityculling.CullTask";

    private static volatile boolean resolved;
    private static volatile Field instanceField;
    private static volatile Field cullTaskField;
    private static volatile Field disableEntityCullingField;
    private static volatile boolean requested;
    private static volatile boolean applied;
    private static volatile boolean previousValue;
    private static volatile int attempts;
    private static volatile int reassertCounter;

    private EntityCullingCompat() {
    }

    public static boolean present() {
        resolve();
        return instanceField != null;
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (EntityCullingCompat.class) {
            if (resolved) return;
            Class<?> base = CompatBridge.find(MOD_BASE);
            Class<?> task = CompatBridge.find(CULL_TASK);
            if (base != null && task != null) {
                instanceField = CompatBridge.field(base, "instance");
                cullTaskField = CompatBridge.field(base, "cullTask");
                disableEntityCullingField = CompatBridge.field(task, "disableEntityCulling");
            }
            resolved = true;
        }
    }

    /** Запросить передачу culling сущностей Hyperion (применится, когда мод инициализируется). */
    public static void requestTakeOver() {
        requested = true;
    }

    /**
     * Мод инициализируется в своём порядке, поэтому применение отложенное:
     * вызывается из клиентского тика, пока не получится (или пока не сдадимся).
     */
    public static void applyPending() {
        if (!requested || applied || !present()) return;
        if (attempts++ > 600) { // ~30 секунд тиков — дальше не настаиваем
            requested = false;
            Hyperion.LOGGER.warn("[Hyperion][compat] EntityCulling не отдал culling (мод не инициализировался) — работаем рядом");
            return;
        }
        Object instance = CompatBridge.read(instanceField, null);
        Object task = CompatBridge.read(cullTaskField, instance);
        if (task == null) return;
        previousValue = CompatBridge.readBoolean(disableEntityCullingField, task, false);
        if (CompatBridge.write(disableEntityCullingField, task, Boolean.TRUE)) {
            applied = true;
            Hyperion.LOGGER.info("[Hyperion][compat] EntityCulling: culling сущностей передан Hyperion "
                    + "(culling блок-сущностей остаётся моду)");
        }
    }

    public static boolean takenOver() {
        return applied;
    }

    /**
     * Перепроверить флаг: мод может перечитать свой конфиг (например, после
     * {@code /entityculling reload} или смены мира) и сбросить его обратно.
     * Вызывается из клиентского тика, но не чаще раза в 5 секунд — это одно
     * чтение поля, а не работа по сущностям.
     */
    public static void reassert() {
        if (!applied || !present()) return;
        if (++reassertCounter < 100) return;
        reassertCounter = 0;
        Object instance = CompatBridge.read(instanceField, null);
        Object task = CompatBridge.read(cullTaskField, instance);
        if (task == null) return;
        if (!CompatBridge.readBoolean(disableEntityCullingField, task, true)) {
            if (CompatBridge.write(disableEntityCullingField, task, Boolean.TRUE)) {
                Hyperion.LOGGER.info("[Hyperion][compat] EntityCulling сбросил флаг — culling сущностей снова у Hyperion");
            }
        }
    }

    /** Вернуть моду его настройку (выход из игры / отключение мода). */
    public static void restore() {
        if (!applied) return;
        Object instance = CompatBridge.read(instanceField, null);
        Object task = CompatBridge.read(cullTaskField, instance);
        if (task != null) {
            CompatBridge.write(disableEntityCullingField, task, previousValue);
        }
        applied = false;
        requested = false;
        attempts = 0;
        reassertCounter = 0;
    }

    public static String describe() {
        if (!present()) return "EntityCulling не установлен";
        if (applied) {
            return "EntityCulling: culling сущностей выполняет Hyperion, блок-сущности — мод";
        }
        if (requested) {
            return "EntityCulling: ждём инициализацию мода, чтобы забрать culling сущностей (попытка " + attempts + ")";
        }
        return "EntityCulling: работает рядом (двойного отсечения нет: решения объединяются)";
    }
}
