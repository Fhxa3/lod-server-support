package dev.vox.lss.mixin;

import dev.vox.lss.networking.server.LSSServerNetworking;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class IntegratedServerLanHook {
    // MC 26.2 added a leading MultiplayerScope parameter to IntegratedServer#publishServer; the
    // injected handler's descriptor must match the target or the mixin fails to apply.
    @Inject(method = "publishServer", at = @At("RETURN"))
    private void lss$onLanPublished(MinecraftServer.MultiplayerScope scope, GameType gameType,
                                     boolean allowCheats, int port,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            LSSServerNetworking.startServiceForLan((IntegratedServer) (Object) this);
        }
    }
}
