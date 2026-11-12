package io.github.brainage04.autosleep;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

import baritone.api.BaritoneAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("UnstableApiUsage")
public class BedRuleClientGameTest implements FabricClientGameTest {
    private static final ResourceKey<Level> EXPLODING_BEDS = testDimension("exploding_beds");
    private static final ResourceKey<Level> ALLOWED_BEDS = testDimension("allowed_beds");
    private static final int Y = 100;

    /** Far from the End's main island, so the dragon stays away. */
    private static final BlockPos PLAYER = new BlockPos(1000, Y, 0);

    private static final BlockPos BED_HEAD = PLAYER.offset(0, 0, 8);
    private static final BlockPos OTHER_BED_HEAD = PLAYER.offset(-10, 0, 0);
    private static final Vec3 BESIDE_BED =
            new Vec3(PLAYER.getX() + 0.5D, Y, BED_HEAD.getZ() - 1.5D);
    private static final int GROUND_Y = 4;
    private static final int OUT_OF_RANGE_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 4;
    private static final long MIDNIGHT = 18000L;
    private static final int WATCH_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 8;
    private static final int WALK_AND_SLEEP_TIMEOUT_TICKS = 400;
    private static final int WAKE_UP_TIMEOUT_TICKS = 600;

    private final AtomicInteger serverBedUses = new AtomicInteger();
    private final List<String> failures = new ArrayList<>();
    private ClientGameTestContext context;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register(
                (player, level, hand, hitResult) -> {
                    if (!level.isClientSide()
                            && level.getBlockState(hitResult.getBlockPos()).getBlock()
                                    instanceof BedBlock) {
                        serverBedUses.incrementAndGet();
                    }
                    return InteractionResult.PASS;
                });

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Mode mode = Mode.SINGLEPLAYER;
            TestServerContext server = singleplayer.getServer();
            setUp(server);
            check(mode, server, Level.NETHER, Expect.STAYS_PUT);
            check(mode, server, Level.END, Expect.STAYS_PUT);
            check(mode, server, EXPLODING_BEDS, Expect.STAYS_PUT);
            check(mode, server, ALLOWED_BEDS, Expect.SLEEPS);
            checkAnswerIsNotReusedForAnotherBed(server);
            checkAnswerIsNotReusedInAnotherDimension(server);
            checkAnswerIsNotReusedLater(server);
            check(mode, server, Level.OVERWORLD, Expect.SLEEPS_THROUGH_THE_NIGHT);
        }

        try (TestDedicatedServerContext server =
                        context.worldBuilder().createServer(GameTestServers.serverProperties());
                TestServerConnection ignored = server.connect()) {
            Mode mode = Mode.DEDICATED;
            setUp(server);
            check(mode, server, Level.NETHER, Expect.STAYS_PUT);
            check(mode, server, Level.END, Expect.STAYS_PUT);
            check(mode, server, EXPLODING_BEDS, Expect.STAYS_PUT);
            check(mode, server, ALLOWED_BEDS, Expect.STAYS_PUT);
            check(mode, server, Level.OVERWORLD, Expect.SLEEPS_THROUGH_THE_NIGHT);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    failures.size()
                            + " bed-rule case(s) failed:\n  "
                            + String.join("\n  ", failures));
        }
    }

    private void setUp(TestServerContext server) {
        server.runOnServer(
                minecraftServer -> {
                    minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
                    ServerLevel overworld = minecraftServer.overworld();
                    overworld.getGameRules().set(GameRules.ADVANCE_TIME, false, minecraftServer);
                    overworld.getGameRules().set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
                    overworld
                            .getGameRules()
                            .set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, minecraftServer);
                    level(minecraftServer, EXPLODING_BEDS);
                    level(minecraftServer, ALLOWED_BEDS);
                });
        server.runCommand("gamemode survival @a");
        server.runCommand("weather clear");
        server.runCommand("time set " + MIDNIGHT);
        // Full damage immunity, so a bed the mod wrongly uses cannot kill the player and end the
        // run early.
        server.runCommand("effect give @a minecraft:resistance infinite 4 true");
    }

    private void check(
            Mode mode, TestServerContext server, ResourceKey<Level> dimension, Expect expect) {
        String dimensionId = dimension.identifier().toString();
        String id = mode.id + "-" + dimension.identifier().getPath() + "-bed";
        String situation = "Night in " + dimensionId + " (" + mode.title + "), bed out of reach";
        switch (expect) {
            case STAYS_PUT ->
                    step(
                            id,
                            situation,
                            "Stays put, never uses the bed (" + reasonToStay(dimension) + ")");
            case SLEEPS -> step(id, situation, "Walks to the bed and sleeps");
            case SLEEPS_THROUGH_THE_NIGHT ->
                    step(
                            id,
                            situation,
                            "Walks to the bed and sleeps, stays up after the night is skipped");
        }

        server.runOnServer(
                minecraftServer -> {
                    buildStage(level(minecraftServer, dimension));
                    minecraftServer
                            .overworld()
                            .getGameRules()
                            .set(
                                    GameRules.ADVANCE_TIME,
                                    expect == Expect.SLEEPS_THROUGH_THE_NIGHT,
                                    minecraftServer);
                });
        server.runCommand("time set " + MIDNIGHT);
        teleport(server, dimension, PLAYER.getX() + 0.5D, PLAYER.getZ() + 0.5D, 0.0F);
        context.waitFor(
                client ->
                        client.level != null
                                && client.level.dimension() == dimension
                                && client.player.blockPosition().equals(PLAYER)
                                && !client.player.isSleeping()
                                && client.level.getBlockState(BED_HEAD).getBlock()
                                        instanceof BedBlock,
                400);
        logRules(mode, server, dimension, BED_HEAD);

        serverBedUses.set(0);
        Vec3 start = context.computeOnClient(client -> client.player.position());
        if (expect != Expect.STAYS_PUT) {
            try {
                context.waitFor(client -> client.player.isSleeping(), WALK_AND_SLEEP_TIMEOUT_TICKS);
                ClientGameTestRecordingHud.log("Asleep in the bed");
                System.out.println("[BED_RULE] " + id + ": asleep");
            } catch (AssertionError e) {
                failures.add(
                        id
                                + ": expected the mod to walk to the bed and sleep, but it did not ("
                                + e.getMessage()
                                + ")");
                ClientGameTestRecordingHud.log("FAILED: did not sleep");
                return;
            }
            if (expect == Expect.SLEEPS_THROUGH_THE_NIGHT) {
                stayUpAfterTheNightIsSkipped(id);
            }
            return;
        }

        context.waitTicks(WATCH_TICKS);
        double moved =
                context.computeOnClient(client -> client.player.position().distanceTo(start));
        int uses = serverBedUses.get();
        boolean bedLeft =
                server.computeOnServer(
                        minecraftServer ->
                                level(minecraftServer, dimension).getBlockState(BED_HEAD).getBlock()
                                        instanceof BedBlock);
        String outcome =
                "moved "
                        + String.format("%.1f", moved)
                        + " blocks, the server saw "
                        + uses
                        + " bed use(s), the bed "
                        + (bedLeft ? "is still there" : "is gone (exploded)");
        System.out.println("[BED_RULE] " + id + ": " + outcome);
        if (moved > 0.5D || uses != 0 || !bedLeft) {
            failures.add(id + ": expected the mod to leave the bed alone, but it " + outcome);
            ClientGameTestRecordingHud.log("FAILED: " + outcome);
        } else {
            ClientGameTestRecordingHud.log("Stayed put for " + WATCH_TICKS / 20 + " s, no bed use");
        }
    }

    private void stayUpAfterTheNightIsSkipped(String id) {
        // Count from before the wake-up, so an attempt sent in the very tick the player wakes is
        // not missed.
        serverBedUses.set(0);
        try {
            context.waitFor(client -> !client.player.isSleeping(), WAKE_UP_TIMEOUT_TICKS);
        } catch (AssertionError e) {
            failures.add(
                    id
                            + ": expected the night to be skipped and the player to wake up ("
                            + e.getMessage()
                            + ")");
            return;
        }
        ClientGameTestRecordingHud.log("Woke up: the night was skipped");
        context.waitTicks(WATCH_TICKS);
        boolean sleeping = context.computeOnClient(client -> client.player.isSleeping());
        int uses = serverBedUses.get();
        System.out.println(
                "[BED_RULE] "
                        + id
                        + " morning: sleeping="
                        + sleeping
                        + ", the server saw "
                        + uses
                        + " bed use(s)");
        if (sleeping || uses != 0) {
            failures.add(
                    id
                            + ": expected the player to stay up after the night was skipped, but sleeping="
                            + sleeping
                            + " and the server saw "
                            + uses
                            + " bed use(s)");
            ClientGameTestRecordingHud.log("FAILED: went back to bed in the morning");
        } else {
            ClientGameTestRecordingHud.log(
                    "Awake for " + WATCH_TICKS / 20 + " s in the morning, no bed use");
        }
    }

    private void checkAnswerIsNotReusedForAnotherBed(TestServerContext server) {
        String id = "singleplayer-exploding_biome-bed";
        step(
                id,
                "Walking to a bed in autosleep_test:allowed_beds, then put beside a bed in a biome whose beds explode",
                "Never uses the second bed: the answer about the first bed is not reused");
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = level(minecraftServer, ALLOWED_BEDS);
                    buildStage(level);
                    placeBed(level, OTHER_BED_HEAD);
                });
        server.runCommand(
                "execute in "
                        + ALLOWED_BEDS.identifier()
                        + " run fillbiome "
                        + coordinates(OTHER_BED_HEAD.offset(-4, -4, -4))
                        + " "
                        + coordinates(OTHER_BED_HEAD.offset(4, 4, 4))
                        + " autosleep_test:exploding_beds");
        server.runCommand("time set " + MIDNIGHT);
        teleport(server, ALLOWED_BEDS, PLAYER.getX() + 0.5D, PLAYER.getZ() + 0.5D, 0.0F);
        context.waitFor(
                client ->
                        client.level.dimension() == ALLOWED_BEDS
                                && client.player.blockPosition().equals(PLAYER)
                                && !client.player.isSleeping()
                                && client.level.getBlockState(OTHER_BED_HEAD).getBlock()
                                        instanceof BedBlock,
                400);
        logRules(Mode.SINGLEPLAYER, server, ALLOWED_BEDS, OTHER_BED_HEAD);

        if (!startWalkingInAllowedBeds(server, id)) {
            return;
        }
        ClientGameTestRecordingHud.log("Walking to the first bed; put beside the second");
        serverBedUses.set(0);
        teleport(
                server,
                ALLOWED_BEDS,
                OTHER_BED_HEAD.getX() + 1.5D,
                OTHER_BED_HEAD.getZ() + 0.5D,
                90.0F);
        Vec3 besideOtherBed = Vec3.atBottomCenterOf(OTHER_BED_HEAD.east());
        context.waitFor(client -> client.player.position().distanceTo(besideOtherBed) < 1.0D, 100);
        cancelWalk();
        expectBedLeftAlone(id, server, ALLOWED_BEDS, OTHER_BED_HEAD);
    }

    private void checkAnswerIsNotReusedInAnotherDimension(TestServerContext server) {
        String id = "singleplayer-other_dimension-bed";
        step(
                id,
                "Walking to a bed in autosleep_test:allowed_beds, then put beside the bed at the same position in autosleep_test:exploding_beds",
                "Never uses it: the answer was about the other dimension's bed");
        server.runOnServer(
                minecraftServer -> {
                    buildStage(level(minecraftServer, ALLOWED_BEDS));
                    buildStage(level(minecraftServer, EXPLODING_BEDS));
                });
        server.runCommand("time set " + MIDNIGHT);
        if (!startWalkingInAllowedBeds(server, id)) {
            return;
        }
        ClientGameTestRecordingHud.log(
                "Walking to the bed; put beside the same bed in exploding_beds");
        serverBedUses.set(0);
        teleport(server, EXPLODING_BEDS, PLAYER.getX() + 0.5D, Y, BESIDE_BED.z, 0.0F);
        context.waitFor(
                client ->
                        client.level.dimension() == EXPLODING_BEDS
                                && client.player.position().distanceTo(BESIDE_BED) < 1.0D,
                200);
        cancelWalk();
        expectBedLeftAlone(id, server, EXPLODING_BEDS, BED_HEAD);
    }

    private void checkAnswerIsNotReusedLater(TestServerContext server) {
        String id = "singleplayer-stale_answer-bed";
        step(
                id,
                "Walking to a bed in autosleep_test:allowed_beds, then out of range while its biome changes to one whose beds explode",
                "Back beside it: never uses it, the old answer is not reused");
        server.runOnServer(minecraftServer -> buildStage(level(minecraftServer, ALLOWED_BEDS)));
        server.runCommand("time set " + MIDNIGHT);
        if (!startWalkingInAllowedBeds(server, id)) {
            return;
        }
        ClientGameTestRecordingHud.log(
                "Walking to the bed; put out of range for " + OUT_OF_RANGE_TICKS / 20 + " s");
        serverBedUses.set(0);
        teleport(server, ALLOWED_BEDS, PLAYER.getX() + 0.5D, GROUND_Y, PLAYER.getZ() + 0.5D, 0.0F);
        context.waitFor(client -> client.player.getY() < GROUND_Y + 1, 100);
        cancelWalk();
        context.waitTicks(OUT_OF_RANGE_TICKS);
        server.runCommand(
                "execute in "
                        + ALLOWED_BEDS.identifier()
                        + " run fillbiome "
                        + coordinates(BED_HEAD.offset(-4, -4, -4))
                        + " "
                        + coordinates(BED_HEAD.offset(4, 4, 4))
                        + " autosleep_test:exploding_beds");
        logRules(Mode.SINGLEPLAYER, server, ALLOWED_BEDS, BED_HEAD);
        ClientGameTestRecordingHud.log(
                "The bed's biome now makes beds explode; back beside the bed");
        teleport(server, ALLOWED_BEDS, PLAYER.getX() + 0.5D, Y, BESIDE_BED.z, 0.0F);
        context.waitFor(client -> client.player.position().distanceTo(BESIDE_BED) < 1.0D, 100);
        expectBedLeftAlone(id, server, ALLOWED_BEDS, BED_HEAD);
    }

    private boolean startWalkingInAllowedBeds(TestServerContext server, String id) {
        teleport(server, ALLOWED_BEDS, PLAYER.getX() + 0.5D, Y, PLAYER.getZ() + 0.5D, 0.0F);
        context.waitFor(
                client ->
                        client.level.dimension() == ALLOWED_BEDS
                                && client.player.blockPosition().equals(PLAYER)
                                && !client.player.isSleeping()
                                && client.level.getBlockState(BED_HEAD).getBlock()
                                        instanceof BedBlock,
                400);
        Vec3 start = context.computeOnClient(client -> client.player.position());
        try {
            context.waitFor(
                    client -> client.player.position().distanceTo(start) > 0.5D,
                    WALK_AND_SLEEP_TIMEOUT_TICKS);
            return true;
        } catch (AssertionError e) {
            failures.add(
                    id + ": expected the mod to start walking to the bed (" + e.getMessage() + ")");
            return false;
        }
    }

    private void cancelWalk() {
        context.runOnClient(
                client ->
                        BaritoneAPI.getProvider()
                                .getPrimaryBaritone()
                                .getPathingBehavior()
                                .cancelEverything());
    }

    private void expectBedLeftAlone(
            String id, TestServerContext server, ResourceKey<Level> dimension, BlockPos head) {
        context.waitTicks(WATCH_TICKS);
        int uses = serverBedUses.get();
        boolean bedLeft =
                server.computeOnServer(
                        minecraftServer ->
                                level(minecraftServer, dimension).getBlockState(head).getBlock()
                                        instanceof BedBlock);
        String outcome =
                "the server saw "
                        + uses
                        + " bed use(s), the bed "
                        + (bedLeft ? "is still there" : "is gone (exploded)");
        System.out.println("[BED_RULE] " + id + ": " + outcome);
        if (uses != 0 || !bedLeft) {
            failures.add(id + ": expected the mod to leave the bed alone, but " + outcome);
            ClientGameTestRecordingHud.log("FAILED: " + outcome);
        } else {
            ClientGameTestRecordingHud.log("Left the bed alone for " + WATCH_TICKS / 20 + " s");
        }
    }

    private static void teleport(
            TestServerContext server, ResourceKey<Level> dimension, double x, double z, float yaw) {
        teleport(server, dimension, x, Y, z, yaw);
    }

    private static void teleport(
            TestServerContext server,
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z,
            float yaw) {
        server.runCommand(
                "execute in "
                        + dimension.identifier()
                        + " run tp @a "
                        + x
                        + " "
                        + y
                        + " "
                        + z
                        + " "
                        + yaw
                        + " 10");
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String reasonToStay(ResourceKey<Level> dimension) {
        if (dimension == ALLOWED_BEDS) {
            return "the client cannot see a datapack dimension's bed rule";
        }
        return "beds explode here";
    }

    private void logRules(
            Mode mode, TestServerContext server, ResourceKey<Level> dimension, BlockPos head) {
        String serverRule =
                server.computeOnServer(
                        minecraftServer -> {
                            ServerLevel level = level(minecraftServer, dimension);
                            return describe(
                                    level.environmentAttributes()
                                            .getValue(EnvironmentAttributes.BED_RULE, head),
                                    level);
                        });
        String clientRule =
                context.computeOnClient(
                        client ->
                                describe(
                                        client.level
                                                .environmentAttributes()
                                                .getValue(EnvironmentAttributes.BED_RULE, head),
                                        client.level));
        System.out.println(
                "[BED_RULE] "
                        + mode.id
                        + " "
                        + dimension.identifier()
                        + " "
                        + coordinates(head)
                        + " server rule: "
                        + serverRule
                        + " | client rule: "
                        + clientRule);
    }

    private static String describe(BedRule rule, Level level) {
        return "explodes="
                + rule.explodes()
                + " can_sleep="
                + rule.canSleep().getSerializedName()
                + " canSleepNow="
                + rule.canSleep(level);
    }

    private static ServerLevel level(MinecraftServer server, ResourceKey<Level> dimension) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            throw new AssertionError(
                    "No level " + dimension.identifier() + "; is the test datapack loaded?");
        }
        return level;
    }

    private static void buildStage(ServerLevel level) {
        for (BlockPos pos :
                BlockPos.betweenClosed(PLAYER.offset(-12, -1, -12), PLAYER.offset(12, 4, 12))) {
            level.setBlockAndUpdate(
                    pos,
                    pos.getY() == Y - 1
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
        }
        placeBed(level, BED_HEAD);
    }

    private static void placeBed(ServerLevel level, BlockPos head) {
        BlockState bed =
                Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(
                head.relative(Direction.NORTH), bed.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static ResourceKey<Level> testDimension(String path) {
        return ResourceKey.create(
                Registries.DIMENSION, Identifier.fromNamespaceAndPath("autosleep_test", path));
    }

    private enum Mode {
        SINGLEPLAYER("singleplayer", "singleplayer"),
        DEDICATED("dedicated", "dedicated server");

        final String id;
        final String title;

        Mode(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private enum Expect {
        STAYS_PUT,
        SLEEPS,
        SLEEPS_THROUGH_THE_NIGHT
    }
}
