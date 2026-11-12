package dev.hyperion.mixin;

import dev.hyperion.core.HyperionGuard;
import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.entity.path.HyperionPathSystem;
import dev.hyperion.entity.path.NavigationAccess;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Точка входа асинхронного поиска пути (opt-in, {@code asyncPathfinding=false} по умолчанию).
 *
 * <p>Ставится только если функция включена в конфиге — решение принимает
 * {@code HyperionMixinPlugin} до применения миксина. Если выключена, ванильная
 * навигация не патчится вообще.
 *
 * <p>Контракт простой: {@link HyperionPathSystem#findPath} возвращает либо готовый
 * перепроверенный путь, либо null. Null — и ванильный A* работает как обычно,
 * поэтому «моб не смог найти путь из-за мода» невозможно by design.
 *
 * <p>Владельца навигации берём через {@link NavigationAccess} (поиск поля по типу),
 * а не через {@code @Shadow}: Mixin AP не пишет refmap для shadow-полей, и в
 * intermediary-рантайме такое поле не нашлось бы. Подробности — в NavigationAccess.
 */
@Mixin(MobNavigation.class)
public abstract class MobNavigationMixin {

    @Inject(method = "findPathTo(Lnet/minecraft/util/math/BlockPos;I)Lnet/minecraft/entity/ai/pathing/Path;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void hyperion$asyncPath(BlockPos target, int distance, CallbackInfoReturnable<Path> cir) {
        MobEntity mob = NavigationAccess.entityOf((MobNavigation) (Object) this);
        if (mob == null) return;
        Path path = HyperionGuard.get(Feature.ENTITY_AI,
                () -> HyperionPathSystem.get().findPath(mob, target, distance), null);
        if (path != null) {
            cir.setReturnValue(path);
        }
    }
}
