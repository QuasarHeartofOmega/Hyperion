package dev.hyperion.core;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Обёртка «никогда не роняем игру».
 *
 * <p>Любой хук Hyperion в ванильный или чужой метод выполняется через этот класс
 * (или через явный try/catch в горячих путях). Исключение перехватывается,
 * учитывается в предохранителе {@link HyperionStatus} и наружу не выходит: ванильный метод
 * просто продолжает работу так, как будто мода нет.
 *
 * <p>Это ключевое требование совместимости: даже если Sodium/Lithium/Iris поменяли
 * внутренности так, что наше предположение перестало быть верным, пользователь
 * получает строчку в логе и жёлтый индикатор, а не крашлог.
 */
public final class HyperionGuard {

    private HyperionGuard() {
    }

    public static void run(HyperionStatus.Feature feature, Runnable body) {
        if (!HyperionStatus.usable(feature)) return;
        try {
            body.run();
        } catch (Throwable t) {
            HyperionStatus.fail(feature, t);
        }
    }

    /** Для хуков, которые должны отработать даже когда подсистема на паузе. */
    public static void runAlways(HyperionStatus.Feature feature, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            HyperionStatus.fail(feature, t);
        }
    }

    public static boolean test(HyperionStatus.Feature feature, BooleanSupplier body, boolean fallback) {
        if (!HyperionStatus.usable(feature)) return fallback;
        try {
            return body.getAsBoolean();
        } catch (Throwable t) {
            HyperionStatus.fail(feature, t);
            return fallback;
        }
    }

    public static <T> T get(HyperionStatus.Feature feature, Supplier<T> body, T fallback) {
        if (!HyperionStatus.usable(feature)) return fallback;
        try {
            T value = body.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            HyperionStatus.fail(feature, t);
            return fallback;
        }
    }

    /** Разовые действия без привязки к подсистеме (индикатор, команды, отчёты). */
    public static void safe(String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            Hyperion.LOGGER.warn("[Hyperion] {} не удалось (пропущено): {}", what, String.valueOf(t));
        }
    }

    public static <T> T safe(String what, Supplier<T> body, T fallback) {
        try {
            T value = body.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            Hyperion.LOGGER.warn("[Hyperion] {} не удалось (пропущено): {}", what, String.valueOf(t));
            return fallback;
        }
    }
}
