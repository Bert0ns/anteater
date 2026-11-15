"""Writes the synthetic videos both metadata readers are tested on: fixtures/videos/.

Run it from lab/, with ffmpeg on the PATH: uv run python scripts/make_video_fixtures.py

Every video is ffmpeg's test pattern and a sine tone, a second long and 64 x 48, a few KB.
expected.json holds what both readers must read, written here from the parameters each file
is made with, not read back from the files. The bitrate is the file's size over its duration,
so it is computed from the file. The capture times are UTC in the files and local time in
expected.json, in the zone it names (docs/design.md, section 7).
"""

import json
import subprocess
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "fixtures/videos"
ZONE = "Europe/Rome"
PATTERN = ["-f", "lavfi", "-i", "testsrc=size=64x48:rate={rate}"]
TONE = ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate={rate}"]
H264 = ["-c:v", "libx264", "-pix_fmt", "yuv420p"]
AAC = ["-c:a", "aac", "-b:a", "8k", "-ac", "1"]


def ffmpeg(*args: str) -> None:
    command = ["ffmpeg", "-y", "-v", "error", *args]
    subprocess.run(command, check=True)


def fill(args: list[str], **values: object) -> list[str]:
    return [a.format(**values) for a in args]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    out = lambda name: str(OUT / name)  # noqa: E731
    created = ["-metadata", "creation_time=2024-05-01T10:20:30Z"]
    ffmpeg(*fill(PATTERN, rate=30), *fill(TONE, rate=8000), "-t", "1", *H264, *AAC, *created,
           out("clip.mp4"))  # fmt: skip
    # Encoded upright, then a display matrix turns it: the coded frames stay 64 x 48.
    upright = OUT / "upright.tmp.mp4"
    ffmpeg(*fill(PATTERN, rate=25), "-t", "1", *H264, str(upright))
    ffmpeg("-display_rotation", "90", "-i", str(upright), "-c", "copy",
           "-metadata", "creation_time=2024-05-01T10:20:35Z", out("rotated.mp4"))  # fmt: skip
    upright.unlink()
    ffmpeg(*fill(PATTERN, rate=30), "-t", "1", "-c:v", "libx265", "-x265-params", "log-level=error",
           "-pix_fmt", "yuv420p10le", "-color_trc", "smpte2084", "-color_primaries", "bt2020",
           "-colorspace", "bt2020nc", "-tag:v", "hvc1", out("hdr.mp4"))  # fmt: skip
    ffmpeg(*fill(PATTERN, rate=30), *fill(TONE, rate=48000), "-t", "1", "-c:v", "libvpx-vp9",
           "-b:v", "50k", "-c:a", "libopus", "-b:a", "6k", out("clip.webm"))  # fmt: skip
    ffmpeg(*fill(TONE, rate=8000), "-t", "1", *AAC, out("audio.mp4"))
    # A video's head, so the walk takes it, and no moov box after it.
    (OUT / "broken.mp4").write_bytes((OUT / "clip.mp4").read_bytes()[:32] + bytes(2048))

    def video(name, width, height, duration_ms, frame_rate, has_audio, codec, hdr, captured_at):
        size = (OUT / name).stat().st_size
        return {
            "width": width,
            "height": height,
            "duration_ms": duration_ms,
            "frame_rate": frame_rate,
            "bitrate": round(size * 8000 / duration_ms),
            "has_audio": has_audio,
            "video_codec": codec,
            "hdr": hdr,
            "captured_at": captured_at,
        }

    expected = {
        "zone": ZONE,
        "videos": {
            "clip.mp4": video(
                "clip.mp4", 64, 48, 1000, 30.0, True, "video/avc", False, "2024-05-01T12:20:30"
            ),
            "rotated.mp4": video(
                "rotated.mp4", 48, 64, 1000, 25.0, False, "video/avc", False, "2024-05-01T12:20:35"
            ),
            "hdr.mp4": video("hdr.mp4", 64, 48, 1000, 30.0, False, "video/hevc", True, None),
            # Opus adds its 8 ms of pre-skip. A Matroska date is not read (docs/design.md, 7).
            "clip.webm": video(
                "clip.webm", 64, 48, 1008, 30.0, True, "video/x-vnd.on2.vp9", False, None
            ),
        },
        "no_video_track": ["audio.mp4"],
        "unreadable": ["broken.mp4"],
    }
    (OUT / "expected.json").write_text(json.dumps(expected, indent=2) + "\n")


if __name__ == "__main__":
    main()
