package dev.hyperion.mixin;

import dev.hyperion.core.HyperionExecutors;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.entity.HyperionEntitySystem;
import dev.hyperion.memory.BlockStateCache;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Метрики серверного тика, прогрев кэша blockstate и корректное завершение пулов. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private long hyperion$tickStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void hyperion$tickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        this.hyperion$tickStart = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void hyperion$tickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        HyperionMetrics.recordTick(System.nanoTime() - this.hyperion$tickStart);
    }

    @Inject(method = "loadWorld", at = @At("RETURN"))
    private void hyperion$warmCaches(CallbackInfo ci) {
        BlockStateCache.requestInit();
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void hyperion$shutdown(CallbackInfo ci) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        HyperionEntitySystem.get().shutdown();
        // В одиночной игре остановка интегрированного сервера не должна убивать
        // клиентские воркеры (culling продолжает работать в меню/другом мире).
        if (self.isDedicated()) {
            HyperionExecutors.shutdown();
        }
    }
}
