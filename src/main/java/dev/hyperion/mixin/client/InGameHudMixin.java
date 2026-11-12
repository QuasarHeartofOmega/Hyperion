package dev.hyperion.mixin.client;

import dev.hyperion.core.HyperionClient;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.renderer.HyperionHud;
import dev.hyperion.renderer.HyperionIndicator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Индикатор работы и HUD метрик.
 *
 * <p>Почему это безопасно с любым рендер-модом: рисуем в самом конце ванильного
 * HUD через DrawContext (ванильные RenderLayer'ы), не меняя GL-состояние.
 * Sodium не трогает HUD, Iris рисует GUI обычным конвейером, ImmediatelyFast
 * батчит наши вызовы вместе с остальными — всё это работает без специальных случаев.
 *
 * <p>Приоритет 1200 (выше дефолтных 1000): наш @Inject(RETURN) применяется после
 * чужих, поэтому индикатор оказывается поверх, а не под чужими оверлеями.
 */
@Mixin(value = InGameHud.class, priority = 1200)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void hyperion$renderIndicator(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        HyperionConfig cfg = HyperionConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        if (client.options.hudHidden) return;

        InGameHud self = (InGameHud) (Object) this;

        if (cfg.indicator) {
            boolean debugVisible = cfg.indicatorHideWithDebug && self.getDebugHud().shouldShowDebugHud();
            boolean screenOpen = client.currentScreen != null && !cfg.indicatorInScreens;
            if (!debugVisible && !screenOpen) {
                HyperionIndicator.render(context, self.getTextRenderer(), HyperionIndicator.mode());
            }
        }

        // Полный HUD метрик — отдельная настройка (/hyperion hud).
        if (HyperionClient.hudEnabled() && HyperionIndicator.mode() != HyperionIndicator.Mode.FULL) {
            HyperionHud.render(context, self.getTextRenderer());
        }
    }
}
