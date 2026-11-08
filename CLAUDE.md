# AutoSleep: notes for agents

- Target: Minecraft 1.21.11, Fabric, client-only (servers need nothing). Decompiled source: `~/agent-work/mc-src/1.21.11/merged/` (see its README).
- Vanilla decides beds by the `BED_RULE` environment attribute (`BedBlock#useWithoutItem`, `ServerPlayer#startSleepInBed`). It is not synced to clients (`EnvironmentAttributes.java:105`), so the client level always reads the default rule; `AutoSleeper#maySleepIn` explains the singleplayer and remote routes.
- Behaviours that must survive, with the GameTest step that covers each:
  - never walks to or uses a Nether bed: `singleplayer-the_nether-bed`, `dedicated-the_nether-bed`
  - never uses a bed whose datapack `bed_rule` explodes: `singleplayer-exploding_beds-bed` (integrated-server read), `dedicated-exploding_beds-bed` (non-`minecraft` dimension types refused on servers)
  - follows a datapack dimension's own rule, not the Overworld's key: `singleplayer-allowed_beds-bed`
  - on a server, never uses a bed in a non-`minecraft` dimension type: `dedicated-allowed_beds-bed`
  - sleeps at night in the Overworld, in the nearest bed: `nightfall-walks-to-bed`, `bed-in-reach`, `nearest-bed`, `*-overworld-bed`; stays up by day and after waking: `daytime`, `nightfall-walks-to-bed`
- Tests and recording: `nomad-heavy --mem 8G --lock "$PWD/.gametest.lock" --lock /tmp/autosleep-dedicated-server.lock --checkout "$PWD" --fetch build/recording/ --fetch build/run/clientGameTest/logs/ --fetch build/libs/ -- bash -c "$(cat ~/agent-work/autosleep/record3.cmd)"` (build, unit tests, client GameTests under Xvfb with ffmpeg). Without a recording: `nomad-heavy --quick --mem 8G ... -- xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew --no-daemon runClientGameTest`.
