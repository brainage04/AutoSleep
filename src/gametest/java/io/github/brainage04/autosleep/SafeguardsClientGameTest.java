package io.github.brainage04.autosleep;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What the player keeps control of, and what the mod does with a bed it cannot use, on a dedicated
 * server without the mod. Each step builds its own stage, far from the other tests' beds, by day,
 * and then sets midnight with time stopped.
 */
@SuppressWarnings("UnstableApiUsage")
public class SafeguardsClientGameTest implements FabricClientGameTest {
    private static final int Y = 100;
    private static final BlockPos CENTRE = new BlockPos(2000, Y, 0);

    /** The stone floor under the stage, at {@code Y - 1}. */
    private static final int FLOOR_RADIUS = 12;

    /** Cleared around the stage: the mod looks for beds 24 blocks away and 6 up or down. */
    private static final int CLEAR_RADIUS = 26;

    private static final long MORNING = 1000L;
    private static final long MIDNIGHT = 18000L;
    private static final int WATCH_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 3;
    private static final int STAY_UP_TICKS = 200;
    private static final int SLEEP_TIMEOUT_TICKS = 400;
    private static final int STAGE_LOAD_TIMEOUT_TICKS = 600;

    private final List<String> failures = new ArrayList<>();
    private final List<BlockPos> serverBedUses = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientBedUses = new AtomicInteger();
    private final List<String> serverChat = new CopyOnWriteArrayList<>();
    private final List<TranslatableContents> modMessages = new CopyOnWriteArrayList<>();
    private ClientGameTestContext context;
    private TestDedicatedServerContext server;

    // Read and written on the client thread only.
    private @Nullable List<BlockPos> watchedForWater;
    private boolean waterSeen;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register(
                (player, level, hand, hitResult) -> {
                    if (level.getBlockState(hitResult.getBlockPos()).getBlock()
                            instanceof BedBlock) {
                        if (level.isClientSide()) {
                            clientBedUses.incrementAndGet();
                        } else {
                            serverBedUses.add(bedHead(level, hitResult.getBlockPos()));
                        }
                    }
                    return InteractionResult.PASS;
                });
        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, params) -> serverChat.add(message.signedContent()));
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> {
                    if (message.getContents() instanceof TranslatableContents translatable
                            && translatable.getKey().startsWith(AutoSleep.MOD_ID + ".")) {
                        modMessages.add(translatable);
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(this::watch);

        try (TestDedicatedServerContext server =
                        context.worldBuilder().createServer(GameTestServers.serverProperties());
                TestServerConnection ignored = server.connect()) {
            this.server = server;
            server.runOnServer(
                    minecraftServer -> {
                        minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
                        ServerLevel overworld = minecraftServer.overworld();
                        overworld
                                .getGameRules()
                                .set(GameRules.ADVANCE_TIME, false, minecraftServer);
                        overworld
                                .getGameRules()
                                .set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
                        overworld
                                .getGameRules()
                                .set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, minecraftServer);
                    });
            command("gamemode survival @a");
            command("weather clear");

            run("chat-reaches-the-server", this::chatReachesTheServer);
            run("stays-up-after-leaving-the-bed", this::staysUpAfterLeavingTheBed);
            run("sneaking-with-blocks", this::waitsWhileSneakingWithBlocks);
            run("bed-under-the-floor", this::givesUpABedTheCrosshairCannotReach);
            run("crosshair-short-of-the-bed", this::neverUsesABedTheCrosshairFallsShortOf);
            run("bed-in-a-pit", this::neverUsesAWaterBucket);
            run("own-baritone-goal", this::leavesThePlayersBaritoneAlone);
            run("screen-open", this::waitsWhileAScreenIsOpen);
            run("spectator", this::doesNothingInSpectatorMode);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    failures.size()
                            + " safeguard step(s) failed:\n  "
                            + String.join("\n  ", failures));
        }
    }

    /** Runs one step; a failure is recorded, the player is put back in order, and the next runs. */
    private void run(String id, Runnable step) {
        try {
            step.run();
            System.out.println("[SAFEGUARD] " + id + ": passed");
        } catch (AssertionError e) {
            String where =
                    Arrays.stream(e.getStackTrace())
                            .filter(frame -> frame.getClassName().equals(getClass().getName()))
                            .findFirst()
                            .map(frame -> " (line " + frame.getLineNumber() + ")")
                            .orElse("");
            failures.add(id + ": " + e.getMessage() + where);
            System.out.println("[SAFEGUARD] " + id + ": FAILED: " + e.getMessage() + where);
            ClientGameTestRecordingHud.log("FAILED: " + e.getMessage());
        } finally {
            context.getInput().releaseKey(options -> options.keyShift);
            context.setScreen(() -> null);
            context.runOnClient(
                    client -> {
                        watchedForWater = null;
                        baritone().getPathingBehavior().cancelEverything();
                    });
            command("gamemode survival @a");
            command("clear @a");
            day();
        }
    }

    private void chatReachesTheServer() {
        step(
                "chat-reaches-the-server",
                "Chat \"help me\", which starts with a Baritone command",
                "The server gets the message: Baritone does not take it as a command");
        serverChat.clear();
        // What the chat screen sends (ChatScreen#handleChatInput), and where Baritone looks for
        // its commands (MixinClientPlayNetHandler#sendChatMessage in Baritone 1.17.0).
        context.runOnClient(client -> client.player.connection.sendChat("help me"));
        try {
            context.waitFor(client -> serverChat.contains("help me"), 40);
        } catch (AssertionError e) {
            throw new AssertionError(
                    "Expected the server to receive the chat message \"help me\", but it got "
                            + serverChat);
        }
    }

    private void staysUpAfterLeavingTheBed() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "stays-up-after-leaving-the-bed",
                "Midnight, a bed in reach; the player sleeps, then leaves the bed",
                "Stays up for the rest of the night, and sleeps again the next night");
        night();
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 2);
        // What the Leave Bed button sends (InBedChatScreen#sendWakeUp).
        context.runOnClient(
                client ->
                        client.player.connection.send(
                                new ServerboundPlayerCommandPacket(
                                        client.player,
                                        ServerboundPlayerCommandPacket.Action.STOP_SLEEPING)));
        context.waitFor(client -> !client.player.isSleeping(), 40);
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(STAY_UP_TICKS);
        assertNoBedUse("in the " + STAY_UP_TICKS / 20 + " s after leaving the bed");
        ClientGameTestRecordingHud.log("Stayed up for " + STAY_UP_TICKS / 20 + " s");
        day();
        night();
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 2);
        ClientGameTestRecordingHud.log("Asleep again the next night");
    }

    private void waitsWhileSneakingWithBlocks() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        command("item replace entity @a hotbar.0 with minecraft:oak_planks 64");
        context.getInput().holdKey(options -> options.keyShift);
        context.waitFor(client -> client.player.isShiftKeyDown(), 20);
        step(
                "sneaking-with-blocks",
                "Midnight, a bed in reach; the player sneaks with planks in hand",
                "Does nothing while the player sneaks, then sleeps once they stop");
        float[] view = view();
        serverBedUses.clear();
        clientBedUses.set(0);
        night();
        context.waitTicks(WATCH_TICKS);
        assertNoBedUse("while sneaking with planks");
        assertViewStill(view, "while sneaking with planks");
        boolean bedClear =
                server.computeOnServer(
                        minecraftServer ->
                                minecraftServer.overworld().getBlockState(bed.above()).isAir()
                                        && minecraftServer
                                                .overworld()
                                                .getBlockState(bed.north().above())
                                                .isAir());
        if (!bedClear) {
            throw new AssertionError("Expected nothing placed on the bed while sneaking.");
        }
        context.getInput().releaseKey(options -> options.keyShift);
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 3);
    }

    private void givesUpABedTheCrosshairCannotReach() {
        day();
        buildStage();
        // Under the floor the player stands on: in vanilla reach, but the floor is in the way.
        BlockPos underTheFloor = CENTRE.offset(0, -2, 0);
        BlockPos open = CENTRE.offset(0, 0, 8);
        placeBed(underTheFloor);
        placeBed(open);
        standAt(CENTRE, 180.0F);
        step(
                "bed-under-the-floor",
                "Midnight, the nearest bed under the floor, an open bed 8 blocks away",
                "Tries the bed under the floor "
                        + AutoSleeper.TRIES_PER_BED
                        + " times, says so once, then sleeps in the open bed");
        modMessages.clear();
        serverBedUses.clear();
        night();
        fallAsleepIn(open, SLEEP_TIMEOUT_TICKS);
        assertToldOnce(AutoSleeper.BED_UNUSABLE_MESSAGE, underTheFloor);
        if (serverBedUses.contains(underTheFloor)) {
            throw new AssertionError("Expected no use of the bed under the floor.");
        }
        ClientGameTestRecordingHud.log("Left the bed under the floor, asleep in the open bed");
    }

    private void neverUsesABedTheCrosshairFallsShortOf() {
        day();
        buildStage();
        BlockPos bed = CENTRE;
        placeBed(bed);
        // Feet 2 blocks above the bed and (3, 2.5) away from its head: in the mod's reach, but the
        // block interaction range (4.5) ends above the bed, inside the bed's block. The game's pick
        // then returns a miss that carries the bed's position (BlockGetter.java:85).
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    level.setBlockAndUpdate(bed.offset(3, 1, 2), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(bed.offset(3, 1, 3), Blocks.STONE.defaultBlockState());
                });
        Vec3 feet = new Vec3(bed.getX() + 3.5D, Y + 2.0D, bed.getZ() + 3.0D);
        teleport(feet, 0.0F);
        String geometry =
                context.computeOnClient(
                        client -> {
                            Vec3 centre = Vec3.atCenterOf(bed);
                            double toCentre = client.player.getEyePosition().distanceTo(centre);
                            boolean inReach = AutoSleeper.inReach(client.player, bed);
                            return inReach && toCentre > client.player.blockInteractionRange()
                                    ? null
                                    : "in reach " + inReach + ", eye to centre " + toCentre;
                        });
        if (geometry != null) {
            throw new AssertionError("The stage does not set up a short pick: " + geometry);
        }
        step(
                "crosshair-short-of-the-bed",
                "Midnight, a bed in reach whose centre is beyond the interaction range",
                "Never uses it: the pick ends short of the bed; after "
                        + AutoSleeper.TRIES_PER_BED
                        + " tries it leaves the view alone");
        modMessages.clear();
        serverBedUses.clear();
        clientBedUses.set(0);
        night();
        context.waitTicks(WATCH_TICKS);
        if (clientBedUses.get() != 0 || !serverBedUses.isEmpty()) {
            throw new AssertionError(
                    "Expected no bed use with the crosshair short of the bed, but the client sent "
                            + clientBedUses.get()
                            + " and the server saw "
                            + serverBedUses.size());
        }
        assertToldOnce(AutoSleeper.BED_UNUSABLE_MESSAGE, bed);
        // Given up: the view the player turns to now stays there.
        context.runOnClient(
                client -> {
                    client.player.setYRot(90.0F);
                    client.player.setXRot(0.0F);
                });
        float[] away = view();
        context.waitTicks(WATCH_TICKS);
        assertViewStill(away, "after the bed was given up");
    }

    private void neverUsesAWaterBucket() {
        day();
        buildStage();
        // A shaft 6 blocks deep, 2x2, with a bed at its bottom: only a water-bucket fall gets
        // there. The open bed is farther away.
        BlockPos pitBed = CENTRE.offset(-1, -6, 3);
        BlockPos open = CENTRE.offset(9, 0, 0);
        List<BlockPos> shaft = new ArrayList<>();
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    for (BlockPos pos :
                            BlockPos.betweenClosed(
                                    CENTRE.offset(-3, -7, 0), CENTRE.offset(2, -1, 5))) {
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    for (BlockPos pos :
                            BlockPos.betweenClosed(
                                    CENTRE.offset(-1, -6, 2), CENTRE.offset(0, -1, 3))) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                });
        for (BlockPos pos :
                BlockPos.betweenClosed(CENTRE.offset(-1, -6, 2), CENTRE.offset(0, 0, 3))) {
            shaft.add(pos.immutable());
        }
        placeBed(pitBed);
        placeBed(open);
        standAt(CENTRE, 0.0F);
        command("item replace entity @a hotbar.0 with minecraft:water_bucket");
        context.waitFor(client -> !client.player.getMainHandItem().isEmpty(), 20);
        step(
                "bed-in-a-pit",
                "Midnight, the nearest bed 6 blocks down a shaft, a water bucket in hand",
                "Never falls in with the bucket: says the bed is out of reach, sleeps in the open bed");
        modMessages.clear();
        context.runOnClient(
                client -> {
                    watchedForWater = shaft;
                    waterSeen = false;
                });
        night();
        fallAsleepIn(open, SLEEP_TIMEOUT_TICKS);
        boolean water = context.computeOnClient(client -> waterSeen);
        if (water) {
            throw new AssertionError("Expected no water in the shaft, but Baritone placed some.");
        }
        float health = context.computeOnClient(client -> client.player.getHealth());
        float maxHealth = context.computeOnClient(client -> client.player.getMaxHealth());
        if (health < maxHealth) {
            throw new AssertionError("Expected full health, but the player has " + health + ".");
        }
        assertToldOnce(AutoSleeper.BED_UNREACHABLE_MESSAGE, pitBed);
    }

    private void leavesThePlayersBaritoneAlone() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 7);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "own-baritone-goal",
                "The player's own Baritone goal set by day, then nightfall",
                "Keeps the player's goal and settings, and changes the settings only for its own walk");
        assertSettings(true, "by day, before any walk");
        BlockPos goalPos = CENTRE.offset(-8, 0, 0);
        Goal playersGoal =
                context.computeOnClient(
                        client -> {
                            baritone()
                                    .getCommandManager()
                                    .execute(
                                            "goal "
                                                    + goalPos.getX()
                                                    + " "
                                                    + goalPos.getY()
                                                    + " "
                                                    + goalPos.getZ());
                            return baritone().getCustomGoalProcess().getGoal();
                        });
        if (playersGoal == null) {
            throw new AssertionError("Baritone's goal command set no goal.");
        }
        night();
        context.waitTicks(WATCH_TICKS);
        assertGoal(playersGoal, "at night");
        day();
        context.waitTicks(5);
        assertGoal(playersGoal, "at daybreak");
        context.runOnClient(client -> baritone().getCommandManager().execute("stop"));
        context.waitFor(client -> baritone().getCustomGoalProcess().getGoal() == null, 20);

        night();
        context.waitFor(client -> baritone().getCustomGoalProcess().isActive(), 10);
        assertSettings(false, "during AutoSleep's walk");
        context.runOnClient(client -> baritone().getCommandManager().execute("stop"));
        context.waitTicks(2);
        assertSettings(true, "after the player stopped AutoSleep's walk");
        fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
        assertSettings(true, "after AutoSleep's walk ended in bed");
    }

    private void waitsWhileAScreenIsOpen() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        step(
                "screen-open",
                "Midnight, a bed in reach, the inventory open",
                "Does nothing while the inventory is open, then sleeps once it is closed");
        float[] view = view();
        serverBedUses.clear();
        clientBedUses.set(0);
        night();
        context.waitTicks(WATCH_TICKS);
        assertNoBedUse("while the inventory is open");
        assertViewStill(view, "while the inventory is open");
        context.setScreen(() -> null);
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 3);
    }

    private void doesNothingInSpectatorMode() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        command("gamemode spectator @a");
        context.waitFor(client -> client.player.isSpectator(), 40);
        standAt(CENTRE, 180.0F);
        step(
                "spectator",
                "Midnight, a bed in reach, the player in spectator mode",
                "Does nothing in spectator mode, then sleeps once back in survival");
        float[] view = view();
        serverBedUses.clear();
        clientBedUses.set(0);
        night();
        context.waitTicks(WATCH_TICKS);
        assertNoBedUse("in spectator mode");
        assertViewStill(view, "in spectator mode");
        command("gamemode survival @a");
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 3);
    }

    /** Every tick on the client: notes water in the watched blocks, however briefly it is there. */
    private void watch(Minecraft client) {
        if (client.level == null || watchedForWater == null) {
            return;
        }
        for (BlockPos pos : watchedForWater) {
            if (!client.level.getFluidState(pos).isEmpty()) {
                waterSeen = true;
            }
        }
    }

    /**
     * Clears the stage and puts the player on its floor, so the client has its chunks before any
     * bed is placed.
     */
    private void buildStage() {
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    for (BlockPos pos :
                            BlockPos.betweenClosed(
                                    CENTRE.offset(-CLEAR_RADIUS, -8, -CLEAR_RADIUS),
                                    CENTRE.offset(CLEAR_RADIUS, 6, CLEAR_RADIUS))) {
                        boolean floor =
                                pos.getY() == Y - 1
                                        && Math.abs(pos.getX() - CENTRE.getX()) <= FLOOR_RADIUS
                                        && Math.abs(pos.getZ() - CENTRE.getZ()) <= FLOOR_RADIUS;
                        level.setBlock(
                                pos,
                                floor
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                });
        standAt(CENTRE, 180.0F);
        // The first stage is 2000 blocks from spawn: its chunks can take a while to generate.
        context.waitFor(
                client -> client.level.getBlockState(CENTRE.below()).is(Blocks.STONE),
                STAGE_LOAD_TIMEOUT_TICKS);
    }

    /** A bed with its head at {@code head}, facing south. */
    private void placeBed(BlockPos head) {
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    BlockState bed =
                            Blocks.RED_BED
                                    .defaultBlockState()
                                    .setValue(BedBlock.FACING, Direction.SOUTH);
                    level.setBlockAndUpdate(
                            head.relative(Direction.NORTH),
                            bed.setValue(BedBlock.PART, BedPart.FOOT));
                    level.setBlockAndUpdate(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
                });
        context.waitFor(
                client -> client.level.getBlockState(head).getBlock() instanceof BedBlock, 100);
    }

    private void standAt(BlockPos feet, float yaw) {
        teleport(Vec3.atBottomCenterOf(feet), yaw);
    }

    private void teleport(Vec3 feet, float yaw) {
        command("tp @a " + feet.x + " " + feet.y + " " + feet.z + " " + yaw + " 10");
        context.waitFor(client -> client.player.position().distanceTo(feet) < 0.1D, 100);
        context.waitTicks(5);
    }

    private void day() {
        command("time set " + MORNING);
        context.waitFor(
                client -> !client.level.isDarkOutside() && !client.player.isSleeping(), 100);
        context.waitTicks(2);
    }

    private void night() {
        command("time set " + MIDNIGHT);
        context.waitFor(client -> client.level.isDarkOutside(), 100);
    }

    private void fallAsleepIn(BlockPos head, int timeoutTicks) {
        try {
            context.waitFor(client -> client.player.isSleeping(), timeoutTicks);
        } catch (AssertionError e) {
            throw new AssertionError(
                    "Expected the player to fall asleep in the bed at "
                            + head.toShortString()
                            + " within "
                            + timeoutTicks
                            + " ticks.");
        }
        BlockPos in =
                context.computeOnClient(client -> client.player.getSleepingPos().orElse(null));
        if (!head.equals(in)) {
            throw new AssertionError(
                    "Expected the player asleep in the bed at "
                            + head.toShortString()
                            + ", but they are in "
                            + in);
        }
    }

    private float[] view() {
        return context.computeOnClient(
                client -> new float[] {client.player.getYRot(), client.player.getXRot()});
    }

    private void assertViewStill(float[] before, String when) {
        float[] after = view();
        if (!Arrays.equals(before, after)) {
            throw new AssertionError(
                    "Expected the view to stay put "
                            + when
                            + ", but it turned from "
                            + Arrays.toString(before)
                            + " to "
                            + Arrays.toString(after));
        }
    }

    private void assertNoBedUse(String when) {
        boolean sleeping = context.computeOnClient(client -> client.player.isSleeping());
        if (sleeping || !serverBedUses.isEmpty() || clientBedUses.get() != 0) {
            throw new AssertionError(
                    "Expected no bed use "
                            + when
                            + ", but sleeping="
                            + sleeping
                            + ", the client sent "
                            + clientBedUses.get()
                            + " use(s) and the server saw "
                            + serverBedUses.size());
        }
    }

    private void assertToldOnce(String key, BlockPos head) {
        List<Object> args = List.of(head.getX(), head.getY(), head.getZ());
        long told =
                modMessages.stream()
                        .filter(
                                message ->
                                        message.getKey().equals(key)
                                                && Arrays.asList(message.getArgs()).equals(args))
                        .count();
        if (told != 1) {
            throw new AssertionError(
                    "Expected the player told once ("
                            + key
                            + ") about the bed at "
                            + head.toShortString()
                            + ", but told "
                            + told
                            + " times.");
        }
    }

    private void assertGoal(Goal expected, String when) {
        Goal goal = context.computeOnClient(client -> baritone().getCustomGoalProcess().getGoal());
        if (goal != expected) {
            throw new AssertionError(
                    "Expected the player's Baritone goal "
                            + expected
                            + " to stay "
                            + when
                            + ", but the goal is "
                            + goal);
        }
    }

    private void assertSettings(boolean expected, String when) {
        String values =
                context.computeOnClient(
                        client -> {
                            Settings settings = BaritoneAPI.getSettings();
                            return settings.allowBreak.value
                                    + " "
                                    + settings.allowPlace.value
                                    + " "
                                    + settings.allowWaterBucketFall.value;
                        });
        String wanted = expected + " " + expected + " " + expected;
        if (!values.equals(wanted)) {
            throw new AssertionError(
                    "Expected allowBreak, allowPlace and allowWaterBucketFall to be "
                            + wanted
                            + " "
                            + when
                            + ", but they are "
                            + values);
        }
    }

    private void command(String command) {
        server.runCommand(command);
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private static BlockPos bedHead(Level level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? bed
                : bed.relative(state.getValue(BedBlock.FACING));
    }
}
