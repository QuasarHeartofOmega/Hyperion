package dev.hyperion.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.hyperion.command.HyperionCommand;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Регистрация /hyperion без зависимости от Fabric API. */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    @Shadow
    @Final
    private CommandDispatcher<ServerCommandSource> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void hyperion$registerCommands(CommandManager.RegistrationEnvironment environment,
                                           CommandRegistryAccess registryAccess,
                                           CallbackInfo ci) {
        HyperionCommand.register(this.dispatcher);
    }
}
