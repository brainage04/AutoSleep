# AutoSleep

AutoSleep is a client-side only Fabric mod for Minecraft 1.21.11 that puts you to sleep in the nearest bed whenever the game would let you sleep (at night, or in a thunderstorm, in the Overworld) and you are not already in bed, walking you there if it is out of reach.

It uses the bed as your own right click would, so servers need nothing installed.
It does move you on its own: it turns your view and walks you with [Baritone](https://github.com/cabaletta/baritone), which many servers' rules forbid, so check a server's rules before you use it there.

## Features

### Which beds it uses

While you are awake, the mod asks every tick whether the game would let you sleep in a bed here, and finds the nearest bed within 24 blocks horizontally and 6 vertically.
Vanilla decides that by the dimension's bed rule (the `gameplay/bed_rule` environment attribute at the bed): whether beds explode, and whether you can sleep always, never or only when it is dark outside.
Servers do not send that rule to clients, so the mod gets it like this:

- **Singleplayer, or the host of a LAN world:** it asks the game's own integrated server, so every dimension's and biome's bed rule counts, datapack and modded ones included.
- **On a server, in a vanilla dimension type** (`minecraft:...`): it uses vanilla's default rule, sleep when dark outside. The Nether and the End have no night, so beds there are never used.
- **On a server, in any other dimension type:** it never uses a bed, because that dimension's beds may explode and the client cannot tell.

On a server the mod assumes that no datapack changes the bed rule of a vanilla dimension type, of a biome or through a timeline.

### Using the bed

If you may sleep in the bed and it is in reach (vanilla's 3 blocks horizontally and 2 vertically, and your block interaction range), the mod puts your view on the bed in one frame and right-clicks it on the next tick, so the server has your new view before the click.
The mod asks the game for the crosshair target itself, so it uses the bed only when the crosshair is on the bed itself and the bed is within your block interaction range, exactly as when you right-click it yourself.
Otherwise Baritone walks you next to the bed first.
On a server the walk starts on the first tick the game lets you sleep. In singleplayer it starts a second later, because the mod first asks the integrated server about that bed.

### Walking with Baritone

Baritone is bundled in the mod's jar. For its own walks the mod sets Baritone's `allowBreak`, `allowPlace` and `allowWaterBucketFall` to false, so a walk never breaks or places a block and never pours water to break a long fall. A walk does open doors and fence gates on the way, as Baritone always does.
Your own values of those settings come back when the walk ends. The mod never starts a walk while a Baritone task of yours (a `#goto`, `#goal` or `#mine`) is running, and never cancels one.
The mod turns off Baritone's `chatControl`, so a chat message that starts with a Baritone command, such as "help me" or "stop", goes to the server as chat. Messages that start with `#` still go to Baritone.

A Baritone command of yours runs as if the mod were not there, with your own settings, so `#mine` or `#goto` start at once as you set them up. If the mod is putting you to bed at the time (walking you, or waiting out monsters at a bed), the command takes over: the mod stops for the rest of the night and says so in chat, so `#stop` stops it for good. The next night it works again.
Every command also sees your own `chatControl`, so `#set` saves only your own values to `baritone/settings.txt`, never the mod's.

### Staying up

When you get out of bed, or something wakes you, while it is still night, the mod leaves you up until the next day, also after a trip to another dimension or a reconnect to the same world. This holds only in the dimension and world where you left the bed: in another dimension where you may sleep, or on another server, the mod puts you to bed as usual.
The mod tells worlds apart by the save folder of a singleplayer world or by the server address, so two different worlds behind one address count as one world: a night the mod recorded in one can carry into the other while both are on the same day number.
Waking up in the morning works the same way until your client sees the new day, which the server sends once a second.

### When it waits or gives up

The mod waits, without turning your view, while you sneak with anything in either hand, because a right click then uses that item on the bed (placing a block on it, for example) instead of the bed.
It does nothing while a screen is open (the pause menu, your inventory, the death screen), while a singleplayer game is paused (also under the loading overlay of a resource reload) or while you are in spectator mode.

If the crosshair cannot reach the bed (a floor, a wall or a cat in the way) or the server refuses it (someone else in it, a plugin), the mod tries that bed 3 times, tells you once in chat and leaves it alone until the next night.
A bed that Baritone finds no way to, or does not reach within 30 seconds of walking (behind a door the server will not open, for example), is left alone at once, with a message too. Time with a screen open (the pause menu, for example) or with a singleplayer game paused does not count. Either way the mod then tries the next nearest bed.

Using a bed sets your respawn point there, as your own right click does, even when the server then refuses sleep. The mod picks the nearest bed within 24 blocks, which may be a bed in a village or in someone else's base, so your respawn point can move to it.
When the server refuses a bed because monsters are nearby, that bed now has your respawn point, so the mod uses no other bed in that world that night, in any dimension, also after a trip to another dimension or a reconnect. It tries that bed again every 5 seconds while you are in reach, never walks you back to it, and after 30 seconds it tells you once and stops trying it. The 30 seconds run on while a screen is open, since the world goes on behind it, but not while a singleplayer game is paused. The next day the mod uses beds as usual again.
A bed that someone breaks while you are away still counts as there: the mod cannot tell until you are near it again, so that night can go unused.
On a server whose world time never advances, the day number never changes, so the mod stops using beds in that world until you restart the game.

## Getting started

- Fabric Loader >=0.19.2 with [Fabric API](https://modrinth.com/mod/fabric-api).
- Put the mod JAR in the `mods` folder of a Fabric 1.21.11 client. Servers need nothing.

## Commands

No commands yet.

## Configuration

No configuration yet.

## Compatibility

- AutoSleep bundles the Baritone it needs, Baritone's API build (`baritone-api-fabric` 1.17.0). Baritone's standalone jar (`baritone-standalone-fabric`) replaces the bundled one and has no API, so with that jar in the `mods` folder AutoSleep turns itself off and says so in chat. A `baritone-api-fabric` 1.17.0 jar of your own works.
- It was not tested against anti-cheat plugins.

## Documentation

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) - building, the unit tests, the client GameTest steps and the recording.
- [docs/RELEASE.md](docs/RELEASE.md) - release automation.

Generated from [ModernMinecraftModTemplate](https://github.com/brainage04/ModernMinecraftModTemplate) (its last Fabric 1.21.11 version).
Bundles [Baritone](https://github.com/cabaletta/baritone) 1.17.0 for walking to the bed, unmodified. Baritone is under the GNU LGPL 3.0: see `NOTICE`, `COPYING.LESSER` and `COPYING`, which the mod's jar carries too. Its source for this version is at <https://github.com/cabaletta/baritone/tree/v1.17.0>.
