# AutoSleep

AutoSleep is a client-side Fabric mod for Minecraft 1.21.11. Whenever the game would let you sleep (at night, or in a thunderstorm, in the Overworld) and you are not already in bed, it puts you to sleep in the nearest bed, walking you there if it is out of reach.

It uses the bed for you exactly as a right click would, so it works on any server, including vanilla servers without the mod.

## How it works

While you are awake, the mod asks every tick whether the game would let you sleep in a bed here, and finds the nearest bed within 24 blocks horizontally and 6 vertically.
Vanilla decides that by the dimension's bed rule (the `gameplay/bed_rule` environment attribute at the bed): whether beds explode, and whether you can sleep always, never or only when it is dark outside.
Servers do not send that rule to clients, so the mod gets it like this:

- **Singleplayer, or the host of a LAN world:** it asks the game's own integrated server, so every dimension's and biome's bed rule counts, datapack and modded ones included.
- **On a server, in a vanilla dimension type** (`minecraft:...`): it uses vanilla's default rule, sleep when dark outside. The Nether and the End have no night, so beds there are never used.
- **On a server, in any other dimension type:** it never uses a bed, because that dimension's beds may explode and the client cannot tell.

If you may sleep in the bed and it is in reach (vanilla's 3 blocks horizontally and 2 vertically, and your block interaction range), the mod puts your view on the bed and right-clicks it on that same tick: it does not walk the view round, and it does not wait before using the bed.
The bed is only ever used that way: the mod asks the game for the crosshair target itself, so it uses the bed only when you are looking at it and it is within your block interaction range, exactly as when you right-click it yourself.
Otherwise [Baritone](https://github.com/cabaletta/baritone) walks you next to it first, and the walk starts on the same tick the game first lets you sleep rather than at the next retry. Baritone is bundled in the mod's jar and is set never to break or place blocks on the way.

After waking up the mod waits two seconds, because the server sends the new day time only once a second and the morning would otherwise still look like night.

The mod assumes the simple case: the bed is free and not obstructed, no monsters are nearby and time runs normally.
On a server it also assumes that no datapack changes the bed rule of a vanilla dimension type, of a biome or through a timeline.
If the server refuses the bed anyway, you see the usual vanilla message and the mod tries again a second later.

## Installing

Put the mod JAR in the `mods` folder of a Fabric 1.21.11 client, together with [Fabric API](https://modrinth.com/mod/fabric-api).
Servers need nothing.

## Development

- Java 21 or newer.
- `./gradlew build` builds the mod into `build/libs` and runs the unit tests.
- `./gradlew runClient` launches a development client.

## Testing

```shell
./gradlew test
```

runs the Fabric-aware metadata tests and the `BedLook` turn tests in `src/test/java`.

The client GameTests in `src/gametest` run these steps in order. Each step's number and id show in the recording panel in the top-right corner (a 1.21.11 port of MinecraftModConventions' recording HUD), and the log has a `[CLIENT_GAMETEST_RECORDER]` line for it. The top-left panel shows the day time out of 24000, the Overworld's night by the clock, the dimension, and whether the client sees it as night.

`AutoSleepClientGameTest` starts a dedicated server without the mod, joins it and loads a saved scene, `data/autosleep_test/structure/bedroom.nbt`: an oak plank floor with a red and a blue bed ten blocks apart, out of reach of the player. Each step is one setup filmed twice: about two seconds by day, where the mod must do nothing, then nightfall on the same setup, where it must act at once.

|Step|Id|Situation|By day|At nightfall|
|---|---|---|---|---|
|1|`out-of-reach`|the red bed four and a half blocks away|does nothing, does not even turn|starts walking within two ticks, reaches the bed, looks at it and sleeps|
|2|`in-reach`|the red bed in reach and in view, 30 degrees beside the crosshair|does nothing, does not even turn|looks at the bed and uses it within two ticks, then sleeps|
|3|`nearest-bed-blue`|both beds on camera, the blue bed nearer|does nothing, does not even turn|starts walking within two ticks to the blue bed, not the red one, and sleeps|
|4|`nearest-bed-red`|the same two beds, the red bed nearer|does nothing, does not even turn|starts walking within two ticks to the red bed and sleeps|

After each night the test checks the player stays up once the night is skipped.

Every bed use is checked through a `UseBlockCallback` handler on the client, where the mod made the decision: the game's crosshair target must be that bed, the player's eye must be within the vanilla 4.5-block block interaction range of it, and neither interaction range may have been modified.
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
|12|`singleplayer-overworld-bed`|walks to the bed and sleeps, stays up after the night is skipped|
|13|`dedicated-the_nether-bed`|stays put, never uses the bed|
|14|`dedicated-the_end-bed`|stays put, never uses the bed|
|15|`dedicated-exploding_beds-bed`|stays put, never uses the bed|
|16|`dedicated-allowed_beds-bed`|stays put: the client cannot see this dimension's bed rule|
|17|`dedicated-overworld-bed`|walks to the bed and sleeps, stays up after the night is skipped|

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
Bundles [Baritone](https://github.com/cabaletta/baritone) 1.17.0 (LGPL-3.0) for walking to the bed.
