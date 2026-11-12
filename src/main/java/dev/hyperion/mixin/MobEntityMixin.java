package dev.hyperion.mixin;

import dev.hyperion.entity.AiThrottleContext;
import dev.hyperion.entity.HyperionEntitySystem;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Выставляет контекст throttling для GoalSelector'ов этого моба.
 * Навигация, прыжки и физика продолжают тикать каждый тик — пропускается только
 * выбор/пересчёт целей, поэтому мобы не «замирают» и не дёргаются.
 */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

    @Inject(method = "tickNewAi", at = @At("HEAD"))
    private void hyperion$beforeAi(CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        AiThrottleContext.set(HyperionEntitySystem.get().shouldSkipAi(self.getId()));
    }

    @Inject(method = "tickNewAi", at = @At("RETURN"))
    private void hyperion$afterAi(CallbackInfo ci) {
        AiThrottleContext.clear();
    }
}
