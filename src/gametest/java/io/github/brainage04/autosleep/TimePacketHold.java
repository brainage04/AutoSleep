package io.github.brainage04.autosleep;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.jetbrains.annotations.Nullable;

/**
 * Holds back the server's time for the first ticks of a new client level, as when the time packet
 * arrives in a later network read than the level: the client then ticks the new level at day time
 * 0. The test harness otherwise handles every packet the server has sent before the next tick.
 * Client thread only.
 */
public final class TimePacketHold {
    private static @Nullable ClientLevel armedIn;
    private static int holdTicks;
    private static @Nullable ClientLevel heldFor;
    private static int ticksLeft;
    private static @Nullable ClientboundSetTimePacket held;
    private static boolean replaying;

    private TimePacketHold() {}

    /** Holds the time packets of the next level after {@code current} for {@code ticks} ticks. */
    static void armForNextLevel(ClientLevel current, int ticks) {
        armedIn = current;
        holdTicks = ticks;
    }

    static void reset() {
        armedIn = null;
        heldFor = null;
        held = null;
    }

    /** Whether to hold this packet back; called by the mixin before the game handles it. */
    public static boolean hold(ClientboundSetTimePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (replaying || !minecraft.isSameThread() || armedIn == null || level == null) {
            return false;
        }
        if (heldFor == null && level != armedIn) {
            heldFor = level;
            ticksLeft = holdTicks;
        }
        if (level != heldFor) {
            return false;
        }
        held = packet;
        return true;
    }

    /** Hands the last held packet to the game once the hold is over. */
    static void tick(Minecraft minecraft) {
        if (heldFor == null || --ticksLeft > 0) {
            return;
        }
        ClientboundSetTimePacket packet = held;
        ClientLevel level = heldFor;
        armedIn = null;
        heldFor = null;
        held = null;
        ClientPacketListener connection = minecraft.getConnection();
        if (packet != null && connection != null && minecraft.level == level) {
            replaying = true;
            try {
                connection.handleSetTime(packet);
            } finally {
                replaying = false;
            }
        }
    }
}
