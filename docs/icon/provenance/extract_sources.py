#!/usr/bin/env python3
"""Copy the vanilla assets the icon candidates use out of the Minecraft client jars into sources/,
and record each jar's and each file's sha256 in sources/sources.json."""
import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
PRISM = Path.home() / ".local/share/PrismLauncher/libraries/com/mojang/minecraft"

JARS = {
    "1.21.11": (
        PRISM / "1.21.11/minecraft-1.21.11-client.jar",
        {
            "assets/minecraft/textures/entity/bed/red.png": "red_bed_entity.png",
            "assets/minecraft/textures/entity/player/wide/steve.png": "steve.png",
            "assets/minecraft/textures/font/ascii.png": "ascii.png",
            "assets/minecraft/font/include/default.json": "font_include_default.json",
            "assets/minecraft/textures/environment/celestial/moon/waxing_crescent.png": "moon_waxing_crescent.png",
        },
    ),
    # 1.8.9 is the newest client jar here with a flat bed item sprite: the 1.12.2 jar has none, and 1.21.11 draws
    # the bed item from the 3D bed model (assets/minecraft/items/red_bed.json, "type": "minecraft:special").
    "1.8.9": (
        PRISM / "1.8.9/minecraft-1.8.9-client.jar",
        {"assets/minecraft/textures/items/bed.png": "bed_item_1.8.9.png"},
    ),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    SOURCES.mkdir(exist_ok=True)
    record = {}
    for version, (jar, members) in JARS.items():
        files = {}
        with zipfile.ZipFile(jar) as zf:
            for member, name in members.items():
                data = zf.read(member)
                (SOURCES / name).write_bytes(data)
                files[name] = {"member": member, "sha256": sha256(data)}
        record[version] = {"jar": str(jar), "jar_sha256": sha256(jar.read_bytes()), "files": files}
    (SOURCES / "sources.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()
