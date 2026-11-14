#!/usr/bin/env bash
# Cuts the AutoSleep showcase out of a recorded client GameTest run, writes the sidecar beside it, and measures the
# result. Run from the repository root after the recording job has fetched build/recording/.
#
#   finalize-recording.sh OUT_DIR OUT_TAG JOB_PEAK_MEMORY HOST [ASK...]
#
# OUT_DIR receives the video and its sidecar, HOST is the host the take was recorded on, and each ASK is a register id
# (A-NNNN) the take answers; all three go into the sidecar as given.
set -uo pipefail

usage="usage: finalize-recording.sh OUT_DIR OUT_TAG JOB_PEAK_MEMORY HOST [ASK...]"
out_dir=${1:?$usage}
recordings=$(realpath -m "$out_dir")
tag=${2:?$usage}
peak=${3:?$usage}
host=${4:?$usage}
shift 4
asks=()
for ask in "$@"; do
	asks+=(--ask "$ask")
done
video="$recordings/autosleep-$(date +%Y%m%d)-$tag.mp4"
mkdir -p "$recordings" || exit 1

cd build/recording || exit 1

python3 ../../scripts/trim-scenarios.py --raw raw.mp4 --log recorder-lines.txt --start ffmpeg-start \
	--audio --audio-file raw-audio.wav --out "$video" | tee "trim-$tag.txt" || exit 1
sed -n '/^{/,$p' "trim-$tag.txt" > "segments-$tag.json"

renderer=$(sed -n 's/.* - OpenGL: //p' recorder-lines.txt | head -1)
if [ -z "$renderer" ]; then
	echo "recorder-lines.txt has no OpenGL line: the take did not check its renderer, so it cannot be delivered." >&2
	exit 1
fi

python3 ../../scripts/make-sidecar.py --video "$video" --segments "segments-$tag.json" \
	--out "${video%.mp4}.json" --host "$host" --peak "$peak" --renderer "$renderer" \
	--commit "$(git -C ../.. rev-parse --short HEAD)" --audio \
	--raw "$(pwd)/raw.mp4" ${asks[@]+"${asks[@]}"} \
	--note "Cut from the recorded client GameTest run with scripts/trim-scenarios.py on the [CLIENT_GAMETEST_RECORDER] epoch-millis lines: 0.10 s after each step marker, and 0.9 s (1.6 s in the in-reach scenario) after the marker that ends the step." \
	--note "Each scenario is one setup filmed in one shot: about two seconds by day, where the mod does nothing, then nightfall on the same setup, where it acts at once." \
	--note "The scene is a saved structure, src/gametest/resources/data/autosleep_test/structure/bedroom.nbt: an oak plank floor with the red and the blue bed. It is loaded before the first scenario, and the player is placed before each step marker, so no setup is on camera." \
	--note "Sound: the game's own mix, played into a private PulseAudio null sink at unity gain and recorded from its monitor as 32-bit float (raw-audio.wav), then raised by one linear gain toward -20 LUFS, stopping where the true peak reaches -2 dBFS before the AAC encode. Nothing compresses or limits it." \
	--note "Vanilla plays no sound when a bed is used, so the in-reach scene is silent; the walks carry the footsteps on the plank floor." \
	--note "Every cut is faded 30 ms in and out, and each boundary was measured to fall in a quiet moment, so no footstep is chopped." \
	--note "The 13 bed-rule and dimension steps are not in this showcase; they are in autosleep-client-gametest-bedrule.mp4 (9 Nov 2026)." || exit 1

echo "=== audio ==="
ffmpeg -hide_banner -nostats -i "$video" -map 0:a:0 -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume"
ffmpeg -hide_banner -nostats -i "$video" -map 0:a:0 -af astats -f null - 2>&1 | grep -E "Peak level dB|RMS level dB|Number of samples"
ffmpeg -hide_banner -nostats -i "$video" -map 0:a:0 -af ebur128=peak=true -f null - 2>&1 | grep -A20 "Summary:" \
	| grep -E "I:|Peak:"

echo "=== new images per second (mpdecimate over 1 s windows) ==="
ffmpeg -hide_banner -nostats -i "$video" -vf mpdecimate,showinfo -an -f null - 2>&1 \
	| grep -c "n:" | awk '{printf "unique frames: %d in ", $1}'
ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$video"
