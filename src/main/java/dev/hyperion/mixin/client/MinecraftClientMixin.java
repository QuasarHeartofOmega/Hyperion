package dev.hyperion.mixin.client;

import dev.hyperion.chunk.HyperionChunkSystem;
import dev.hyperion.client.HyperionKeys;
import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.HyperionStatus;
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
 *
 * <p>Здесь же живёт динамика совместимости: {@link HyperionCompat#clientFrame()}
 * обновляет состояние шейдеров Iris (пак можно включить на ходу), а
 * {@link HyperionCompat#clientTick()} дожимает отложенные переопределения чужих
 * модов — например, флаг EntityCulling выставляется только после его инициализации.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Unique
    private long hyperion$frameStart;

    /** Мир, для которого построены снапшоты: при смене мира кэши обязаны обнулиться. */
    @Unique
    private ClientWorld hyperion$lastWorld;

    @Inject(method = "render", at = @At("HEAD"))
    private void hyperion$frameStart(boolean tick, CallbackInfo ci) {
        this.hyperion$frameStart = System.nanoTime();
        GpuCapabilities.probe();
        HyperionCompat.clientFrame();
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

        // Смена мира (в том числе переход между серверами с Distant Horizons/Bobby):
        // id сущностей и геометрия секций относятся к старому миру — кэши сбрасываем.
        if (world != this.hyperion$lastWorld) {
            this.hyperion$lastWorld = world;
            OcclusionSnapshots.get().clear();
            AsyncOcclusionEngine.get().clear();
        }

        HyperionCompat.clientTick();

        // Горячая клавиша индикатора (H / Shift+H). Опрос GLFW раз в тик —
        // Fabric API не нужен, реестр клавиш не трогаем.
        HyperionKeys.poll(self);

        if (world != null && cfg.entityCulling && HyperionCompat.works(HyperionCompat.FEATURE_CULLING)) {
            OcclusionSnapshots.get().pump(world, cfg.occlusionSectionBudgetPerTick);
            AsyncOcclusionEngine.get().configure(cfg);
            AsyncOcclusionEngine.get().prune();
        }
        HyperionChunkSystem.get().drainWorkerLog();
        HyperionStatus.sample();
    }

    /** Выход из мира: чужие настройки возвращаем сразу, а не только при выходе из игры. */
    @Inject(method = "disconnect()V", at = @At("HEAD"), require = 0)
    private void hyperion$onDisconnect(CallbackInfo ci) {
        OcclusionSnapshots.get().clear();
        AsyncOcclusionEngine.get().clear();
        this.hyperion$lastWorld = null;
    }

    /** Остановка клиента: возвращаем чужим модам их настройки. */
    @Inject(method = "stop", at = @At("HEAD"), require = 0)
    private void hyperion$onStop(CallbackInfo ci) {
        HyperionCompat.shutdown();
    }
}
