#!/usr/bin/env python3
"""Writes the sidecar JSON next to a finished AutoSleep recording.

Takes the segments the trim script printed, turns their durations into frame ranges of the joined video, and fills in the
hash, duration, frame count and resolution from the file itself.

    make-sidecar.py --video OUT.mp4 --segments segments.json --out OUT.json --host thomas-server2 \
        --peak "8.1G" --commit abc1234 --raw RAW.mp4 --note "..." [--audio]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess


def probe(path: str) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,width,height,r_frame_rate,nb_frames",
         "-show_entries", "format=duration", "-of", "json", path],
        check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True)
    parser.add_argument("--segments", required=True, help="the JSON the trim script printed")
    parser.add_argument("--out", required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--peak", required=True)
    parser.add_argument("--commit", action="append", default=[])
    parser.add_argument("--raw", default="")
    parser.add_argument("--ask", action="append", default=[])
    parser.add_argument("--audio", action="store_true")
    parser.add_argument("--note", action="append", default=[])
    parser.add_argument("--renderer", required=True, help="the OpenGL vendor and renderer the take's log reported")
    args = parser.parse_args()

    with open(args.segments, encoding="utf-8") as handle:
        segments = json.load(handle)["segments"]

    info = probe(args.video)
    video = next(stream for stream in info["streams"] if stream["codec_type"] == "video")
    has_audio = any(stream["codec_type"] == "audio" for stream in info["streams"])
    duration = float(info["format"]["duration"])
    numerator, denominator = (int(part) for part in video["r_frame_rate"].split("/"))
    fps = numerator / denominator
    frames = int(video.get("nb_frames") or round(duration * fps))

    scenarios = []
    frame_cursor = 0
    for segment in segments:
        length = segment["end"] - segment["start"]
        count = round(length * fps)
        scenarios.append({
            "id": segment["id"],
            "caption": segment.get("caption", ""),
            "start": f"{frame_cursor / fps:.2f}s",
            "end": f"{(frame_cursor + count) / fps:.2f}s",
            "startFrame": frame_cursor,
            "endFrameExclusive": frame_cursor + count,
        })
        frame_cursor += count

    sha = hashlib.sha256(open(args.video, "rb").read()).hexdigest()
    sidecar = {
        "video": args.video,
        "sha256": sha,
        "durationSeconds": round(duration, 2),
        "frames": frames,
        "fps": round(fps, 2),
        "resolution": f"{video['width']}x{video['height']}",
        "audio": ("the game's own mix, recorded as 32-bit float from a private PulseAudio null sink at unity gain and "
                  "raised by one linear gain to about -20 LUFS, with a 30 ms fade at every cut"
                  if has_audio else "none"),
        "ownerAsks": args.ask,
        "repository": "AutoSleep, branch rec2 (GitHub brainage04/AutoSleep), /home/thomas/01_TM/Coding/Minecraft/AutoSleep",
        "commits": args.commit,
        "recordedOn": args.host,
        "jobPeakMemory": args.peak,
        "rawRecording": args.raw,
        "guiScale": 3,
        "renderer": args.renderer,
        "scenarios": scenarios,
        "notes": args.note,
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(sidecar, handle, indent=1)
        handle.write("\n")
    print(json.dumps(sidecar, indent=1))


if __name__ == "__main__":
    main()
