package io.github.brainage04.autosleep;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

@SuppressWarnings("UnstableApiUsage")
public class AutoSleepClientGameTest implements FabricClientGameTest {
    private static final int AWAKE_CHECK_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 5;
    private static final int WALK_AND_SLEEP_TIMEOUT_TICKS = 400;
    private static final int WAKE_UP_TIMEOUT_TICKS = 600;
    private static final long BEFORE_NIGHT = DayNightCycleHud.NIGHT_START - 100L;

    private final AtomicInteger serverBedUses = new AtomicInteger();
    private ClientGameTestContext context;
    private TestDedicatedServerContext server;
    private int y;
    private BlockPos redBed;
    private BlockPos blueBed;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() && level.getBlockState(hitResult.getBlockPos()).getBlock() instanceof BedBlock) {
                serverBedUses.incrementAndGet();
            }
            return InteractionResult.PASS;
        });

        Properties serverProperties = GameTestServers.serverProperties();

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(serverProperties);
             TestServerConnection ignored = server.connect()) {
            this.server = server;
            y = server.computeOnServer(minecraftServer -> {
                ServerLevel level = minecraftServer.overworld();
                minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
                level.getGameRules().set(GameRules.ADVANCE_TIME, true, minecraftServer);
                level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
                level.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, minecraftServer);
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            });
            redBed = new BlockPos(0, y, 10);
            blueBed = new BlockPos(12, y, -6);
            server.runOnServer(minecraftServer -> {
                placeBed(minecraftServer.overworld(), redBed, Blocks.RED_BED);
                placeBed(minecraftServer.overworld(), blueBed, Blocks.BLUE_BED);
            });
            command("gamemode survival @a");
            command("weather clear");
            command("time set day");
            teleport(0.5, 0.5);
            context.waitFor(client -> client.level.getBlockState(blueBed).getBlock() instanceof BedBlock, 200);
            context.runOnClient(client -> {
                if (!AutoSleepClient.isInitialized()) {
                    throw new AssertionError("Expected the AutoSleep client initializer to run before the client GameTest.");
                }
            });

            step("daytime", "Daytime, two beds out of reach", "Stays where they are, awake");
            serverBedUses.set(0);
            Vec3 start = context.computeOnClient(client -> client.player.position());
            assertStaysAwake("during the day");
            double moved = context.computeOnClient(client -> client.player.position().distanceTo(start));
            if (moved > 0.5D) {
                throw new AssertionError("Expected the player to stay put during the day, but they moved " + moved + " blocks.");
            }

            step("nightfall-walks-to-bed", "Night falls, the red bed is nearest but out of reach", "Walks to the red bed and sleeps");
            command("time set " + BEFORE_NIGHT);
            fallAsleepIn(redBed, "when night falls", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();

            step("bed-in-reach", "Next night, woke up beside the red bed", "Sleeps straight away");
            command("time set night");
            fallAsleepIn(redBed, "with the bed in reach", AutoSleeper.RETRY_INTERVAL_TICKS * 3);
            sleepThroughTheNight();

            step("nearest-bed", "Moved closer to the blue bed, both out of reach", "Walks to the blue bed and sleeps");
            teleport(6.5, 0.5);
            command("time set night");
            fallAsleepIn(blueBed, "when the blue bed is nearest", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();
            context.waitTicks(40);
        }
    }

    private static void placeBed(ServerLevel level, BlockPos head, Block bed) {
        BlockState state = bed.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(head.relative(Direction.NORTH), state.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(head, state.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private void command(String command) {
        server.runCommand(command);
    }

    private void teleport(double x, double z) {
        command("tp @a " + x + " " + y + " " + z + " 0 30");
        BlockPos target = BlockPos.containing(x, y, z);
        context.waitFor(client -> client.player.blockPosition().equals(target), 100);
    }

    private void fallAsleepIn(BlockPos bed, String when, int timeoutTicks) {
        context.waitFor(client -> client.player.isSleeping(), timeoutTicks);
        assertSleepingPos(Optional.of(bed), when);
        long dayTime = context.computeOnClient(client -> Math.floorMod(client.level.getDayTime(), SharedConstants.TICKS_PER_GAME_DAY));
        ClientGameTestRecordingHud.log("Asleep in the " + (bed.equals(redBed) ? "red" : "blue") + " bed at day time " + dayTime);
    }

    private void sleepThroughTheNight() {
        // Count from before the wake-up, so an attempt sent in the very tick the player wakes is not missed.
        serverBedUses.set(0);
        context.waitFor(client -> !client.player.isSleeping(), WAKE_UP_TIMEOUT_TICKS);
        ClientGameTestRecordingHud.log("Woke up: the night was skipped");
        assertStaysAwake("after waking up in the morning");
    }

    private void assertStaysAwake(String when) {
        context.waitTicks(AWAKE_CHECK_TICKS);
        assertSleepingPos(Optional.empty(), when);
        int uses = serverBedUses.get();
        if (uses != 0) {
            throw new AssertionError("Expected no bed use " + when + ", but the server saw " + uses + ".");
        }
        ClientGameTestRecordingHud.log("Awake for " + AWAKE_CHECK_TICKS / 20 + " s, no bed use");
    }

    private void assertSleepingPos(Optional<BlockPos> expected, String when) {
        Optional<BlockPos> actual = context.computeOnClient(client -> client.player.getSleepingPos());
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected the player's bed " + when + " to be " + expected + ", but was " + actual + ".");
        }
    }
}
