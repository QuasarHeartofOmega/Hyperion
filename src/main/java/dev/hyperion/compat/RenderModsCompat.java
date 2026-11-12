package dev.hyperion.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Моды, которые меняют <b>сам рендер</b>, а не отдельную оптимизацию.
 *
 * <p>Их нельзя «переопределить»: у VulkanMod другой графический бэкенд, у OptiFine
 * переписан рендер сущностей и свои шейдеры, Nvidium рисует чанки mesh-шейдерами.
 * Здесь собраны честные ответы на два вопроса:
 *
 * <ol>
 *   <li><b>Можно ли вообще работать?</b> {@link #cullingYielded()} — если бэкенд
 *       заменён, подсистема culling уступает целиком (решение принимает
 *       {@link CompatPlan}, тут только справка для отчёта).</li>
 *   <li><b>Где границы применимости?</b> {@link #lodTerrainPresent()} — Distant
 *       Horizons и Bobby дорисовывают мир за ванильной дальностью. Их ландшафт не входит
 *       в снапшоты Hyperion, поэтому за пределами ванильной дальности прорисовки
 *       culling не применяется: лучше нарисовать лишнего моба, чем спрятать
 *       видимого на фоне LOD-ландшафта.</li>
 * </ol>
 *
 * <p>Indium, MoreCulling, ImmediatelyFast и Exordium перечислены отдельно: они
 * часто стоят в сборке рядом, но с Hyperion не пересекаются (FRAPI для Sodium,
 * отсечение блок-сущностей/предметов, батчинг HUD). Знать о них нужно, чтобы
 * {@code /hyperion compat} не выглядел так, будто мод их «не заметил».
 */
public final class RenderModsCompat {

    private RenderModsCompat() {
    }

    /** Заменён графический бэкенд или переписан рендер сущностей. */
    public static boolean backendReplaced() {
        return HyperionCompat.has("vulkanmod") || HyperionCompat.has("optifabric");
    }

    /** Culling Hyperion уступает целиком (только справка: решает CompatPlan). */
    public static boolean cullingYielded() {
        return backendReplaced();
    }

    /** Мир дорисовывается за ванильной дальностью (Distant Horizons / Bobby). */
    public static boolean lodTerrainPresent() {
        return HyperionCompat.hasLodTerrain();
    }

    /** Nvidium рисует чанки своими шейдерами — на сущностей не влияет. */
    public static boolean nvidium() {
        return HyperionCompat.has("nvidium");
    }

    /** Indium добавляет Sodium поддержку Fabric Rendering API — пересечений нет. */
    public static boolean indium() {
        return HyperionCompat.has("indium");
    }

    public static String describe() {
        List<String> parts = new ArrayList<>();
        if (HyperionCompat.has("vulkanmod")) {
            parts.add("VulkanMod: бэкенд Vulkan, culling сущностей уступлен");
        }
        if (HyperionCompat.has("optifabric")) {
            parts.add("OptiFine/OptiFabric: свой рендер сущностей и шейдеры, culling уступлен");
        }
        if (nvidium()) {
            parts.add("Nvidium: рендер чанков не трогаем, сущности совместимы");
        }
        if (indium()) {
            parts.add("Indium: FRAPI поверх Sodium, пересечений нет");
        }
        if (lodTerrainPresent()) {
            List<String> lod = new ArrayList<>();
            if (HyperionCompat.has("distanthorizons")) lod.add("Distant Horizons");
            if (HyperionCompat.has("bobby")) lod.add("Bobby");
            parts.add(String.join("+", lod) + ": culling только внутри ванильной дальности прорисовки");
        }
        if (HyperionCompat.has("moreculling")) {
            parts.add("MoreCulling: блок-сущности и предметы — его работа, сущности — наша");
        }
        if (HyperionCompat.has("immediatelyfast")) {
            parts.add("ImmediatelyFast: HUD Hyperion рисуется через DrawContext и батчится вместе с остальным");
        }
        if (HyperionCompat.has("exordium")) {
            parts.add("Exordium: HUD рисуется реже — индикатор Hyperion это переживает (без своего GL-состояния)");
        }
        if (parts.isEmpty()) return "Модов, меняющих рендер, не найдено";
        return String.join("; ", parts);
    }
}
