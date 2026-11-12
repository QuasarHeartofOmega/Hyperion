package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Слой совместимости.
 *
 * <p>Вместо «чёрного списка модов» из спеки — карта правил: какой мод пересекается
 * с какой подсистемой Hyperion и что мы делаем (отключаем свою подсистему, предупреждаем
 * или ничего). Так мод не ломает чужие оптимизации и не патчит то, что уже пропатчено.
 */
public final class HyperionCompat {

    public static final String FEATURE_CHUNK_IO = "chunk-io";
    public static final String FEATURE_CULLING = "entity-culling";
    public static final String FEATURE_ENTITY_AI = "entity-ai";

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();
    private static final Set<String> DISABLED = new HashSet<>();
    private static final List<String> FINDINGS = new ArrayList<>();
    private static volatile boolean scanned;

    static {
        // Полная замена системы чанков — наш read-ahead не нужен и может конфликтовать.
        RULES.put("c2me", new Rule(FEATURE_CHUNK_IO, Severity.DISABLE,
                "C2ME полностью переписывает I/O чанков; read-ahead Hyperion отключён"));
        RULES.put("noisium", new Rule(FEATURE_CHUNK_IO, Severity.INFO,
                "Noisium ускоряет генерацию мира; конфликта нет"));
        // Culling уже реализован другим модом.
        RULES.put("entityculling", new Rule(FEATURE_CULLING, Severity.DISABLE,
                "EntityCulling уже делает occlusion-culling; наш отключён, чтобы не считать дважды"));
        RULES.put("moreculling", new Rule(FEATURE_CULLING, Severity.INFO,
                "MoreCulling работает с блоками, не с сущностями; конфликта нет"));
        // Lithium тоже трогает ИИ/GoalSelector.
        RULES.put("lithium", new Rule(FEATURE_ENTITY_AI, Severity.WARN,
                "Lithium оптимизирует GoalSelector; throttling Hyperion работает поверх, но проверьте поведение мобов"));
        RULES.put("servercore", new Rule(FEATURE_ENTITY_AI, Severity.WARN,
                "ServerCore динамически троттлит сущности; возможен двойной throttling"));
        // Рендереры.
        RULES.put("sodium", new Rule(null, Severity.INFO,
                "Sodium совместим: Hyperion не трогает рендер чанков"));
        RULES.put("iris", new Rule(null, Severity.INFO,
                "Iris совместим: шейдеры OpenGL остаются нетронутыми"));
        RULES.put("vulkanmod", new Rule(null, Severity.WARN,
                "VulkanMod заменяет OpenGL-бэкенд; culling сущностей может работать некорректно"));
        RULES.put("indium", new Rule(null, Severity.INFO, "Indium совместим"));
        RULES.put("ferritecore", new Rule(null, Severity.INFO,
                "FerriteCore совместим: наши кэши не пересекаются с его дедупликацией"));
    }

    private HyperionCompat() {
    }

    public static void scan() {
        if (scanned) return;
        synchronized (HyperionCompat.class) {
            if (scanned) return;
            FabricLoader loader = FabricLoader.getInstance();
            for (ModContainer mod : loader.getAllMods()) {
                String id = mod.getMetadata().getId();
                Rule rule = RULES.get(id);
                if (rule == null) continue;
                String version = mod.getMetadata().getVersion().getFriendlyString();
                FINDINGS.add(switch (rule.severity) {
                    case DISABLE -> "ОТКЛЮЧЕНО " + id + " " + version + ": " + rule.message;
                    case WARN -> "ВНИМАНИЕ " + id + " " + version + ": " + rule.message;
                    case INFO -> "OK " + id + " " + version + ": " + rule.message;
                });
                if (rule.severity == Severity.DISABLE && rule.feature != null) {
                    DISABLED.add(rule.feature);
                }
            }
            scanned = true;
        }
    }

    public static boolean isDisabled(String feature) {
        scan();
        return DISABLED.contains(feature);
    }

    public static List<String> findings() {
        scan();
        return Collections.unmodifiableList(FINDINGS);
    }

    public static void logReport() {
        scan();
        if (FINDINGS.isEmpty()) {
            if (HyperionConfig.get().verboseLogging) {
                Hyperion.LOGGER.info("[Hyperion] Известных конфликтующих модов не найдено.");
            }
            return;
        }
        for (String finding : FINDINGS) {
            if (finding.startsWith("OK")) {
                if (HyperionConfig.get().verboseLogging) Hyperion.LOGGER.info("[Hyperion][compat] {}", finding);
            } else {
                Hyperion.LOGGER.warn("[Hyperion][compat] {}", finding);
            }
        }
    }

    private enum Severity { INFO, WARN, DISABLE }

    private record Rule(String feature, Severity severity, String message) {
    }
}
