package io.github.brainage04.autosleep;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Holds back one chunk of the next client level for a while after it arrives, as a server sends a
 * chunk away from the player some ticks after the level and its time. Client thread only.
 */
public final class ChunkPacketHold {
    private static @Nullable ClientLevel armedIn;
    private static @Nullable ChunkPos chunk;
    private static int holdTicks;
    private static @Nullable ClientLevel heldFor;
    private static int ticksLeft;
    private static @Nullable ClientboundLevelChunkWithLightPacket held;
    private static boolean replaying;

    private ChunkPacketHold() {}

    /** Holds that chunk of the next level after {@code current} for {@code ticks} ticks. */
    static void armForNextLevel(ClientLevel current, ChunkPos chunk, int ticks) {
        armedIn = current;
        ChunkPacketHold.chunk = chunk;
        holdTicks = ticks;
    }

    static void reset() {
        armedIn = null;
        chunk = null;
        heldFor = null;
        held = null;
    }

    /** Whether to hold this packet back; called by the mixin before the game handles it. */
    public static boolean hold(ClientboundLevelChunkWithLightPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (replaying
                || !minecraft.isSameThread()
                || armedIn == null
                || chunk == null
                || level == null
                || packet.getX() != chunk.x
                || packet.getZ() != chunk.z) {
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

    /** Hands the held packet to the game once the hold is over. */
    static void tick(Minecraft minecraft) {
        if (heldFor == null || --ticksLeft > 0) {
            return;
        }
        ClientboundLevelChunkWithLightPacket packet = held;
        ClientLevel level = heldFor;
        reset();
        ClientPacketListener connection = minecraft.getConnection();
        if (packet != null && connection != null && minecraft.level == level) {
            replaying = true;
            try {
                connection.handleLevelChunkWithLight(packet);
            } finally {
                replaying = false;
            }
        }
    }
}
