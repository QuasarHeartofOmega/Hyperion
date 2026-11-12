package dev.hyperion.mixin.client;

import dev.hyperion.renderer.EntityCulling;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Асинхронное occlusion-culling: если ваниль решила рисовать сущность, но наш
 * фоновый рейкаст показал, что она полностью за непрозрачными блоками — пропускаем.
 * Результат всегда берётся из готовой карты, рендер-поток никогда не ждёт.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void hyperion$afterShouldRender(E entity, Frustum frustum,
                                                               double cameraX, double cameraY, double cameraZ,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (EntityCulling.shouldCull(entity, cameraX, cameraY, cameraZ)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
