package io.github.brainage04.autosleep;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

import baritone.api.BaritoneAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

@SuppressWarnings("UnstableApiUsage")
public class AutoSleepClientGameTest implements FabricClientGameTest {
    /**
     * Ten ticks before the Overworld's night, so the recording shows the trigger itself rather than
     * a wait.
     */
    private static final long TEN_TICKS_BEFORE_NIGHT = DayNightCycleHud.NIGHT_START - 10L;

    /**
     * Every scenario shows its setup by day first: a second and a half of daylight, then the ten
     * ticks of dusk, about two seconds in which the mod must do nothing, before night falls on the
     * same setup.
     */
    private static final int DAY_CHECK_TICKS = 30;

    /** Ticks for the player to settle after a teleport, before the scenario starts on camera. */
    private static final int SETTLE_TICKS = 5;

    private static final int AWAKE_CHECK_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 5;
    private static final int WALK_AND_SLEEP_TIMEOUT_TICKS = 200;
    private static final int WAKE_UP_TIMEOUT_TICKS = 600;

    /**
     * The mod must start on the first tick that shows night: a tick or two of ordering slack,
     * nothing more.
     */
    private static final int TICKS_TO_ACT = 2;

    /**
     * The pre-built scene: an oak plank floor with the red bed's head at (0, surface, 8) and the
     * blue bed's head at (10, surface, 8), both facing south. Its corner sits at this offset from
     * the red bed's head.
     */
    private static final Identifier SCENE =
            Identifier.fromNamespaceAndPath("autosleep_test", "bedroom");

    private static final BlockPos SCENE_CORNER_FROM_RED_BED = new BlockPos(-3, -1, -9);

    /**
     * Four and a half blocks in front of the red bed's foot: the mod's 3-block reach is exceeded,
     * and the walk to the bed is short enough to show at once.
     */
    private static final double RED_BED_FOUR_AND_A_HALF_BLOCKS_AWAY_Z = 2.5D;

    /** In reach of the red bed, 2.7 blocks north of its foot, inside the reach box. */
    private static final double IN_REACH_OF_THE_RED_BED_Z = 4.8D;

    /** The red bed is south of the start position, at larger Z: looking at it is yaw 0. */
    private static final float TOWARD_THE_RED_BED = 0.0F;

    /**
     * Thirty degrees beside the red bed: the whole bed is in view, and the crosshair is on the
     * floor beside it.
     */
    private static final float BESIDE_THE_RED_BED = 30.0F;

    private static final float LOOK_DOWN_AT_THE_FLOOR = 12.0F;
    private static final float LOOK_DOWN_AT_THE_BED = 22.0F;

    private final AtomicInteger serverBedUses = new AtomicInteger();
    private final AtomicInteger clientBedUses = new AtomicInteger();
    private final AtomicReference<String> unplayableBedUse = new AtomicReference<>();
    private ClientGameTestContext context;
    private TestDedicatedServerContext server;
    private int y;
    private BlockPos redBed;
    private BlockPos blueBed;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        if (Boolean.getBoolean("autosleep.requireNvidia")) {
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
        UseBlockCallback.EVENT.register(
                (player, level, hand, hitResult) -> {
                    if (!(level.getBlockState(hitResult.getBlockPos()).getBlock()
                            instanceof BedBlock)) {
                        return InteractionResult.PASS;
                    }
                    if (level.isClientSide() && player instanceof LocalPlayer localPlayer) {
                        clientBedUses.incrementAndGet();
                        String problem = unplayableUse(localPlayer, hitResult);
                        if (problem != null) {
                            unplayableBedUse.compareAndSet(null, problem);
                        }
                    } else if (!level.isClientSide()) {
                        serverBedUses.incrementAndGet();
                    }
                    return InteractionResult.PASS;
                });

        Properties serverProperties = GameTestServers.serverProperties();

        try (TestDedicatedServerContext server =
                        context.worldBuilder().createServer(serverProperties);
                TestServerConnection ignored = server.connect()) {
            this.server = server;
            y =
                    server.computeOnServer(
                            minecraftServer -> {
                                ServerLevel level = minecraftServer.overworld();
                                minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
                                level.getGameRules()
                                        .set(GameRules.ADVANCE_TIME, true, minecraftServer);
                                level.getGameRules()
                                        .set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
                                level.getGameRules()
                                        .set(
                                                GameRules.SHOW_ADVANCEMENT_MESSAGES,
                                                false,
                                                minecraftServer);
                                return level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
                            });
            // Ten blocks apart and both facing the player, so a scenario can stand nearer either
            // one and show that the
            // mod picks the nearest bed rather than a fixed one.
            redBed = new BlockPos(0, y, 8);
            blueBed = new BlockPos(10, y, 8);
            server.runOnServer(minecraftServer -> placeScene(minecraftServer.overworld()));
            command("gamemode survival @a");
            command("weather clear");
            command("time set day");
            teleport(0.5D, RED_BED_FOUR_AND_A_HALF_BLOCKS_AWAY_Z, TOWARD_THE_RED_BED);
            context.waitFor(
                    client -> client.level.getBlockState(blueBed).getBlock() instanceof BedBlock,
                    200);
            context.runOnClient(
                    client -> {
                        if (!AutoSleepClient.isInitialized()) {
                            throw new AssertionError(
                                    "Expected the AutoSleep client initializer to run before the client GameTest.");
                        }
                    });

            // Every scenario is one shot of one setup: by day the mod must do nothing, then night
            // falls on the same
            // setup and it must act at once. The setup happens before the step marker, which the
            // cut starts after,
            // so each clip opens on its scene, already in place.
            startScenario(
                    "out-of-reach",
                    "Day, the red bed 4.5 blocks away: nothing happens.",
                    "Night falls: it walks to the bed at once and sleeps.");
            assertStaysStill("by day, out of reach", DAY_CHECK_TICKS);
            nightFalls();
            assertWalksAtOnce();
            assertOutOfReach("before walking");
            fallAsleepIn(redBed, "when night falls", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();

            teleport(0.5D, IN_REACH_OF_THE_RED_BED_Z, BESIDE_THE_RED_BED, LOOK_DOWN_AT_THE_BED);
            assertInReach("beside the crosshair");
            assertBedsInView("beside the crosshair", redBed);
            assertCrosshairOffTheBed();
            startScenario(
                    "in-reach",
                    "Day, the red bed in reach beside the crosshair: nothing happens.",
                    "Night falls: it looks at the bed and uses it in one tick.");
            assertStaysStill("by day, in reach", DAY_CHECK_TICKS);
            nightFalls();
            // The use itself, not the sleeping state: the pose reaches the client a tick or two
            // after the server
            // accepts it, and this is what the mod decides on its own.
            int ticksToUse = context.waitFor(client -> clientBedUses.get() > 0, 20);
            if (ticksToUse > TICKS_TO_ACT) {
                throw new AssertionError(
                        "Expected the bed to be used within "
                                + TICKS_TO_ACT
                                + " ticks of the client"
                                + " seeing night, but it took "
                                + ticksToUse
                                + " ticks.");
            }
            context.waitFor(client -> client.player.isSleeping(), 20);
            assertSleepingPos(Optional.of(redBed), "with the bed in reach");
            assertNoUnplayableUse();
            ClientGameTestRecordingHud.log(
                    "The bed was used " + ticksToUse + " ticks after nightfall");
            sleepThroughTheNight();

            standBetweenTheBeds(9.0D, 1.0D);
            assertBedsInView("with the blue bed nearer", redBed, blueBed);
            startScenario(
                    "nearest-bed-blue",
                    "Day, two beds in view, the blue one nearer: nothing happens.",
                    "Night falls: it walks to the blue bed and sleeps.");
            assertStaysStill("by day, with the blue bed nearer", DAY_CHECK_TICKS);
            nightFalls();
            assertWalksAtOnce();
            assertOutOfReach("before walking to the blue bed");
            fallAsleepIn(blueBed, "when the blue bed is nearest", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();

            standBetweenTheBeds(2.0D, 1.0D);
            assertBedsInView("with the red bed nearer", redBed, blueBed);
            startScenario(
                    "nearest-bed-red",
                    "Day, the same two beds, the red one nearer: nothing happens.",
                    "Night falls: it walks to the red bed and sleeps.");
            assertStaysStill("by day, with the red bed nearer", DAY_CHECK_TICKS);
            nightFalls();
            assertWalksAtOnce();
            assertOutOfReach("before walking to the red bed");
            fallAsleepIn(redBed, "when the red bed is nearest", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();
            ClientGameTestRecordingHud.log(
                    "Client FPS at the end of the run: "
                            + context.computeOnClient(client -> client.getFps()));
            context.waitTicks(40);
        }
    }

    /**
     * Loads the pre-built scene, a saved structure, and checks the beds landed where the scenarios
     * expect them: a moved structure would otherwise show up only as a walk to the wrong place.
     */
    private void placeScene(ServerLevel level) {
        StructureTemplate scene =
                level.getServer()
                        .getStructureManager()
                        .get(SCENE)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "The test scene " + SCENE + " is missing."));
        BlockPos corner = redBed.offset(SCENE_CORNER_FROM_RED_BED);
        scene.placeInWorld(
                level,
                corner,
                corner,
                new StructurePlaceSettings(),
                level.getRandom(),
                Block.UPDATE_ALL);
        for (BlockPos head : new BlockPos[] {redBed, blueBed}) {
            BlockState state = level.getBlockState(head);
            if (!(state.getBlock() instanceof BedBlock)
                    || state.getValue(BedBlock.PART) != BedPart.HEAD
                    || state.getValue(BedBlock.FACING) != Direction.SOUTH) {
                throw new AssertionError(
                        "Expected the scene to put a bed's head, facing south, at "
                                + head
                                + ", but found "
                                + state
                                + ".");
            }
        }
        if (level.getBlockState(redBed).getBlock() != Blocks.RED_BED
                || level.getBlockState(blueBed).getBlock() != Blocks.BLUE_BED) {
            throw new AssertionError(
                    "Expected the red bed at " + redBed + " and the blue bed at " + blueBed + ".");
        }
    }

    /**
     * Sets daylight, lets the player settle where the scenario put them, empties the chat and only
     * then prints the step marker: the cut starts after the marker, so the clip opens on the
     * finished scene.
     */
    private void startScenario(String id, String title, String expected) {
        command("time set day");
        context.waitTicks(SETTLE_TICKS);
        serverBedUses.set(0);
        clientBedUses.set(0);
        clearChat();
        step(id, title, expected);
    }

    /** Dusk, ten ticks before night, then waits until the client sees night. */
    private void nightFalls() {
        command("time set " + TEN_TICKS_BEFORE_NIGHT);
        int ticksToNight = context.waitFor(client -> client.level.isDarkOutside(), 100);
        ClientGameTestRecordingHud.log("Night fell after " + ticksToNight + " ticks of dusk");
    }

    private void assertWalksAtOnce() {
        int ticksToWalk = context.waitFor(client -> hasBaritoneGoal(), 20);
        if (ticksToWalk > TICKS_TO_ACT) {
            throw new AssertionError(
                    "Expected the walk to start within "
                            + TICKS_TO_ACT
                            + " ticks of the client"
                            + " seeing night, but it started "
                            + ticksToWalk
                            + " ticks later.");
        }
        ClientGameTestRecordingHud.log("Walking on tick " + ticksToWalk + " of the night");
    }

    /**
     * The in-reach scenario's bed is in view but not under the crosshair, so the mod has to turn to
     * it.
     */
    private void assertCrosshairOffTheBed() {
        String onBed =
                context.computeOnClient(
                        client -> {
                            HitResult crosshair =
                                    client.player.raycastHitResult(1.0F, client.player);
                            return crosshair instanceof BlockHitResult looked
                                            && client.level
                                                            .getBlockState(looked.getBlockPos())
                                                            .getBlock()
                                                    instanceof BedBlock
                                    ? looked.getBlockPos().toShortString()
                                    : null;
                        });
        if (onBed != null) {
            throw new AssertionError(
                    "Expected the crosshair beside the red bed, but it is on the bed at "
                            + onBed
                            + ".");
        }
    }

    /**
     * Stands the player at a spot and turns the view so both beds sit in the middle of it, half
     * each side: the yaw between them, the short way round. The position goes first, because the
     * turn is measured from where the player now is; the second teleport is absolute, since
     * relative coordinates in {@code /tp} resolve against the command source and would put the
     * player at the world spawn instead.
     */
    private void standBetweenTheBeds(double x, double z) {
        teleport(x, z, TOWARD_THE_RED_BED);
        float yaw =
                context.computeOnClient(
                        client -> {
                            Vec3 eye = client.player.getEyePosition();
                            float toRed = BedLook.yawTo(eye, Vec3.atCenterOf(redBed));
                            float toBlue = BedLook.yawTo(eye, Vec3.atCenterOf(blueBed));
                            return BedLook.normalizeYaw(
                                    toRed + Mth.wrapDegrees(toBlue - toRed) / 2.0F);
                        });
        command("tp @a " + x + " " + y + " " + z + " " + yaw + " " + LOOK_DOWN_AT_THE_FLOOR);
        context.waitFor(
                client -> Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 1.0F, 40);
    }

    /**
     * A bed is on camera when every corner of both its halves is inside the view and its middle is
     * not hidden. The corners matter: a bed that is half out of the frame is not "on screen".
     */
    private void assertBedsInView(String when, BlockPos... beds) {
        for (BlockPos bed : beds) {
            String problem =
                    context.computeOnClient(
                            client -> {
                                LocalPlayer player = client.player;
                                Vec3 eye = player.getEyePosition();
                                double verticalFov = client.options.fov().get();
                                double halfHorizontalFov =
                                        Math.toDegrees(
                                                Math.atan(
                                                        Math.tan(Math.toRadians(verticalFov) / 2.0D)
                                                                * (double)
                                                                        client.getWindow()
                                                                                .getWidth()
                                                                / client.getWindow().getHeight()));
                                for (Vec3 corner : bedCorners(client.level, bed)) {
                                    double yawOff =
                                            Math.abs(
                                                    Mth.wrapDegrees(
                                                            BedLook.yawTo(eye, corner)
                                                                    - player.getYRot()));
                                    double pitchOff =
                                            Math.abs(
                                                    BedLook.pitchTo(eye, corner)
                                                            - player.getXRot());
                                    if (yawOff > halfHorizontalFov) {
                                        return "a corner of the bed at "
                                                + bed
                                                + " is "
                                                + Math.round(yawOff)
                                                + " degrees to the side of"
                                                + " a view that is "
                                                + Math.round(halfHorizontalFov * 2.0D)
                                                + " degrees wide";
                                    }
                                    if (pitchOff > verticalFov / 2.0D) {
                                        return "a corner of the bed at "
                                                + bed
                                                + " is "
                                                + Math.round(pitchOff)
                                                + " degrees above or"
                                                + " below the view";
                                    }
                                }
                                Vec3 middle = Vec3.atCenterOf(bed);
                                HitResult hit =
                                        client.level.clip(
                                                new ClipContext(
                                                        eye,
                                                        middle,
                                                        ClipContext.Block.OUTLINE,
                                                        ClipContext.Fluid.NONE,
                                                        player));
                                if (!(hit instanceof BlockHitResult blockHit)
                                        || hit.getType() == HitResult.Type.MISS
                                        || !sameBed(client.level, blockHit.getBlockPos(), bed)) {
                                    return "the bed at "
                                            + bed
                                            + " is hidden: the view hits "
                                            + (hit == null ? "nothing" : hit.getType());
                                }
                                return null;
                            });
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
     * The eight corners of each half of a bed: the block footprint's corners at its base and at its
     * top.
     */
    private static List<Vec3> bedCorners(Level level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        BlockPos other =
                state.getValue(BedBlock.PART) == BedPart.HEAD
                        ? bed.relative(state.getValue(BedBlock.FACING).getOpposite())
                        : bed.relative(state.getValue(BedBlock.FACING));
        List<Vec3> corners = new ArrayList<>();
        for (BlockPos block : new BlockPos[] {bed, other}) {
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

    private boolean hasBaritoneGoal() {
        return BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive();
    }

    /**
     * The rule a player cannot break, checked on the client where the mod decided to use the bed:
     * the crosshair was on that bed, the player was within vanilla reach of it, and no interaction
     * range was changed.
     *
     * @return what the use broke, or null when it would have been a normal right click
     */
    private static @Nullable String unplayableUse(LocalPlayer player, BlockHitResult used) {
        AttributeInstance blockRange = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (blockRange == null
                || blockRange.getValue() != 4.5D
                || !blockRange.getModifiers().isEmpty()) {
            return "the block interaction range is "
                    + (blockRange == null
                            ? "missing"
                            : blockRange.getValue() + " " + blockRange.getModifiers())
                    + " instead of vanilla's 4.5";
        }
        AttributeInstance entityRange = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (entityRange == null
                || entityRange.getValue() != 3.0D
                || !entityRange.getModifiers().isEmpty()) {
            return "the entity interaction range is "
                    + (entityRange == null
                            ? "missing"
                            : entityRange.getValue() + " " + entityRange.getModifiers())
                    + " instead of vanilla's 3.0";
        }
        HitResult crosshair = player.raycastHitResult(1.0F, player);
        if (!(crosshair instanceof BlockHitResult looked)) {
            return "the crosshair was on nothing, not on the bed at " + used.getBlockPos();
        }
        if (!sameBed(player.level(), looked.getBlockPos(), used.getBlockPos())) {
            return "the crosshair was on "
                    + looked.getBlockPos()
                    + ", not on the bed at "
                    + used.getBlockPos();
        }
        if (!player.isWithinBlockInteractionRange(used.getBlockPos(), 0.0D)) {
            return "the bed at "
                    + used.getBlockPos()
                    + " was out of reach of "
                    + player.getEyePosition();
        }
        return null;
    }

    private static boolean sameBed(Level level, BlockPos first, BlockPos second) {
        return level.getBlockState(first).getBlock() instanceof BedBlock
                && level.getBlockState(second).getBlock() instanceof BedBlock
                && bedHead(level, first).equals(bedHead(level, second));
    }

    private static BlockPos bedHead(Level level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? bed
                : bed.relative(state.getValue(BedBlock.FACING));
    }

    /**
     * Empties the chat box before a scenario starts, so each clip shows only the lines its own
     * scene earns. Called before the step marker, which the cut starts 0.10 s after, so the clear
     * itself is never on camera.
     */
    private void clearChat() {
        context.runOnClient(client -> client.gui.getChat().clearMessages(false));
    }

    private void command(String command) {
        server.runCommand(command);
    }

    private void teleport(double x, double z, float yaw) {
        teleport(x, z, yaw, LOOK_DOWN_AT_THE_FLOOR);
    }

    private void teleport(double x, double z, float yaw, float pitch) {
        command("tp @a " + x + " " + y + " " + z + " " + yaw + " " + pitch);
        BlockPos target = BlockPos.containing(x, y, z);
        context.waitFor(client -> client.player.blockPosition().equals(target), 100);
    }

    private void assertOutOfReach(String when) {
        boolean outOfReach =
                context.computeOnClient(
                        client ->
                                !inReachOfEitherHalf(client.player, redBed)
                                        && !inReachOfEitherHalf(client.player, blueBed));
        if (!outOfReach) {
            throw new AssertionError(
                    "Expected the player to be out of reach of both beds " + when + ".");
        }
        ClientGameTestRecordingHud.log("Out of reach of both beds " + when);
    }

    private void assertInReach(String when) {
        boolean inReach =
                context.computeOnClient(client -> inReachOfEitherHalf(client.player, redBed));
        if (!inReach) {
            throw new AssertionError("Expected the red bed to be in reach " + when + ".");
        }
        ClientGameTestRecordingHud.log("The red bed is in reach " + when);
    }

    /**
     * The mod measures reach to the nearer half of the bed, so the player may be in reach of the
     * foot and not the head.
     */
    private static boolean inReachOfEitherHalf(LocalPlayer player, BlockPos head) {
        BlockPos foot = head.relative(Direction.NORTH);
        return AutoSleeper.inReach(player, head) || AutoSleeper.inReach(player, foot);
    }

    private void assertStaysStill(String when, int ticks) {
        Vec3 start = context.computeOnClient(client -> client.player.position());
        float yaw = context.computeOnClient(client -> client.player.getYRot());
        float pitch = context.computeOnClient(client -> client.player.getXRot());
        context.waitTicks(ticks);
        Vec3 moved = context.computeOnClient(client -> client.player.position().subtract(start));
        if (moved.length() > 0.1D) {
            throw new AssertionError(
                    "Expected the player to stay put " + when + ", but they moved " + moved + ".");
        }
        float yawAfter = context.computeOnClient(client -> client.player.getYRot());
        float pitchAfter = context.computeOnClient(client -> client.player.getXRot());
        if (Math.abs(yawAfter - yaw) > 1.0E-3F || Math.abs(pitchAfter - pitch) > 1.0E-3F) {
            throw new AssertionError(
                    "Expected the view to stay put "
                            + when
                            + ", but it turned from "
                            + yaw
                            + "/"
                            + pitch
                            + " to "
                            + yawAfter
                            + "/"
                            + pitchAfter
                            + ".");
        }
        assertSleepingPos(Optional.empty(), when);
        int uses = serverBedUses.get();
        if (uses != 0) {
            throw new AssertionError(
                    "Expected no bed use " + when + ", but the server saw " + uses + ".");
        }
        ClientGameTestRecordingHud.log("Nothing happened for " + ticks + " ticks " + when);
        assertNoUnplayableUse();
    }

    private void fallAsleepIn(BlockPos bed, String when, int timeoutTicks) {
        context.waitFor(client -> client.player.isSleeping(), timeoutTicks);
        assertSleepingPos(Optional.of(bed), when);
        assertNoUnplayableUse();
        int clientUses = clientBedUses.get();
        if (clientUses == 0) {
            throw new AssertionError(
                    "The client never ran a bed use "
                            + when
                            + ", so the look-at-bed and reach rules were not checked.");
        }
        long dayTime =
                context.computeOnClient(
                        client ->
                                Math.floorMod(
                                        client.level.getDayTime(),
                                        SharedConstants.TICKS_PER_GAME_DAY));
        ClientGameTestRecordingHud.log(
                "Asleep in the "
                        + (bed.equals(redBed) ? "red" : "blue")
                        + " bed at "
                        + dayTime
                        + ": "
                        + clientUses
                        + " client and "
                        + serverBedUses.get()
                        + " server use");
    }

    private void assertNoUnplayableUse() {
        String unplayable = unplayableBedUse.get();
        if (unplayable != null) {
            throw new AssertionError("AutoSleep used a bed as no player could: " + unplayable);
        }
    }

    private void sleepThroughTheNight() {
        // Count from before the wake-up, so an attempt sent in the very tick the player wakes is
        // not missed.
        serverBedUses.set(0);
        clientBedUses.set(0);
        context.waitFor(client -> !client.player.isSleeping(), WAKE_UP_TIMEOUT_TICKS);
        ClientGameTestRecordingHud.log("Woke up: the night was skipped");
        assertStaysAwake("after waking up in the morning", AWAKE_CHECK_TICKS);
    }

    private void assertStaysAwake(String when, int ticks) {
        context.waitTicks(ticks);
        assertSleepingPos(Optional.empty(), when);
        int uses = serverBedUses.get();
        if (uses != 0) {
            throw new AssertionError(
                    "Expected no bed use " + when + ", but the server saw " + uses + ".");
        }
        ClientGameTestRecordingHud.log("Awake for " + ticks + " ticks " + when + ": no bed use");
        assertNoUnplayableUse();
    }

    private void assertSleepingPos(Optional<BlockPos> expected, String when) {
        Optional<BlockPos> actual =
                context.computeOnClient(client -> client.player.getSleepingPos());
        if (!actual.equals(expected)) {
            throw new AssertionError(
                    "Expected the player's bed "
                            + when
                            + " to be "
                            + expected
                            + ", but was "
                            + actual
                            + ".");
        }
    }
}
