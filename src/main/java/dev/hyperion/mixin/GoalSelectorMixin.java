package dev.hyperion.mixin;

import dev.hyperion.entity.AiThrottleContext;
import net.minecraft.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Пропуск тика селектора целей для далёких мобов (решение принято заранее). */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hyperion$maybeSkip(CallbackInfo ci) {
        if (AiThrottleContext.shouldSkip()) {
            ci.cancel();
        }
    }
}
