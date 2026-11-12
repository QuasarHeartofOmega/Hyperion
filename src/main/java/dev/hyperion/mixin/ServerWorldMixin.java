package dev.hyperion.mixin;

import dev.hyperion.entity.HyperionEntitySystem;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Снимает снапшот сущностей (главный поток) и запускает параллельный анализ в конце тика.
 * Сами сущности тикаются строго последовательно — как и должно быть.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Inject(method = "tickEntity", at = @At("HEAD"))
    private void hyperion$collectEntity(Entity entity, CallbackInfo ci) {
        HyperionEntitySystem.get().collect((ServerWorld) (Object) this, entity);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void hyperion$finishTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        HyperionEntitySystem.get().finishTick((ServerWorld) (Object) this);
    }
}
