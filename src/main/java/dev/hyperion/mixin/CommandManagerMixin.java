package dev.hyperion.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.hyperion.command.HyperionCommand;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Регистрация /hyperion без зависимости от Fabric API. */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    // Без @Shadow-поля: Mixin Annotation Processor не пишет в refmap записи для
    // @Shadow-полей, поэтому в intermediary-рантайме поле "dispatcher" не находится
    // (InvalidMixinException) и сервер падает при загрузке. Публичный геттер
    // getDispatcher() мапится обычным ремапом байткода и работает в обеих средах.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void hyperion$registerCommands(CommandManager.RegistrationEnvironment environment,
                                           CommandRegistryAccess registryAccess,
                                           CallbackInfo ci) {
        CommandDispatcher<ServerCommandSource> dispatcher = ((CommandManager) (Object) this).getDispatcher();
        HyperionCommand.register(dispatcher);
    }
}
