#!/usr/bin/env python3
"""Cuts the recorded AutoSleep client GameTest run into its captioned showcase clips.

Every step and every observation the test makes prints one `[CLIENT_GAMETEST_RECORDER] <epoch millis> <text>` line into
the game log, and the capture began at the epoch in `ffmpeg-start`. A line therefore maps to a video timestamp, so the
clips are cut on those markers instead of by eye.

Audio. The game's mix arrives at its true level, because the capture route is at unity: the game's stream sits in the
null sink at 0.00 dB, and a tone played into that sink is recorded back at the same level. Nothing in the route is 40 dB
down: the mix is quiet because these scenes only ever play footsteps, whose samples peak around -21 dBFS and which
vanilla plays at 0.15 volume (Entity#playStepSound). Gain therefore belongs here, where the final track can be measured,
and it is a loudness normalisation, not a guessed constant: a two-pass loudnorm to -16 LUFS with a -1.5 dBFS
true-peak ceiling. Every cut gets a 30 ms fade in and out, and each boundary is checked to fall in a quiet moment, so no
footstep is ever chopped.

    trim-scenarios.py --raw RAW.mp4 --log recorder-lines.txt --start ffmpeg-start --out OUT.mp4 [--audio] [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

MARKER = re.compile(r"\[CLIENT_GAMETEST_RECORDER\] (\d{13}) (.*)$")
# The caption of a step is drawn in the frame after the step marker, and the last frames of a cut should still show the
# result the caption promises.
LEAD = 0.10
FADE_SECONDS = 0.03
# These clips carry nothing but footsteps: no music, no voice, and vanilla plays no sound at all when a bed is used, so
# the mix is sparse and about 40 dB below a normal programme. Normalising such a track to a streaming target of -16 LUFS
# would push every footstep onto the true-peak ceiling, which is the loud, limited sound the owner called fried. -20
# LUFS with a -3 dBFS ceiling keeps the footsteps well under full scale and leaves the transients their shape.
LOUDNESS_TARGET = -20.0
TRUE_PEAK_CEILING = -3.0
LOUDNESS_RANGE = 7.0
QUIET_AT_CUT_DB = -45.0


class Segment:
    """One clip: the step marker it starts on, the markers it may end on, and how long to hold after that."""

    def __init__(self, step_id: str, end_markers: tuple[str, ...], tail: float, caption: str | None = None) -> None:
        self.step_id = step_id
        self.end_markers = end_markers
        self.tail = tail
        self.caption = caption


# Scenario 1 holds the day negative and the nightfall positive in one shot, so it is never cut in the middle of the
# point it makes and the viewer sees the walk reach the bed.
# A clip ends on the first of its end markers that the log has after its step marker. The alternatives are there because
# the in-reach step reports the use and the red-bed sleeps report the bed, and either line proves the same moment.
SEGMENTS = [
    Segment("1. daytime", ("Asleep in the red bed",), 0.55,
            "Day, the red bed 4.5 blocks away: nothing happens. Night falls: it walks at once and sleeps."),
    Segment("2. bed-in-reach-day", ("Nothing happened for 20 ticks in reach",), 0.05,
            "Day, in reach of the red bed and looking at it: nothing happens."),
    Segment("3. bed-in-reach", ("The bed was used", "Asleep in the red bed"), 0.55,
            "Night, beside the red bed and facing away: it looks at the bed and uses it in one tick."),
    Segment("4. nearest-bed", ("Asleep in the blue bed",), 0.55,
            "Night, two beds in view, the blue one nearer: it walks to the blue bed."),
    Segment("5. nearest-bed-red", ("Asleep in the red bed",), 0.55,
            "Night, the same two beds, the red one nearer: it walks to the red bed."),
]


def read_markers(log_path: str, start: float) -> list[tuple[float, str]]:
    markers = []
    with open(log_path, encoding="utf-8", errors="replace") as lines:
        for line in lines:
            match = MARKER.search(line)
            if match:
                markers.append((int(match.group(1)) / 1000.0 - start, match.group(2).strip()))
    if not markers:
        sys.exit(f"no [CLIENT_GAMETEST_RECORDER] lines in {log_path}")
    return markers


def index_after(markers: list[tuple[float, str]], texts: tuple[str, ...], after: int) -> int:
    for index in range(after, len(markers)):
        if any(text in markers[index][1] for text in texts):
            return index
    sys.exit(f"no marker containing any of {texts!r} after marker {after}")


def peak_db(raw: str, start: float, length: float) -> float:
    """The loudest sample of a slice of the raw capture, in dBFS."""
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-ss", f"{max(start, 0.0):.3f}", "-t", f"{length:.3f}",
         "-i", raw, "-map", "0:a:0", "-af", "astats=measure_perchannel=none", "-f", "null", "-"],
        capture_output=True, text=True)
    match = re.search(r"Peak level dB:\s*(-?[\d.]+|-inf)", result.stderr)
    if not match:
        sys.exit(f"could not measure the audio at {start:.3f}s:\n{result.stderr[-2000:]}")
    return float(match.group(1)) if match.group(1) != "-inf" else float("-inf")


def build_graph(cuts: list[tuple[str, float, float, str, str]], audio_input: int | None) -> tuple[str, str]:
    """Per-cut filters (trim before the cut, reset timestamps, fade in and out) and the concat inputs."""
    parts = []
    joined = []
    for index, (_, start, end, _, _) in enumerate(cuts):
        length = end - start
        parts.append(f"[0:v]trim=start={start:.3f}:end={end:.3f},setpts=PTS-STARTPTS[v{index}]")
        if audio_input is not None:
            parts.append(
                f"[{audio_input}:a]atrim=start={start:.3f}:end={end:.3f},asetpts=PTS-STARTPTS,"
                f"afade=t=in:st=0:d={FADE_SECONDS},"
                f"afade=t=out:st={max(length - FADE_SECONDS, 0.0):.3f}:d={FADE_SECONDS}[a{index}]")
            joined.append(f"[v{index}][a{index}]")
        else:
            joined.append(f"[v{index}]")
    return ";".join(parts), "".join(joined)


def measure_loudness(command: list[str], graph: str, joined: str, count: int) -> dict:
    """Pass one: the joined track's loudness, measured before any gain is applied."""
    filters = (f"{graph};{joined}concat=n={count}:v=1:a=1[v][plain];"
               f"[plain]loudnorm=I={LOUDNESS_TARGET}:TP={TRUE_PEAK_CEILING}:print_format=json[measured]")
    result = subprocess.run(
        command[:1] + ["-hide_banner", "-nostats"] + command[1:] + ["-filter_complex", filters,
         "-map", "[v]", "-map", "[measured]", "-f", "null", "-"],
        capture_output=True, text=True)
    match = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", result.stderr, re.DOTALL)
    if not match:
        sys.exit(f"could not measure the loudness of the cut audio:\n{result.stderr[-4000:]}")
    return json.loads(match.group(0))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--start", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--audio", action="store_true", help="cut the game sound along with the video")
    parser.add_argument("--audio-file", default="raw-audio.flac",
                        help="the lossless game sound recorded beside the raw video")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    with open(args.start, encoding="utf-8") as handle:
        capture_start = float(handle.read().strip())
    markers = read_markers(args.log, capture_start)

    cuts = []
    begin_index = 0
    for segment in SEGMENTS:
        start_index = index_after(markers, (segment.step_id,), begin_index)
        end_index = index_after(markers, segment.end_markers, start_index)
        start = markers[start_index][0] + LEAD
        end = markers[end_index][0] + segment.tail
        cuts.append((segment.step_id, start, end, markers[start_index][1], markers[end_index][1]))
        begin_index = end_index

    for index in range(1, len(cuts)):
        if cuts[index][1] < cuts[index - 1][2]:
            sys.exit(f"{cuts[index][0]} starts at {cuts[index][1]:.2f}s, before {cuts[index - 1][0]} ends at"
                     f" {cuts[index - 1][2]:.2f}s: the clips would overlap, so one of the markers or tails is wrong")

    total = 0.0
    for step_id, start, end, begin_text, end_text in cuts:
        print(f"{step_id}: {start:8.2f}s .. {end:8.2f}s ({end - start:5.2f}s)  {begin_text!r} .. {end_text!r}")
        total += end - start
    print(f"total {total:.2f}s")

    if args.audio:
        for step_id, start, end, _, _ in cuts:
            before = peak_db(args.audio_file, start - 0.12, 0.12)
            after = peak_db(args.audio_file, end + 0.02, 0.12)
            print(f"{step_id}: audio either side of the cut {before:.1f} dBFS / {after:.1f} dBFS")
            if before > QUIET_AT_CUT_DB or after > QUIET_AT_CUT_DB:
                sys.exit(f"{step_id} would cut through a sound; move its boundaries to a quiet moment")

    if args.dry_run:
        return

    command = ["ffmpeg", "-y", "-loglevel", "error", "-i", args.raw]
    audio_input = None
    if args.audio:
        command += ["-i", args.audio_file]
        audio_input = 1
    graph, joined = build_graph(cuts, audio_input)
    if args.audio:
        measured = measure_loudness(command, graph, joined, len(cuts))
        input_i = float(measured["input_i"])
        if input_i < -70.0:
            sys.exit("the cut audio is silent: nothing to normalise")
        print(f"measured loudness {input_i} LUFS, true peak {measured['input_tp']} dBFS")
        normalise = (f"loudnorm=I={LOUDNESS_TARGET}:TP={TRUE_PEAK_CEILING}:LRA={LOUDNESS_RANGE}:linear=true"
                     f":measured_I={measured['input_i']}:measured_TP={measured['input_tp']}"
                     f":measured_LRA={measured['input_lra']}:measured_thresh={measured['input_thresh']}"
                     f":offset={measured['target_offset']}")
        filters = f"{graph};{joined}concat=n={len(cuts)}:v=1:a=1[v][plain];[plain]{normalise}[aout]"
        command += ["-filter_complex", filters, "-map", "[v]", "-map", "[aout]", "-c:a", "aac", "-b:a", "192k"]
    else:
        filters = f"{graph};{joined}concat=n={len(cuts)}:v=1:a=0[v]"
        command += ["-filter_complex", filters, "-map", "[v]"]
    command += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", args.out]
    subprocess.run(command, check=True)

    segments = []
    for segment, (step_id, start, end, begin_text, _) in zip(SEGMENTS, cuts):
        caption = segment.caption or " | ".join(begin_text.split("|")[1:]).strip()
        segments.append({"id": step_id, "caption": caption, "start": round(start, 3), "end": round(end, 3)})
    print(json.dumps({"video": args.out, "segments": segments}, indent=1))


if __name__ == "__main__":
    main()
