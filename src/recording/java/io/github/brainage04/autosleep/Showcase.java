package io.github.brainage04.autosleep;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * The film's steps in the client GameTests: the renderer check of a take, the framing of the beds,
 * the empty chat at each scenario start and the hold at the end.
 */
public final class Showcase {
    private static final int CLOSING_HOLD_TICKS = 40;

    private Showcase() {}

    /**
     * A take on the GPU display ({@code -PautosleepRequireNvidia=true}, set by the recording
     * script) fails unless the client draws with the card: a silent fallback to software rendering
     * would be a slow recording that looks like a GPU one.
     */
    public static void checkRenderer(ClientGameTestContext context) {
        if (!Boolean.getBoolean("autosleep.requireNvidia")) {
            return;
        }
        String gl =
                context.computeOnClient(
                        client ->
                                GL11.glGetString(GL11.GL_VENDOR)
                                        + " / "
                                        + GL11.glGetString(GL11.GL_RENDERER));
        ClientGameTestRecordingHud.log("OpenGL: " + gl);
        if (!gl.contains("NVIDIA")) {
            throw new AssertionError(
                    "Expected the recording to be drawn by NVIDIA, but the client draws with "
                            + gl
                            + ".");
        }
    }

    /**
     * A bed is on camera when every corner of both its halves is inside the view and its middle is
     * not hidden. The corners matter: a bed that is half out of the frame is not "on screen".
     */
    public static void assertBedsInView(
            ClientGameTestContext context, String when, BlockPos... beds) {
        for (BlockPos bed : beds) {
            String problem = context.computeOnClient(client -> framingProblem(client, bed));
            if (problem != null) {
                String view =
                        context.computeOnClient(
                                client ->
                                        " (player at "
                                                + client.player.position()
                                                + ", looking "
                                                + client.player.getYRot()
                                                + "/"
                                                + client.player.getXRot()
                                                + ")");
                throw new AssertionError(
                        "Expected the bed fully on camera "
                                + when
                                + ", but "
                                + problem
                                + view
                                + ".");
            }
        }
        ClientGameTestRecordingHud.log(
                (beds.length == 1 ? "The bed is" : "Both beds are") + " on camera " + when);
    }

    /**
     * Empties the chat box before a scenario starts, so each clip shows only the lines its own
     * scene earns. Called before the step marker, which the cut starts 0.10 s after, so the clear
     * itself is never on camera.
     */
    public static void clearChat(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.getChat().clearMessages(false));
    }

    /** Logs the frame rate the take ran at and holds the last scene for two seconds. */
    public static void hold(ClientGameTestContext context) {
        ClientGameTestRecordingHud.log(
                "Client FPS at the end of the run: "
                        + context.computeOnClient(client -> client.getFps()));
        context.waitTicks(CLOSING_HOLD_TICKS);
    }

    private static @Nullable String framingProblem(Minecraft client, BlockPos bed) {
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition();
        double verticalFov = client.options.fov().get();
        double halfHorizontalFov =
                Math.toDegrees(
                        Math.atan(
                                Math.tan(Math.toRadians(verticalFov) / 2.0D)
                                        * (double) client.getWindow().getWidth()
                                        / client.getWindow().getHeight()));
        BlockPos otherHalf = otherHalf(client.level, bed);
        for (Vec3 corner : corners(bed, otherHalf)) {
            double yawOff =
                    Math.abs(Mth.wrapDegrees(BedLook.yawTo(eye, corner) - player.getYRot()));
            double pitchOff = Math.abs(BedLook.pitchTo(eye, corner) - player.getXRot());
            if (yawOff > halfHorizontalFov) {
                return "a corner of the bed at "
                        + bed
                        + " is "
                        + Math.round(yawOff)
                        + " degrees to the side of a view that is "
                        + Math.round(halfHorizontalFov * 2.0D)
                        + " degrees wide";
            }
            if (pitchOff > verticalFov / 2.0D) {
                return "a corner of the bed at "
                        + bed
                        + " is "
                        + Math.round(pitchOff)
                        + " degrees above or below the view";
            }
        }
        HitResult hit =
                client.level.clip(
                        new ClipContext(
                                eye,
                                Vec3.atCenterOf(bed),
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                player));
        if (!(hit instanceof BlockHitResult blockHit)
                || hit.getType() == HitResult.Type.MISS
                || !(blockHit.getBlockPos().equals(bed)
                        || blockHit.getBlockPos().equals(otherHalf))) {
            return "the bed at "
                    + bed
                    + " is hidden: the view hits "
                    + (hit == null ? "nothing" : hit.getType());
        }
        return null;
    }

    private static BlockPos otherHalf(Level level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? bed.relative(state.getValue(BedBlock.FACING).getOpposite())
                : bed.relative(state.getValue(BedBlock.FACING));
    }

    /** The eight corners of each half: the block footprint's corners at its base and its top. */
    private static List<Vec3> corners(BlockPos bed, BlockPos otherHalf) {
        List<Vec3> corners = new ArrayList<>();
        for (BlockPos block : new BlockPos[] {bed, otherHalf}) {
            for (double x = 0.0D; x <= 1.0D; x += 1.0D) {
                for (double z = 0.0D; z <= 1.0D; z += 1.0D) {
                    for (double y = 0.0D; y <= 0.5625D; y += 0.5625D) {
                        corners.add(new Vec3(block.getX() + x, block.getY() + y, block.getZ() + z));
                    }
                }
            }
        }
        return corners;
    }
}
