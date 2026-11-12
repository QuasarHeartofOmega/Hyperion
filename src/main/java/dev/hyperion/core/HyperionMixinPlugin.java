package dev.hyperion.core;

import dev.hyperion.compat.CompatGate;
import dev.hyperion.compat.HyperionCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Включает/выключает миксины на этапе загрузки класса — до того, как они применятся.
 * Это нужно, чтобы не патчить ваниль, если функция выключена в конфиге или конфликтует
 * с другим модом (Sodium/C2ME/EntityCulling и т.п.).
 *
 * <p><b>Важное ограничение фазы.</b> Здесь нельзя вызывать рантайм-мосты
 * ({@code SodiumCompat.present()}, {@code IrisApi.getInstance()} и т.п.): они грузят
 * чужие классы, а мы находимся внутри трансформации байткода — это прямой путь к
 * циклической инициализации. Поэтому все решения берутся из {@link CompatGate},
 * который читает только метаданные модов, ресурсы jar'ов и конфиг.
 *
 * <p>Решение «применять ли миксин» необратимо до перезапуска игры, а «как себя вести»
 * принимает рантайм ({@link HyperionCompat#works}). Поэтому миксин в Sodium ставится
 * всегда, когда Sodium найден: смена режима командой /hyperion compat mode не должна
 * требовать перезапуска.
 */
public final class HyperionMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        // Конфиг может читаться здесь раньше, чем onInitialize — это безопасно,
        // он не трогает классы Minecraft.
        HyperionConfig.get();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        HyperionConfig cfg = HyperionConfig.get();
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);

        if (!cfg.enabled) {
            return false;
        }

        // Миксины в чужие моды: только если мод реально есть в сборке.
        if (mixinClassName.startsWith("dev.hyperion.mixin.compat.")) {
            return CompatGate.allowForeignMixin(simple);
        }

        switch (simple) {
            case "RegionFileMixin":
                return cfg.chunkPrefetch && CompatGate.allow(HyperionCompat.FEATURE_CHUNK_IO);
            case "ServerWorldMixin":
            case "MobEntityMixin":
            case "GoalSelectorMixin":
                return cfg.entityThrottling && CompatGate.allow(HyperionCompat.FEATURE_ENTITY_AI);
            case "EntityRenderDispatcherMixin":
            case "ClientWorldMixin":
                return cfg.entityCulling && CompatGate.allow(HyperionCompat.FEATURE_CULLING);
            case "DebugHudMixin":
                return cfg.debugHudLine;
            case "InGameHudMixin":
                return cfg.indicator || cfg.hud;
            default:
                return true;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
