package dev.hyperion.renderer;

import dev.hyperion.chunk.HyperionChunkSystem;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.entity.HyperionEntitySystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** HUD с метриками Hyperion (включается /hyperion hud или конфигом). */
public final class HyperionHud {

    private static final int COLOR = 0xFFE0E0E0;
    private static final int ACCENT = 0xFF7FD4FF;
    private static long lastUpdate;
    private static List<String> lines = List.of();

    private HyperionHud() {
    }

    public static void render(DrawContext context, TextRenderer textRenderer) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate > 250) {
            lastUpdate = now;
            lines = build();
        }
        int x = 4;
        int y = 4;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, textRenderer.getWidth(line));
        }
        context.fill(x - 2, y - 2, x + width + 2, y + lines.size() * 10 + 1, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            context.drawTextWithShadow(textRenderer, lines.get(i), x, y + i * 10, i == 0 ? ACCENT : COLOR);
        }
    }

    private static List<String> build() {
        List<String> out = new ArrayList<>(8);
        MinecraftClient client = MinecraftClient.getInstance();
        HyperionConfig cfg = HyperionConfig.get();
        HyperionMetrics.Percentiles frame = HyperionMetrics.framePercentiles();

        out.add("Hyperion 1.0.0");
        out.add(String.format("FPS %d | frame p50 %.2fms p95 %.2fms", client.getCurrentFps(), frame.p50(), frame.p95()));
        if (cfg.entityCulling) {
            out.add(String.format("culling: скрыто %d | очередь %d | снапшотов %d",
                    HyperionMetrics.LAST_CULLED_ENTITIES,
                    AsyncOcclusionEngine.get().pending(),
                    OcclusionSnapshots.get().size()));
        }
        if (cfg.chunkPrefetch) {
            out.add(String.format("chunks: hit %.1f%% | кэш %d шт / %d МБ | prefetch %d",
                    HyperionMetrics.hitRate(),
                    HyperionChunkSystem.get().cachedChunks(),
                    HyperionChunkSystem.get().cachedMegabytes(),
                    HyperionChunkSystem.get().inFlight()));
        }
        if (cfg.entityThrottling) {
            out.add(String.format("entities: %d | throttled %d | ИИ сэкономлено %.1f%%",
                    HyperionMetrics.LAST_ENTITY_COUNT,
                    HyperionEntitySystem.get().publishedThrottleable(),
                    HyperionMetrics.aiSavedPercent()));
        }
        out.add(String.format("blockstate cache: %d записей", HyperionMetrics.BLOCKSTATE_CACHE_ENTRIES));
        return out;
    }
}
