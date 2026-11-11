#!/usr/bin/env bash
# Builds the mod, runs the unit tests, then runs the client GameTests while ffmpeg records the screen and the game's
# sound. Run from the repository root, through nomad-heavy (see CLAUDE.md).
#
# Screen: the GPU display named by AUTOSLEEP_RECORDING_DISPLAY, or by the host file /etc/gametest-recording-display
# ("<display> [expected-renderer]", e.g. ":1 NVIDIA"). That display is held for the whole run by an exclusive lock, and
# the run fails unless the client really draws with the expected renderer. Without either, the game runs on a headless
# 1920x1080 Xvfb screen as before.
#
# Sound: with a reachable PulseAudio server the game plays into a private null sink and ffmpeg records that sink's
# monitor as well, so the recording has the game's sound; the desktop's default sink is never touched. The null sink and
# its monitor are at unity gain (checked: a played tone is recorded at the same level), so the game's quiet mix arrives
# at its true level and is normalised once, in the trim step, not by a blind gain here. Without one
# (AUTOSLEEP_RECORDING_AUDIO=off, or no pactl) it records video only.
#
# Writes into build/recording: raw.mp4, ffmpeg-start and ffmpeg-end (epoch seconds, for trimming against the log),
# audio-sink-inputs.txt (when the game's audio route was seen) and recorder-lines.txt, the game log's
# [CLIENT_GAMETEST_RECORDER] lines, whose epoch millis date each scenario.
set -uo pipefail

# The recording is of an X screen, whether that is the GPU display or Xvfb.
unset WAYLAND_DISPLAY

export OUT=build/recording
export SCREEN=1920x1080
export FPS=30
export AUTOSLEEP_GUI_SCALE=3
export AUTOSLEEP_APP_ID="autosleep.gametest.$$"

display="${AUTOSLEEP_RECORDING_DISPLAY:-}"
expected_renderer="NVIDIA"
if [ -z "$display" ] && [ -f "${AUTOSLEEP_RECORDING_DISPLAY_FILE:-/etc/gametest-recording-display}" ]; then
	read -r display expected_renderer < "${AUTOSLEEP_RECORDING_DISPLAY_FILE:-/etc/gametest-recording-display}"
fi

audio_module=""
lock_fd=""
stop_audio_sink() {
	if [ -n "$audio_module" ]; then
		pactl unload-module "$audio_module" >/dev/null 2>&1
	fi
}
trap stop_audio_sink EXIT

rm -rf "$OUT" build/run/clientGameTest/logs
mkdir -p "$OUT"

gradle_args=(-PautosleepGuiScale="$AUTOSLEEP_GUI_SCALE")
if [ -n "$display" ]; then
	# One client on the shared display at a time: a second take would record the first one's window.
	display_number="${display#:}"
	exec 9>"/tmp/.gtr-recording-display-${display_number}.lock"
	if ! flock -n 9; then
		echo "The X display ${display} is already recording: not starting a second take." >&2
		exit 1
	fi
	lock_fd=9
	export DISPLAY="$display"
	gradle_args+=(-PautosleepRequireNvidia=true)
	echo "Screen: GPU display ${display}, renderer must be ${expected_renderer}"

	if command -v glxinfo >/dev/null 2>&1; then
		renderer=$(glxinfo -B | sed -n 's/^[[:space:]]*OpenGL renderer string:[[:space:]]*//p' | head -1)
		if [ -z "$renderer" ] || [ "${renderer#*$expected_renderer}" = "$renderer" ]; then
			echo "The display ${display} does not draw with ${expected_renderer} (renderer: ${renderer:-none})." >&2
			exit 1
		fi
		echo "Pre-flight renderer check: $renderer"
	fi
else
	echo "Screen: headless Xvfb at ${SCREEN}"
fi

if [ "${AUTOSLEEP_RECORDING_AUDIO:-auto}" != off ] && pactl info >/dev/null 2>&1; then
	AUDIO_SINK="autosleep_recording_$(date +%Y%m%d-%H%M%S)"
	audio_module=$(pactl load-module module-null-sink "sink_name=$AUDIO_SINK" rate=48000 channels=2 \
		channel_map=front-left,front-right "sink_properties=device.description=$AUDIO_SINK" 2>/dev/null)
	if [ -n "$audio_module" ]; then
		export AUDIO_SINK
		export AUTOSLEEP_SOUND_DEVICE="$AUDIO_SINK"
		export ALSOFT_DRIVERS=pulse
		export ALSOFT_PULSE_DEFAULT="$AUDIO_SINK"
		export PULSE_SINK="$AUDIO_SINK"
		export PULSE_PROP="application.id=$AUTOSLEEP_APP_ID"
		echo "Game sound: private PulseAudio sink $AUDIO_SINK, recorded from $AUDIO_SINK.monitor"
	else
		AUDIO_SINK=""
		echo "Could not create a private PulseAudio sink: recording video only." >&2
	fi
else
	echo "No reachable PulseAudio server: recording video only." >&2
fi

if [ -z "${AUDIO_SINK:-}" ]; then
	export ALSOFT_DRIVERS=null
	unset AUTOSLEEP_SOUND_DEVICE
fi

if [ -n "${AUTOSLEEP_SOUND_DEVICE:-}" ]; then
	gradle_args+=(-PautosleepSoundDevice="$AUTOSLEEP_SOUND_DEVICE")
fi

./gradlew --no-daemon "${gradle_args[@]}" build || exit $?

if [ -n "$lock_fd" ]; then
	scripts/capture-client-gametest.sh
else
	xvfb-run -a -s "-screen 0 ${SCREEN}x24" scripts/capture-client-gametest.sh
fi
status=$?

log=build/run/clientGameTest/logs/latest.log
if [ -f "$log" ]; then
	grep -F '[CLIENT_GAMETEST_RECORDER]' "$log" > "$OUT/recorder-lines.txt"
fi

if [ -f "$OUT/raw.mp4" ]; then
	ffprobe -v error -show_entries stream=codec_type,width,height,r_frame_rate -show_entries format=duration \
		-of default=noprint_wrappers=1 "$OUT/raw.mp4"
	if [ -n "${AUDIO_SINK:-}" ]; then
		ffmpeg -hide_banner -nostats -i "$OUT/raw.mp4" -map 0:a:0 -af astats -f null - 2>&1 \
			| grep -E 'Peak level dB|RMS level dB'
	fi
fi

exit "$status"
