# AutoSleep

A client-side Fabric mod for Minecraft 1.21.11: when it is night and you are not already in bed, it puts you to sleep in the nearest bed, walking you there if it is out of reach.

It uses the bed for you exactly as a right click would, so it works on any server, including vanilla servers without the mod.

## How it works

Night is day time 12523 to 23477 out of 24000: the part of the night when a clear overworld sky is dark enough for vanilla to let you sleep.
Once per second while you are awake at night, the mod finds the nearest bed within 24 blocks horizontally and 6 vertically; it leaves beds alone where the dimension's bed rule makes them explode (the Nether, the End, or a custom dimension that says so).
If it is in reach (vanilla's 3 blocks horizontally and 2 vertically, and your block interaction range), the mod uses it.
Otherwise [Baritone](https://github.com/cabaletta/baritone) walks you next to it first; Baritone is bundled in the mod's jar and is set never to break or place blocks on the way.

After waking up the mod waits two seconds, because the server sends the new day time only once a second and the morning would otherwise still look like night.

The mod assumes the simple case: the bed is free and not obstructed, no monsters are nearby and time runs normally.
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

The client GameTest in `src/gametest` starts a dedicated server without the mod, joins it, places a red and a blue bed out of reach of the player and runs these steps in order. Each step's number and id show in the recording panel in the top-right corner (a 1.21.11 port of MinecraftModConventions' recording HUD), and the log has a `[CLIENT_GAMETEST_RECORDER]` line for it. The top-left panel shows the day time out of 24000, when night starts and ends, and whether it is day or night.

|Step|Id|Situation|Expected|
|---|---|---|---|
|1|`daytime`|daytime, two beds out of reach|stays put, awake|
|2|`nightfall-walks-to-bed`|night falls, the red bed is nearest|walks to the red bed and sleeps, stays up after the night is skipped|
|3|`bed-in-reach`|next night, beside the red bed|sleeps straight away|
|4|`nearest-bed`|moved closer to the blue bed|walks to the blue bed and sleeps|

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
