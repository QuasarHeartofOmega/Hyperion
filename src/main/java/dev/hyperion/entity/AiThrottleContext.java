package dev.hyperion.entity;

/**
 * Контекст текущего тика ИИ (главный поток).
 *
 * <p>{@code GoalSelector} не знает, какому мобу он принадлежит, поэтому решение
 * «пропустить тик целей» передаётся через thread-local, выставленный миксином
 * в {@code MobEntity#tickNewAi}. Это строго однопоточный контекст главного потока.
 */
public final class AiThrottleContext {

    private static final ThreadLocal<Boolean> SKIP = new ThreadLocal<>();

    private AiThrottleContext() {
    }

    public static void set(boolean skip) {
        SKIP.set(skip);
    }

    public static boolean shouldSkip() {
        Boolean value = SKIP.get();
        return value != null && value;
    }

    public static void clear() {
        SKIP.remove();
    }
}
