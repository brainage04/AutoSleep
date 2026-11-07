package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * At night, puts the local player to bed in the nearest bed: it walks there with Baritone when the bed is out of reach,
 * then uses it exactly as a right click would, so the server needs no mod.
 */
public final class AutoSleeper {
    /** Ticks between two attempts, so the server is not asked every tick. */
    static final int RETRY_INTERVAL_TICKS = 20;
    /**
     * Ticks to wait after waking up. The server skips the night and wakes everyone in the same tick, but it sends
     * the new day time only once a second, so until then the client still sees night and would get back into bed.
     */
    static final int WAKE_UP_GRACE_TICKS = 40;

    static final long DAY_LENGTH_TICKS = 24000L;
    /**
     * The overworld night by the clock: the day times at which a clear sky is dark enough for vanilla to let players
     * sleep (the {@code sky_light_level} track of {@code timeline/day.json} in 1.21.11). Night ends at {@link #NIGHT_END}.
     */
    static final long NIGHT_START = 12523L;
    static final long NIGHT_END = 23478L;

    /** How far to look for a bed: horizontally and vertically from the player's feet. */
    private static final int SEARCH_RADIUS_HORIZONTAL = 24;
    private static final int SEARCH_RADIUS_VERTICAL = 6;
    /** How far the player's feet may be from a bed block: vanilla's {@code ServerPlayer#isReachableBedBlock}. */
    private static final double BED_RANGE_HORIZONTAL = 3.0D;
    private static final double BED_RANGE_VERTICAL = 2.0D;

    private int cooldownTicks;
    private boolean wasSleeping;
    /** The bed Baritone is walking to, or null when this class has not started a walk. */
    private @Nullable BlockPos walkingTo;

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        boolean sleeping = player != null && player.isSleeping();
        if (wasSleeping && !sleeping) {
            cooldownTicks = WAKE_UP_GRACE_TICKS;
        }
        wasSleeping = sleeping;

        if (player == null || level == null || minecraft.gameMode == null) {
            walkingTo = null;
            return;
        }
        if (sleeping || !isNight(level)) {
            stopWalking();
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = RETRY_INTERVAL_TICKS;

        BlockPos bed = findNearestBed(level, player);
        // The dimension's bed rule, as the server applies it: beds explode in the Nether, the End and any custom
        // dimension (or biome) that says so.
        if (bed == null || level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, bed).explodes()) {
            stopWalking();
            return;
        }

        if (inReach(player, bed)) {
            stopWalking();
            minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false));
        } else if (!bed.equals(walkingTo) || !baritone().getCustomGoalProcess().isActive()) {
            walkingTo = bed;
            baritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(bed));
        }
    }

    static boolean isNight(ClientLevel level) {
        long dayTime = Math.floorMod(level.getDayTime(), DAY_LENGTH_TICKS);
        return dayTime >= NIGHT_START && dayTime < NIGHT_END;
    }

    /** The bed block nearest to the player's feet within the search box, either half. */
    static @Nullable BlockPos findNearestBed(ClientLevel level, LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SEARCH_RADIUS_HORIZONTAL, -SEARCH_RADIUS_VERTICAL, -SEARCH_RADIUS_HORIZONTAL),
                origin.offset(SEARCH_RADIUS_HORIZONTAL, SEARCH_RADIUS_VERTICAL, SEARCH_RADIUS_HORIZONTAL))) {
            double distance = player.position().distanceToSqr(Vec3.atBottomCenterOf(pos));
            if (distance < nearestDistance && level.getBlockState(pos).getBlock() instanceof BedBlock) {
                nearest = pos.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Close enough for the server to accept the bed: vanilla's bed range and the player's block interaction range. */
    private static boolean inReach(LocalPlayer player, BlockPos bed) {
        Vec3 bottomCenter = Vec3.atBottomCenterOf(bed);
        return Math.abs(player.getX() - bottomCenter.x) <= BED_RANGE_HORIZONTAL
                && Math.abs(player.getY() - bottomCenter.y) <= BED_RANGE_VERTICAL
                && Math.abs(player.getZ() - bottomCenter.z) <= BED_RANGE_HORIZONTAL
                && player.isWithinBlockInteractionRange(bed, 0.0D);
    }

    /** Cancels only a walk this class started, never one the player gave Baritone. */
    private void stopWalking() {
        if (walkingTo != null) {
            walkingTo = null;
            baritone().getPathingBehavior().cancelEverything();
        }
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }
}
