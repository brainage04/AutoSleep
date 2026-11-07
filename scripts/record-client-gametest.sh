#!/usr/bin/env bash
# Builds the mod, runs the unit tests, then runs the client GameTests on a headless Xvfb screen while ffmpeg records it.
# Run from the repository root (through nomad-heavy, see CLAUDE.md). Writes build/recording/raw.mp4 and
# build/recording/ffmpeg-start (when the recording began, in epoch seconds, for trimming against the game log).
set -uo pipefail

export ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1
unset WAYLAND_DISPLAY
XDG_RUNTIME_DIR=$(mktemp -d)
export XDG_RUNTIME_DIR
chmod 700 "$XDG_RUNTIME_DIR"
trap 'rm -rf "$XDG_RUNTIME_DIR"' EXIT

rm -rf build/recording build/run/clientGameTest/logs
mkdir -p build/recording
./gradlew --no-daemon build || exit $?

# shellcheck disable=SC2016 # expanded by the inner shell, under Xvfb
xvfb-run -a -s "-screen 0 1280x720x24" bash -c '
  date +%s.%N > build/recording/ffmpeg-start
  ffmpeg -loglevel error -y -f x11grab -draw_mouse 0 -video_size 1280x720 -framerate 30 -i "$DISPLAY" \
    -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p build/recording/raw.mp4 &
  ffmpeg_pid=$!
  timeout 20m ./gradlew --no-daemon runClientGameTest
  status=$?
  kill -INT "$ffmpeg_pid"
  wait "$ffmpeg_pid"
  echo "runClientGameTest exit $status"
  exit "$status"'
