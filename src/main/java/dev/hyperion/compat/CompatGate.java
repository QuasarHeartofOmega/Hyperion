package dev.hyperion.compat;

import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionStatus.Feature;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Решения совместимости <b>на этапе миксинов</b> — до того, как игра начала грузить классы.
 *
 * <p>Зачем отдельный класс. {@link HyperionCompat#replan()} уточняет сборку рефлексией
 * ({@code SodiumWorldRenderer.instanceNullable()}, {@code IrisApi.getInstance()},
 * {@code Config.getSafe()}), а это загрузка и инициализация чужих классов. Делать это
 * из {@code IMixinConfigPlugin.shouldApplyMixin} нельзя: mixin-плагин работает внутри
 * трансформации байткода, и ранняя загрузка чужого мода даёт циклическую инициализацию
 * («мод ещё не применён, а его класс уже грузится»). Поэтому здесь:
 *
 * <ol>
 *   <li>список модов берётся у FabricLoader (это просто метаданные, классы не грузятся);</li>
 *   <li>вложенные jar'ы, у которых нет своего id, находятся <b>по ресурсу</b>
 *       {@code net/.../Class.class} — чтение ресурса не загружает и не инициализирует класс;</li>
 *   <li>решение принимает чистый {@link CompatPlan} без рефлексии.</li>
 * </ol>
 *
 * <p>Разделение обязанностей: этот класс решает, <b>применять ли миксин</b> (решение
 * необратимо до перезапуска), а {@link HyperionCompat} — <b>как себя вести</b> в рантайме
 * (шейдеры включились, мир загрузился, пользователь сменил режим). Поэтому миксин в
 * Sodium ставится всегда, когда Sodium найден, а «переопределять или нет» решает
 * уже рантайм-флаг: иначе смена режима командой требовала бы перезапуска игры.
 */
public final class CompatGate {

    private static final Set<String> PRESENT = new LinkedHashSet<>();
    private static volatile boolean scanned;
    private static volatile CompatPlan.Result early;

    private CompatGate() {
    }

    // ------------------------------------------------------------------ опрос

    /** Есть ли мод в сборке. Безопасно вызывать из mixin-плагина. */
    public static boolean present(String id) {
        scan();
        return PRESENT.contains(id);
    }

    public static Set<String> presentMods() {
        scan();
        return Collections.unmodifiableSet(PRESENT);
    }

    private static void scan() {
        if (scanned) return;
        synchronized (CompatGate.class) {
            if (scanned) return;
            FabricLoader loader = null;
            try {
                loader = FabricLoader.getInstance();
            } catch (Throwable ignored) {
                // Автономные тесты / среда без Fabric — считаем, что модов нет.
            }
            for (CompatMods.Mod mod : CompatMods.all()) {
                boolean found = false;
                if (loader != null) {
                    try {
                        found = loader.isModLoaded(mod.id());
                    } catch (Throwable ignored) {
                    }
                }
                if (!found) found = resourcePresent(mod.markers());
                if (found) PRESENT.add(mod.id());
            }
            scanned = true;
        }
    }

    /**
     * Существует ли класс — <b>без его загрузки</b>. Поиск ресурса не запускает
     * ни загрузку класса, ни статическую инициализацию чужого мода, поэтому годится
     * для самой ранней фазы. KnotClassLoader ищет ресурсы по всем jar'ам сборки.
     */
    public static boolean resourcePresent(String... classNames) {
        if (classNames == null) return false;
        ClassLoader cl = CompatGate.class.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        for (String name : classNames) {
            if (name == null || name.isEmpty()) continue;
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in != null) return true;
            } catch (Throwable ignored) {
                // Недоступный jar — не повод падать на этапе трансформации.
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ план

    /**
     * Ранний план: только id модов и конфиг. Значения, которые в принципе нельзя
     * узнать до загрузки мира (включён ли entity-culling у Sodium, activation range
     * у ServerCore), берутся как «включено» — это значения по умолчанию у обоих модов.
     */
    public static CompatPlan.Result early() {
        CompatPlan.Result local = early;
        if (local != null) return local;
        synchronized (CompatGate.class) {
            if (early == null) {
                HyperionConfig cfg = HyperionConfig.get();
                CompatPlan.Input in = new CompatPlan.Input();
                in.mods.addAll(presentMods());
                in.mode = CompatPlan.Mode.parse(cfg.compatMode, CompatPlan.Mode.HYPERION);
                in.chunkPrefetch = cfg.chunkPrefetch;
                in.entityThrottling = cfg.entityThrottling;
                in.entityCulling = cfg.entityCulling;
                in.blockStateCache = cfg.blockStateCache;
                in.blockPosPool = cfg.blockPosPool;
                in.overrideSodiumEntityCulling = cfg.overrideSodiumEntityCulling;
                in.overrideEntityCullingMod = cfg.overrideEntityCullingMod;
                in.irisCulling = cfg.irisCulling;
                early = CompatPlan.plan(in);
            }
            return early;
        }
    }

    /** Подсистема уступлена/выключена уже на этапе миксинов. */
    public static boolean disabled(Feature feature) {
        CompatPlan.Decision decision = early().get(feature);
        return decision == null || !decision.hyperionWorks();
    }

    public static boolean allow(Feature feature) {
        return !disabled(feature);
    }

    /** Сброс кэша (после /hyperion reload). На уже применённые миксины не влияет. */
    public static void invalidate() {
        synchronized (CompatGate.class) {
            early = null;
        }
    }

    // ------------------------------------------------------- миксины в чужие моды

    /**
     * Применять ли миксин в чужой мод. Проверяется только «есть ли мод» и «нужна ли
     * подсистема»: поведение (переопределять или работать рядом) решает рантайм.
     */
    public static boolean allowForeignMixin(String simpleName) {
        HyperionConfig cfg = HyperionConfig.get();
        if (!cfg.enabled) return false;
        return switch (simpleName) {
            case "SodiumEntityVisibilityMixin" -> cfg.entityCulling && present("sodium")
                    && resourcePresent("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            case "SodiumLegacyEntityVisibilityMixin" -> cfg.entityCulling && present("sodium")
                    && resourcePresent("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
            default -> false;
        };
    }
}
