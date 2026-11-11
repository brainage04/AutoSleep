#!/usr/bin/env bash
# Runs the client GameTests inside the X display xvfb-run gives this script and records that display with ffmpeg, with
# the game's sound when AUDIO_SINK names a PulseAudio sink whose monitor carries it. Called by
# scripts/record-client-gametest.sh from the repository root; not for direct use.
set -uo pipefail

: "${OUT:?OUT must be the recording directory}"
: "${SCREEN:=1920x1080}"
: "${FPS:=30}"
mkdir -p "$OUT"

capture=(-y -loglevel error -f x11grab -draw_mouse 0 -video_size "$SCREEN" -framerate "$FPS" -i "$DISPLAY.0")
if [ -n "${AUDIO_SINK:-}" ]; then
	capture+=(-f pulse -thread_queue_size 1024 -i "$AUDIO_SINK.monitor" -map 0:v:0 -map 1:a:0 -c:a aac -b:a 192k)
else
	capture+=(-map 0:v:0)
fi
capture+=(-c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$OUT/raw.mp4")

date +%s.%N > "$OUT/ffmpeg-start"
ffmpeg "${capture[@]}" &
ffmpeg_pid=$!

watcher=""
if [ -n "${AUDIO_SINK:-}" ]; then
	# Bounded: the game opens its sound stream while it loads, long before the tests start.
	(
		for _ in $(seq 1 120); do
			if pactl list sink-inputs 2>/dev/null | grep -qF "application.id = \"$AUTOSLEEP_APP_ID\""; then
				pactl list sink-inputs > "$OUT/audio-sink-inputs.txt" 2>/dev/null
				exit 0
			fi
			sleep 1
		done
	) &
	watcher=$!
fi

gradle_args=(-PautosleepGuiScale="${AUTOSLEEP_GUI_SCALE:-3}")
if [ -n "${AUTOSLEEP_SOUND_DEVICE:-}" ]; then
	gradle_args+=(-PautosleepSoundDevice="$AUTOSLEEP_SOUND_DEVICE")
fi

timeout 20m ./gradlew --no-daemon runClientGameTest "${gradle_args[@]}"
status=$?
echo "runClientGameTest exit $status"

if [ -n "$watcher" ]; then
	kill "$watcher" 2>/dev/null
	wait "$watcher" 2>/dev/null
fi

date +%s.%N > "$OUT/ffmpeg-end"
kill -INT "$ffmpeg_pid"
wait "$ffmpeg_pid"
exit "$status"
