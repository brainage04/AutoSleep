#!/usr/bin/env bash
# Cuts the AutoSleep showcase out of a recorded client GameTest run, writes the sidecar beside it, and measures the
# result. Run from the repository root after the recording job has fetched build/recording/.
#
#   finalize-recording.sh OUT_TAG JOB_PEAK_MEMORY
set -uo pipefail

recordings=${AUTOSLEEP_RECORDINGS:-/home/thomas/agent-work/recordings-20261030}
tag=${1:?usage: finalize-recording.sh OUT_TAG JOB_PEAK_MEMORY}
peak=${2:?usage: finalize-recording.sh OUT_TAG JOB_PEAK_MEMORY}
video="$recordings/autosleep-$(date +%Y%m%d)-$tag.mp4"

cd build/recording || exit 1

python3 ../../scripts/trim-scenarios.py --raw raw.mp4 --log recorder-lines.txt --start ffmpeg-start \
	--out "$video" --audio | tee "trim-$tag.txt" || exit 1
sed -n '/^{/,$p' "trim-$tag.txt" > "segments-$tag.json"

python3 ../../scripts/make-sidecar.py --video "$video" --segments "segments-$tag.json" \
	--out "${video%.mp4}.json" --host thomas-server2 --peak "$peak" \
	--commit "$(git -C ../.. rev-parse --short HEAD)" --audio \
	--raw "$(pwd)/raw.mp4" --ask A-0375 \
	--note "Cut from the recorded client GameTest run with scripts/trim-scenarios.py on the [CLIENT_GAMETEST_RECORDER] epoch-millis lines: 0.10 s after each step marker, and 0.30-0.55 s after the marker that ends the step." \
	--note "Sound: the game's own mix, played into a private PulseAudio null sink and recorded from its monitor. The route is at unity (sink-input 0.00 dB; a test tone is recorded at the level it was played), so the quiet mix is the game's, not a capture fault." \
	--note "Every cut is faded 30 ms in and out, and each boundary was measured to fall in a quiet moment, so no footstep is chopped." \
	--note "The 13 bed-rule and dimension steps are not in this showcase; they are in autosleep-client-gametest-bedrule.mp4 (9 Nov 2026)." || exit 1

echo "=== audio ==="
ffmpeg -hide_banner -nostats -i "$video" -map 0:a:0 -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume"
ffmpeg -hide_banner -nostats -i "$video" -map 0:a:0 -af astats -f null - 2>&1 | grep -E "Peak level dB|RMS level dB|Number of samples"

echo "=== new images per second (mpdecimate over 1 s windows) ==="
ffmpeg -hide_banner -nostats -i "$video" -vf mpdecimate,showinfo -an -f null - 2>&1 \
	| grep -c "n:" | awk '{printf "unique frames: %d in ", $1}'
ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$video"
