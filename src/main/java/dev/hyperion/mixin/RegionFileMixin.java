package dev.hyperion.mixin;

import dev.hyperion.chunk.HyperionChunkSystem;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Точка входа асинхронного read-ahead чанков.
 *
 * <p>Кэш отдаёт уже распакованный NBT (попадание = ни диска, ни inflate на вызывающем
 * потоке), промах уходит в ваниль и запускает prefetch соседей в I/O-пуле.
 * Запись чанка инвалидирует кэш, закрытие файла — тоже.
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin {

    @Unique
    private String hyperion$fileKey;

    @Unique
    private String hyperion$key() {
        if (hyperion$fileKey == null) {
            Path path = ((RegionFile) (Object) this).getPath();
            hyperion$fileKey = path.toString();
        }
        return hyperion$fileKey;
    }

    @Inject(method = "getChunkInputStream", at = @At("HEAD"), cancellable = true)
    private void hyperion$onRead(ChunkPos pos, CallbackInfoReturnable<DataInputStream> cir) {
        if (HyperionChunkSystem.isInternalRead()) {
            return; // это наш собственный воркер — отдаём управление ванили
        }
        HyperionChunkSystem system = HyperionChunkSystem.get();
        String key = hyperion$key();
        byte[] cached = system.lookup(key, pos.x, pos.z);
        if (cached != null) {
            cir.setReturnValue(new DataInputStream(new ByteArrayInputStream(cached)));
            return;
        }
        system.prefetchNeighbours((RegionFile) (Object) this, key, pos);
    }

    @Inject(method = "writeChunk", at = @At("HEAD"))
    private void hyperion$onWrite(ChunkPos pos, ByteBuffer buffer, CallbackInfo ci) {
        HyperionChunkSystem.get().invalidate(hyperion$key(), pos.x, pos.z);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void hyperion$onClose(CallbackInfo ci) {
        HyperionChunkSystem.get().invalidateFile(hyperion$key());
    }
}
