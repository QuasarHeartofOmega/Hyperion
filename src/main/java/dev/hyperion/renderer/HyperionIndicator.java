package dev.hyperion.renderer;

import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.compat.IrisCompat;
import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Индикатор работы Hyperion.
 *
 * <p>Что показывает:
 * <ul>
 *   <li><b>светодиод</b> — цвет по агрегированному состоянию подсистем:
 *       зелёный = всё работает, голубой = пауза (шейдеры Iris), жёлтый = часть
 *       работы уступлена другому моду, красный = предохранитель отключил подсистему,
 *       серый = мод выключен;</li>
 *   <li><b>пульс</b> — «дышит» только когда подсистемы реально что-то сделали
 *       за последние 700 мс (не декоративная анимация);</li>
 *   <li><b>строку состояния</b> — «активен 4/5», «пауза», «уступил часть»;</li>
 *   <li><b>метрики</b> в режиме COMPACT — сколько сущностей отсечено, сколько
 *       тиков ИИ сэкономлено, hit-rate кэша чанков.</li>
 * </ul>
 *
 * <p>Рисуется только через {@link DrawContext} (ванильные RenderLayer'ы), поэтому
 * корректно работает и с Sodium, и с Iris, и с батчингом HUD от ImmediatelyFast:
 * мы не трогаем GL-состояние вручную и не создаём своих шейдеров/текстур.
 */
public final class HyperionIndicator {

    /** Режим индикатора. */
    public enum Mode {
        OFF, MINIMAL, COMPACT, FULL;

        public static Mode parse(String value, Mode fallback) {
            if (value == null) return fallback;
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) return mode;
            }
            return fallback;
        }

        public Mode next() {
            return switch (this) {
                case OFF -> MINIMAL;
                case MINIMAL -> COMPACT;
                case COMPACT -> FULL;
                case FULL -> OFF;
            };
        }
    }

    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFFA8B0B8;
    private static final int BACKGROUND = 0xA0101418;

    private static long lastBuild;
    private static List<String> lines = List.of();
    private static String headline = "";
    private static int lastColor = 0xFF8A8F98;

    private HyperionIndicator() {
    }

    public static Mode mode() {
        return Mode.parse(HyperionConfig.get().indicatorMode, Mode.COMPACT);
    }

    /**
     * Точка входа из InGameHud. Никаких исключений наружу: индикатор не имеет права
     * уронить кадр, даже если что-то пошло не так.
     */
    public static void render(DrawContext context, TextRenderer textRenderer, Mode mode) {
        if (mode == Mode.OFF) return;
        try {
            if (mode == Mode.FULL) {
                HyperionHud.render(context, textRenderer);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastBuild > 250 || lines.isEmpty()) {
                lastBuild = now;
                HyperionStatus.sample();
                headline = HyperionStatus.headline();
                lastColor = HyperionStatus.color();
                lines = mode == Mode.COMPACT ? compactLines() : List.of();
            }

            String title = "Hyperion " + headline;
            int textWidth = textRenderer.getWidth(title);
            for (String line : lines) {
                textWidth = Math.max(textWidth, textRenderer.getWidth(line));
            }
            int lineCount = 1 + lines.size();
            IndicatorLayout.Corner corner = IndicatorLayout.Corner.parse(
                    HyperionConfig.get().indicatorCorner, IndicatorLayout.Corner.TOP_LEFT);
            IndicatorLayout.Rect panel = IndicatorLayout.panel(corner,
                    context.getScaledWindowWidth(), context.getScaledWindowHeight(),
                    textWidth + 8, lineCount,
                    HyperionConfig.get().indicatorX, HyperionConfig.get().indicatorY);

            context.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), BACKGROUND);
            // Тонкая полоса состояния слева — читается даже без текста.
            context.fill(panel.x(), panel.y(), panel.x() + 1, panel.bottom(), withAlpha(lastColor, 0xC0));

            int led = ledColor(lastColor);
            int lx = panel.x() + 4;
            int ly = panel.y() + 4;
            context.fill(lx, ly, lx + 5, ly + 5, led);
            context.fill(lx + 1, ly + 1, lx + 4, ly + 4, withAlpha(0xFFFFFFFF, 0x50));

            int tx = lx + 9;
            int ty = panel.y() + 3;
            context.drawTextWithShadow(textRenderer, title, tx, ty, TEXT);
            for (int i = 0; i < lines.size(); i++) {
                context.drawTextWithShadow(textRenderer, lines.get(i), panel.x() + 4, ty + 10 * (i + 1), DIM);
            }
        } catch (Throwable t) {
            // Один раз в лог и больше не рисуем в этом кадре.
            Hyperion.LOGGER.debug("[Hyperion] индикатор пропущен: {}", String.valueOf(t));
        }
    }

    private static List<String> compactLines() {
        List<String> out = new ArrayList<>(3);
        StringBuilder metrics = new StringBuilder();
        if (HyperionStatus.active(Feature.ENTITY_CULLING)) {
            metrics.append("cull ").append(HyperionMetrics.LAST_CULLED_ENTITIES);
        } else if (HyperionStatus.state(Feature.ENTITY_CULLING) == HyperionStatus.State.PAUSED) {
            metrics.append("cull пауза");
        } else if (HyperionStatus.state(Feature.ENTITY_CULLING) == HyperionStatus.State.YIELDED) {
            metrics.append("cull -> ").append(ownerOr(Feature.ENTITY_CULLING, "мод"));
        }
        if (HyperionStatus.active(Feature.ENTITY_AI)) {
            if (metrics.length() > 0) metrics.append(" | ");
            metrics.append(String.format("ИИ -%.0f%%", HyperionMetrics.aiSavedPercent()));
        }
        if (HyperionStatus.active(Feature.CHUNK_IO)) {
            if (metrics.length() > 0) metrics.append(" | ");
            metrics.append(String.format("чанки %.0f%%", HyperionMetrics.hitRate()));
        }
        if (metrics.length() > 0) out.add(metrics.toString());

        String env = HyperionCompat.environmentLine();
        String shader = IrisCompat.shadersInUse() ? " | шейдеры" : "";
        out.add(env + shader);

        // Если что-то пошло не так — показываем это прямо в индикаторе.
        for (Feature feature : Feature.values()) {
            if (HyperionStatus.state(feature) == HyperionStatus.State.ERROR) {
                out.add("! " + feature.label + ": " + HyperionStatus.reason(feature));
                break;
            }
        }
        return out;
    }

    private static String ownerOr(Feature feature, String fallback) {
        String owner = HyperionStatus.owner(feature);
        return owner.isEmpty() ? fallback : owner;
    }

    private static int ledColor(int base) {
        double pulse = HyperionConfig.get().indicatorPulse ? HyperionStatus.pulse() : 1.0;
        int alpha = (int) (0x60 + pulse * (0xFF - 0x60));
        return withAlpha(base, Math.min(0xFF, Math.max(0x40, alpha)));
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}
