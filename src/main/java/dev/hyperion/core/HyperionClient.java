package dev.hyperion.core;

import dev.hyperion.renderer.GpuCapabilities;
import net.fabricmc.api.ClientModInitializer;

/** Клиентская точка входа. */
public final class HyperionClient implements ClientModInitializer {

    private static boolean hudOverride;
    private static boolean hudOverrideValue;

    @Override
    public void onInitializeClient() {
        Hyperion.LOGGER.info("[Hyperion] Клиентский модуль загружен. Vulkan-бэкенд: {}",
                GpuCapabilities.vulkanStatus());
    }

    public static boolean hudEnabled() {
        return hudOverride ? hudOverrideValue : HyperionConfig.get().hud;
    }

    public static boolean toggleHud() {
        hudOverrideValue = !hudEnabled();
        hudOverride = true;
        return hudOverrideValue;
    }
}
