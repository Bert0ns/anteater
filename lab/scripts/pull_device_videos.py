"""Copies the videos of the connected Android device into lab/data/device/ (docs/design.md, 10).

The twin of pull-device-images.sh: it keeps the device's folder layout and modification times,
and only reads the device (find, stat and tar). Camera videos may not fit the dev machine, so
`--sample N` copies N videos from each folder given, the same ones on every run: a larger N
keeps the videos of a smaller one. A video already copied with the same size is skipped.

Usage, from lab/:
  uv run python scripts/pull_device_videos.py [--sample N] [--dry-run] [--adb ADB] [FOLDER...]
FOLDER is relative to /sdcard; without any, the folders of pull-device-images.sh are used.
From WSL, `--adb adb.exe` uses the Windows adb, which holds the phone.
"""

import argparse
import hashlib
import shlex
import subprocess
import sys
from pathlib import Path

DEST = Path(__file__).resolve().parents[1] / "data" / "device"
FOLDERS = [
    "DCIM",
    "Pictures",
    "Download",
    "Documents",
    "Screenshots",
    "WhatsApp/Media",
    "Android/media/com.whatsapp/WhatsApp/Media",
    "Telegram",
    "Android/media/org.telegram.messenger/Telegram",
    "Android/media/com.instagram.android",
]
EXTENSIONS = ("mp4", "mov", "3gp", "3g2", "m4v", "mkv", "webm")
"""The containers the walk takes as videos (docs/design.md, section 4), by extension: the phone
side has no head sniffing."""
BATCH = 20
"""Videos per tar stream, which keeps the device's command line short."""


def listing_command(folders: list[str]) -> str:
    """Size and path of every video under the folders. Like the walk, it leaves out files in the
    system trash or still being written, and gallery thumbnail caches."""
    names = " -o ".join(f"-iname '*.{extension}'" for extension in EXTENSIONS)
    quoted = " ".join(shlex.quote(folder) for folder in folders)
    return (
        f"cd /sdcard && find {quoted} -type d -name .thumbnails -prune -o -type f "
        f"\\( {names} \\) ! -name '.trashed-*' ! -name '.pending-*' "
        "-exec stat -c '%s %n' {} + 2>/dev/null; true"  # a missing folder is not an error
    )


def parse_listing(text: str) -> list[tuple[int, str]]:
    files = []
    for line in text.splitlines():
        if line.strip():
            size, path = line.split(" ", 1)
            files.append((int(size), path))
    return files


def pick(
    files: list[tuple[int, str]], folders: list[str], sample: int | None
) -> list[tuple[int, str]]:
    """`sample` videos from each folder, or all of them. Ordered by a hash of the path, so the
    same videos come first on every run, whatever the order of the listing."""
    picked = []
    for folder in folders:
        inside = [f for f in files if f[1].startswith(folder.rstrip("/") + "/")]
        inside.sort(key=lambda f: hashlib.sha1(f[1].encode()).hexdigest())
        picked += inside if sample is None else inside[:sample]
    return picked


def to_copy(picked: list[tuple[int, str]], dest: Path) -> list[tuple[int, str]]:
    return [(size, path) for size, path in picked if _size(dest / path) != size]


def copy_command(paths: list[str]) -> str:
    # exec-out mixes stderr into the stream, so the device side must stay silent.
    return f"cd /sdcard && tar -cf - -- {' '.join(shlex.quote(p) for p in paths)} 2>/dev/null"


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("folders", nargs="*", default=FOLDERS, metavar="FOLDER")
    parser.add_argument("--sample", type=int, help="videos taken from each folder")
    parser.add_argument("--dry-run", action="store_true", help="say what would be copied")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args(argv)

    listing = subprocess.run(
        [args.adb, "exec-out", listing_command(args.folders)],
        capture_output=True,
        check=True,
        text=True,
    ).stdout
    files = parse_listing(listing)
    picked = pick(files, args.folders, args.sample)
    for folder in args.folders:
        found = [f for f in files if f[1].startswith(folder.rstrip("/") + "/")]
        chosen = [f for f in picked if f[1].startswith(folder.rstrip("/") + "/")]
        print(f"{folder}: {len(found)} videos, {_gb(found)}; picked {len(chosen)}, {_gb(chosen)}")
    missing = to_copy(picked, DEST)
    print(f"to copy: {len(missing)} videos, {_gb(missing)}, into {DEST}")
    if args.dry_run:
        return

    DEST.mkdir(parents=True, exist_ok=True)
    for start in range(0, len(missing), BATCH):
        batch = [path for _, path in missing[start : start + BATCH]]
        adb = subprocess.Popen([args.adb, "exec-out", copy_command(batch)], stdout=subprocess.PIPE)
        subprocess.run(["tar", "-xf", "-", "-C", str(DEST)], stdin=adb.stdout, check=True)
        if adb.wait():
            sys.exit(f"adb failed on the batch starting with {batch[0]}")
        print(f"copied {min(start + BATCH, len(missing))}/{len(missing)}", flush=True)


def _size(path: Path) -> int | None:
    return path.stat().st_size if path.is_file() else None


def _gb(files: list[tuple[int, str]]) -> str:
    return f"{sum(size for size, _ in files) / 1e9:.2f} GB"


if __name__ == "__main__":
    main()
