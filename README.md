# AutoSleep

AutoSleep is a client-side Fabric mod for Minecraft 1.21.11. Whenever the game would let you sleep (at night, or in a thunderstorm, in the Overworld) and you are not already in bed, it puts you to sleep in the nearest bed, walking you there if it is out of reach.

It uses the bed as your own right click would, so servers need nothing installed.
It does move you on its own: it turns your view and walks you with [Baritone](https://github.com/cabaletta/baritone), which many servers' rules forbid, so check a server's rules before you use it there.
It was not tested against anti-cheat plugins.

## How it works

While you are awake, the mod asks every tick whether the game would let you sleep in a bed here, and finds the nearest bed within 24 blocks horizontally and 6 vertically.
Vanilla decides that by the dimension's bed rule (the `gameplay/bed_rule` environment attribute at the bed): whether beds explode, and whether you can sleep always, never or only when it is dark outside.
Servers do not send that rule to clients, so the mod gets it like this:

- **Singleplayer, or the host of a LAN world:** it asks the game's own integrated server, so every dimension's and biome's bed rule counts, datapack and modded ones included.
- **On a server, in a vanilla dimension type** (`minecraft:...`): it uses vanilla's default rule, sleep when dark outside. The Nether and the End have no night, so beds there are never used.
- **On a server, in any other dimension type:** it never uses a bed, because that dimension's beds may explode and the client cannot tell.

If you may sleep in the bed and it is in reach (vanilla's 3 blocks horizontally and 2 vertically, and your block interaction range), the mod puts your view on the bed in one frame and right-clicks it on the next tick, so the server has your new view before the click.
The mod asks the game for the crosshair target itself, so it uses the bed only when the crosshair is on the bed itself and the bed is within your block interaction range, exactly as when you right-click it yourself.
Otherwise Baritone walks you next to the bed first.
On a server the walk starts on the first tick the game lets you sleep. In singleplayer it starts a second later, because the mod first asks the integrated server about that bed.

Baritone is bundled in the mod's jar. For its own walks the mod sets Baritone's `allowBreak`, `allowPlace` and `allowWaterBucketFall` to false, so a walk never breaks or places a block and never pours water to break a long fall. A walk does open doors and fence gates on the way, as Baritone always does.
Your own values of those settings come back when the walk ends. The mod never starts a walk while a Baritone task of yours (a `#goto`, `#goal` or `#mine`) is running, and never cancels one.
The mod turns off Baritone's `chatControl`, so a chat message that starts with a Baritone command, such as "help me" or "stop", goes to the server as chat. Messages that start with `#` still go to Baritone.

A Baritone command of yours runs as if the mod were not there, with your own settings, so `#mine` or `#goto` start at once as you set them up. If the mod is putting you to bed at the time (walking you, or waiting out monsters at a bed), the command takes over: the mod stops for the rest of the night and says so in chat, so `#stop` stops it for good. The next night it works again.
Every command also sees your own `chatControl`, so `#set` saves only your own values to `baritone/settings.txt`, never the mod's.

When you get out of bed, or something wakes you, while it is still night, the mod leaves you up until the next day, also after a trip to another dimension or a reconnect to the same world. This holds only in the dimension and world where you left the bed: in another dimension where you may sleep, or on another server, the mod puts you to bed as usual.
The mod tells worlds apart by the save folder of a singleplayer world or by the server address, so two different worlds behind one address count as one world: a night the mod recorded in one can carry into the other while both are on the same day number.
Waking up in the morning works the same way until your client sees the new day, which the server sends once a second.

The mod waits, without turning your view, while you sneak with anything in either hand, because a right click then uses that item on the bed (placing a block on it, for example) instead of the bed.
It does nothing while a screen is open (the pause menu, your inventory, the death screen), while a singleplayer game is paused (also under the loading overlay of a resource reload) or while you are in spectator mode.

If the crosshair cannot reach the bed (a floor, a wall or a cat in the way) or the server refuses it (someone else in it, a plugin), the mod tries that bed 3 times, tells you once in chat and leaves it alone until the next night.
A bed that Baritone finds no way to, or does not reach within 30 seconds of walking (behind a door the server will not open, for example), is left alone at once, with a message too. Time with a screen open (the pause menu, for example) or with a singleplayer game paused does not count. Either way the mod then tries the next nearest bed.

Using a bed sets your respawn point there, as your own right click does, even when the server then refuses sleep. The mod picks the nearest bed within 24 blocks, which may be a bed in a village or in someone else's base, so your respawn point can move to it.
When the server refuses a bed because monsters are nearby, that bed now has your respawn point, so the mod uses no other bed in that world that night, in any dimension, also after a trip to another dimension or a reconnect. It tries that bed again every 5 seconds while you are in reach, never walks you back to it, and after 30 seconds of monsters it tells you once and stops trying it. The 30 seconds run on while a screen is open, since the world goes on behind it, but not while a singleplayer game is paused. The next day the mod uses beds as usual again.
A bed that someone breaks while you are away still counts as there: the mod cannot tell until you are near it again, so that night can go unused.
On a server whose world time never advances, the day number never changes, so the mod stops using beds in that world until you restart the game.

On a server the mod assumes that no datapack changes the bed rule of a vanilla dimension type, of a biome or through a timeline.

## Installing

Put the mod JAR in the `mods` folder of a Fabric 1.21.11 client, together with [Fabric API](https://modrinth.com/mod/fabric-api).
Servers need nothing.

AutoSleep bundles the Baritone it needs, Baritone's API build (`baritone-api-fabric` 1.17.0).
Baritone's standalone jar (`baritone-standalone-fabric`) replaces the bundled one and has no API, so with that jar in the `mods` folder AutoSleep turns itself off and says so in chat.
A `baritone-api-fabric` 1.17.0 jar of your own works.

## Development

- JDK 25 to run Gradle. The mod itself targets Java 21.
- `./gradlew build` builds the mod into `build/libs` and runs the unit tests. It fails on unformatted code (`./gradlew spotlessApply` fixes that), Checkstyle findings and compiler warnings.
- `./gradlew runClient` launches a development client.

## Testing

```shell
./gradlew test
```

runs the Fabric-aware metadata tests, the `BedLook` turn tests and `BaritoneMissingTest`, which starts the mod with Baritone's API hidden, as Baritone's standalone jar hides it, and checks that it turns itself off instead of crashing.

The client GameTests in `src/gametest` run these steps in order. Each step's number and id show in the recording panel in the top-right corner (a 1.21.11 port of MinecraftModConventions' recording HUD), and the log has a `[CLIENT_GAMETEST_RECORDER]` line for it. The top-left panel shows the day time out of 24000, the Overworld's night by the clock, the dimension, and whether the client sees it as night.

`AutoSleepClientGameTest` starts a dedicated server without the mod, joins it and loads a saved scene, `data/autosleep_test/structure/bedroom.nbt`: an oak plank floor with a red and a blue bed ten blocks apart, out of reach of the player. Each step is one setup filmed twice: about two seconds by day, where the mod must do nothing, then nightfall on the same setup, where it must act at once.

|Step|Id|Situation|By day|At nightfall|
|---|---|---|---|---|
|1|`out-of-reach`|the red bed four and a half blocks away|does nothing, does not even turn|starts walking within two ticks, reaches the bed, looks at it and sleeps|
|2|`in-reach`|the red bed in reach and in view, 30 degrees beside the crosshair|does nothing, does not even turn|turns to the bed at once and uses it on the next tick, within two ticks of nightfall, then sleeps|
|3|`nearest-bed-blue`|both beds on camera, the blue bed nearer|does nothing, does not even turn|starts walking within two ticks to the blue bed, not the red one, and sleeps|
|4|`nearest-bed-red`|the same two beds, the red bed nearer|does nothing, does not even turn|starts walking within two ticks to the red bed and sleeps|

After each night the test checks the player stays up once the night is skipped.

Every bed use is checked through a `UseBlockCallback` handler on the client, where the mod made the decision: the game's crosshair target must be a block hit on that bed, the player's eye must be within the vanilla 4.5-block block interaction range of it, and neither interaction range may have been modified.
The same handler on the server checks that the player's view, as the server last received it, was already on that bed when the use arrived.
A use that fails any of those, or any use while the player is out of reach of the bed, fails the test.

`BedRuleClientGameTest` checks the bed rules, first in a singleplayer world and then on a dedicated server, at midnight with a bed 8 blocks away. The test datapack in `src/gametest/resources/data/autosleep_test` adds two dimensions with a day-night cycle, `exploding_beds`, whose beds explode although they allow sleep, and `allowed_beds`, whose beds work, and a biome `exploding_beds` whose beds explode.

|Step|Id|Expected|
|---|---|---|
|5|`singleplayer-the_nether-bed`|stays put, never uses the bed|
|6|`singleplayer-the_end-bed`|stays put, never uses the bed|
|7|`singleplayer-exploding_beds-bed`|stays put, never uses the bed|
|8|`singleplayer-allowed_beds-bed`|walks to the bed and sleeps|
|9|`singleplayer-exploding_biome-bed`|walking to a bed in `allowed_beds`, put beside a second bed in the exploding biome: never uses the second bed|
|10|`singleplayer-other_dimension-bed`|walking to the bed in `allowed_beds`, put beside the bed at the same position in `exploding_beds`: never uses it|
|11|`singleplayer-stale_answer-bed`|walking to the bed in `allowed_beds`, out of range for 4 s while its biome becomes the exploding one, then back beside it: never uses it|
|12|`singleplayer-next_night-bed`|walking to the bed in `allowed_beds`, then day comes, its biome becomes the exploding one, and the next night falls with the player beside it: never uses it|
|13|`singleplayer-overworld-bed`|walks to the bed and sleeps, stays up after the night is skipped|
|14|`dedicated-the_nether-bed`|stays put, never uses the bed|
|15|`dedicated-the_end-bed`|stays put, never uses the bed|
|16|`dedicated-exploding_beds-bed`|stays put, never uses the bed|
|17|`dedicated-allowed_beds-bed`|stays put: the client cannot see this dimension's bed rule|
|18|`dedicated-overworld-bed`|walks to the bed and sleeps, stays up after the night is skipped|

Each of those steps starts its night from a day, because a player woken at night stays up until the next night.

`SafeguardsClientGameTest` checks what the player keeps control of and what the mod does with a bed it cannot use: steps 19 to 46 on a dedicated server, steps 47 to 51 in singleplayer. Each step builds its own stage by day and then sets midnight with time stopped.

|Step|Id|Situation|Expected|
|---|---|---|---|
|19|`chat-reaches-the-server`|the player chats "help me", which starts with a Baritone command|the server receives the message|
|20|`stays-up-after-leaving-the-bed`|a bed in reach, the player sleeps and then leaves the bed|no bed use for 10 s, nor after a trip to the Nether and back (the Overworld's time held back 5 ticks on the way back, as a network can) or a reconnect that night, then sleeps again the next night|
|21|`stays-up-in-its-own-dimension`|the player leaves the bed, then goes to a second dimension of the Overworld's type with a bed in reach|sleeps there|
|22|`stay-up-ends-the-next-day`|the player leaves the bed, the next day and the next night pass while they are in the Nether|back in the Overworld, sleeps in the bed|
|23|`stays-up-in-its-own-world`|the player leaves the bed, then joins the server under another address|sleeps there|
|24|`sneaking-with-blocks`|a bed in reach, the player sneaking with planks in hand|no turn, no use and nothing placed on the bed, then sleeps once the player stops sneaking|
|25|`sneak-before-the-click`|a bed in reach, planks in hand, the player starts sneaking on the tick the view turns to the bed|no use and nothing placed on the bed, then sleeps once the player stops sneaking|
|26|`bed-under-the-floor`|the nearest bed under the floor the player stands on, an open bed 8 blocks away|tells the player once that it leaves the first bed alone, then sleeps in the open bed|
|27|`crosshair-short-of-the-bed`|a bed in reach whose centre is beyond the 4.5-block interaction range, so the game's pick ends above it|never uses it, tells the player once, then leaves the view alone|
|28|`server-refuses-the-bed`|a bed in reach that the server refuses, another bed 8 blocks away|3 uses of the first bed, tells the player once, then sleeps in the other bed|
|29|`monsters-nearby`|a bed in reach with a monster beside it, another bed 11 blocks away|tries the first bed every 5 s, never the other, no message, sleeps in the first bed once the monster is gone|
|30|`monster-stays`|the same, the monster never leaves, the inventory open after the first refusal|after 30 s tells the player once and stops trying, never the other bed|
|31|`monster-walk-away`|the same, the player moves 11 blocks away after the first refusal|no walk back and no use|
|32|`monster-stop`|the same, the player types `#stop`, then `#help`, after the first refusal|no use, tells the player once|
|33|`monster-reconnect`|the same, the player moves 5 blocks from the other bed after the first refusal, reconnects (the first bed's chunk held back 3 s, as a server can send it late), then goes to the Nether and back|no use of the other bed that night, sleeps in it the next night|
|34|`monster-other-dimension`|the same, the player goes to a second dimension of the Overworld's type with a bed in reach after the first refusal|no use there|
|35|`monster-bed-broken`|the same, the first bed is broken after the first refusal|sleeps in the other bed|
|36|`bed-in-a-pit`|the nearest bed at the bottom of a 6-block shaft, a water bucket in hand, an open bed farther away|no water ever in the shaft, full health, tells the player once that it found no way to the first bed, sleeps in the open bed|
|37|`walk-never-arrives`|the nearest bed in a room whose door the server keeps shut, an open bed farther away|gives the room bed up after 30 s, tells the player once, sleeps in the open bed|
|38|`walk-switches-beds`|a bed 12 blocks away, and a nearer bed beside the path once the walk starts|sleeps in the nearer bed, and the player's own `allowBreak`, `allowPlace` and `allowWaterBucketFall` are back afterwards|
|39|`own-baritone-goal`|the player's own Baritone goal set by day, then nightfall|keeps the player's goal at night and at daybreak, turns off `allowBreak`, `allowPlace` and `allowWaterBucketFall` only during its own walk, and gives them back after sleeping|
|40|`goal-from-another-mod`|another mod sends Baritone elsewhere through its API during the mod's walk|leaves that walk alone: the player arrives there|
|41|`stop-ends-the-walk`|two beds out of reach, the player types `#stop` during the walk to the nearer one|the player's settings are back, tells the player once, no walk and no use for the rest of the night, sleeps the next night|
|42|`mine-during-the-walk`|the player types `#mine stone` during the mod's walk|Baritone mines, with the player's own settings|
|43|`settings-file`|the player saves a Baritone setting by day, then a walk, a `#set` during a walk, a reconnect and a trip to the Nether during walks, then `#modified`, `#reset all` and `#set` by day|`baritone/settings.txt` read from disk: the same bytes after the walk and after each later `#set` of the same value, only the typed setting changed after the `#set` during the walk, only `chatDebug true` after `#reset all` and `#set`, and never the mod's values|
|44|`own-chat-control`|the player turns `chatControl` off with `#set`, then sets another setting|the file keeps the player's `chatControl false`|
|45|`screen-open`|a bed in reach, the inventory open|no turn and no use while it is open, sleeps once it is closed|
|46|`spectator`|a bed in reach, the player in spectator mode|no turn and no use, sleeps once back in survival|
|47|`walk-paused`|singleplayer, a bed out of reach, the pause menu open for 31 s during the walk|no message, sleeps in that bed once the menu is closed|
|48|`walk-paused-overlay`|the same, the game paused for 31 s under an overlay with no screen, as during a resource reload|no message, sleeps in that bed once the overlay is gone|
|49|`turn-paused-overlay`|singleplayer, midnight, a bed out of the search range; while an overlay pauses the game the player is moved into reach of it|no turn and no use while paused, sleeps once the overlay is gone|
|50|`monster-wait-paused`|singleplayer, a bed in reach with a monster beside it, the pause menu open for 31 s after the first refusal, then the monster is gone|no message, sleeps in that bed|
|51|`stay-up-paused`|singleplayer, the player leaves the bed, goes to the Nether and back, and the game is paused as the Overworld loads, before its time arrives (held back for 10 ticks of running game)|no bed use|

```shell
./gradlew runClientGameTest
```

On a headless Linux machine without a GPU display, run it under Xvfb:

```shell
ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 \
xvfb-run -a --server-args="-screen 0 1920x1080x24" \
./gradlew --no-daemon runClientGameTest
```

The client GameTest window is 1920x1080 at GUI scale 3, so it fills that screen with the same 640x360 GUI pixels these recordings have always shown.

`scripts/record-client-gametest.sh` builds the mod, runs the unit tests and records the client GameTests the same way into `build/recording/raw.mp4`.
On a host with a GPU display for recordings, name it in `AUTOSLEEP_RECORDING_DISPLAY` (`:1`) or in `/etc/gametest-recording-display` (`:1 NVIDIA`): the run then draws on that display, holds it under `/tmp/.gtr-recording-display-1.lock` for the whole take, and fails unless the client reports an NVIDIA renderer.
When a PulseAudio server is reachable it also records the game's sound: the game plays into a private null sink named `autosleep_recording_<timestamp>`, ffmpeg records that sink's monitor as 32-bit float into `build/recording/raw-audio.wav`, and the sink is removed afterwards, so the desktop's own output is never touched.
Without one (or with `AUTOSLEEP_RECORDING_AUDIO=off`) it records video only and says so.
Every step and observation the test makes prints a `[CLIENT_GAMETEST_RECORDER] <epoch millis>` line into the game log; with `ffmpeg-start` (the capture's epoch) those lines date each scenario in the recording.
`scripts/trim-scenarios.py` cuts the scenarios out of the raw capture on those lines, fades every cut for 30 ms, and raises the game's sound by one linear gain, the smaller of the two that reach -20 LUFS and a -2 dBFS true peak, so nothing compresses or limits it; `scripts/finalize-recording.sh` runs the cut, writes the sidecar beside the video and measures the result.

## Publishing

Release automation is documented in [docs/RELEASE.md](docs/RELEASE.md).

## Credits

Generated from [ModernMinecraftModTemplate](https://github.com/brainage04/ModernMinecraftModTemplate) (its last Fabric 1.21.11 version).
Bundles [Baritone](https://github.com/cabaletta/baritone) 1.17.0 for walking to the bed, unmodified. Baritone is under the GNU LGPL 3.0: see `NOTICE`, `COPYING.LESSER` and `COPYING`, which the mod's jar carries too. Its source for this version is at <https://github.com/cabaletta/baritone/tree/v1.17.0>.
