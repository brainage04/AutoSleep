package io.github.brainage04.autosleep;

import static io.github.brainage04.autosleep.ClientGameTestRecordingHud.step;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
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
    private static final int MONSTER_WATCH_TICKS = AutoSleeper.RETRY_INTERVAL_TICKS * 6;

    /** A stone box to stand in for a moment, in the Nether or the second Overworld. */
    private static final BlockPos BOX_SPOT = new BlockPos(0, 100, 0);

    /** A datapack dimension of the Overworld's type, where a server lets the player sleep. */
    private static final ResourceKey<Level> SECOND_OVERWORLD =
            ResourceKey.create(
                    Registries.DIMENSION,
                    Identifier.fromNamespaceAndPath("autosleep_test", "second_overworld"));

    private static final long DAY_LENGTH = 24000L;

    private final List<String> failures = new ArrayList<>();
    private final List<BlockPos> serverBedUses = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientBedUses = new AtomicInteger();
    private final List<String> serverChat = new CopyOnWriteArrayList<>();
    private final List<TranslatableContents> modMessages = new CopyOnWriteArrayList<>();
    private ClientGameTestContext context;
    private TestServerContext server;
    private TestDedicatedServerContext dedicated;
    private TestServerConnection connection;

    /** The server refuses every use of this bed, as a plugin may. */
    private volatile @Nullable BlockPos refusedBed;

    /** The server refuses every use of a door, as spawn protection or a claim plugin does. */
    private final AtomicBoolean refuseDoors = new AtomicBoolean();

    private final AtomicInteger refusedDoorUses = new AtomicInteger();

    // Read and written on the client thread only.
    private @Nullable List<BlockPos> watchedForWater;
    private boolean waterSeen;
    private float @Nullable [] sneakOnTurnFrom;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        UseBlockCallback.EVENT.register(
                (player, level, hand, hitResult) -> {
                    BlockState state = level.getBlockState(hitResult.getBlockPos());
                    if (!level.isClientSide()
                            && refuseDoors.get()
                            && state.getBlock() instanceof DoorBlock) {
                        refusedDoorUses.incrementAndGet();
                        return InteractionResult.FAIL;
                    }
                    if (state.getBlock() instanceof BedBlock) {
                        if (level.isClientSide()) {
                            clientBedUses.incrementAndGet();
                        } else {
                            BlockPos head = bedHead(level, hitResult.getBlockPos());
                            serverBedUses.add(head);
                            if (head.equals(refusedBed)) {
                                return InteractionResult.FAIL;
                            }
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

        try (TestDedicatedServerContext dedicated =
                context.worldBuilder().createServer(GameTestServers.serverProperties())) {
            this.dedicated = dedicated;
            server = dedicated;
            connection = dedicated.connect();
            try {
                setUpServer();
                run("chat-reaches-the-server", this::chatReachesTheServer);
                run("stays-up-after-leaving-the-bed", this::staysUpAfterLeavingTheBed);
                run("stays-up-in-its-own-dimension", this::sleepsInAnotherDimensionAfterStayingUp);
                run("stay-up-ends-the-next-day", this::staysUpUntilTheNextDayAnywhere);
                run("stays-up-in-its-own-world", this::sleepsInAnotherWorldAfterStayingUp);
                run("sneaking-with-blocks", this::waitsWhileSneakingWithBlocks);
                run("sneak-before-the-click", this::neverClicksWhenSneakingStartsAtTheTurn);
                run("bed-under-the-floor", this::givesUpABedTheCrosshairCannotReach);
                run("crosshair-short-of-the-bed", this::neverUsesABedTheCrosshairFallsShortOf);
                run("server-refuses-the-bed", this::givesUpABedTheServerRefuses);
                run("monsters-nearby", this::keepsToABedWithMonstersNearby);
                run("monster-stays", this::givesUpABedAMonsterNeverLeaves);
                run("monster-walk-away", this::neverWalksBackToAMonstersBed);
                run("monster-stop", this::stopsAtAMonstersBedOnStop);
                run("monster-reconnect", this::keepsToAMonstersBedAcrossAReconnect);
                run("monster-other-dimension", this::usesNoBedElsewhereAfterAMonstersBed);
                run("monster-bed-broken", this::forgetsAMonstersBedOnceItIsGone);
                run("bed-in-a-pit", this::neverUsesAWaterBucket);
                run("walk-never-arrives", this::givesUpAWalkThatNeverArrives);
                run("walk-switches-beds", this::keepsThePlayersSettingsWhenTheWalkSwitchesBeds);
                run("own-baritone-goal", this::leavesThePlayersBaritoneAlone);
                run("goal-from-another-mod", this::keepsAGoalAnotherModSets);
                run("stop-ends-the-walk", this::staysUpAfterStop);
                run("mine-during-the-walk", this::letsThePlayerMineDuringTheWalk);
                run("settings-file", this::keepsTheSettingsFileThePlayers);
                run("own-chat-control", this::keepsThePlayersOwnChatControl);
                run("screen-open", this::waitsWhileAScreenIsOpen);
                run("spectator", this::doesNothingInSpectatorMode);
            } finally {
                connection.close();
            }
        }

        // A singleplayer game pauses (Minecraft#isPaused) under the pause menu or an overlay.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            server = singleplayer.getServer();
            setUpServer();
            run("walk-paused", this::keepsTheWalkThroughThePauseMenu);
            run("walk-paused-overlay", this::keepsTheWalkThroughAPausingOverlay);
            run("turn-paused-overlay", this::neverTurnsWhileAnOverlayPausesTheGame);
            run("monster-wait-paused", this::keepsTheMonsterWaitThroughThePauseMenu);
            run("stay-up-paused", this::staysUpThroughAPauseBeforeTheTimeArrives);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    failures.size()
                            + " safeguard step(s) failed:\n  "
                            + String.join("\n  ", failures));
        }
    }

    private void setUpServer() {
        server.runOnServer(
                minecraftServer -> {
                    minecraftServer.setDifficulty(Difficulty.PEACEFUL, true);
                    GameRules rules = minecraftServer.overworld().getGameRules();
                    rules.set(GameRules.ADVANCE_TIME, false, minecraftServer);
                    rules.set(GameRules.ADVANCE_WEATHER, false, minecraftServer);
                    rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, minecraftServer);
                    // The monster steps summon their own: no other may spawn while they run.
                    rules.set(GameRules.SPAWN_MONSTERS, false, minecraftServer);
                });
        command("gamemode survival @a");
        command("weather clear");
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
            refusedBed = null;
            refuseDoors.set(false);
            context.getInput().releaseKey(options -> options.keyShift);
            context.setScreen(() -> null);
            context.runOnClient(
                    client -> {
                        client.options.keyShift.setDown(false);
                        client.setOverlay(null);
                        sneakOnTurnFrom = null;
                        watchedForWater = null;
                        TimePacketHold.reset();
                        ChunkPacketHold.reset();
                        baritone().getPathingBehavior().cancelEverything();
                    });
            server.runOnServer(
                    minecraftServer -> minecraftServer.setDifficulty(Difficulty.PEACEFUL, true));
            command("kill @e[type=minecraft:zombie]");
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
        leaveTheBed(bed);
        assertStaysUp("in the " + STAY_UP_TICKS / 20 + " s after leaving the bed");
        ClientGameTestRecordingHud.log("Stayed up for " + STAY_UP_TICKS / 20 + " s");
        visitTheNether();
        // The Overworld's time reaches the client 5 ticks after the level, as it can over a
        // network: until then the client sees day time 0, a morning.
        context.runOnClient(client -> TimePacketHold.armForNextLevel(client.level, 5));
        serverBedUses.clear();
        clientBedUses.set(0);
        standAt(CENTRE, 180.0F);
        assertStaysUp("after a trip to the Nether and back the same night");
        reconnect();
        assertStaysUp("after a reconnect the same night");
        ClientGameTestRecordingHud.log("Still up after the Nether and a reconnect");
        day();
        night();
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 2);
        ClientGameTestRecordingHud.log("Asleep again the next night");
    }

    private void sleepsInAnotherDimensionAfterStayingUp() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "stays-up-in-its-own-dimension",
                "Midnight; the player leaves the bed, then goes to another dimension of the"
                        + " Overworld's type, with a bed in reach",
                "Sleeps there: staying up holds only in the dimension where the player left the"
                        + " bed");
        night();
        leaveTheBed(bed);
        assertStaysUp("after leaving the bed");
        BlockPos otherBed = standInABox(SECOND_OVERWORLD, true);
        fallAsleepIn(otherBed, SLEEP_TIMEOUT_TICKS);
    }

    private void staysUpUntilTheNextDayAnywhere() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "stay-up-ends-the-next-day",
                "Midnight; the player leaves the bed, the next day and the next night pass while"
                        + " they are in the Nether, then they come back",
                "Sleeps in the bed: the new day in the Nether ended staying up");
        night();
        leaveTheBed(bed);
        assertStaysUp("after leaving the bed");
        visitTheNether();
        command("time set " + (MORNING + DAY_LENGTH));
        context.waitFor(client -> client.level.getDayTime() == MORNING + DAY_LENGTH, 100);
        context.waitTicks(5);
        command("time set " + (MIDNIGHT + DAY_LENGTH));
        context.waitFor(client -> client.level.getDayTime() == MIDNIGHT + DAY_LENGTH, 100);
        standAt(CENTRE, 180.0F);
        fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
    }

    private void sleepsInAnotherWorldAfterStayingUp() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "stays-up-in-its-own-world",
                "Midnight; the player leaves the bed, then joins the server under another address,"
                        + " which the mod takes for another world",
                "Sleeps there: staying up holds only in the world where the player left the bed");
        night();
        leaveTheBed(bed);
        assertStaysUp("after leaving the bed");
        connection.close();
        int port = dedicated.computeOnServer(minecraftServer -> minecraftServer.getPort());
        try {
            joinAs("127.0.0.1:" + port);
            fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
        } finally {
            leaveTheServer();
            connection = dedicated.connect();
        }
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
        assertNothingPlacedOn(bed, "while sneaking");
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

    private void neverClicksWhenSneakingStartsAtTheTurn() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        command("item replace entity @a hotbar.0 with minecraft:oak_planks 64");
        context.waitFor(client -> !client.player.getMainHandItem().isEmpty(), 20);
        step(
                "sneak-before-the-click",
                "Midnight, a bed in reach, planks in hand; the player starts sneaking on the tick"
                        + " the view turns to the bed",
                "No click on the next tick or while sneaking, nothing placed on the bed; sleeps"
                        + " once they stop");
        float[] view = view();
        context.runOnClient(client -> sneakOnTurnFrom = view);
        serverBedUses.clear();
        clientBedUses.set(0);
        night();
        context.waitFor(client -> sneakOnTurnFrom == null, AutoSleeper.RETRY_INTERVAL_TICKS);
        context.waitTicks(WATCH_TICKS);
        assertNoBedUse("after sneaking started on the tick of the turn");
        assertNothingPlacedOn(bed, "after sneaking started on the tick of the turn");
        context.runOnClient(client -> client.options.keyShift.setDown(false));
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 3);
    }

    private void givesUpABedTheServerRefuses() {
        day();
        buildStage();
        BlockPos refused = CENTRE.offset(0, 0, 3);
        BlockPos other = CENTRE.offset(0, 0, -8);
        placeBed(refused);
        placeBed(other);
        standAt(CENTRE, 180.0F);
        step(
                "server-refuses-the-bed",
                "Midnight, a bed in reach the server refuses, another bed 8 blocks away",
                "Uses the refused bed "
                        + AutoSleeper.TRIES_PER_BED
                        + " times, says so once, then sleeps in the other bed");
        modMessages.clear();
        serverBedUses.clear();
        refusedBed = refused;
        night();
        fallAsleepIn(other, SLEEP_TIMEOUT_TICKS);
        long uses = serverBedUses.stream().filter(refused::equals).count();
        if (uses != AutoSleeper.TRIES_PER_BED) {
            throw new AssertionError(
                    "Expected "
                            + AutoSleeper.TRIES_PER_BED
                            + " uses of the refused bed, but the server saw "
                            + uses);
        }
        assertToldOnce(AutoSleeper.BED_UNUSABLE_MESSAGE, refused);
    }

    /** The near bed (in reach) with a monster beside it, and a far bed 11 blocks away. */
    private record MonsterStage(BlockPos near, BlockPos far) {}

    private MonsterStage buildMonsterStage() {
        day();
        buildStage();
        MonsterStage stage = new MonsterStage(CENTRE.offset(0, 0, 3), CENTRE.offset(0, 0, -11));
        placeBed(stage.near());
        placeBed(stage.far());
        standAt(CENTRE, 180.0F);
        // Within the 8 x 5 x 8 blocks around the near bed that ServerPlayer#startSleepInBed checks
        // (ServerPlayer.java:1203-1214 in 1.21.11), far from the other bed.
        Vec3 monster = Vec3.atBottomCenterOf(CENTRE.offset(3, 0, 6));
        server.runOnServer(minecraftServer -> minecraftServer.setDifficulty(Difficulty.EASY, true));
        command(
                "summon minecraft:zombie "
                        + monster.x
                        + " "
                        + monster.y
                        + " "
                        + monster.z
                        + " {NoAI:1b,PersistenceRequired:1b,Silent:1b}");
        context.waitFor(
                client ->
                        !client.level
                                .getEntitiesOfClass(
                                        Zombie.class, new AABB(stage.near()).inflate(8.0D))
                                .isEmpty(),
                100);
        modMessages.clear();
        serverBedUses.clear();
        clientBedUses.set(0);
        return stage;
    }

    /** Night falls; waits for the first use of the near bed, which the server refuses. */
    private void waitForTheFirstRefusal(MonsterStage stage) {
        night();
        context.waitFor(client -> serverBedUses.contains(stage.near()), 100);
        context.waitTicks(5);
    }

    private void keepsToABedWithMonstersNearby() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monsters-nearby",
                "Midnight, a bed in reach with a monster beside it, another bed 11 blocks away",
                "Tries the first bed, which has the respawn point now, every "
                        + AutoSleeper.NOT_SAFE_RETRY_TICKS / 20
                        + " s and never the other; sleeps once the monster is gone");
        waitForTheFirstRefusal(stage);
        serverBedUses.clear();
        int watch = AutoSleeper.NOT_SAFE_RETRY_TICKS * 2 + 10;
        context.waitTicks(watch);
        long nearUses = serverBedUses.stream().filter(stage.near()::equals).count();
        if (nearUses < 1
                || nearUses > 2
                || serverBedUses.contains(stage.far())
                || !modMessages.isEmpty()) {
            throw new AssertionError(
                    "Expected the bed with the monster beside it tried once or twice in "
                            + watch
                            + " ticks, the other bed never and no message, but the server saw "
                            + serverBedUses
                            + " and the mod said "
                            + modMessages.stream().map(TranslatableContents::getKey).toList());
        }
        command("kill @e[type=minecraft:zombie]");
        fallAsleepIn(stage.near(), SLEEP_TIMEOUT_TICKS);
    }

    private void givesUpABedAMonsterNeverLeaves() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-stays",
                "Midnight, a bed in reach with a monster beside it that never leaves; the player"
                        + " has the inventory open after the first refusal",
                "Gives the bed up after "
                        + AutoSleeper.NOT_SAFE_GIVE_UP_TICKS / 20
                        + " s, open inventory included, says so once, uses no other bed");
        waitForTheFirstRefusal(stage);
        // On a server the world runs behind a screen, so the monster could have left.
        context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        context.waitTicks(AutoSleeper.NOT_SAFE_GIVE_UP_TICKS + 20);
        context.setScreen(() -> null);
        assertToldOnce(AutoSleeper.BED_NOT_SAFE_MESSAGE, stage.near());
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(AutoSleeper.NOT_SAFE_RETRY_TICKS * 2);
        assertNoBedUse("after the bed was given up");
    }

    private void neverWalksBackToAMonstersBed() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-walk-away",
                "Midnight, a bed in reach with a monster beside it; after the first refusal the"
                        + " player moves 11 blocks away",
                "Never walks the player back to that bed, and uses no other");
        waitForTheFirstRefusal(stage);
        BlockPos away = CENTRE.offset(-6, 0, -6);
        standAt(away, 180.0F);
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(AutoSleeper.NOT_SAFE_RETRY_TICKS * 2);
        boolean walking =
                context.computeOnClient(client -> baritone().getCustomGoalProcess().isActive());
        BlockPos at = context.computeOnClient(client -> client.player.blockPosition());
        if (walking || !at.equals(away)) {
            throw new AssertionError(
                    "Expected the player left where they moved to, at "
                            + away.toShortString()
                            + ", but they are at "
                            + at.toShortString()
                            + (walking ? " and Baritone walks" : ""));
        }
        assertNoBedUse("after the player moved away");
    }

    private void stopsAtAMonstersBedOnStop() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-stop",
                "Midnight, a bed in reach with a monster beside it; after the first refusal the"
                        + " player types #stop, then #help",
                "Stops for the night, says so once");
        waitForTheFirstRefusal(stage);
        playerCommand("#stop");
        playerCommand("#help");
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(AutoSleeper.NOT_SAFE_RETRY_TICKS * 2);
        assertNoBedUse("after #stop");
        assertToldOnce(AutoSleeper.STOPPED_MESSAGE);
    }

    private void keepsToAMonstersBedAcrossAReconnect() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-reconnect",
                "Midnight, a bed in reach with a monster beside it; after the first refusal the"
                        + " player moves 5 blocks from the other bed, reconnects (the first bed's"
                        + " chunk arriving 3 s after the rest), then goes to the Nether and back",
                "Uses no other bed that night, and the other bed the next night");
        waitForTheFirstRefusal(stage);
        BlockPos nearTheOther = CENTRE.offset(0, 0, -6);
        standAt(nearTheOther, 180.0F);
        // Until that chunk arrives, the client reads air where the first bed is.
        context.runOnClient(
                client ->
                        ChunkPacketHold.armForNextLevel(
                                client.level, new ChunkPos(stage.near()), 60));
        serverBedUses.clear();
        clientBedUses.set(0);
        reconnect();
        assertStaysUp("after a reconnect");
        visitTheNether();
        standAt(nearTheOther, 180.0F);
        assertStaysUp("after a trip to the Nether and back");
        ClientGameTestRecordingHud.log("No other bed after a reconnect and the Nether");
        day();
        night();
        fallAsleepIn(stage.far(), SLEEP_TIMEOUT_TICKS);
    }

    private void usesNoBedElsewhereAfterAMonstersBed() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-other-dimension",
                "Midnight, a bed in reach with a monster beside it; after the first refusal the"
                        + " player goes to another dimension of the Overworld's type, with a bed in"
                        + " reach",
                "Uses no bed there: the first bed has the respawn point");
        waitForTheFirstRefusal(stage);
        serverBedUses.clear();
        clientBedUses.set(0);
        standInABox(SECOND_OVERWORLD, true);
        assertStaysUp("in the other dimension");
    }

    private void forgetsAMonstersBedOnceItIsGone() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-bed-broken",
                "Midnight, a bed in reach with a monster beside it; after the first refusal that"
                        + " bed is broken",
                "Sleeps in the other bed");
        waitForTheFirstRefusal(stage);
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    level.setBlock(stage.near(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(
                            stage.near().relative(Direction.NORTH),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                });
        fallAsleepIn(stage.far(), SLEEP_TIMEOUT_TICKS);
    }

    private void givesUpAWalkThatNeverArrives() {
        day();
        buildStage();
        // A closed stone room whose oak door the server refuses to open, as spawn protection or a
        // claim plugin does: Baritone has a path, but the walk never arrives.
        BlockPos door = CENTRE;
        BlockPos roomBed = CENTRE.offset(0, 0, 6);
        BlockPos open = CENTRE.offset(12, 0, -6);
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.overworld();
                    for (BlockPos pos :
                            BlockPos.betweenClosed(
                                    CENTRE.offset(-3, 0, 0), CENTRE.offset(3, 3, 8))) {
                        int dx = pos.getX() - CENTRE.getX();
                        int dz = pos.getZ() - CENTRE.getZ();
                        if (Math.abs(dx) == 3 || dz == 0 || dz == 8 || pos.getY() == Y + 3) {
                            level.setBlock(
                                    pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                    BlockState doorState =
                            Blocks.OAK_DOOR
                                    .defaultBlockState()
                                    .setValue(DoorBlock.FACING, Direction.NORTH);
                    level.setBlock(
                            door,
                            doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER),
                            Block.UPDATE_CLIENTS);
                    level.setBlock(
                            door.above(),
                            doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER),
                            Block.UPDATE_CLIENTS);
                });
        placeBed(roomBed);
        placeBed(open);
        standAt(CENTRE.offset(0, 0, -6), 180.0F);
        context.waitFor(
                client -> client.level.getBlockState(door).getBlock() instanceof DoorBlock, 100);
        step(
                "walk-never-arrives",
                "Midnight, the nearest bed in a room whose door the server keeps shut, an open bed"
                        + " farther away",
                "Gives the room bed up after "
                        + AutoSleeper.WALK_DEADLINE_TICKS / 20
                        + " s, says so once, then sleeps in the open bed");
        modMessages.clear();
        refusedDoorUses.set(0);
        refuseDoors.set(true);
        night();
        fallAsleepIn(open, AutoSleeper.WALK_DEADLINE_TICKS + SLEEP_TIMEOUT_TICKS);
        if (refusedDoorUses.get() == 0) {
            throw new AssertionError("Expected the server to refuse the door at least once.");
        }
        assertToldOnce(AutoSleeper.BED_UNREACHABLE_MESSAGE, roomBed);
    }

    private void keepsThePlayersSettingsWhenTheWalkSwitchesBeds() {
        day();
        buildStage();
        BlockPos far = CENTRE.offset(0, 0, 12);
        // Beside the path to the far bed, never in reach of it: the walk has to switch.
        BlockPos near = CENTRE.offset(4, 0, 6);
        placeBed(far);
        standAt(CENTRE, 180.0F);
        step(
                "walk-switches-beds",
                "Midnight, a bed 12 blocks away; once the walk starts, a nearer bed appears beside"
                        + " the path",
                "Walks to the nearer bed and gives the player's own Baritone settings back once"
                        + " asleep in it");
        assertSettings(true, "by day");
        night();
        waitForTheWalk();
        placeBed(near);
        fallAsleepIn(near, SLEEP_TIMEOUT_TICKS);
        assertSettings(true, "after the walk switched beds and ended in bed");
    }

    private void keepsAGoalAnotherModSets() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 10);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "goal-from-another-mod",
                "Midnight; during the mod's walk another mod sends Baritone elsewhere through its API",
                "Leaves that walk alone: the player arrives there");
        night();
        waitForTheWalk();
        BlockPos elsewhere = CENTRE.offset(-8, 0, 0);
        context.runOnClient(
                client ->
                        baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(elsewhere)));
        try {
            context.waitFor(
                    client -> client.player.blockPosition().equals(elsewhere), SLEEP_TIMEOUT_TICKS);
        } catch (AssertionError e) {
            BlockPos at = context.computeOnClient(client -> client.player.blockPosition());
            Goal goal =
                    context.computeOnClient(client -> baritone().getCustomGoalProcess().getGoal());
            throw new AssertionError(
                    "Expected the other mod's walk to reach "
                            + elsewhere.toShortString()
                            + ", but the player is at "
                            + at.toShortString()
                            + " and Baritone's goal is "
                            + goal);
        }
    }

    private void staysUpAfterStop() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 8);
        BlockPos other = CENTRE.offset(0, 0, -12);
        placeBed(bed);
        placeBed(other);
        standAt(CENTRE, 180.0F);
        step(
                "stop-ends-the-walk",
                "Midnight, two beds out of reach; the player types #stop during the walk to the"
                        + " nearer one",
                "Stops, says so once, gives the player's settings back, uses no bed for the rest of"
                        + " the night, and sleeps the next night");
        modMessages.clear();
        night();
        waitForTheWalk();
        playerCommand("#stop");
        assertSettings(true, "after #stop");
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(WATCH_TICKS * 3);
        boolean walking =
                context.computeOnClient(client -> baritone().getCustomGoalProcess().isActive());
        if (walking) {
            throw new AssertionError("Expected no walk after #stop, but Baritone walks again.");
        }
        assertNoBedUse("after #stop");
        assertToldOnce(AutoSleeper.STOPPED_MESSAGE);
        day();
        standAt(CENTRE, 180.0F);
        night();
        fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
    }

    private void letsThePlayerMineDuringTheWalk() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 10);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "mine-during-the-walk",
                "Midnight; during the mod's walk the player types #mine stone",
                "Baritone mines with the player's own settings: the mod's walk ends first");
        night();
        waitForTheWalk();
        playerCommand("#mine stone");
        boolean mining = context.computeOnClient(client -> baritone().getMineProcess().isActive());
        if (!mining) {
            throw new AssertionError("Expected Baritone to mine, but it refused #mine.");
        }
        assertSettings(true, "while the player's #mine runs");
    }

    /**
     * Reads {@code baritone/settings.txt} from disk: the player's own values only, whatever the
     * mod's walk was doing when Baritone saved it. Closing the game mid-walk cannot be a step (the
     * test runs inside that game); Baritone writes the file only from {@code #set}
     * (SetCommand.java:58,131,205 in Baritone 1.17.0), so a closed game leaves the last save.
     */
    private void keepsTheSettingsFileThePlayers() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 8);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "settings-file",
                "The player saves a Baritone setting by day; then a walk, a #set during a walk, a"
                        + " reconnect and a trip to the Nether during walks",
                "baritone/settings.txt only ever holds the player's own values");
        playerCommand("#set chatDebug true");
        String saved = settingsFile();
        if (!saved.lines().toList().contains("chatDebug true")) {
            throw new AssertionError(
                    "Expected the player's #set saved, but the file is:\n" + saved);
        }
        assertNoForcedValues(saved, "after the player's own #set by day");

        night();
        fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
        assertSettingsFile(saved, "after a walk to bed");

        day();
        standAt(CENTRE, 180.0F);
        night();
        waitForTheWalk();
        playerCommand("#set chatDebug false");
        String afterSet = settingsFile();
        assertNoForcedValues(afterSet, "after a #set during a walk");
        assertSettingsFile(
                saved.replace("chatDebug true\n", ""),
                "after #set chatDebug false during a walk (only that line gone)");

        day();
        standAt(CENTRE, 180.0F);
        night();
        waitForTheWalk();
        reconnect();
        playerCommand("#set chatDebug true");
        assertSettingsFile(saved, "after a reconnect during a walk, then #set chatDebug true");

        day();
        standAt(CENTRE, 180.0F);
        night();
        waitForTheWalk();
        visitTheNether();
        standAt(CENTRE, 180.0F);
        playerCommand("#set chatDebug false");
        assertSettingsFile(
                afterSet, "after a trip to the Nether during a walk, then #set chatDebug false");

        // Aliases run their target command through the command manager (CommandAlias.java:46-48
        // in Baritone 1.17.0), so the wrapped target runs inside the wrapped alias.
        day();
        playerCommand("#modified");
        playerCommand("#set chatDebug true");
        assertSettingsFile(saved, "after #modified, then #set chatDebug true");
        playerCommand("#reset all");
        assertNoForcedValues(settingsFile(), "after #reset all");
        playerCommand("#set chatDebug true");
        List<String> afterReset = settingsFile().lines().toList();
        if (!afterReset.equals(List.of("chatDebug true"))) {
            throw new AssertionError(
                    "Expected baritone/settings.txt after #reset all, then #set chatDebug true, to"
                            + " hold only chatDebug true, but it reads "
                            + afterReset);
        }
    }

    private void keepsThePlayersOwnChatControl() {
        step(
                "own-chat-control",
                "By day the player turns Baritone's chatControl off with #set, then sets another"
                        + " setting",
                "baritone/settings.txt keeps the player's chatControl false");
        playerCommand("#set chatControl false");
        playerCommand("#set chatDebug false");
        List<String> lines = settingsFile().lines().toList();
        playerCommand("#set chatControl true");
        if (!lines.contains("chatControl false")) {
            throw new AssertionError(
                    "Expected the player's chatControl false kept in baritone/settings.txt, but it"
                            + " reads "
                            + lines);
        }
    }

    private void keepsTheWalkThroughThePauseMenu() {
        walksOnAfterAPause("walk-paused", "the pause menu is open", false);
    }

    private void keepsTheWalkThroughAPausingOverlay() {
        walksOnAfterAPause(
                "walk-paused-overlay",
                "the game is paused under an overlay with no screen, as while resource packs"
                        + " reload,",
                true);
    }

    private void walksOnAfterAPause(String id, String pause, boolean overlay) {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 10);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                id,
                "Singleplayer, midnight, a bed out of reach; "
                        + pause
                        + " for "
                        + (AutoSleeper.WALK_DEADLINE_TICKS + 20) / 20
                        + " s during the walk",
                "Goes on with the walk once the game runs again, and sleeps in that bed");
        modMessages.clear();
        night();
        waitForTheWalk();
        pause(overlay);
        context.waitTicks(AutoSleeper.WALK_DEADLINE_TICKS + 20);
        unpause();
        fallAsleepIn(bed, SLEEP_TIMEOUT_TICKS);
        assertNoMessage();
    }

    private void neverTurnsWhileAnOverlayPausesTheGame() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        // A pillar top 8 blocks above the floor: the bed is beyond the search's 6 blocks down.
        BlockPos pillar = CENTRE.offset(0, 7, 0);
        server.runOnServer(
                minecraftServer ->
                        minecraftServer
                                .overworld()
                                .setBlockAndUpdate(pillar, Blocks.STONE.defaultBlockState()));
        standAt(pillar.above(), 180.0F);
        step(
                "turn-paused-overlay",
                "Singleplayer, midnight, a bed out of the search range; the game is paused under an"
                        + " overlay with no screen, and the player is moved into reach of the bed",
                "No turn and no use while the game is paused, then sleeps");
        night();
        pause(true);
        standAt(CENTRE, 180.0F);
        server.runOnServer(
                minecraftServer ->
                        minecraftServer
                                .overworld()
                                .setBlockAndUpdate(pillar, Blocks.AIR.defaultBlockState()));
        float[] view = view();
        serverBedUses.clear();
        clientBedUses.set(0);
        context.waitTicks(WATCH_TICKS);
        assertNoBedUse("while the game is paused");
        assertViewStill(view, "while the game is paused");
        unpause();
        fallAsleepIn(bed, AutoSleeper.RETRY_INTERVAL_TICKS * 3);
    }

    private void keepsTheMonsterWaitThroughThePauseMenu() {
        MonsterStage stage = buildMonsterStage();
        step(
                "monster-wait-paused",
                "Singleplayer, midnight, a bed in reach with a monster beside it; after the first"
                        + " refusal the pause menu is open for "
                        + (AutoSleeper.NOT_SAFE_GIVE_UP_TICKS + 20) / 20
                        + " s, then the monster is gone",
                "No message, and sleeps in that bed");
        waitForTheFirstRefusal(stage);
        pause(false);
        context.waitTicks(AutoSleeper.NOT_SAFE_GIVE_UP_TICKS + 20);
        unpause();
        command("kill @e[type=minecraft:zombie]");
        fallAsleepIn(stage.near(), SLEEP_TIMEOUT_TICKS);
        assertNoMessage();
    }

    private void staysUpThroughAPauseBeforeTheTimeArrives() {
        day();
        buildStage();
        BlockPos bed = CENTRE.offset(0, 0, 3);
        placeBed(bed);
        standAt(CENTRE, 180.0F);
        step(
                "stay-up-paused",
                "Singleplayer, midnight; the player leaves the bed, goes to the Nether and back,"
                        + " and the pause menu opens as the Overworld loads, before its time arrives",
                "Stays up: the paused seconds are no wait for the server's time");
        night();
        leaveTheBed(bed);
        visitTheNether();
        // The Overworld's time arrives after half the wait the mod gives it, counting only ticks
        // in which the game runs.
        context.runOnClient(
                client ->
                        TimePacketHold.armForNextLevel(
                                client.level, AutoSleeper.TIME_SYNC_TICKS / 2));
        serverBedUses.clear();
        clientBedUses.set(0);
        Vec3 feet = Vec3.atBottomCenterOf(CENTRE);
        command("tp @a " + feet.x + " " + feet.y + " " + feet.z + " 180 10");
        context.waitFor(
                client -> client.level.dimension() == Level.OVERWORLD, STAGE_LOAD_TIMEOUT_TICKS);
        pause(false);
        context.waitTicks(AutoSleeper.TIME_SYNC_TICKS * 2);
        unpause();
        assertStaysUp("after the pause");
    }

    /**
     * Pauses a singleplayer game with the pause menu, or with an overlay and no screen, as a
     * resource reload does ({@code Overlay#isPauseScreen} is true unless overridden).
     */
    private void pause(boolean overlay) {
        if (overlay) {
            context.runOnClient(
                    client ->
                            client.setOverlay(
                                    new Overlay() {
                                        @Override
                                        public void render(
                                                GuiGraphics graphics,
                                                int mouseX,
                                                int mouseY,
                                                float partialTick) {}
                                    }));
        } else {
            context.setScreen(() -> new PauseScreen(true));
        }
        context.waitFor(Minecraft::isPaused, 20);
    }

    private void unpause() {
        context.runOnClient(client -> client.setOverlay(null));
        context.setScreen(() -> null);
    }

    private void assertNoMessage() {
        if (!modMessages.isEmpty()) {
            throw new AssertionError(
                    "Expected no message, but the mod said "
                            + modMessages.stream().map(TranslatableContents::getKey).toList());
        }
    }

    /**
     * Every tick on the client, after the mod's tick: notes water in the watched blocks, however
     * briefly it is there, and starts sneaking on the tick the mod turns the view.
     */
    private void watch(Minecraft client) {
        TimePacketHold.tick(client);
        ChunkPacketHold.tick(client);
        if (client.level == null || client.player == null) {
            return;
        }
        if (sneakOnTurnFrom != null
                && (client.player.getYRot() != sneakOnTurnFrom[0]
                        || client.player.getXRot() != sneakOnTurnFrom[1])) {
            client.options.keyShift.setDown(true);
            sneakOnTurnFrom = null;
        }
        if (watchedForWater != null) {
            for (BlockPos pos : watchedForWater) {
                if (!client.level.getFluidState(pos).isEmpty()) {
                    waterSeen = true;
                }
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
        // Steps build their stage by day: a morning the mod sees in the Overworld ends any stay-up
        // the last step left, once the new level has waited for the server's time.
        context.waitTicks(AutoSleeper.TIME_SYNC_TICKS + 5);
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

    private void assertToldOnce(String key) {
        long told = modMessages.stream().filter(message -> message.getKey().equals(key)).count();
        if (told != 1) {
            throw new AssertionError(
                    "Expected the player told once (" + key + "), but told " + told + " times.");
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

    private void waitForTheWalk() {
        try {
            context.waitFor(
                    client -> baritone().getCustomGoalProcess().isActive(),
                    AutoSleeper.RETRY_INTERVAL_TICKS * 2);
        } catch (AssertionError e) {
            throw new AssertionError("Expected the mod to start walking to the bed.");
        }
    }

    /** What the chat screen sends, where Baritone looks for its {@code #} commands. */
    private void playerCommand(String message) {
        context.runOnClient(client -> client.player.connection.sendChat(message));
        context.waitTicks(2);
    }

    private String settingsFile() {
        Path file =
                context.computeOnClient(
                        client ->
                                client.gameDirectory
                                        .toPath()
                                        .resolve("baritone")
                                        .resolve("settings.txt"));
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file + ": " + e);
        }
    }

    private void assertSettingsFile(String expected, String when) {
        String actual = settingsFile();
        assertNoForcedValues(actual, when);
        if (!actual.equals(expected)) {
            throw new AssertionError(
                    "Expected baritone/settings.txt "
                            + when
                            + " to read\n"
                            + expected
                            + "but it reads\n"
                            + actual);
        }
    }

    /** The values the mod sets: the player's defaults are the opposite. */
    private static void assertNoForcedValues(String file, String when) {
        List<String> forced =
                file.lines()
                        .filter(
                                line ->
                                        List.of(
                                                        "allowBreak false",
                                                        "allowPlace false",
                                                        "allowWaterBucketFall false",
                                                        "chatControl false")
                                                .contains(line))
                        .toList();
        if (!forced.isEmpty()) {
            throw new AssertionError(
                    "Expected none of the mod's values in baritone/settings.txt "
                            + when
                            + ", but it has "
                            + forced);
        }
    }

    /** Disconnects and joins the same server again, where the player was. */
    private void reconnect() {
        connection.close();
        connection = dedicated.connect();
        context.waitTicks(20);
    }

    /**
     * Joins the dedicated server under another address, the way the harness's {@code connect()}
     * does with its own ({@code TestDedicatedServerContextImpl#connect}).
     */
    private void joinAs(String address) {
        context.runOnClient(
                client ->
                        ConnectScreen.startConnecting(
                                client.screen,
                                client,
                                ServerAddress.parseString(address),
                                new ServerData("other address", address, ServerData.Type.OTHER),
                                false,
                                null));
        context.waitFor(
                client ->
                        client.level != null
                                && client.player != null
                                && !(client.screen instanceof LevelLoadingScreen),
                STAGE_LOAD_TIMEOUT_TICKS);
        context.waitTicks(20);
    }

    /** Leaves a server joined with {@link #joinAs}, as {@code TestServerConnection#close} does. */
    private void leaveTheServer() {
        context.runOnClient(
                client -> {
                    if (client.level != null) {
                        client.level.disconnect(Component.literal("Disconnecting"));
                        client.disconnectWithSavingScreen();
                    }
                });
        context.waitFor(client -> client.level == null);
        context.setScreen(TitleScreen::new);
    }

    /** The player sleeps in the bed and then leaves it, as with the Leave Bed button. */
    private void leaveTheBed(BlockPos bed) {
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
    }

    /** No bed use in the next {@link #STAY_UP_TICKS}. */
    private void assertStaysUp(String when) {
        context.waitTicks(STAY_UP_TICKS);
        assertNoBedUse(when);
        serverBedUses.clear();
        clientBedUses.set(0);
    }

    /** A second in a stone box in the Nether. */
    private void visitTheNether() {
        standInABox(Level.NETHER, false);
    }

    /**
     * Puts the player in a stone box at {@link #BOX_SPOT} in that dimension, with a bed in reach if
     * asked for, and returns the bed's head.
     */
    private @Nullable BlockPos standInABox(ResourceKey<Level> dimension, boolean withBed) {
        BlockPos head = BOX_SPOT.offset(0, 0, 2);
        server.runOnServer(
                minecraftServer -> {
                    ServerLevel level = minecraftServer.getLevel(dimension);
                    for (BlockPos pos :
                            BlockPos.betweenClosed(
                                    BOX_SPOT.offset(-3, -1, -3), BOX_SPOT.offset(3, 3, 3))) {
                        boolean wall =
                                Math.abs(pos.getX() - BOX_SPOT.getX()) == 3
                                        || Math.abs(pos.getZ() - BOX_SPOT.getZ()) == 3
                                        || pos.getY() == BOX_SPOT.getY() - 1
                                        || pos.getY() == BOX_SPOT.getY() + 3;
                        level.setBlock(
                                pos,
                                wall
                                        ? Blocks.STONE.defaultBlockState()
                                        : Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                    if (withBed) {
                        BlockState bed =
                                Blocks.RED_BED
                                        .defaultBlockState()
                                        .setValue(BedBlock.FACING, Direction.SOUTH);
                        level.setBlockAndUpdate(
                                head.relative(Direction.NORTH),
                                bed.setValue(BedBlock.PART, BedPart.FOOT));
                        level.setBlockAndUpdate(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
                    }
                });
        Vec3 feet = Vec3.atBottomCenterOf(BOX_SPOT);
        command(
                "execute in "
                        + dimension.identifier()
                        + " run tp @a "
                        + feet.x
                        + " "
                        + feet.y
                        + " "
                        + feet.z);
        context.waitFor(
                client ->
                        client.level.dimension() == dimension
                                && client.player.position().distanceTo(feet) < 0.1D,
                STAGE_LOAD_TIMEOUT_TICKS);
        context.waitTicks(20);
        return withBed ? head : null;
    }

    private void assertNothingPlacedOn(BlockPos bed, String when) {
        boolean clear =
                server.computeOnServer(
                        minecraftServer ->
                                minecraftServer.overworld().getBlockState(bed.above()).isAir()
                                        && minecraftServer
                                                .overworld()
                                                .getBlockState(bed.north().above())
                                                .isAir());
        if (!clear) {
            throw new AssertionError("Expected nothing placed on the bed " + when + ".");
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
