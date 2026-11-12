#!/usr/bin/env bash
# Records the client GameTests from the X display this script is given: the GPU display when the parent script picked
# one, otherwise the Xvfb screen it runs the game on. The game's sound is recorded too when AUDIO_SINK names a
# PulseAudio sink whose monitor carries it. Called by scripts/record-client-gametest.sh from the repository root; not for
# direct use.
set -uo pipefail

: "${OUT:?OUT must be the recording directory}"
: "${SCREEN:=1920x1080}"
: "${FPS:=30}"
mkdir -p "$OUT"

# The video and the game's sound go to separate files, the sound as the sink's own 32-bit float samples. Vanilla plays
# footsteps at 0.15 volume, 20 to 40 dB below full scale, where ffmpeg's default 16-bit pulse capture keeps only a few
# bits of them and the cutter's normalising gain then raises that grain with them; float keeps their full detail, and a
# mix that ever went over full scale would show as samples above 1.0 instead of being cut off. The pulse input asks the
# server for the format named by its codec (libavdevice pulse_audio_dec.c), so -c:a goes before -i.
capture=(-y -loglevel error -f x11grab -draw_mouse 0 -video_size "$SCREEN" -framerate "$FPS" -i "$DISPLAY.0")
if [ -n "${AUDIO_SINK:-}" ]; then
	capture+=(-f pulse -c:a pcm_f32le -thread_queue_size 1024 -i "$AUDIO_SINK.monitor"
		-map 0:v:0 -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$OUT/raw.mp4"
		-map 1:a:0 -c:a pcm_f32le "$OUT/raw-audio.wav")
else
	capture+=(-map 0:v:0 -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$OUT/raw.mp4")
fi

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
if [ "${AUTOSLEEP_REQUIRE_NVIDIA:-}" = true ]; then
	gradle_args+=(-PautosleepRequireNvidia=true)
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
