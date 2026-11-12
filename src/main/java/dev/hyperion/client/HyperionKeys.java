package dev.hyperion.client;

import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.renderer.HyperionIndicator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * Горячая клавиша индикатора без зависимости от Fabric API.
 *
 * <p>Fabric API не требуется моду в принципе, поэтому вместо KeyBindingHelper —
 * опрос состояния клавиши через GLFW раз в клиентский тик (20 раз в секунду,
 * это дешевле, чем любой listener, и работает всегда, даже если другой мод
 * перехватил реестр клавиш).
 *
 * <p>По умолчанию: <b>H</b> — включить/выключить индикатор,
 * <b>Shift+H</b> — переключить режим (MINIMAL → COMPACT → FULL → OFF).
 * Клавиша настраивается в hyperion.json ({@code indicatorToggleKey}, код GLFW;
 * -1 — отключить), поэтому конфликт с любым другим модом решается пользователем.
 */
public final class HyperionKeys {

    private static boolean wasDown;
    private static boolean initialized;

    private HyperionKeys() {
    }

    /** Вызывать из клиентского тика (render thread — GLFW требует главный поток). */
    public static void poll(MinecraftClient client) {
        try {
            pollUnsafe(client);
        } catch (Throwable t) {
            // Клавиша — удобство, а не подсистема: падать из-за неё нельзя.
            Hyperion.LOGGER.debug("[Hyperion] опрос клавиши индикатора пропущен: {}", t.toString());
        }
    }

    private static void pollUnsafe(MinecraftClient client) {
        HyperionConfig cfg = HyperionConfig.get();
        int key = cfg.indicatorToggleKey;
        if (key <= 0 || client == null) return;
        // В открытом экране H может быть частью ввода (чат, книга, поиск) — не перехватываем.
        if (client.currentScreen != null) {
            wasDown = true;
            return;
        }
        long window;
        try {
            window = client.getWindow().getHandle();
        } catch (Throwable t) {
            return;
        }
        if (window == 0L) return;

        boolean down;
        try {
            down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return; // нет GLFW (например, headless-тест) — просто ничего не делаем
        }
        if (!initialized) {
            initialized = true;
            wasDown = down;
            return;
        }
        if (down && !wasDown) {
            boolean shift = isShift(window);
            if (shift) {
                cycleMode(client, cfg);
            } else {
                toggle(client, cfg);
            }
        }
        wasDown = down;
    }

    private static boolean isShift(long window) {
        try {
            return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toggle(MinecraftClient client, HyperionConfig cfg) {
        cfg.indicator = !cfg.indicator;
        HyperionConfig.save();
        overlay(client, "Индикатор Hyperion: " + (cfg.indicator ? "включён" : "выключен"));
        Hyperion.LOGGER.info("[Hyperion] индикатор {}", cfg.indicator ? "включён" : "выключен");
    }

    private static void cycleMode(MinecraftClient client, HyperionConfig cfg) {
        HyperionIndicator.Mode next = HyperionIndicator.mode().next();
        cfg.indicatorMode = next.name();
        cfg.indicator = next != HyperionIndicator.Mode.OFF;
        HyperionConfig.save();
        overlay(client, "Режим индикатора: " + next.name());
    }

    private static void overlay(MinecraftClient client, String message) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal(message).formatted(Formatting.AQUA), true);
    }
}
