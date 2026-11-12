package dev.hyperion.mixin.client;

import dev.hyperion.renderer.OcclusionSnapshots;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Мгновенная инвалидация снапшота секции при изменении блока — чтобы сломанный блок
 * сразу «открывал» спрятанных за ним мобов, не дожидаясь TTL.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void hyperion$onBlockChange(BlockPos pos, BlockState state, int flags, int depth,
                                        CallbackInfoReturnable<Boolean> cir) {
        OcclusionSnapshots.get().invalidateBlock(pos);
    }
}
