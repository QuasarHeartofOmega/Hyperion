package dev.hyperion.mixin.client;

import dev.hyperion.chunk.HyperionChunkSystem;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.renderer.AsyncOcclusionEngine;
import dev.hyperion.renderer.EntityCulling;
import dev.hyperion.renderer.GpuCapabilities;
import dev.hyperion.renderer.OcclusionSnapshots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кадры и клиентский тик: замер времени кадра, разовый опрос GPU и — главное —
 * бюджетное построение снапшотов секций, которые запросили воркеры.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Unique
    private long hyperion$frameStart;

    @Inject(method = "render", at = @At("HEAD"))
    private void hyperion$frameStart(boolean tick, CallbackInfo ci) {
        this.hyperion$frameStart = System.nanoTime();
        GpuCapabilities.probe();
        EntityCulling.beginFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void hyperion$frameEnd(boolean tick, CallbackInfo ci) {
        HyperionMetrics.recordFrame(System.nanoTime() - this.hyperion$frameStart);
        EntityCulling.endFrame();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void hyperion$clientTick(CallbackInfo ci) {
        HyperionConfig cfg = HyperionConfig.get();
        MinecraftClient self = (MinecraftClient) (Object) this;
        ClientWorld world = self.world;
        if (world != null && cfg.entityCulling) {
            OcclusionSnapshots.get().pump(world, cfg.occlusionSectionBudgetPerTick);
            AsyncOcclusionEngine.get().configure(cfg);
            AsyncOcclusionEngine.get().prune();
        }
        HyperionChunkSystem.get().drainWorkerLog();
    }
}
