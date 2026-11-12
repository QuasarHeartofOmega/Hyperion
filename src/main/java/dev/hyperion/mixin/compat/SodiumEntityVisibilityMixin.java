package dev.hyperion.mixin.compat;

import dev.hyperion.compat.SodiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Переопределение entity-culling у Sodium 0.6 (пакет {@code net.caffeinemc.mods.sodium}).
 *
 * <p>Точка внедрения — {@code SodiumWorldRenderer.isEntityVisible}. Sodium вызывает
 * её из своего миксина в {@code EntityRenderer.shouldRender}, то есть <b>до</b> хука
 * Hyperion в {@code EntityRenderDispatcher.shouldRender}. В режиме приоритета
 * HYPERION мы возвращаем «видима», чтобы решение принимал только асинхронный движок
 * Hyperion: два независимых рейкаста по одной сущности — это двойная работа и
 * «мигание» мобов на границе видимости.
 *
 * <p>Три важных детали реализации:
 * <ul>
 *   <li><b>{@code @Pseudo} + {@code require = 0}.</b> Класса Sodium нет в сборке при
 *       компиляции и может не быть у пользователя — миксин просто не применится.</li>
 *   <li><b>{@code remap = false}.</b> {@code isEntityVisible} — собственный метод
 *       Sodium, он не обфусцируется и не входит в маппинги Minecraft. Имя ищем как есть.</li>
 *   <li><b>Обработчик без аргументов цели.</b> Сигнатура метода у Sodium менялась
 *       ({@code EntityRenderer<T>} → {@code EntityRenderer<T,S>}, добавлялся
 *       {@code partialTicks}); обработчик, принимающий только {@code CallbackInfoReturnable},
 *       переживает все эти изменения и не тянет в миксин ни одного класса Minecraft.</li>
 * </ul>
 *
 * <p>Поведение (переопределять или уступить) решает рантайм — {@link SodiumCompat#forceVisible()}.
 * Если подсистема Hyperion на паузе (шейдеры Iris), уступлена или сломана, миксин
 * ничего не делает и Sodium отсекает сущностей как обычно.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumEntityVisibilityMixin {

    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void hyperion$letHyperionDecide(CallbackInfoReturnable<Boolean> cir) {
        if (SodiumCompat.forceVisible()) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
