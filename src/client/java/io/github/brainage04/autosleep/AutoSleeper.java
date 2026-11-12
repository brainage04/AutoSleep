package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Vanilla decides by the {@code BED_RULE} environment attribute at the bed's head: {@code
 * BedBlock#useWithoutItem} blows the bed up when it {@code explodes()}, whatever else the rule
 * says, and {@code ServerPlayer#startSleepInBed} refuses unless it {@code canSleep(level)}. {@code
 * BED_RULE} is not synced to clients (it is not {@code syncable()} in {@code
 * EnvironmentAttributes}, and the client decodes dimension types and biomes with codecs that keep
 * only syncable attributes), so a client level always reads the default rule: sleep when dark,
 * never explode.
 */
public final class AutoSleeper {
    static final int RETRY_INTERVAL_TICKS = 20;

    /**
     * Ticks to wait after waking up. The server skips the night and wakes everyone in the same
     * tick, but it sends the new day time only once a second, so until then the client still sees
     * night and would get back into bed.
     */
    static final int WAKE_UP_GRACE_TICKS = 40;

    /** Ticks before a look that missed the bed is tried again: the bed itself has not moved. */
    static final int MISSED_LOOK_RETRY_TICKS = 2;

    private static final int SEARCH_RADIUS_HORIZONTAL = 24;
    private static final int SEARCH_RADIUS_VERTICAL = 6;

    /**
     * How far the player's feet may be from a bed block: vanilla's {@code
     * ServerPlayer#isReachableBedBlock}.
     */
    private static final double BED_RANGE_HORIZONTAL = 3.0D;

    private static final double BED_RANGE_VERTICAL = 2.0D;

    private int cooldownTicks;
    private boolean wasSleeping;

    /** Whether the last tick's answer to "would the game let me sleep here" was yes. */
    private boolean maySleepHere;

    private @Nullable BlockPos walkingTo;

    /** Whether the walk this mod started has already been noticed as finished. */
    private boolean walkEnded;

    /**
     * The integrated server's latest answer, written on the server thread; see {@link
     * #integratedServerAllows}.
     */
    private volatile @Nullable ServerAnswer serverAnswer;

    private @Nullable Question lastQuestion;

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
            lastQuestion = null;
            serverAnswer = null;
            maySleepHere = false;
            return;
        }
        if (sleeping) {
            stopWalking();
            // Nothing asked before the night was skipped may be used in the morning.
            lastQuestion = null;
            // The player is in bed, so the game let them sleep: waking up in a stale night must not
            // look like nightfall
            // and must not cut the wake-up grace short.
            maySleepHere = true;
            return;
        }

        // Read every tick, because the answer changes with the time of day, and the client's day
        // time is exact: the
        // walk starts on the first tick the game would let the player sleep, not on the next retry.
        IntegratedServer server = minecraft.getSingleplayerServer();
        boolean allowedNow =
                server != null
                        ? clientRuleAllows(level, player.blockPosition())
                        : remoteServerAllows(level, player.blockPosition());
        if (allowedNow && !maySleepHere) {
            // Night has just fallen, or a datapack's rule has just changed: act on this tick, not a
            // second later.
            cooldownTicks = 0;
        }
        maySleepHere = allowedNow;
        if (allowedNow) {
            boolean walkActive = baritone().getCustomGoalProcess().isActive();
            if (walkingTo != null && !walkActive && !walkEnded) {
                walkEnded = true;
                // The walk has just stopped: if it stopped beside the bed, use it on this tick
                // rather than standing
                // next to it until the next retry.
                if (inReach(player, walkingTo)) {
                    cooldownTicks = 0;
                }
            }
        }
        if (!allowedNow) {
            stopWalking();
            if (cooldownTicks > 0) {
                cooldownTicks--;
            }
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = RETRY_INTERVAL_TICKS;

        // Every attempt takes the answer to the previous attempt's question, if any, and asks
        // afresh only when it
        // finds a bed in singleplayer: an attempt that asks nothing leaves nothing for the next one
        // to use.
        Question previousQuestion = lastQuestion;
        ServerAnswer previousAnswer = serverAnswer;
        lastQuestion = null;
        serverAnswer = null;

        BlockPos bed = findNearestBed(level, player);
        if (bed == null
                || server != null
                        && !integratedServerAllows(
                                server,
                                new Question(level, bedHead(level, bed)),
                                previousQuestion,
                                previousAnswer)) {
            stopWalking();
            return;
        }

        if (inReach(player, bed)) {
            stopWalking();
            if (lookAndUse(minecraft, player, level, bed) == Aim.MISSED) {
                cooldownTicks = MISSED_LOOK_RETRY_TICKS;
            }
        } else if (!bed.equals(walkingTo) || !baritone().getCustomGoalProcess().isActive()) {
            walkingTo = bed;
            walkEnded = false;
            baritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(bed));
        }
    }

    /**
     * Puts the view on the bed and uses it on the same tick, so no time is spent between walking
     * there and lying down. The hit result is the game's own pick, so the bed is only used when the
     * crosshair is on it, from vanilla reach.
     */
    private Aim lookAndUse(
            Minecraft minecraft, LocalPlayer player, ClientLevel level, BlockPos bed) {
        if (!(level.getBlockState(bed).getBlock() instanceof BedBlock) || !inReach(player, bed)) {
            return Aim.GONE;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(bed);
        float yaw = BedLook.normalizeYaw(BedLook.yawTo(eye, target));
        float pitch = BedLook.pitchTo(eye, target);
        // Both the rotation and the one the renderer interpolates from, so the view is on the bed
        // in one frame.
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;

        HitResult hit = player.raycastHitResult(1.0F, player);
        if (!(hit instanceof BlockHitResult blockHit)
                || !sameBed(level, blockHit.getBlockPos(), bed)) {
            return Aim.MISSED;
        }
        minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
        return Aim.USED;
    }

    /**
     * Either half of the same bed: vanilla lets a player use the bed from the foot as well as from
     * the head.
     */
    private static boolean sameBed(ClientLevel level, BlockPos first, BlockPos second) {
        return level.getBlockState(first).getBlock() instanceof BedBlock
                && level.getBlockState(second).getBlock() instanceof BedBlock
                && bedHead(level, first).equals(bedHead(level, second));
    }

    /**
     * In singleplayer and on a LAN host the integrated server has the real rule. It is asked on its
     * own thread, and its answer is used at the next attempt only, and only if that attempt finds
     * the same bed in the same level.
     */
    private boolean integratedServerAllows(
            IntegratedServer server,
            Question question,
            @Nullable Question previousQuestion,
            @Nullable ServerAnswer previousAnswer) {
        lastQuestion = question;
        server.execute(() -> serverAnswer = askServer(server, question));
        return previousAnswer != null
                && previousAnswer.question() == previousQuestion
                && question.equals(previousQuestion)
                && previousAnswer.maySleep();
    }

    /**
     * Runs on the integrated server's thread; a stopped server runs it in place, so it refuses
     * there.
     */
    private static ServerAnswer askServer(IntegratedServer server, Question question) {
        ServerLevel level =
                server.isSameThread() ? server.getLevel(question.level().dimension()) : null;
        boolean maySleep = false;
        if (level != null) {
            BedRule rule =
                    level.environmentAttributes()
                            .getValue(EnvironmentAttributes.BED_RULE, question.head());
            maySleep = !rule.explodes() && rule.canSleep(level);
        }
        return new ServerAnswer(question, maySleep);
    }

    /**
     * The rule the client can read at a position: the default one everywhere a remote server has
     * not sent it.
     */
    private static boolean clientRuleAllows(ClientLevel level, BlockPos pos) {
        BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
        return !rule.explodes() && rule.canSleep(level);
    }

    /**
     * On a remote server the client sees only the default rule, the same at every position, so it
     * is read at the player's position before looking for a bed.
     */
    private static boolean remoteServerAllows(ClientLevel level, BlockPos pos) {
        // vanilla-identity: the client cannot see BED_RULE (see above), so it can only rely on the
        // rule of a vanilla
        // dimension type, as long as the server's datapacks leave it alone: the Nether and the End
        // have fixed time, so
        // the default rule never lets anyone sleep there. Any other dimension type may make beds
        // explode.
        if (!level.dimensionTypeRegistration()
                .is(key -> key.identifier().getNamespace().equals(Identifier.DEFAULT_NAMESPACE))) {
            return false;
        }
        return clientRuleAllows(level, pos);
    }

    /**
     * The head half of a bed, where vanilla reads the bed rule ({@code BedBlock#useWithoutItem}).
     */
    private static BlockPos bedHead(ClientLevel level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? bed
                : bed.relative(state.getValue(BedBlock.FACING));
    }

    static @Nullable BlockPos findNearestBed(ClientLevel level, LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos :
                BlockPos.betweenClosed(
                        origin.offset(
                                -SEARCH_RADIUS_HORIZONTAL,
                                -SEARCH_RADIUS_VERTICAL,
                                -SEARCH_RADIUS_HORIZONTAL),
                        origin.offset(
                                SEARCH_RADIUS_HORIZONTAL,
                                SEARCH_RADIUS_VERTICAL,
                                SEARCH_RADIUS_HORIZONTAL))) {
            double distance = player.position().distanceToSqr(Vec3.atBottomCenterOf(pos));
            if (distance < nearestDistance
                    && level.getBlockState(pos).getBlock() instanceof BedBlock) {
                nearest = pos.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Vanilla reach to the bed: its own 3x2 block rule and the player's block interaction range.
     */
    static boolean inReach(LocalPlayer player, BlockPos bed) {
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

    /** A bed, by its head, in one client level: a new world or dimension is a new level. */
    private record Question(ClientLevel level, BlockPos head) {}

    private record ServerAnswer(Question question, boolean maySleep) {}

    /** What one tick of looking at a bed did. */
    private enum Aim {
        /** The bed was used with the crosshair on it. */
        USED,
        /** The crosshair did not land on the bed: nothing was used. */
        MISSED,
        /** The bed is gone or out of reach: this attempt is over. */
        GONE
    }
}
