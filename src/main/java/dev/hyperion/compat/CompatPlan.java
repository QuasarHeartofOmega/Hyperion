package dev.hyperion.compat;

import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.core.HyperionStatus.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Чистая логика «кто главный» — без Minecraft и без рефлексии, поэтому проверяется
 * автономными тестами. На вход — какие моды найдены и что включено в конфиге,
 * на выход — по каждому признаку: кто владеет работой, что сделать с чужим модом
 * и что показать пользователю.
 *
 * <p>Правила (в порядке приоритета):
 * <ol>
 *   <li>выключено в конфиге Hyperion → OFF, никого не трогаем;</li>
 *   <li>полная замена подсистемы другим модом (C2ME для I/O, VulkanMod для рендера)
 *       → YIELD: переопределить это «где возможно» невозможно, лезть туда = краш;</li>
 *   <li>режим HYPERION + пересекающийся мод, у которого есть точка переопределения
 *       → ACTIVE + действие «взять работу себе» (Sodium.isEntityVisible,
 *           EntityCulling.CullTask.disableEntityCulling);</li>
 *   <li>режим COOPERATE → ACTIVE без переопределения (работаем поверх, вдвойне не считаем);</li>
 *   <li>режим YIELD → уступаем везде, где есть пересечение.</li>
 * </ol>
 */
public final class CompatPlan {

    /** Глобальная политика приоритета. */
    public enum Mode {
        /** Hyperion главный: перекрывающиеся функции чужих модов берутся на себя. */
        HYPERION,
        /** Работаем рядом, чужие оптимизации не трогаем. */
        COOPERATE,
        /** Hyperion уступает всё, что кто-то уже делает. */
        YIELD;

        public static Mode parse(String value, Mode fallback) {
            if (value == null) return fallback;
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) return mode;
            }
            return fallback;
        }
    }

    /** Что сделать с чужим модом, чтобы не было двойной работы. */
    public enum Action {
        /** Ничего. */
        NONE,
        /** Заставить SodiumWorldRenderer.isEntityVisible всегда возвращать true (наш миксин). */
        BYPASS_SODIUM_ENTITY_CULLING,
        /** Выставить CullTask.disableEntityCulling=true у мода EntityCulling (штатный флаг мода). */
        DISABLE_ENTITYCULLING_MOD,
        /** Не троттлить мобов, которых уже пометил ServerCore (проверка в рантайме). */
        DEFER_TO_SERVERCORE,
        /** Не отсекать сущности в shadow pass Iris (иначе у мобов пропадают тени). */
        RESPECT_IRIS_SHADOW_PASS
    }

    /** Решение по одной подсистеме. */
    public record Decision(Feature feature, State state, String owner, String note, Set<Action> actions) {
        public boolean hyperionWorks() {
            return state == State.ACTIVE || state == State.BOOT;
        }
    }

    /** Вход планировщика: только примитивы и строки — удобно тестировать. */
    public static final class Input {
        public final Set<String> mods = new LinkedHashSet<>();
        public Mode mode = Mode.HYPERION;

        public boolean chunkPrefetch = true;
        public boolean entityThrottling = true;
        public boolean entityCulling = true;
        public boolean blockStateCache = true;
        public boolean blockPosPool = true;

        public boolean overrideSodiumEntityCulling = true;
        public boolean overrideEntityCullingMod = true;

        /** Фактически включён ли entity-culling у Sodium (читается рефлекссией). */
        public boolean sodiumEntityCullingActive = true;
        /** Фактически включён ли activation range у ServerCore. */
        public boolean serverCoreActivationRange = true;

        /** Политика culling при шейдерах Iris: OFF | SHADOW_SAFE | FORCE. */
        public String irisCulling = "SHADOW_SAFE";
        /** Включён ли шейдерпак прямо сейчас (на этапе миксинов всегда false). */
        public boolean irisShadersInUse;

        public Input mod(String id) {
            mods.add(id);
            return this;
        }

        public boolean has(String id) {
            return mods.contains(id);
        }

        /** Кто-то дорисовывает мир за ванильной дальностью (Distant Horizons, Bobby). */
        public boolean hasLodTerrain() {
            return has("distanthorizons") || has("bobby");
        }

        /** Кто-то заменил графический бэкенд или переписал рендер сущностей. */
        public boolean hasRenderBackendConflict() {
            return has("vulkanmod") || has("optifabric");
        }

        public IrisCulling shaderCulling() {
            return IrisCulling.parse(irisCulling, IrisCulling.SHADOW_SAFE);
        }
    }

    /** Дублирует {@link IrisCompat.ShaderCulling}, чтобы планировщик остался без рефлексии. */
    public enum IrisCulling {
        OFF, SHADOW_SAFE, FORCE;

        public static IrisCulling parse(String value, IrisCulling fallback) {
            if (value == null) return fallback;
            for (IrisCulling mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) return mode;
            }
            return fallback;
        }
    }

    public static final class Result {
        public final Map<Feature, Decision> decisions = new EnumMap<>(Feature.class);
        public final List<String> report = new ArrayList<>();
        public final Set<Action> actions = new LinkedHashSet<>();

        public Decision get(Feature feature) {
            return decisions.get(feature);
        }

        public boolean works(Feature feature) {
            Decision d = decisions.get(feature);
            return d != null && d.hyperionWorks();
        }

        public boolean has(Action action) {
            return actions.contains(action);
        }
    }

    private CompatPlan() {
    }

    public static Result plan(Input in) {
        Result out = new Result();

        // ---------------------------------------------------------------- чанки
        if (!in.chunkPrefetch) {
            put(out, Feature.CHUNK_IO, State.OFF, "", "выключено в конфиге hyperion.json");
        } else if (in.has("c2me")) {
            put(out, Feature.CHUNK_IO, State.YIELDED, "c2me",
                    "C2ME полностью заменяет I/O чанков — read-ahead Hyperion уступает (переопределение невозможно)");
        } else if (in.has("dashloader")) {
            put(out, Feature.CHUNK_IO, State.YIELDED, "dashloader",
                    "DashLoader кэширует загрузку мира целиком — read-ahead Hyperion уступает");
        } else {
            List<String> notes = new ArrayList<>();
            notes.add(in.has("lithium")
                    ? "работает поверх Lithium (Lithium не трогает RegionFile)"
                    : "асинхронный read-ahead NBT");
            if (in.has("noisium")) notes.add("Noisium оптимизирует генерацию, а не чтение с диска");
            if (in.has("starlight")) notes.add("Starlight переписал свет, формат региона не тронут");
            put(out, Feature.CHUNK_IO, State.ACTIVE, "", String.join("; ", notes));
        }

        // ------------------------------------------------------------------ ИИ
        if (!in.entityThrottling) {
            put(out, Feature.ENTITY_AI, State.OFF, "", "выключено в конфиге hyperion.json");
        } else if (in.has("servercore") && in.serverCoreActivationRange) {
            if (in.mode == Mode.YIELD) {
                put(out, Feature.ENTITY_AI, State.YIELDED, "servercore",
                        "ServerCore activation range уже троттлит мобов — Hyperion уступает");
            } else {
                // Оба работают, но по разным мобам: тех, кого ServerCore пометил
                // неактивными, Hyperion не трогает — двойного throttling нет.
                put(out, Feature.ENTITY_AI, State.ACTIVE, "servercore",
                        "Hyperion троттлит только мобов вне activation range ServerCore",
                        Action.DEFER_TO_SERVERCORE);
            }
        } else {
            List<String> notes = new ArrayList<>();
            notes.add(in.has("lithium")
                    ? "работает поверх Lithium (оптимизации GoalSelector сохранены)"
                    : "троттлинг ИИ далёких мобов");
            if (in.has("vmp")) notes.add("VMP работает со списками игроков и чанков, ИИ не трогает");
            if (in.has("threadtweak")) notes.add("ThreadTweak меняет приоритеты потоков — пулы Hyperion это учитывают");
            put(out, Feature.ENTITY_AI, State.ACTIVE, "", String.join("; ", notes));
        }

        // -------------------------------------------------------------- culling
        if (!in.entityCulling) {
            put(out, Feature.ENTITY_CULLING, State.OFF, "", "выключено в конфиге hyperion.json");
        } else if (in.has("vulkanmod")) {
            put(out, Feature.ENTITY_CULLING, State.YIELDED, "vulkanmod",
                    "VulkanMod меняет графический бэкенд — culling Hyperion уступает");
        } else if (in.has("optifabric")) {
            put(out, Feature.ENTITY_CULLING, State.YIELDED, "optifabric",
                    "OptiFine переписывает рендер сущностей и шейдеры — culling Hyperion уступает");
        } else if (in.mode == Mode.YIELD && (in.has("entityculling") || (in.has("sodium") && in.sodiumEntityCullingActive))) {
            String owner = in.has("entityculling") ? "entityculling" : "sodium";
            put(out, Feature.ENTITY_CULLING, State.YIELDED, owner,
                    "режим YIELD: culling сущностей отдан " + owner);
        } else {
            Set<Action> actions = new LinkedHashSet<>();
            List<String> notes = new ArrayList<>();
            String owner = "";
            if (in.has("entityculling")) {
                if (in.mode == Mode.HYPERION && in.overrideEntityCullingMod) {
                    actions.add(Action.DISABLE_ENTITYCULLING_MOD);
                    notes.add("culling сущностей взят у EntityCulling (block-entity culling остаётся ему)");
                    owner = "hyperion";
                } else {
                    notes.add("EntityCulling рядом: решения объединяются (лишней работы нет)");
                }
            }
            if (in.has("sodium") && in.sodiumEntityCullingActive) {
                if (in.mode == Mode.HYPERION && in.overrideSodiumEntityCulling) {
                    actions.add(Action.BYPASS_SODIUM_ENTITY_CULLING);
                    notes.add("Sodium.isEntityVisible переопределён — видимость решает Hyperion");
                    owner = "hyperion";
                } else {
                    notes.add("Sodium entity-culling работает первым, Hyperion только дожимает");
                }
            }
            State state = State.ACTIVE;
            if (in.has("iris")) {
                IrisCulling shaders = in.shaderCulling();
                if (shaders != IrisCulling.FORCE) {
                    actions.add(Action.RESPECT_IRIS_SHADOW_PASS);
                }
                if (in.irisShadersInUse && shaders == IrisCulling.OFF) {
                    state = State.PAUSED;
                    owner = "iris";
                    notes.add("шейдеры Iris активны, irisCulling=OFF — culling на паузе");
                } else {
                    notes.add(switch (shaders) {
                        case OFF -> "Iris: culling выключится, пока активен шейдерпак";
                        case SHADOW_SAFE -> "Iris: culling уважает шейдеры и shadow pass";
                        case FORCE -> "Iris: culling принудительно (возможны артефакты теней)";
                    });
                }
            }
            if (in.has("moreculling")) {
                notes.add("MoreCulling отсекает блок-сущности и предметы — области работы не пересекаются");
            }
            if (in.has("nvidium")) {
                notes.add("Nvidium ускоряет рендер чанков, рендер сущностей не трогает");
            }
            if (in.hasLodTerrain()) {
                notes.add("Distant Horizons/Bobby: за ванильной дальностью прорисовки culling не применяется");
            }
            if (notes.isEmpty()) notes.add("асинхронный occlusion-culling сущностей");
            put(out, Feature.ENTITY_CULLING, state, owner, String.join("; ", notes), actions);
        }

        // --------------------------------------------------------------- память
        if (!in.blockStateCache) {
            put(out, Feature.BLOCKSTATE, State.OFF, "", "выключено в конфиге hyperion.json");
        } else {
            String note = (in.has("ferritecore") || in.has("modernfix"))
                    ? "кэш свойств blockstate (не пересекается с дедупликацией FerriteCore/ModernFix)"
                    : "плоский кэш свойств blockstate";
            put(out, Feature.BLOCKSTATE, State.ACTIVE, "", note);
        }
        if (!in.blockPosPool) {
            put(out, Feature.BLOCKPOS, State.OFF, "", "выключено в конфиге hyperion.json");
        } else {
            String note = in.has("lithium")
                    ? "пул курсоров BlockPos (Lithium пулит не курсоры, а коллекции сущностей)"
                    : "пул курсоров BlockPos";
            put(out, Feature.BLOCKPOS, State.ACTIVE, "", note);
        }

        // -------------------------------------------------------------- отчёт
        out.report.add("режим приоритета: " + in.mode.name());
        for (Decision d : out.decisions.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append(d.feature().id).append(": ").append(stateWord(d.state()));
            if (!d.owner().isEmpty()) sb.append(" [").append(d.owner()).append(']');
            if (!d.note().isEmpty()) sb.append(" — ").append(d.note());
            out.report.add(sb.toString());
        }
        for (Action action : out.actions) {
            out.report.add("переопределение: " + actionWord(action));
        }
        return out;
    }

    private static String stateWord(State state) {
        return switch (state) {
            case ACTIVE -> "Hyperion";
            case BOOT -> "Hyperion (запуск)";
            case PAUSED -> "пауза";
            case YIELDED -> "уступлено";
            case OFF -> "выключено";
            case ERROR -> "сбой";
        };
    }

    private static String actionWord(Action action) {
        return switch (action) {
            case NONE -> "нет";
            case BYPASS_SODIUM_ENTITY_CULLING -> "SodiumWorldRenderer.isEntityVisible -> решение Hyperion";
            case DISABLE_ENTITYCULLING_MOD -> "EntityCulling.CullTask.disableEntityCulling = true";
            case DEFER_TO_SERVERCORE -> "ServerCore activation range учитывается (без двойного троттлинга)";
            case RESPECT_IRIS_SHADOW_PASS -> "Iris shadow pass: сущности рисуются всегда (тени не пропадают)";
        };
    }

    private static void put(Result out, Feature feature, State state, String owner, String note, Action... actions) {
        Set<Action> set = new LinkedHashSet<>();
        Collections.addAll(set, actions);
        put(out, feature, state, owner, note, set);
    }

    /** Перегрузка для уже собранного набора действий (varargs не принимает Set). */
    private static void put(Result out, Feature feature, State state, String owner, String note, Set<Action> actions) {
        Set<Action> set = new LinkedHashSet<>(actions);
        out.decisions.put(feature, new Decision(feature, state, owner, note, set));
        out.actions.addAll(set);
    }
}
