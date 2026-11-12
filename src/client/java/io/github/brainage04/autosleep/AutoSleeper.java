package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.ICommand;
import baritone.api.command.registry.Registry;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBaritoneProcess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
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

    /** Ticks before a look that missed the bed is tried again. */
    static final int MISSED_LOOK_RETRY_TICKS = 2;

    /**
     * Failed tries on one bed in one night (a look that misses it, or a use that does not put the
     * player to sleep) before the mod leaves that bed alone until the next night.
     */
    static final int TRIES_PER_BED = 3;

    static final String BED_UNUSABLE_MESSAGE = "autosleep.message.bed_unusable";
    static final String BED_UNREACHABLE_MESSAGE = "autosleep.message.bed_unreachable";
    static final String BED_NOT_SAFE_MESSAGE = "autosleep.message.bed_not_safe";
    static final String STOPPED_MESSAGE = "autosleep.message.stopped";

    /**
     * Ticks between tries of a bed the server refused because monsters are nearby: a monster that
     * wanders off is usually gone within seconds, and the view turns to the bed only at a try.
     */
    static final int NOT_SAFE_RETRY_TICKS = 100;

    /**
     * Ticks after the first "monsters nearby" refusal before that bed is given up for the night: 30
     * s, about six more tries, so a monster that never leaves (a name-tagged mob, a spawner cell
     * next to the bedroom) does not hold the player at the bed all night.
     */
    static final int NOT_SAFE_GIVE_UP_TICKS = 600;

    /**
     * Ticks a walk may take before its bed is given up for the night, counted on {@link
     * Clock#PLAYER}, for a walk that never arrives and never fails, such as one to a door the
     * server will not open: Baritone then times the door movement out after its estimate plus
     * {@code movementTimeoutTicks} (100 by default, PathExecutor.java:242-249 in Baritone 1.17.0)
     * and plans the same path again, every 6 s in the {@code walk-never-arrives} GameTest step. 30
     * s gives Baritone five such re-plans, and covers about 130 blocks of walking at its cost of
     * 4.63 ticks a block (ActionCosts.java:25), almost 4 times the distance to the farthest bed the
     * search finds (35 blocks, a corner of the search box).
     */
    static final int WALK_DEADLINE_TICKS = 600;

    private static final int SEARCH_RADIUS_HORIZONTAL = 24;
    private static final int SEARCH_RADIUS_VERTICAL = 6;

    /**
     * How far the player's feet may be from a bed block: vanilla's {@code
     * ServerPlayer#isReachableBedBlock} (ServerPlayer.java:1242-1245 in 1.21.11), restated because
     * that method is private and runs only on the server.
     */
    private static final double BED_RANGE_HORIZONTAL = 3.0D;

    private static final double BED_RANGE_VERTICAL = 2.0D;

    /**
     * Ticks a new client level waits before its day time ends a stay-up. The level starts at day
     * time 0; the server sends the real time right after it, at a join, a respawn and a dimension
     * change ({@code PlayerList#sendLevelInfo}, PlayerList.java:640-644 in 1.21.11, called from
     * :214, :424 and ServerPlayer.java:1128), but the client handles packets between its ticks, so
     * the new level can tick before that packet is handled. One second is the interval of the
     * server's regular time sync (MinecraftServer.java:1109-1111), which ends any longer gap.
     */
    static final int TIME_SYNC_TICKS = 20;

    /**
     * Ticks until the next attempt, counted on {@link Clock#PLAYER}: {@link #tick} returns before
     * the count in any other tick, as the mod cannot right-click then.
     */
    private int cooldownTicks;

    private boolean wasSleeping;

    /**
     * The level of the last tick, and the ticks the client has been in it, counted on {@link
     * Clock#WORLD}: the server sends its time only while the world runs.
     */
    private @Nullable ClientLevel lastLevel;

    private int levelTicks;

    /** Whether the last tick's answer to "would the game let me sleep here" was yes. */
    private boolean maySleepHere;

    /**
     * The player got out of bed, was woken, or ran a Baritone command while the mod was putting
     * them to bed, while the game still lets them sleep: no bed in that dimension of that world
     * until the game refuses sleep there, or until a later day starts anywhere in that world. A
     * trip to another dimension or a reconnect keeps it; beds in other dimensions and other worlds
     * are used as usual. Waking up in the morning sets it too, which keeps the player up until the
     * client sees the new day: the server sends the day time only once a second.
     */
    private @Nullable Night stayUp;

    /**
     * The bed the server refused because monsters are nearby. The use set the respawn point there
     * (ServerPlayer.java:1192-1196 in 1.21.11, before the monster check), so the mod uses no other
     * bed in that world tonight: it tries this one every {@link #NOT_SAFE_RETRY_TICKS} while the
     * player is in reach, never walks the player back to it, and gives it up after {@link
     * #NOT_SAFE_GIVE_UP_TICKS}. A trip to another dimension or a reconnect keeps it; it ends as
     * {@link #stayUp} does, or once the bed is gone.
     */
    private @Nullable NotSafe notSafe;

    /**
     * Ticks since the first "monsters nearby" refusal of {@link #notSafe}, counted on {@link
     * Clock#WORLD} while the player is in that world: a monster can leave while a screen is open,
     * but not while the game is paused.
     */
    private int notSafeTicks;

    /** How many wrapped Baritone commands are running: an alias runs its target inside it. */
    private int commandDepth;

    /** The player's own {@code chatControl}, kept off while the mod runs. */
    private boolean playerChatControl;

    /** Tonight's failed tries per bed; a bed at {@link #TRIES_PER_BED} is left alone. */
    private final Map<Question, Integer> failedTries = new HashMap<>();

    /**
     * The bed the view was put on last tick. It is used on this tick, after the player's tick has
     * sent the new view to the server.
     */
    private @Nullable Aim aim;

    /** The bed used at the last attempt: a player still awake at the next attempt was refused. */
    private @Nullable Question usedBed;

    private @Nullable Walk walk;

    /** Ticks since the current walk started, counted on {@link Clock#PLAYER}. */
    private int walkTicks;

    /** Baritone found no way to the bed of the current walk. */
    private boolean walkFailed;

    /**
     * The integrated server's latest answer, written on the server thread; see {@link
     * #integratedServerAllows}.
     */
    private volatile @Nullable ServerAnswer serverAnswer;

    private @Nullable Question lastQuestion;

    AutoSleeper() {
        // Otherwise Baritone runs any chat message that starts with one of its command names ("help
        // me", "stop", "come here") instead of sending it; its own comment asks for it to be off
        // when Baritone is used inside another mod (Settings.java:681-685 in Baritone 1.17.0).
        // Messages starting with its prefix (#) still go to Baritone.
        Settings settings = BaritoneAPI.getSettings();
        playerChatControl = settings.chatControl.value;
        settings.chatControl.value = false;
        wrapCommands();
        baritone()
                .getGameEventHandler()
                .registerEventListener(
                        new AbstractGameEventListener() {
                            @Override
                            public void onPathEvent(PathEvent event) {
                                if (event == PathEvent.CALC_FAILED
                                        && walk != null
                                        && ownsWalk(walk)) {
                                    walkFailed = true;
                                }
                            }
                        });
    }

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        boolean sleeping = player != null && player.isSleeping();
        if (wasSleeping && !sleeping && player != null && level != null) {
            stayUp = Night.of(world(minecraft), level);
        }
        wasSleeping = sleeping;
        if (level != lastLevel) {
            lastLevel = level;
            levelTicks = 0;
        } else if (counts(minecraft, Clock.WORLD)) {
            levelTicks++;
        }

        if (player == null || level == null || minecraft.gameMode == null) {
            endWalk();
            startNewNight();
            maySleepHere = false;
            return;
        }
        if (sleeping) {
            stopWalking();
            // Nothing asked before the night was skipped may be used in the morning.
            lastQuestion = null;
            aim = null;
            usedBed = null;
            return;
        }

        // Read every tick, because the answer changes with the time of day, and the client's day
        // time is exact: the walk starts on the first tick the game would let the player sleep, not
        // on the next retry.
        IntegratedServer server = minecraft.getSingleplayerServer();
        boolean allowedNow =
                server != null
                        ? clientRuleAllows(level, player.blockPosition())
                        : remoteServerAllows(level, player.blockPosition());
        @Nullable Object world = world(minecraft);
        if (levelTicks > TIME_SYNC_TICKS) {
            if (stayUp != null && stayUp.isOver(world, level, allowedNow)) {
                stayUp = null;
            }
            if (notSafe != null && notSafe.night().isOver(world, level, allowedNow)) {
                notSafe = null;
            }
        }
        if (!allowedNow) {
            stopWalking();
            startNewNight();
            maySleepHere = false;
            return;
        }
        if (!maySleepHere) {
            // Night has just fallen, or a datapack's rule has just changed: act on this tick, not a
            // second later.
            maySleepHere = true;
            cooldownTicks = 0;
        }
        if (stayUp != null && stayUp.inDimension(world, level)) {
            return;
        }

        boolean playerCanAct = counts(minecraft, Clock.PLAYER);
        if (walk != null) {
            Walk ended = walk;
            if (walkFailed || playerCanAct && ++walkTicks > WALK_DEADLINE_TICKS) {
                stopWalking();
                fail(player, ended.bed(), TRIES_PER_BED, BED_UNREACHABLE_MESSAGE);
                cooldownTicks = 0;
            } else if (!ownsWalk(ended)) {
                stopWalking();
                if (inReach(player, ended.target())) {
                    // The walk has just stopped beside the bed: use it on this tick rather than
                    // standing next to it until the next retry.
                    cooldownTicks = 0;
                }
            }
        }
        forgetGoneNotSafeBed(world, level);
        if (notSafe != null
                && !notSafe.givenUp()
                && notSafe.night().inWorld(world)
                && counts(minecraft, Clock.WORLD)
                && ++notSafeTicks > NOT_SAFE_GIVE_UP_TICKS) {
            notSafe = notSafe.givenUpNow();
            tell(player, BED_NOT_SAFE_MESSAGE, notSafe.head());
        }
        if (!playerCanAct || walk == null && baritoneBusy()) {
            // A player could not right-click now (the game paused, the pause menu, an inventory,
            // the death screen, spectator mode), or the player's own Baritone task has the player:
            // leave both alone.
            aim = null;
            return;
        }
        if (aim != null) {
            Aim aimed = aim;
            aim = null;
            use(minecraft, player, level, aimed);
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        cooldownTicks = RETRY_INTERVAL_TICKS;
        if (usedBed != null) {
            fail(player, usedBed, 1, BED_UNUSABLE_MESSAGE);
            usedBed = null;
        }

        // Every attempt takes the answer to the previous attempt's question, if any, and asks
        // afresh only when it finds a bed in singleplayer: an attempt that asks nothing leaves
        // nothing for the next one to use.
        Question previousQuestion = lastQuestion;
        ServerAnswer previousAnswer = serverAnswer;
        lastQuestion = null;
        serverAnswer = null;

        BlockPos bed = findNearestBed(world, level, player);
        if (bed == null) {
            stopWalking();
            return;
        }
        Question question = new Question(level, bedHead(level, bed));
        if (server != null
                && !integratedServerAllows(server, question, previousQuestion, previousAnswer)) {
            stopWalking();
            return;
        }

        if (inReach(player, bed)) {
            stopWalking();
            if (!usesHeldItem(player)) {
                lookAt(player, bed);
                aim = new Aim(question, bed);
            }
        } else if (notSafe != null && notSafe.night().inWorld(world)) {
            // The player has moved away from the bed the server refused for monsters: never walk
            // them back to it.
            stopWalking();
        } else if (walk == null || !walk.bed().equals(question)) {
            walkTo(question, bed);
        }
    }

    /**
     * Puts the view on the bed in one frame, as a player turns with the mouse before
     * right-clicking; {@link #use} clicks on the next tick.
     */
    private static void lookAt(LocalPlayer player, BlockPos bed) {
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
    }

    /**
     * Right-clicks the bed the view was put on last tick. The player's tick has already sent that
     * view to the server (LocalPlayer.java:241 runs inside Minecraft.java:1820, before {@code
     * END_CLIENT_TICK}), so the server gets the turn before the use. The hit result is the game's
     * own pick, so the bed is used only when the crosshair is on it, from vanilla reach.
     */
    private void use(Minecraft minecraft, LocalPlayer player, ClientLevel level, Aim aimed) {
        if (!(level.getBlockState(aimed.target()).getBlock() instanceof BedBlock)
                || !inReach(player, aimed.target())
                || usesHeldItem(player)) {
            return;
        }
        HitResult hit = player.raycastHitResult(1.0F, player);
        if (!(hit instanceof BlockHitResult blockHit)
                || blockHit.getType() != HitResult.Type.BLOCK
                || !sameBed(level, blockHit.getBlockPos(), aimed.target())) {
            fail(player, aimed.bed(), 1, BED_UNUSABLE_MESSAGE);
            cooldownTicks = MISSED_LOOK_RETRY_TICKS;
            return;
        }
        minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
        usedBed = aimed.bed();
    }

    /**
     * A right click while sneaking with anything in either hand uses the held item on the bed
     * (placing a block, lighting a fire) and never the bed itself (MultiPlayerGameMode.java:344-346
     * in 1.21.11, which only that private method decides).
     */
    private static boolean usesHeldItem(LocalPlayer player) {
        return player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
    }

    /** Counts failed tries on a bed, and tells the player once when the bed is given up. */
    private void fail(LocalPlayer player, Question bed, int tries, String message) {
        int before = failedTries.getOrDefault(bed, 0);
        int after = Math.min(TRIES_PER_BED, before + tries);
        failedTries.put(bed, after);
        if (before < TRIES_PER_BED && after == TRIES_PER_BED) {
            tell(player, message, bed.head());
        }
    }

    private static void tell(LocalPlayer player, String message, BlockPos head) {
        player.displayClientMessage(
                Component.translatable(message, head.getX(), head.getY(), head.getZ()), false);
    }

    private boolean givenUp(Question bed) {
        return failedTries.getOrDefault(bed, 0) >= TRIES_PER_BED;
    }

    /**
     * Watches the action bar for the server's answer to the last use. "Monsters nearby" does not
     * count as a failed try; see {@link #notSafe}.
     */
    public void onGameMessage(Component message, boolean overlay) {
        Component notSafeMessage = Player.BedSleepingProblem.NOT_SAFE.message();
        if (overlay
                && usedBed != null
                && notSafeMessage != null
                && message.getContents().equals(notSafeMessage.getContents())) {
            NotSafe refused =
                    new NotSafe(
                            Night.of(world(Minecraft.getInstance()), usedBed.level()),
                            usedBed.head(),
                            false);
            if (notSafe == null || !notSafe.isBed(refused)) {
                notSafe = refused;
                notSafeTicks = 0;
            }
            usedBed = null;
            cooldownTicks = NOT_SAFE_RETRY_TICKS;
        }
    }

    /**
     * Forgets everything about the night that has ended; {@link #stayUp} and {@link #notSafe} end
     * on their own, by world and day (see {@link Night}).
     */
    private void startNewNight() {
        failedTries.clear();
        aim = null;
        usedBed = null;
        // An answer from the integrated server holds for the next attempt only, never for the next
        // night.
        lastQuestion = null;
        serverAnswer = null;
    }

    /**
     * Puts every Baritone command in a {@link PlayerCommand}, keeping their order: the command
     * manager runs the first registered command with the typed name (CommandManager.java:67-73 in
     * Baritone 1.17.0), and a registry prepends what it registers (Registry.java:71-78).
     */
    private void wrapCommands() {
        Registry<ICommand> registry = baritone().getCommandManager().getRegistry();
        List<ICommand> commands = new ArrayList<>(registry.entries);
        commands.forEach(registry::unregister);
        for (ICommand command : commands.reversed()) {
            registry.register(new PlayerCommand(command, this));
        }
    }

    /**
     * A Baritone command of the player's (typed, clicked in chat or run by another mod) runs as if
     * AutoSleep were not there: the player's own settings are in place, so {@code #mine} can break
     * blocks and {@code #set} saves the player's values to {@code baritone/settings.txt}
     * (SetCommand.java:205 saves every changed setting), never the mod's. A command while the mod
     * is putting the player to bed (walking, or waiting out monsters at a bed) is the player taking
     * over: the mod stops for the rest of the night, as when the player leaves the bed. An alias
     * runs its target command inside it (CommandAlias.java:46-48), so only the outermost command
     * does this.
     */
    void beforePlayerCommand() {
        if (commandDepth++ > 0) {
            return;
        }
        BaritoneAPI.getSettings().chatControl.value = playerChatControl;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        @Nullable Object world = world(minecraft);
        boolean waitingOutMonsters =
                notSafe != null && !notSafe.givenUp() && notSafe.night().inWorld(world);
        if ((walk != null || waitingOutMonsters) && player != null && level != null) {
            stopWalking();
            stayUp = Night.of(world, level);
            if (waitingOutMonsters) {
                // Given up without a word: the player has just been told the mod stopped.
                notSafe = notSafe.givenUpNow();
            }
            player.displayClientMessage(Component.translatable(STOPPED_MESSAGE), false);
        }
    }

    /** Keeps what the command did to {@code chatControl} as the player's own value. */
    void afterPlayerCommand() {
        if (--commandDepth > 0) {
            return;
        }
        Settings settings = BaritoneAPI.getSettings();
        playerChatControl = settings.chatControl.value;
        settings.chatControl.value = false;
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
        // rule of a vanilla dimension type, as long as the server's datapacks leave it alone: the
        // Nether and the End have fixed time, so the default rule never lets anyone sleep there.
        // Any other dimension type may make beds explode.
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

    /**
     * Forgets the bed refused for monsters once it is gone. A client level fills in its chunks
     * after a join or a dimension change and reads air where it has none yet
     * (ClientChunkCache.java:74-83 and Level.java:610-613 in 1.21.11), so a bed in a missing chunk
     * is not gone.
     */
    private void forgetGoneNotSafeBed(@Nullable Object world, ClientLevel level) {
        if (notSafe != null
                && notSafe.night().inDimension(world, level)
                && level.isLoaded(notSafe.head())
                && !(level.getBlockState(notSafe.head()).getBlock() instanceof BedBlock)) {
            notSafe = null;
        }
    }

    /**
     * The nearest bed block that has not been given up tonight; in the world of a bed refused for
     * monsters nearby, only that bed, until it is given up.
     */
    private @Nullable BlockPos findNearestBed(
            @Nullable Object world, ClientLevel level, LocalPlayer player) {
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
            if (distance >= nearestDistance
                    || !(level.getBlockState(pos).getBlock() instanceof BedBlock)) {
                continue;
            }
            BlockPos head = bedHead(level, pos);
            if (!givenUp(new Question(level, head))
                    && (notSafe == null || notSafe.allows(world, level, head))) {
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

    /**
     * Walks to the bed with the settings that keep the walk to walking: no digging through or
     * building on someone's base, and no water bucket for a long fall, which Baritone allows apart
     * from {@code allowPlace} (CalculationContext.java:103-104 in Baritone 1.17.0). The player's
     * own values come back when the walk ends.
     */
    private void walkTo(Question bed, BlockPos target) {
        Settings settings = BaritoneAPI.getSettings();
        SavedSettings saved = walk != null ? walk.saved() : SavedSettings.of(settings);
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
        settings.allowWaterBucketFall.value = false;
        Goal goal = new GoalGetToBlock(target);
        walk = new Walk(bed, target, goal, saved);
        walkTicks = 0;
        walkFailed = false;
        baritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    /**
     * Cancels this mod's walk if Baritone is still on it; never a task the player gave Baritone.
     */
    private void stopWalking() {
        if (walk != null && ownsWalk(walk)) {
            baritone().getPathingBehavior().cancelEverything();
        }
        endWalk();
    }

    private void endWalk() {
        if (walk != null) {
            walk.saved().restore(BaritoneAPI.getSettings());
            walk = null;
        }
        walkFailed = false;
    }

    /** Whether Baritone is still on this walk, rather than done, stopped or given another goal. */
    private static boolean ownsWalk(Walk walk) {
        return baritone().getCustomGoalProcess().getGoal() == walk.goal();
    }

    /** A Baritone task of the player's (a {@code #goto}, {@code #goal} or {@code #mine}) is on. */
    private static boolean baritoneBusy() {
        return baritone()
                .getPathingControlManager()
                .mostRecentInControl()
                .filter(IBaritoneProcess::isActive)
                .isPresent();
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** A bed, by its head, in one client level: a new world or dimension is a new level. */
    private record Question(ClientLevel level, BlockPos head) {}

    private record ServerAnswer(Question question, boolean maySleep) {}

    /** A bed the view was put on, and the half the crosshair was aimed at. */
    private record Aim(Question bed, BlockPos target) {}

    private record Walk(Question bed, BlockPos target, Goal goal, SavedSettings saved) {}

    /**
     * A night in one dimension of one world, for {@link #stayUp} and {@link #notSafe}. It is over
     * at a refusal of sleep in that dimension, or once a later day has started anywhere in that
     * world (day numbers are compared, no ticks counted).
     */
    private record Night(@Nullable Object world, ResourceKey<Level> dimension, long day) {
        static Night of(@Nullable Object world, ClientLevel level) {
            return new Night(world, level.dimension(), dayOf(level));
        }

        boolean inWorld(@Nullable Object currentWorld) {
            return Objects.equals(world, currentWorld);
        }

        boolean inDimension(@Nullable Object currentWorld, ClientLevel level) {
            return inWorld(currentWorld) && level.dimension().equals(dimension);
        }

        boolean isOver(@Nullable Object currentWorld, ClientLevel level, boolean allowedNow) {
            return inWorld(currentWorld)
                    && (dayOf(level) != day || level.dimension().equals(dimension) && !allowedNow);
        }
    }

    /** The bed refused for monsters, by its head; see {@link #notSafe}. */
    private record NotSafe(Night night, BlockPos head, boolean givenUp) {
        boolean isBed(NotSafe other) {
            return Objects.equals(night.world(), other.night().world())
                    && night.dimension().equals(other.night().dimension())
                    && head.equals(other.head());
        }

        NotSafe givenUpNow() {
            return new NotSafe(night, head, true);
        }

        /** Whether the mod may use this bed: in this world, only the refused one, not given up. */
        boolean allows(@Nullable Object currentWorld, ClientLevel level, BlockPos bedHead) {
            return !night.inWorld(currentWorld)
                    || !givenUp && night.inDimension(currentWorld, level) && head.equals(bedHead);
        }
    }

    /**
     * Which ticks a tick counter counts; {@link #counts} decides, and each counter names its clock
     * where it is declared. No clock counts a tick in which a singleplayer game is paused: the
     * integrated server then stands still (IntegratedServer.java:111-121 in 1.21.11), and so do the
     * world, its monsters and the time the server sends.
     */
    private enum Clock {
        /** Every tick in which the world runs; an open screen does not stop it. */
        WORLD,

        /**
         * The ticks in which the player could right-click: the world runs, no screen is open and
         * the player is not in spectator mode.
         */
        PLAYER
    }

    private static boolean counts(Minecraft minecraft, Clock clock) {
        if (minecraft.isPaused()) {
            return false;
        }
        return switch (clock) {
            case WORLD -> true;
            case PLAYER ->
                    minecraft.screen == null
                            && minecraft.player != null
                            && !minecraft.player.isSpectator();
        };
    }

    /**
     * The world the client is in: the save folder of a singleplayer or LAN host's world, or the
     * server's address.
     */
    private static @Nullable Object world(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            return server.getWorldPath(LevelResource.ROOT);
        }
        ServerData serverData = minecraft.getCurrentServer();
        return serverData != null ? serverData.ip : null;
    }

    private static long dayOf(ClientLevel level) {
        return level.getDayTime() / SharedConstants.TICKS_PER_GAME_DAY;
    }

    /** The player's own values of the Baritone settings a walk changes. */
    private record SavedSettings(
            boolean allowBreak, boolean allowPlace, boolean allowWaterBucketFall) {
        static SavedSettings of(Settings settings) {
            return new SavedSettings(
                    settings.allowBreak.value,
                    settings.allowPlace.value,
                    settings.allowWaterBucketFall.value);
        }

        void restore(Settings settings) {
            settings.allowBreak.value = allowBreak;
            settings.allowPlace.value = allowPlace;
            settings.allowWaterBucketFall.value = allowWaterBucketFall;
        }
    }
}
