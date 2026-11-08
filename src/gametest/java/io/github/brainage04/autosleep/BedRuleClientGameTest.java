package io.github.brainage04.autosleep;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

/**
 * Checks that the mod follows each dimension's bed rule, in singleplayer and on a dedicated server, at night with a bed
 * eight blocks away: it leaves beds alone in the Nether and in {@code autosleep_test:exploding_beds} (the test
 * datapack's dimension with a day-night cycle and exploding beds), sleeps in the Overworld, and in
 * {@code autosleep_test:allowed_beds} (a datapack dimension whose beds work) sleeps in singleplayer, where the integrated
 * server tells it the rule, but not on a dedicated server, where the client cannot see that dimension type's rule.
 * Every case runs; the failures are listed together at the end.
 */
@SuppressWarnings("UnstableApiUsage")
public class BedRuleClientGameTest implements FabricClientGameTest {
    private static final ResourceKey<Level> EXPLODING_BEDS = testDimension("exploding_beds");
    private static final ResourceKey<Level> ALLOWED_BEDS = testDimension("allowed_beds");
    private static final int Y = 100;
    private static final BlockPos PLAYER = new BlockPos(0, Y, 0);
    private static final BlockPos BED_HEAD = new BlockPos(0, Y, 8);
    /** Night by the Overworld clock; time does not advance during this test. */
    private static final long MIDNIGHT = 18000L;
    /** Long enough for several of the mod's attempts. */
    private static final int WATCH_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 8;
    /** Path search and a walk of eight blocks. */
    private static final int WALK_AND_SLEEP_TIMEOUT_TICKS = 400;

    private final AtomicInteger serverBedUses = new AtomicInteger();
    private final List<String> failures = new ArrayList<>();
    private ClientGameTestContext context;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() && level.getBlockState(hitResult.getBlockPos()).getBlock() instanceof BedBlock) {
                serverBedUses.incrementAndGet();
            }
            return InteractionResult.PASS;
        });

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Mode mode = Mode.SINGLEPLAYER;
            TestServerContext server = singleplayer.getServer();
            setUp(server);
            check(mode, server, Level.NETHER, false);
            check(mode, server, EXPLODING_BEDS, false);
            check(mode, server, ALLOWED_BEDS, true);
            check(mode, server, Level.OVERWORLD, true);
        }

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(GameTestServers.serverProperties());
             TestServerConnection ignored = server.connect()) {
            Mode mode = Mode.DEDICATED;
            setUp(server);
            check(mode, server, Level.NETHER, false);
            check(mode, server, EXPLODING_BEDS, false);
            check(mode, server, ALLOWED_BEDS, false);
            check(mode, server, Level.OVERWORLD, true);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " bed-rule case(s) failed:\n  " + String.join("\n  ", failures));
        }
    }

    private void setUp(TestServerContext server) {
        server.runOnServer(minecraftServer -> {
            minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
            ServerLevel overworld = minecraftServer.overworld();
            overworld.getGameRules().set(GameRules.ADVANCE_TIME, false, minecraftServer);
            overworld.getGameRules().set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
            overworld.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, minecraftServer);
            level(minecraftServer, EXPLODING_BEDS);
            level(minecraftServer, ALLOWED_BEDS);
        });
        server.runCommand("gamemode survival @a");
        server.runCommand("weather clear");
        server.runCommand("time set " + MIDNIGHT);
        // Full damage immunity, so a bed the mod wrongly uses cannot kill the player and end the run early.
        server.runCommand("effect give @a minecraft:resistance infinite 4 true");
    }

    private void check(Mode mode, TestServerContext server, ResourceKey<Level> dimension, boolean sleeps) {
        String dimensionId = dimension.identifier().toString();
        String id = mode.id + "-" + dimension.identifier().getPath() + "-bed";
        String situation = "Night in " + dimensionId + " (" + mode.title + "), bed out of reach";
        if (sleeps) {
            step(id, situation, "Walks to the bed and sleeps");
        } else {
            step(id, situation, "Stays put, never uses the bed (" + reasonToStay(dimension) + ")");
        }

        server.runOnServer(minecraftServer -> buildStage(level(minecraftServer, dimension)));
        server.runCommand("time set " + MIDNIGHT);
        // Also wakes a player still asleep from the previous case. Facing south, looking 10 degrees down: the bed sits
        // just below the crosshair, clear of the recording panels at the top of the screen.
        server.runCommand("execute in " + dimensionId + " run tp @a 0.5 " + Y + " 0.5 0 10");
        context.waitFor(client -> client.level != null && client.level.dimension() == dimension
                && client.player.blockPosition().equals(PLAYER) && !client.player.isSleeping()
                && client.level.getBlockState(BED_HEAD).getBlock() instanceof BedBlock, 400);
        logRules(mode, server, dimension);

        serverBedUses.set(0);
        Vec3 start = context.computeOnClient(client -> client.player.position());
        if (sleeps) {
            try {
                context.waitFor(client -> client.player.isSleeping(), WALK_AND_SLEEP_TIMEOUT_TICKS);
                ClientGameTestRecordingHud.log("Asleep in the bed");
                System.out.println("[BED_RULE] " + id + ": asleep");
            } catch (AssertionError e) {
                failures.add(id + ": expected the mod to walk to the bed and sleep, but it did not (" + e.getMessage() + ")");
                ClientGameTestRecordingHud.log("FAILED: did not sleep");
            }
            return;
        }

        context.waitTicks(WATCH_TICKS);
        double moved = context.computeOnClient(client -> client.player.position().distanceTo(start));
        int uses = serverBedUses.get();
        boolean bedLeft = server.computeOnServer(minecraftServer ->
                level(minecraftServer, dimension).getBlockState(BED_HEAD).getBlock() instanceof BedBlock);
        String outcome = "moved " + String.format("%.1f", moved) + " blocks, the server saw " + uses + " bed use(s), the bed "
                + (bedLeft ? "is still there" : "is gone (exploded)");
        System.out.println("[BED_RULE] " + id + ": " + outcome);
        if (moved > 0.5D || uses != 0 || !bedLeft) {
            failures.add(id + ": expected the mod to leave the bed alone, but it " + outcome);
            ClientGameTestRecordingHud.log("FAILED: " + outcome);
        } else {
            ClientGameTestRecordingHud.log("Stayed put for " + WATCH_TICKS / 20 + " s, no bed use");
        }
    }

    private static String reasonToStay(ResourceKey<Level> dimension) {
        if (dimension == ALLOWED_BEDS) {
            return "the client cannot see a datapack dimension's bed rule";
        }
        return "beds explode here";
    }

    /** Logs the bed rule each side reads at the bed's head: the client's is always the default (BED_RULE is not synced). */
    private void logRules(Mode mode, TestServerContext server, ResourceKey<Level> dimension) {
        String serverRule = server.computeOnServer(minecraftServer -> {
            ServerLevel level = level(minecraftServer, dimension);
            return describe(level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, BED_HEAD), level);
        });
        String clientRule = context.computeOnClient(client ->
                describe(client.level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, BED_HEAD), client.level));
        System.out.println("[BED_RULE] " + mode.id + " " + dimension.identifier() + " server rule: " + serverRule + " | client rule: " + clientRule);
    }

    private static String describe(BedRule rule, Level level) {
        return "explodes=" + rule.explodes() + " can_sleep=" + rule.canSleep().getSerializedName() + " canSleepNow=" + rule.canSleep(level);
    }

    private static ServerLevel level(MinecraftServer server, ResourceKey<Level> dimension) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            throw new AssertionError("No level " + dimension.identifier() + "; is the test datapack loaded?");
        }
        return level;
    }

    /** An air box with a stone floor, the player's spot at its centre and a red bed eight blocks south. */
    private static void buildStage(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(PLAYER.offset(-12, -1, -12), PLAYER.offset(12, 4, 12))) {
            level.setBlockAndUpdate(pos, pos.getY() == Y - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(BED_HEAD.relative(Direction.NORTH), bed.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(BED_HEAD, bed.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static ResourceKey<Level> testDimension(String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("autosleep_test", path));
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
}
