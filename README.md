# AutoSleep

A client-side Fabric mod for Minecraft 1.21.11: whenever the game would let you sleep (at night in the Overworld) and you are not already in bed, it puts you to sleep in the nearest bed, walking you there if it is out of reach.

It uses the bed for you exactly as a right click would, so it works on any server, including vanilla servers without the mod.

## How it works

Once per second while you are awake, the mod finds the nearest bed within 24 blocks horizontally and 6 vertically and asks whether the server would let you sleep in it now.
Vanilla decides that by the dimension's bed rule (the `gameplay/bed_rule` environment attribute at the bed): whether beds explode, and whether you can sleep always, never or only when it is dark outside.
Servers do not send that rule to clients, so the mod gets it like this:

- **Singleplayer, or the host of a LAN world:** it asks the game's own integrated server, so every dimension's and biome's bed rule counts, datapack and modded ones included.
- **On a server, in a vanilla dimension type** (`minecraft:...`): it uses vanilla's default rule, sleep when dark outside. The Nether and the End have no night, so beds there are never used.
- **On a server, in any other dimension type:** it never uses a bed, because that dimension's beds may explode and the client cannot tell.

If you may sleep in the bed and it is in reach (vanilla's 3 blocks horizontally and 2 vertically, and your block interaction range), the mod uses it.
Otherwise [Baritone](https://github.com/cabaletta/baritone) walks you next to it first; Baritone is bundled in the mod's jar and is set never to break or place blocks on the way.

After waking up the mod waits two seconds, because the server sends the new day time only once a second and the morning would otherwise still look like night.

The mod assumes the simple case: the bed is free and not obstructed, no monsters are nearby and time runs normally.
On a server it also assumes that no datapack changes the bed rule of a vanilla dimension type or of a biome.
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

runs the Fabric-aware metadata tests in `src/test/java`.

The client GameTests in `src/gametest` run these steps in order. Each step's number and id show in the recording panel in the top-right corner (a 1.21.11 port of MinecraftModConventions' recording HUD), and the log has a `[CLIENT_GAMETEST_RECORDER]` line for it. The top-left panel shows the day time out of 24000, the Overworld's night by the clock, the dimension, and whether the client sees it as night.

`AutoSleepClientGameTest` starts a dedicated server without the mod, joins it and places a red and a blue bed out of reach of the player:

|Step|Id|Situation|Expected|
|---|---|---|---|
|1|`daytime`|daytime, two beds out of reach|stays put, awake|
|2|`nightfall-walks-to-bed`|night falls, the red bed is nearest|walks to the red bed and sleeps, stays up after the night is skipped|
|3|`bed-in-reach`|next night, beside the red bed|sleeps straight away|
|4|`nearest-bed`|moved closer to the blue bed|walks to the blue bed and sleeps|

`BedRuleClientGameTest` checks each dimension's bed rule, first in a singleplayer world and then on a dedicated server, at midnight with a bed 8 blocks away. The test datapack in `src/gametest/resources/data/autosleep_test` adds two dimensions with a day-night cycle: `exploding_beds`, whose beds explode, and `allowed_beds`, whose beds work.

|Step|Id|Expected|
|---|---|---|
|5|`singleplayer-the_nether-bed`|stays put, never uses the bed|
|6|`singleplayer-exploding_beds-bed`|stays put, never uses the bed|
|7|`singleplayer-allowed_beds-bed`|walks to the bed and sleeps|
|8|`singleplayer-overworld-bed`|walks to the bed and sleeps|
|9|`dedicated-the_nether-bed`|stays put, never uses the bed|
|10|`dedicated-exploding_beds-bed`|stays put, never uses the bed|
|11|`dedicated-allowed_beds-bed`|stays put: the client cannot see this dimension's bed rule|
|12|`dedicated-overworld-bed`|walks to the bed and sleeps|

```shell
./gradlew runClientGameTest
```

On a headless Linux machine, run it under Xvfb:

```shell
ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 \
xvfb-run -a --server-args="-screen 0 1280x720x24" \
./gradlew --no-daemon runClientGameTest
```

The client GameTest window is 1280x720, so it fills that screen.

## Publishing

Release automation is documented in [docs/RELEASE.md](docs/RELEASE.md).

## Credits

Generated from [ModernMinecraftModTemplate](https://github.com/brainage04/ModernMinecraftModTemplate) (its last Fabric 1.21.11 version).
Bundles [Baritone](https://github.com/cabaletta/baritone) 1.17.0 (LGPL-3.0) for walking to the bed.
