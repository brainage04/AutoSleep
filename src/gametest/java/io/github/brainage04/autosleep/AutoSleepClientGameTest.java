package io.github.brainage04.autosleep;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

@SuppressWarnings("UnstableApiUsage")
public class AutoSleepClientGameTest implements FabricClientGameTest {
    /** Ten ticks before the Overworld's night, so the recording shows the trigger itself rather than a wait. */
    private static final long TEN_TICKS_BEFORE_NIGHT = DayNightCycleHud.NIGHT_START - 10L;
    private static final int DAY_CHECK_TICKS = 10;
    private static final int AWAKE_CHECK_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 5;
    private static final int WALK_AND_SLEEP_TIMEOUT_TICKS = 200;
    private static final int WAKE_UP_TIMEOUT_TICKS = 600;
    /**
     * Two blocks beyond the mod's 3-block reach of the red bed's foot: close enough that the player walks only those two
     * blocks, and out of reach, so a bed use from here would be the negative case.
     */
    private static final double TWO_BLOCKS_BEYOND_REACH_Z = 4.5D;
    /** The bed is north of the player here, so a player facing south has to turn to it. */
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_NORTH = 180.0F;

    private final AtomicInteger serverBedUses = new AtomicInteger();
    private final AtomicReference<String> unplayableBedUse = new AtomicReference<>();
    private ClientGameTestContext context;
    private TestDedicatedServerContext server;
    private int y;
    private BlockPos redBed;
    private BlockPos blueBed;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(level.getBlockState(hitResult.getBlockPos()).getBlock() instanceof BedBlock)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide() && player instanceof LocalPlayer localPlayer) {
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
            teleport(0.5D, TWO_BLOCKS_BEYOND_REACH_Z, FACING_NORTH);
            context.waitFor(client -> client.level.getBlockState(blueBed).getBlock() instanceof BedBlock, 200);
            context.runOnClient(client -> {
                if (!AutoSleepClient.isInitialized()) {
                    throw new AssertionError("Expected the AutoSleep client initializer to run before the client GameTest.");
                }
            });

            step("daytime", "Daytime, the red bed two blocks out of reach", "Nothing happens until night falls");
            serverBedUses.set(0);
            Vec3 dayStart = context.computeOnClient(client -> client.player.position());
            assertStaysAwake("during the day", DAY_CHECK_TICKS);
            double moved = context.computeOnClient(client -> client.player.position().distanceTo(dayStart));
            if (moved > 0.5D) {
                throw new AssertionError("Expected the player to stay put during the day, but they moved " + moved + " blocks.");
            }
            command("time set " + TEN_TICKS_BEFORE_NIGHT);
            int ticksToWalk = context.waitFor(client -> client.player.position().distanceTo(dayStart) > 0.25D, 100);
            ClientGameTestRecordingHud.log("Night fell: walking to the red bed " + ticksToWalk + " ticks after the time was set");
            context.waitTicks(10);

            step("nightfall-walks-to-bed", "Night falls, the red bed two blocks out of reach", "Walks the two blocks, then uses the bed looking at it");
            teleport(0.5D, TWO_BLOCKS_BEYOND_REACH_Z, FACING_NORTH);
            command("time set night");
            serverBedUses.set(0);
            assertOutOfReach("before walking");
            fallAsleepIn(redBed, "when night falls", WALK_AND_SLEEP_TIMEOUT_TICKS);
            sleepThroughTheNight();

            step("bed-in-reach", "Next night, beside the red bed but facing away", "Turns to the bed, then uses it");
            teleport(0.5D, 7.0D, FACING_SOUTH);
            command("time set night");
            serverBedUses.set(0);
            fallAsleepIn(redBed, "with the bed in reach", AutoSleeper.RETRY_INTERVAL_TICKS * 3);
            sleepThroughTheNight();

            step("nearest-bed", "Moved closer to the blue bed, both out of reach", "Walks to the blue bed and uses it, on camera");
            teleport(6.5D, 0.5D, -139.4F);
            command("time set night");
            serverBedUses.set(0);
            assertOutOfReach("before walking to the blue bed");
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

    /**
     * The rule a player cannot break, checked on the client where the mod decided to use the bed: the crosshair was on
     * that bed, the player was within vanilla reach of it, and no interaction range was changed.
     *
     * @return what the use broke, or null when it would have been a normal right click
     */
    private static @Nullable String unplayableUse(LocalPlayer player, BlockHitResult used) {
        AttributeInstance blockRange = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (blockRange == null || blockRange.getValue() != 4.5D || !blockRange.getModifiers().isEmpty()) {
            return "the block interaction range is " + (blockRange == null ? "missing" : blockRange.getValue()
                    + " " + blockRange.getModifiers()) + " instead of vanilla's 4.5";
        }
        AttributeInstance entityRange = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (entityRange == null || entityRange.getValue() != 3.0D || !entityRange.getModifiers().isEmpty()) {
            return "the entity interaction range is " + (entityRange == null ? "missing" : entityRange.getValue()
                    + " " + entityRange.getModifiers()) + " instead of vanilla's 3.0";
        }
        HitResult crosshair = player.raycastHitResult(1.0F, player);
        if (!(crosshair instanceof BlockHitResult looked)) {
            return "the crosshair was on nothing, not on the bed at " + used.getBlockPos();
        }
        if (!sameBed(player.level(), looked.getBlockPos(), used.getBlockPos())) {
            return "the crosshair was on " + looked.getBlockPos() + ", not on the bed at " + used.getBlockPos();
        }
        if (!player.isWithinBlockInteractionRange(used.getBlockPos(), 0.0D)) {
            return "the bed at " + used.getBlockPos() + " was out of reach of " + player.getEyePosition();
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
        return state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(state.getValue(BedBlock.FACING));
    }

    private void command(String command) {
        server.runCommand(command);
    }

    private void teleport(double x, double z, float yaw) {
        command("tp @a " + x + " " + y + " " + z + " " + yaw + " 10");
        BlockPos target = BlockPos.containing(x, y, z);
        context.waitFor(client -> client.player.blockPosition().equals(target), 100);
    }

    private void assertOutOfReach(String when) {
        boolean outOfReach = context.computeOnClient(client ->
                !AutoSleeper.inReach(client.player, redBed) && !AutoSleeper.inReach(client.player, blueBed));
        if (!outOfReach) {
            throw new AssertionError("Expected the player to be out of reach of both beds " + when + ".");
        }
        ClientGameTestRecordingHud.log("Out of reach of both beds " + when);
    }

    private void fallAsleepIn(BlockPos bed, String when, int timeoutTicks) {
        context.waitFor(client -> client.player.isSleeping(), timeoutTicks);
        assertSleepingPos(Optional.of(bed), when);
        assertNoUnplayableUse();
        long dayTime = context.computeOnClient(client -> Math.floorMod(client.level.getDayTime(), SharedConstants.TICKS_PER_GAME_DAY));
        ClientGameTestRecordingHud.log("Asleep in the " + (bed.equals(redBed) ? "red" : "blue") + " bed at day time " + dayTime);
    }

    private void assertNoUnplayableUse() {
        String unplayable = unplayableBedUse.get();
        if (unplayable != null) {
            throw new AssertionError("AutoSleep used a bed as no player could: " + unplayable);
        }
    }

    private void sleepThroughTheNight() {
        // Count from before the wake-up, so an attempt sent in the very tick the player wakes is not missed.
        serverBedUses.set(0);
        context.waitFor(client -> !client.player.isSleeping(), WAKE_UP_TIMEOUT_TICKS);
        ClientGameTestRecordingHud.log("Woke up: the night was skipped");
        assertStaysAwake("after waking up in the morning", AWAKE_CHECK_TICKS);
    }

    private void assertStaysAwake(String when, int ticks) {
        context.waitTicks(ticks);
        assertSleepingPos(Optional.empty(), when);
        int uses = serverBedUses.get();
        if (uses != 0) {
            throw new AssertionError("Expected no bed use " + when + ", but the server saw " + uses + ".");
        }
        ClientGameTestRecordingHud.log("Awake for " + ticks + " ticks, no bed use " + when);
        assertNoUnplayableUse();
    }

    private void assertSleepingPos(Optional<BlockPos> expected, String when) {
        Optional<BlockPos> actual = context.computeOnClient(client -> client.player.getSleepingPos());
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected the player's bed " + when + " to be " + expected + ", but was " + actual + ".");
        }
    }
}
