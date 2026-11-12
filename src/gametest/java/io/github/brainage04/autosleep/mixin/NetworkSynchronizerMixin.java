package io.github.brainage04.autosleep.mixin;

import com.google.common.collect.ConcurrentHashMultiset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Works around a lost wakeup in fabric-client-gametest-api-v1 4.3.5 (Fabric API 0.141.3+1.21.11,
// NetworkSynchronizer.java:130-136): waitForPacketHandlers checks for in-flight packets before it
// takes the lock, so a packet handled in between signals nobody, the await times out after 10 s
// with nothing in flight, and the server crashes with "Network synchronizer in invalid state". Seen
// in CI run 42491813697 with inFlightPackets = 0 at the timeout. Fabric API #5484 replaced this
// class in 26.x; drop this mixin when the mod moves there.
@Mixin(
        targets = "net.fabricmc.fabric.impl.client.gametest.threading.NetworkSynchronizer",
        remap = false)
public abstract class NetworkSynchronizerMixin {
    @Shadow @Final private AtomicInteger inFlightPackets;

    @Shadow @Final private ConcurrentHashMultiset<?> mainThreadPacketHandlers;

    @Redirect(
            method = "waitForPacketHandlers",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ljava/util/concurrent/locks/Condition;await(JLjava/util/concurrent/TimeUnit;)Z",
                            remap = false))
    private boolean autosleep$awaitOnlyWhileStillWaiting(
            Condition condition, long time, TimeUnit unit) throws InterruptedException {
        if (inFlightPackets.get() <= 0 || !mainThreadPacketHandlers.isEmpty()) {
            return true;
        }
        return condition.await(time, unit);
    }
}
