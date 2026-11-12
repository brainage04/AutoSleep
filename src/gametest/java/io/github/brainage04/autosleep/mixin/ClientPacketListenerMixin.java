package io.github.brainage04.autosleep.mixin;

import io.github.brainage04.autosleep.TimePacketHold;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSetTime", at = @At("HEAD"), cancellable = true)
    private void autosleep$holdTheTime(ClientboundSetTimePacket packet, CallbackInfo callback) {
        if (TimePacketHold.hold(packet)) {
            callback.cancel();
        }
    }
}
