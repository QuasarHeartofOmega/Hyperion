package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.core.HyperionStatus.State;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Слой совместимости и приоритета.
 *
 * <p>Задача: мод должен работать в любой сборке — с Sodium, Iris, Lithium,
 * EntityCulling, C2ME, ServerCore, FerriteCore, ModernFix и прочими «оптимизаторами»,
 * не падать и не воевать за одну и ту же работу.
 *
 * <p>Как это сделано:
 * <ol>
 *   <li><b>Опрос сборки.</b> Читаем список модов у FabricLoader (id + версия).</li>
 *   <li><b>Планирование.</b> {@link CompatPlan} решает по каждой подсистеме, кто
 *       её владеет: Hyperion, другой мод или оба. Логика чистая и покрыта тестами.</li>
 *   <li><b>Переопределение.</b> Там, где у чужого мода есть штатная точка влияния,
 *       Hyperion забирает работу себе: миксин в {@code SodiumWorldRenderer.isEntityVisible},
 *       флаг {@code CullTask.disableEntityCulling} у EntityCulling, учёт
 *       {@code ActivationEntity.servercore$isInactive()} у ServerCore.</li>
 *   <li><b>Отказ от невозможного.</b> Где переопределение технически невозможно
 *       (C2ME переписал I/O, VulkanMod сменил бэкенд) — подсистема уступает,
 *       а не ломает чужой мод.</li>
 *   <li><b>Динамика.</b> Iris включает/выключает шейдеры на ходу — состояние
 *       подсистемы обновляется каждый кадр ({@link IrisCompat}).</li>
 * </ol>
 *
 * <p>Все миксины в чужие классы включаются только если мод реально найден
 * (см. {@code HyperionMixinPlugin}), с {@code require = 0} и {@code @Pseudo}.
 */
public final class HyperionCompat {

    /** Моды, о которых Hyperion знает что-то полезное (id -> человекочитаемое имя). */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("sodium", "Sodium");
        KNOWN.put("iris", "Iris");
        KNOWN.put("lithium", "Lithium");
        KNOWN.put("indium", "Indium");
        KNOWN.put("entityculling", "EntityCulling");
        KNOWN.put("moreculling", "MoreCulling");
        KNOWN.put("c2me", "C2ME");
        KNOWN.put("servercore", "ServerCore");
        KNOWN.put("ferritecore", "FerriteCore");
        KNOWN.put("modernfix", "ModernFix");
        KNOWN.put("immediatelyfast", "ImmediatelyFast");
        KNOWN.put("exordium", "Exordium");
        KNOWN.put("vulkanmod", "VulkanMod");
        KNOWN.put("nvidium", "Nvidium");
        KNOWN.put("dashloader", "DashLoader");
        KNOWN.put("krypton", "Krypton");
        KNOWN.put("starlight", "Starlight");
        KNOWN.put("vmp", "Very Many Players");
        KNOWN.put("noisium", "Noisium");
        KNOWN.put("alternate-current", "Alternate Current");
        KNOWN.put("threadtweak", "ThreadTweak");
        KNOWN.put("distanthorizons", "Distant Horizons");
        KNOWN.put("bobby", "Bobby");
        KNOWN.put("optifabric", "OptiFabric");
        KNOWN.put("sodium-extra", "Sodium Extra");
        KNOWN.put("reeses-sodium-options", "Reese's Sodium Options");
    }

    private static final Set<String> PRESENT = new LinkedHashSet<>();
    private static final Map<String, String> VERSIONS = new LinkedHashMap<>();
    private static volatile CompatPlan.Result plan;
    private static volatile boolean scanned;
    private static volatile boolean client;

    private HyperionCompat() {
    }

    // ------------------------------------------------------------------ опрос

    public static void scan() {
        if (scanned) return;
        synchronized (HyperionCompat.class) {
            if (scanned) return;
            try {
                FabricLoader loader = FabricLoader.getInstance();
                for (ModContainer mod : loader.getAllMods()) {
                    String id = mod.getMetadata().getId();
                    VERSIONS.put(id, mod.getMetadata().getVersion().getFriendlyString());
                    if (KNOWN.containsKey(id)) PRESENT.add(id);
                }
            } catch (Throwable t) {
                // FabricLoader может быть недоступен в тестах/дедикейтеде без Fabric — не критично.
                Hyperion.LOGGER.debug("[Hyperion][compat] список модов недоступен: {}", String.valueOf(t));
            }
            scanned = true;
        }
    }

    /**
     * Уточнение по классам: некоторые сборки прячут мод внутри другого jar, и id
     * может отсутствовать. ВАЖНО: это делается только в рантайме (onInitialize и
     * позже), никогда из mixin-плагина — ранняя загрузка чужих классов во время
     * трансформации байткода способна устроить циклическую инициализацию.
     */
    private static void probeClasses() {
        if (SodiumCompat.present()) PRESENT.add("sodium");
        if (IrisCompat.present()) PRESENT.add("iris");
        if (LithiumCompat.present()) PRESENT.add("lithium");
        if (EntityCullingCompat.present()) PRESENT.add("entityculling");
        if (ServerCoreCompat.present()) PRESENT.add("servercore");
    }

    /** Пересобрать план (после /hyperion reload или смены конфига). */
    public static synchronized void replan() {
        scan();
        probeClasses();
        HyperionConfig cfg = HyperionConfig.get();
        CompatPlan.Input in = new CompatPlan.Input();
        in.mods.addAll(PRESENT);
        in.mode = CompatPlan.Mode.parse(cfg.compatMode, CompatPlan.Mode.HYPERION);
        in.chunkPrefetch = cfg.chunkPrefetch;
        in.entityThrottling = cfg.entityThrottling;
        in.entityCulling = cfg.entityCulling;
        in.blockStateCache = cfg.blockStateCache;
        in.blockPosPool = cfg.blockPosPool;
        in.overrideSodiumEntityCulling = cfg.overrideSodiumEntityCulling;
        in.overrideEntityCullingMod = cfg.overrideEntityCullingMod;
        in.sodiumEntityCullingActive = SodiumCompat.entityCullingActive();
        in.serverCoreActivationRange = ServerCoreCompat.activationRangeEnabled();

        CompatPlan.Result result = CompatPlan.plan(in);
        plan = result;

        // Статус подсистем — единственный источник правды для индикатора.
        for (Map.Entry<Feature, CompatPlan.Decision> entry : result.decisions.entrySet()) {
            CompatPlan.Decision d = entry.getValue();
            HyperionStatus.set(entry.getKey(), d.state(), d.note(), d.owner());
        }

        // Переопределения чужих методов/флагов.
        SodiumCompat.requestBypass(result.has(CompatPlan.Action.BYPASS_SODIUM_ENTITY_CULLING));
        ServerCoreCompat.requestDefer(result.has(CompatPlan.Action.DEFER_TO_SERVERCORE));
        if (result.has(CompatPlan.Action.DISABLE_ENTITYCULLING_MOD)) {
            EntityCullingCompat.requestTakeOver();
        } else {
            EntityCullingCompat.restore();
        }

        HyperionStatus.environment(environmentLine());
    }

    public static CompatPlan.Result plan() {
        if (plan == null) replan();
        return plan;
    }

    /** Вызывается из клиентского тика: отложенные переопределения + динамика Iris. */
    public static void clientTick() {
        EntityCullingCompat.applyPending();
    }

    public static void markClient() {
        client = true;
    }

    public static boolean isClient() {
        return client;
    }

    // ------------------------------------------------------------------ запросы

    /** Подсистема уступлена другому моду или выключена (миксины не применяются). */
    public static boolean isDisabled(Feature feature) {
        CompatPlan.Result result = plan();
        CompatPlan.Decision decision = result.get(feature);
        return decision == null || !decision.hyperionWorks();
    }

    public static boolean works(Feature feature) {
        return !isDisabled(feature);
    }

    public static boolean sodiumBypass() {
        return SodiumCompat.bypassEntityCulling();
    }

    public static Set<String> presentMods() {
        scan();
        return Collections.unmodifiableSet(PRESENT);
    }

    public static String version(String modId) {
        scan();
        return VERSIONS.getOrDefault(modId, "?");
    }

    public static boolean has(String modId) {
        scan();
        return PRESENT.contains(modId);
    }

    // ------------------------------------------------------------------ отчёты

    public static List<String> findings() {
        scan();
        List<String> out = new ArrayList<>();
        for (String id : PRESENT) {
            out.add(KNOWN.getOrDefault(id, id) + " " + version(id));
        }
        out.addAll(plan().report);
        if (client) {
            out.add(SodiumCompat.describe());
            out.add(IrisCompat.describe());
            out.add(EntityCullingCompat.describe());
        }
        out.add(LithiumCompat.describe());
        out.add(ServerCoreCompat.describe());
        return out;
    }

    /** Строка окружения для индикатора: «Sodium+Iris+Lithium». */
    public static String environmentLine() {
        scan();
        List<String> names = new ArrayList<>();
        for (String id : new String[]{"sodium", "iris", "lithium", "entityculling", "c2me", "servercore"}) {
            if (PRESENT.contains(id)) names.add(KNOWN.getOrDefault(id, id));
        }
        return names.isEmpty() ? "чистая сборка" : String.join("+", names);
    }

    public static void logReport() {
        scan();
        replan();
        HyperionConfig cfg = HyperionConfig.get();
        Hyperion.LOGGER.info("[Hyperion][compat] окружение: {}", environmentLine());
        for (String line : plan().report) {
            Hyperion.LOGGER.info("[Hyperion][compat] {}", line);
        }
        if (cfg.verboseLogging) {
            Hyperion.LOGGER.info("[Hyperion][compat] {}", SodiumCompat.describe());
            Hyperion.LOGGER.info("[Hyperion][compat] {}", LithiumCompat.describe());
            Hyperion.LOGGER.info("[Hyperion][compat] {}", ServerCoreCompat.describe());
        }
        for (Feature feature : Feature.values()) {
            if (HyperionStatus.state(feature) == State.YIELDED) {
                Hyperion.LOGGER.warn("[Hyperion][compat] {} уступлено: {}", feature.id, HyperionStatus.reason(feature));
            }
        }
    }

    /** Восстановить чужие настройки при выходе. */
    public static void shutdown() {
        EntityCullingCompat.restore();
        SodiumCompat.requestBypass(false);
    }
}
