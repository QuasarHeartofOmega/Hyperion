package dev.hyperion.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Живой статус мода: что реально работает прямо сейчас, что уступлено другому моду,
 * что приостановлено (например, из-за шейдеров) и что отключилось после ошибок.
 *
 * <p>Это единственный источник правды для индикатора, HUD, F3-строки и /hyperion status.
 * Класс намеренно не зависит от Minecraft — его можно тестировать автономно
 * ({@code dev.hyperion.selftest.SelfTest}).
 *
 * <p>Здесь же живёт «предохранитель» (circuit breaker): если подсистема бросает
 * исключения, она отключается сама, а игра продолжает работать без мода в этой части.
 * Именно это отличает «мод работает с другими модами без крашей» от «мод падает первым».
 */
public final class HyperionStatus {

    /** Подсистемы, которые умеют сообщать о своей работе. */
    public enum Feature {
        CHUNK_IO("chunk-io", "чанки"),
        ENTITY_AI("entity-ai", "ИИ"),
        ENTITY_CULLING("culling", "culling"),
        BLOCKSTATE("blockstate", "blockstate"),
        BLOCKPOS("blockpos", "blockpos");

        public final String id;
        public final String label;

        Feature(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Feature byId(String id) {
            for (Feature f : values()) {
                if (f.id.equals(id)) return f;
            }
            return null;
        }
    }

    /** Состояние подсистемы. Порядок важен: {@link #worst} выбирает «худшее». */
    public enum State {
        /** Ещё не инициализирована (до первого тика/кадра). */
        BOOT,
        /** Работает. */
        ACTIVE,
        /** Временно приостановлена (шейдеры Iris, нет мира и т.п.). */
        PAUSED,
        /** Уступлена другому моду — он делает эту работу. */
        YIELDED,
        /** Выключена пользователем в конфиге. */
        OFF,
        /** Отключена предохранителем после ошибок. */
        ERROR
    }

    /** Одна подсистема: состояние + счётчик полезной работы + ошибки. */
    private static final class Slot {
        volatile State state = State.BOOT;
        volatile String reason = "";
        volatile String owner = "";
        final LongAdder work = new LongAdder();
        long lastSampleWork;
        long lastSampleNanos;
        volatile double rate;
        volatile long lastWorkNanos;
        volatile int errors;
        volatile long firstErrorNanos;
        volatile boolean reported;
    }

    private static final Map<Feature, Slot> SLOTS = new EnumMap<>(Feature.class);
    private static volatile boolean safeMode = true;
    private static volatile int errorLimit = 8;
    private static volatile long errorWindowNanos = 10_000_000_000L;
    private static volatile long bootNanos = System.nanoTime();
    private static volatile String environment = "";

    static {
        for (Feature f : Feature.values()) SLOTS.put(f, new Slot());
    }

    private HyperionStatus() {
    }

    // ------------------------------------------------------------------ настройка

    public static void configure(boolean safe, int limit) {
        safeMode = safe;
        errorLimit = Math.max(1, limit);
    }

    public static void environment(String description) {
        environment = description == null ? "" : description;
    }

    public static String environment() {
        return environment;
    }

    /** Сброс всего (используется при /hyperion reload и в тестах). */
    public static void resetAll() {
        for (Feature f : Feature.values()) reset(f);
        bootNanos = System.nanoTime();
    }

    public static void reset(Feature feature) {
        Slot slot = slot(feature);
        slot.state = State.BOOT;
        slot.reason = "";
        slot.owner = "";
        slot.errors = 0;
        slot.reported = false;
        slot.work.reset();
        slot.lastSampleWork = 0;
        slot.lastSampleNanos = 0;
        slot.rate = 0;
    }

    // ------------------------------------------------------------------ состояния

    public static void set(Feature feature, State state, String reason) {
        set(feature, state, reason, "");
    }

    public static void set(Feature feature, State state, String reason, String owner) {
        Slot slot = slot(feature);
        // Предохранитель нельзя «перезаписать» обычной логикой: только reset().
        if (slot.state == State.ERROR && state != State.ERROR && state != State.OFF) return;
        slot.state = state;
        slot.reason = reason == null ? "" : reason;
        slot.owner = owner == null ? "" : owner;
    }

    /** Переводит подсистему в ACTIVE, если она не выключена/не уступлена/не сломана. */
    public static void activate(Feature feature) {
        Slot slot = slot(feature);
        if (slot.state == State.BOOT) {
            slot.state = State.ACTIVE;
        }
    }

    public static State state(Feature feature) {
        return slot(feature).state;
    }

    public static String reason(Feature feature) {
        return slot(feature).reason;
    }

    public static String owner(Feature feature) {
        return slot(feature).owner;
    }

    /** Можно ли выполнять работу подсистемы (проверяется в хуках перед делом). */
    public static boolean usable(Feature feature) {
        State state = slot(feature).state;
        return state == State.ACTIVE || state == State.BOOT;
    }

    /** Активна ли подсистема по факту (для индикатора). */
    public static boolean active(Feature feature) {
        return slot(feature).state == State.ACTIVE;
    }

    // ------------------------------------------------------------------ работа

    /** Отметить полезную работу (дёшево: LongAdder + одна неvolatile запись). */
    public static void work(Feature feature) {
        work(feature, 1);
    }

    public static void work(Feature feature, int amount) {
        Slot slot = slot(feature);
        slot.work.add(amount);
        slot.lastWorkNanos = System.nanoTime();
        if (slot.state == State.BOOT) slot.state = State.ACTIVE;
    }

    /** Работа была не позже, чем {@code millis} мс назад. */
    public static boolean workedRecently(Feature feature, long millis) {
        long last = slot(feature).lastWorkNanos;
        return last != 0 && System.nanoTime() - last <= millis * 1_000_000L;
    }

    public static long totalWork(Feature feature) {
        return slot(feature).work.sum();
    }

    /** Работа в секунду (обновляется в {@link #sample()}). */
    public static double rate(Feature feature) {
        return slot(feature).rate;
    }

    /**
     * Пересчёт скоростей — вызывать ~2-4 раза в секунду из тика/HUD, не чаще.
     * Возвращает true, если хоть одна подсистема работала с прошлого вызова.
     */
    public static boolean sample() {
        long now = System.nanoTime();
        boolean any = false;
        for (Slot slot : SLOTS.values()) {
            long total = slot.work.sum();
            long delta = total - slot.lastSampleWork;
            if (slot.lastSampleNanos != 0) {
                double seconds = (now - slot.lastSampleNanos) / 1e9;
                slot.rate = seconds > 0 ? delta / seconds : 0.0;
            }
            slot.lastSampleWork = total;
            slot.lastSampleNanos = now;
            if (delta > 0) any = true;
        }
        return any;
    }

    // ------------------------------------------------------- предохранитель

    /**
     * Зарегистрировать сбой подсистемы. Возвращает true, если подсистема ещё жива.
     * После {@code errorLimit} ошибок за окно — ERROR и работа больше не выполняется.
     */
    public static boolean fail(Feature feature, Throwable error) {
        Slot slot = slot(feature);
        long now = System.nanoTime();
        if (slot.errors == 0 || now - slot.firstErrorNanos > errorWindowNanos) {
            slot.errors = 0;
            slot.firstErrorNanos = now;
        }
        slot.errors++;
        if (!slot.reported) {
            slot.reported = true;
            Hyperion.LOGGER.error("[Hyperion] Сбой подсистемы {} — перехвачен, игра продолжает работу: {}",
                    feature.id, String.valueOf(error), error);
        }
        if (safeMode && slot.errors >= errorLimit && slot.state != State.ERROR) {
            slot.state = State.ERROR;
            slot.reason = "авто-отключение после " + slot.errors + " ошибок: " + shortError(error);
            Hyperion.LOGGER.error("[Hyperion] Подсистема {} отключена предохранителем ({} ошибок за {} с). "
                            + "Остальные подсистемы продолжают работать.",
                    feature.id, slot.errors, errorWindowNanos / 1_000_000_000L);
            return false;
        }
        return slot.state != State.ERROR;
    }

    public static int errors(Feature feature) {
        return slot(feature).errors;
    }

    private static String shortError(Throwable error) {
        if (error == null) return "неизвестно";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null ? name : name + ": " + message;
    }

    // ------------------------------------------------------------------ сводка

    /** «Худшее» состояние среди подсистем — определяет цвет индикатора. */
    public static State worst() {
        State worst = State.ACTIVE;
        boolean any = false;
        for (Feature f : Feature.values()) {
            State state = slot(f).state;
            if (state == State.OFF) continue; // выключено пользователем — не проблема
            any = true;
            worst = worse(worst, state);
        }
        return any ? worst : State.OFF;
    }

    private static State worse(State a, State b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(State state) {
        return switch (state) {
            case ACTIVE -> 0;
            case BOOT -> 1;
            case PAUSED -> 2;
            case YIELDED -> 3;
            case OFF -> 4;
            case ERROR -> 5;
        };
    }

    public static int activeCount() {
        int n = 0;
        for (Feature f : Feature.values()) if (active(f)) n++;
        return n;
    }

    /** Короткая строка для индикатора: «активен 4/5», «уступил Sodium», «ошибка». */
    public static String headline() {
        State worst = worst();
        int total = Feature.values().length;
        return switch (worst) {
            case ACTIVE -> "активен " + activeCount() + "/" + total;
            case BOOT -> "запуск";
            case PAUSED -> "пауза " + activeCount() + "/" + total;
            case YIELDED -> "уступил часть " + activeCount() + "/" + total;
            case OFF -> "выключен";
            case ERROR -> "сбой подсистемы";
        };
    }

    /** Цвет индикатора (ARGB), считается без Minecraft. */
    public static int color() {
        return switch (worst()) {
            case ACTIVE -> 0xFF5BE37A; // зелёный — всё работает
            case BOOT -> 0xFF9AA0A6;   // серый — ещё не началось
            case PAUSED -> 0xFF63C7F0;  // голубой — пауза (шейдеры)
            case YIELDED -> 0xFFF2C14E; // жёлтый — часть отдана другому моду
            case OFF -> 0xFF8A8F98;    // серый — выключен
            case ERROR -> 0xFFEF6B6B;  // красный — предохранитель
        };
    }

    /** Пульс индикатора 0..1: «дышит» только когда есть реальная работа. */
    public static double pulse() {
        boolean working = false;
        for (Feature f : Feature.values()) {
            if (active(f) && workedRecently(f, 700)) {
                working = true;
                break;
            }
        }
        if (!working) return 0.35;
        double phase = (System.nanoTime() / 1_000_000.0 % 1200.0) / 1200.0;
        return 0.55 + 0.45 * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2));
    }

    public static long uptimeMillis() {
        return (System.nanoTime() - bootNanos) / 1_000_000L;
    }

    /** Построчная сводка для /hyperion status и HUD. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>(8);
        for (Feature f : Feature.values()) {
            Slot slot = slot(f);
            StringBuilder line = new StringBuilder();
            line.append(String.format("%-10s %-8s", f.id, stateName(slot.state)));
            if (slot.state == State.ACTIVE) {
                line.append(String.format(" %.0f оп/с", slot.rate));
            }
            if (!slot.owner.isEmpty()) line.append(" -> ").append(slot.owner);
            if (!slot.reason.isEmpty()) line.append(" (").append(slot.reason).append(")");
            if (slot.errors > 0) line.append(" [ошибок: ").append(slot.errors).append(']');
            out.add(line.toString());
        }
        return out;
    }

    public static String stateName(State state) {
        return switch (state) {
            case BOOT -> "запуск";
            case ACTIVE -> "работает";
            case PAUSED -> "пауза";
            case YIELDED -> "уступлено";
            case OFF -> "выключено";
            case ERROR -> "сбой";
        };
    }

    private static Slot slot(Feature feature) {
        return SLOTS.get(feature);
    }
}
