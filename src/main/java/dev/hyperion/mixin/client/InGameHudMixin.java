package dev.hyperion.mixin.client;

import dev.hyperion.core.HyperionClient;
import dev.hyperion.renderer.HyperionHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HUD с метриками (по умолчанию выключен). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private void hyperion$renderHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!HyperionClient.hudEnabled()) return;
        HyperionHud.render(context, ((InGameHud) (Object) this).getTextRenderer());
    }
}
