package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Whenever the dimension's bed rule lets the player sleep, puts them to bed in the nearest bed: it walks there with
 * Baritone when the bed is out of reach, then uses it exactly as a right click would, so the server needs no mod.
 */
public final class AutoSleeper {
    /** Ticks between two attempts, so the server is not asked every tick. */
    static final int RETRY_INTERVAL_TICKS = 20;
    /**
     * Ticks to wait after waking up. The server skips the night and wakes everyone in the same tick, but it sends
     * the new day time only once a second, so until then the client still sees night and would get back into bed.
     */
    static final int WAKE_UP_GRACE_TICKS = 40;
    /** How long the integrated server's answer about a bed stays good: until the attempt after the one that asked. */
    private static final int SERVER_ANSWER_MAX_AGE_TICKS = RETRY_INTERVAL_TICKS * 2;

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
    /** The integrated server's latest answer, written on the server thread; see {@link #maySleepIn}. */
    private volatile @Nullable ServerAnswer serverAnswer;

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
        if (sleeping) {
            stopWalking();
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = RETRY_INTERVAL_TICKS;

        BlockPos bed = findNearestBed(level, player);
        if (bed == null || !maySleepIn(minecraft, level, bed)) {
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

    /**
     * Whether the server would let the player sleep in this bed now. Vanilla decides by the {@code BED_RULE}
     * environment attribute at the bed's head: {@code BedBlock#useWithoutItem} blows the bed up when it
     * {@code explodes()}, and {@code ServerPlayer#startSleepInBed} refuses unless it {@code canSleep(level)}.
     * <p>
     * {@code BED_RULE} is not synced to clients (it is not {@code syncable()} in {@code EnvironmentAttributes}, and the
     * client decodes dimension types and biomes with codecs that keep only syncable attributes), so the client level
     * always reads the default rule: sleep when dark, never explode. In singleplayer and on a LAN host the integrated
     * server has the real rule; it is asked on its own thread, and its answer is used at the next attempt.
     */
    private boolean maySleepIn(Minecraft minecraft, ClientLevel level, BlockPos bed) {
        BlockPos head = bedHead(level, bed);
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            ResourceKey<Level> dimension = level.dimension();
            long now = level.getGameTime();
            ServerAnswer answer = serverAnswer;
            server.execute(() -> serverAnswer = askServer(server, dimension, head, now));
            return answer != null && answer.dimension().equals(dimension) && answer.head().equals(head)
                    && now - answer.askedAt() <= SERVER_ANSWER_MAX_AGE_TICKS && answer.maySleep();
        }

        // vanilla-identity: on a remote server the client cannot see BED_RULE (see above), so it can only rely on the
        // rule of a vanilla dimension type, as long as the server's datapacks leave it alone: the Nether and the End have
        // fixed time, so the default rule never lets anyone sleep there. Any other dimension type may make beds explode.
        if (!level.dimensionTypeRegistration().is(key -> key.identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE))) {
            return false;
        }
        BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, head);
        return !rule.explodes() && rule.canSleep(level);
    }

    /** Runs on the integrated server's thread. */
    private static ServerAnswer askServer(IntegratedServer server, ResourceKey<Level> dimension, BlockPos head, long askedAt) {
        ServerLevel level = server.getLevel(dimension);
        boolean maySleep = false;
        if (level != null) {
            BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, head);
            maySleep = !rule.explodes() && rule.canSleep(level);
        }
        return new ServerAnswer(dimension, head, askedAt, maySleep);
    }

    /** The head half of a bed, where vanilla reads the bed rule ({@code BedBlock#useWithoutItem}). */
    private static BlockPos bedHead(ClientLevel level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(state.getValue(BedBlock.FACING));
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

    /** What the integrated server said about the bed with its head at {@code head}, asked at client game time {@code askedAt}. */
    private record ServerAnswer(ResourceKey<Level> dimension, BlockPos head, long askedAt, boolean maySleep) {
    }
}
