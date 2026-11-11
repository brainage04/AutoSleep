# AutoSleep: notes for agents

- Target: Minecraft 1.21.11, Fabric, client-only (servers need nothing). Decompiled source: `~/agent-work/mc-src/1.21.11/merged/` (see its README).
- Vanilla decides beds by the `BED_RULE` environment attribute (`BedBlock#useWithoutItem`, `ServerPlayer#startSleepInBed`). It is not synced to clients (`EnvironmentAttributes.java:105`), so the client level always reads the default rule; the `AutoSleeper` class comment explains the singleplayer and remote routes.
- Behaviours that must survive, with the GameTest step that covers each:
  - uses a bed only when the player's crosshair is on it and from vanilla reach: the mod turns to the bed at 20 degrees a tick and uses the game's own crosshair pick, never a synthesised hit result: `daytime`, `nightfall-walks-to-bed`, `bed-in-reach`, `nearest-bed` (their `UseBlockCallback` handler checks the crosshair, the reach and both interaction ranges on the client)
  - never walks to or uses a Nether or End bed: `*-the_nether-bed`, `*-the_end-bed`
  - never uses a bed whose rule explodes, even when it allows sleep (the `explodes()` check on the integrated server's answer): `singleplayer-exploding_beds-bed`
  - follows a datapack dimension's own rule through the integrated server, not the Overworld's key: `singleplayer-allowed_beds-bed`
  - uses the integrated server's answer only at the next attempt, for the same bed (`singleplayer-exploding_biome-bed`) in the same level (`singleplayer-other_dimension-bed`); never after an attempt that asked nothing (`singleplayer-stale_answer-bed`) or after sleeping (`singleplayer-overworld-bed`)
  - on a server, never uses a bed in a non-`minecraft` dimension type: `dedicated-exploding_beds-bed`, `dedicated-allowed_beds-bed`
  - sleeps at night in the Overworld, in the nearest bed, and stays up after the night is skipped: `nightfall-walks-to-bed`, `bed-in-reach`, `nearest-bed`, `*-overworld-bed`; stays up by day: `daytime`
- Tests and recording, from the repository root: `nomad-heavy --mem 8G --lock "$PWD/.gametest.lock" --lock /tmp/autosleep-dedicated-server.lock --checkout "$PWD" --fetch build/recording/ --fetch build/run/clientGameTest/logs/ --fetch build/libs/ -- scripts/record-client-gametest.sh` (measured on thomas-server, 11 Nov 2026: 3.9-5G peak, about 4-5 minutes). Without a recording: `nomad-heavy --mem 8G --lock "$PWD/.gametest.lock" --checkout "$PWD" -- xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew --no-daemon runClientGameTest` (no `--quick`: client runs are not quick jobs).
