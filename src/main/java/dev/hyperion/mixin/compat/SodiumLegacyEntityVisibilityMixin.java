package dev.hyperion.mixin.compat;

import dev.hyperion.compat.SodiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * То же переопределение для сборок со старым пакетом Sodium
 * ({@code me.jellysquid.mods.sodium}) — форки и старые версии.
 *
 * <p>Применяется только если найден именно этот класс (см. {@code CompatGate}),
 * поэтому в обычной сборке с Sodium 0.6 миксин не используется.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumLegacyEntityVisibilityMixin {

    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void hyperion$letHyperionDecideLegacy(CallbackInfoReturnable<Boolean> cir) {
        if (SodiumCompat.forceVisible()) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
