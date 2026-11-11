#!/usr/bin/env bash
# Builds the mod, runs the unit tests, then runs the client GameTests on a headless 1920x1080 Xvfb screen while ffmpeg
# records them. With a reachable PulseAudio server the game plays into a private null sink and ffmpeg records that
# sink's monitor as well, so the recording has the game's sound; the desktop's default sink is never touched. Without
# one (AUTOSLEEP_RECORDING_AUDIO=off, or no pactl) it records video only. Run from the repository root (through
# nomad-heavy, see CLAUDE.md).
#
# Writes into build/recording: raw.mp4, ffmpeg-start and ffmpeg-end (epoch seconds, for trimming against the log),
# audio-sink-inputs.txt (when the game's audio route was seen) and recorder-lines.txt, the game log's
# [CLIENT_GAMETEST_RECORDER] lines, whose epoch millis date each scenario.
set -uo pipefail

export LIBGL_ALWAYS_SOFTWARE=1
unset WAYLAND_DISPLAY

export OUT=build/recording
export SCREEN=1920x1080
export FPS=30
export AUTOSLEEP_GUI_SCALE=3
export AUTOSLEEP_APP_ID="autosleep.gametest.$$"

rm -rf "$OUT" build/run/clientGameTest/logs
mkdir -p "$OUT"

audio_module=""
stop_audio_sink() {
	if [ -n "$audio_module" ]; then
		pactl unload-module "$audio_module" >/dev/null 2>&1
	fi
}
trap stop_audio_sink EXIT

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

gradle_args=(-PautosleepGuiScale="$AUTOSLEEP_GUI_SCALE")
if [ -n "${AUTOSLEEP_SOUND_DEVICE:-}" ]; then
	gradle_args+=(-PautosleepSoundDevice="$AUTOSLEEP_SOUND_DEVICE")
fi

./gradlew --no-daemon "${gradle_args[@]}" build || exit $?

xvfb-run -a -s "-screen 0 ${SCREEN}x24" scripts/capture-client-gametest.sh
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
