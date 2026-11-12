package dev.hyperion.mixin.client;

import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.core.Hyperion;
import dev.hyperion.core.HyperionConfig;
import dev.hyperion.core.HyperionGuard;
import dev.hyperion.core.HyperionMetrics;
import dev.hyperion.core.HyperionStatus;
import dev.hyperion.core.HyperionStatus.Feature;
import dev.hyperion.renderer.OcclusionSnapshots;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Строка Hyperion в F3 — «индикатор работы» для тех, кто не хочет оверлей.
 *
 * <p>Работает и с Sodium (он не трогает DebugHud), и с ImmediatelyFast
 * (у него свой миксин сюда же — мы не отменяем чужой результат, а добавляем
 * строку к уже готовому списку). Список копируется: чужой мод мог вернуть
 * immutable-коллекцию, и add() на ней уронил бы кадр.
 */
@Mixin(value = DebugHud.class, priority = 1200)
public abstract class DebugHudMixin {

    @Inject(method = "getLeftText", at = @At("RETURN"), cancellable = true, require = 0)
    private void hyperion$debugLine(CallbackInfoReturnable<List<String>> cir) {
        HyperionGuard.safe("строка Hyperion в F3", () -> {
            if (!HyperionConfig.get().debugHudLine) return;
            List<String> original = cir.getReturnValue();
            if (original == null) return;
            List<String> copy = new ArrayList<>(original);
            copy.add("");
            copy.add("Hyperion " + Hyperion.VERSION + ": " + HyperionStatus.headline()
                    + " [" + HyperionCompat.environmentLine() + "]");
            if (HyperionStatus.active(Feature.ENTITY_CULLING)) {
                copy.add(String.format("hyperion cull: %d скрыто, %d запросов, снапшотов %d",
                        HyperionMetrics.LAST_CULLED_ENTITIES,
                        HyperionMetrics.CULL_QUERIES.sum(),
                        OcclusionSnapshots.get().size()));
            }
            if (HyperionStatus.active(Feature.ENTITY_AI)) {
                copy.add(String.format("hyperion ai: %.1f%% тиков сэкономлено", HyperionMetrics.aiSavedPercent()));
            }
            cir.setReturnValue(copy);
        });
    }
}
