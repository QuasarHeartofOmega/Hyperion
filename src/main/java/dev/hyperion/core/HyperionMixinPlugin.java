package dev.hyperion.core;

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

        switch (simple) {
            case "RegionFileMixin":
                return cfg.chunkPrefetch && !HyperionCompat.isDisabled(HyperionCompat.FEATURE_CHUNK_IO);
            case "ServerWorldMixin":
            case "MobEntityMixin":
            case "GoalSelectorMixin":
                return cfg.entityThrottling;
            case "EntityRenderDispatcherMixin":
            case "ClientWorldMixin":
                return cfg.entityCulling && !HyperionCompat.isDisabled(HyperionCompat.FEATURE_CULLING);
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
