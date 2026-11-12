package dev.hyperion.compat;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Реестр модов, о которых Hyperion что-то знает.
 *
 * <p>Здесь собрано ровно одно знание: <b>id мода</b> (как его видит FabricLoader),
 * <b>человекочитаемое имя</b>, <b>область работы</b> и, если она проверена по исходникам,
 * <b>класс-маркер</b>. Класс-маркер нужен для сборок, где мод вложен в другой jar и
 * его id в списке модов отсутствует.
 *
 * <p>Правило ведения реестра: класс-маркер добавляется только после проверки пути в
 * исходниках мода (см. COMPAT.md, колонка «проверено»). Если путь не проверен —
 * поле пустое, и мод определяется только по id. Врать про «поддерживается» хуже,
 * чем честно определить мод по id.
 *
 * <p>Класс не загружает чужие классы сам: {@link #probe(String)} делает это через
 * {@link CompatBridge} и только в рантайме (никогда из mixin-плагина).
 */
public final class CompatMods {

    /** Чем занимается чужой мод — определяет, пересекается ли он с Hyperion. */
    public enum Kind {
        /** Меняет графический бэкенд или конвейер кадра (VulkanMod, OptiFine, Nvidium). */
        RENDER_BACKEND,
        /** Шейдеры: управляет проходами кадра (Iris). */
        SHADERS,
        /** Дорисовывает мир за пределами ванильной дальности (Distant Horizons, Bobby). */
        LOD,
        /** Переписывает загрузку/хранение чанков (C2ME, DashLoader). */
        CHUNK_IO,
        /** Троттлит сущностей/ИИ (ServerCore, Lithium). */
        ENTITY_AI,
        /** Отсекает невидимое (Sodium entity-culling, EntityCulling, MoreCulling). */
        CULLING,
        /** Экономит память (FerriteCore, ModernFix). */
        MEMORY,
        /** Батчит/переносит отрисовку HUD (ImmediatelyFast, Exordium). */
        HUD,
        /** Сеть и списки игроков (Krypton, VMP). */
        NETWORK,
        /** Генерация мира, свет, редстоун (Starlight, Noisium, Alternate Current). */
        WORLDGEN,
        /** Только настройки/интерфейс (Sodium Extra, Reese's). */
        OPTIONS,
        /** Потоки и приоритеты (ThreadTweak). */
        THREADS
    }

    /** Запись реестра. {@code markers} — проверенные классы-маркеры (может быть пусто). */
    public record Mod(String id, String name, Kind kind, String... markers) {
        public boolean probeable() {
            return markers.length > 0;
        }
    }

    private static final Map<String, Mod> REGISTRY = new LinkedHashMap<>();

    static {
        // --- рендер и шейдеры -------------------------------------------------
        add("sodium", "Sodium", Kind.CULLING,
                "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
                "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
        add("iris", "Iris", Kind.SHADERS,
                "net.irisshaders.iris.api.v0.IrisApi",
                "net.coderbot.iris.api.v0.IrisApi");
        add("vulkanmod", "VulkanMod", Kind.RENDER_BACKEND, "net.vulkanmod.Initializer");
        add("nvidium", "Nvidium", Kind.RENDER_BACKEND, "me.cortex.nvidium.Nvidium");
        add("optifabric", "OptiFabric/OptiFine", Kind.RENDER_BACKEND, "net.optifine.Config");
        add("indium", "Indium", Kind.RENDER_BACKEND, "link.infra.indium.Indium");
        add("distanthorizons", "Distant Horizons", Kind.LOD, "com.seibel.distanthorizons.api.DhApi");
        add("bobby", "Bobby", Kind.LOD, "de.johni0702.minecraft.bobby.Bobby");

        // --- отсечение --------------------------------------------------------
        add("entityculling", "EntityCulling", Kind.CULLING, "dev.tr7zw.entityculling.EntityCullingModBase");
        add("moreculling", "MoreCulling", Kind.CULLING, "ca.fxco.moreculling.MoreCulling");

        // --- чанки и мир ------------------------------------------------------
        add("c2me", "C2ME", Kind.CHUNK_IO, "com.ishland.c2me.base.common.GlobalExecutors");
        add("dashloader", "DashLoader", Kind.CHUNK_IO);
        add("starlight", "Starlight", Kind.WORLDGEN);
        add("noisium", "Noisium", Kind.WORLDGEN);
        add("alternate-current", "Alternate Current", Kind.WORLDGEN);

        // --- сущности и ИИ ----------------------------------------------------
        add("lithium", "Lithium", Kind.ENTITY_AI,
                "net.caffeinemc.mods.lithium.common.LithiumMod",
                "me.jellysquid.mods.lithium.common.LithiumMod");
        add("servercore", "ServerCore", Kind.ENTITY_AI,
                "me.wesley1808.servercore.common.interfaces.activation_range.ActivationEntity",
                "me.wesley1808.servercore.common.config.Config");

        // --- память, HUD, сеть, прочее ---------------------------------------
        add("ferritecore", "FerriteCore", Kind.MEMORY);
        add("modernfix", "ModernFix", Kind.MEMORY);
        add("immediatelyfast", "ImmediatelyFast", Kind.HUD, "net.raphimc.immediatelyfast.ImmediatelyFast");
        add("exordium", "Exordium", Kind.HUD, "dev.tr7zw.exordium.ExordiumModBase");
        add("krypton", "Krypton", Kind.NETWORK);
        add("vmp", "Very Many Players", Kind.NETWORK);
        add("threadtweak", "ThreadTweak", Kind.THREADS);
        add("sodium-extra", "Sodium Extra", Kind.OPTIONS);
        add("reeses-sodium-options", "Reese's Sodium Options", Kind.OPTIONS);
    }

    private CompatMods() {
    }

    private static void add(String id, String name, Kind kind, String... markers) {
        REGISTRY.put(id, new Mod(id, name, kind, markers));
    }

    public static Collection<Mod> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    public static Mod get(String id) {
        return REGISTRY.get(id);
    }

    public static boolean known(String id) {
        return REGISTRY.containsKey(id);
    }

    public static String display(String id) {
        Mod mod = REGISTRY.get(id);
        return mod == null ? id : mod.name();
    }

    public static Kind kind(String id) {
        Mod mod = REGISTRY.get(id);
        return mod == null ? null : mod.kind();
    }

    /**
     * Проверка по классам-маркерам. Только рантайм: загрузка чужого класса во время
     * трансформации байткода может устроить циклическую инициализацию.
     */
    public static boolean probe(String id) {
        Mod mod = REGISTRY.get(id);
        if (mod == null || !mod.probeable()) return false;
        return CompatBridge.exists(mod.markers());
    }
}
