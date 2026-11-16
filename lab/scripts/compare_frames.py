"""Compares the frames the phone took from a video with the lab's (docs/design.md, section 5).

VideoFramesDeviceTest logs a line for each frame of the video it times, under the tag
cribrum-frames: "frame <time_ms> mean <r> <g> <b> clipped <share> embedding <base64>". Pull the same
video into lab/data/, dump the log, and run from lab/:

    uv run python scripts/compare_frames.py data/<video>.mp4 data/frames-log.txt

It prints, for each time, the cosine between the two embeddings and the mean colour and clipped
share on each side. It needs the MobileCLIP files in lab/data/models/.
"""

import argparse
import base64
import re
from pathlib import Path

import numpy as np

from cribrum_lab import frames, mobileclip

LINE = re.compile(
    r"frame (\d+) mean ([\d.]+) ([\d.]+) ([\d.]+) clipped ([\d.]+) embedding ([A-Za-z0-9+/=]+)"
)


def phone_frames(log: str) -> dict[int, tuple[list[float], float, np.ndarray]]:
    """The phone's frames by time: mean RGB, clipped share and embedding."""
    found = {}
    for time_ms, r, g, b, clipped, embedding in LINE.findall(log):
        vector = np.frombuffer(base64.b64decode(embedding), dtype="<f4")
        found[int(time_ms)] = ([float(r), float(g), float(b)], float(clipped), vector)
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("video", type=Path)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    phone = phone_frames(args.log.read_text(errors="replace"))
    if not phone:
        raise SystemExit(f"{args.log}: no frame lines of VideoFramesDeviceTest")
    taken = dict(frames.read_frames(args.video, sorted(phone)))
    if missing := sorted(phone.keys() - taken.keys()):
        print(f"the phone kept frames at {missing} ms, which the lab's keyframes do not")
    vectors = mobileclip.MobileClip().embed_pixels(
        np.stack([mobileclip.model_input(image) for image in taken.values()])
    )
    print("time ms  cosine   phone mean RGB      lab mean RGB        phone clip  lab clip")
    for (time_ms, image), vector in zip(taken.items(), vectors, strict=True):
        mean, clipped, phone_vector = phone[time_ms]
        lab_mean, lab_clipped = frames.brightness(image)
        print(
            f"{time_ms:7d}  {float(vector @ phone_vector):.4f}  "
            f"{' '.join(f'{c:5.1f}' for c in mean)}   {' '.join(f'{c:5.1f}' for c in lab_mean)}"
            f"   {clipped:9.4f}  {lab_clipped:8.4f}"
        )


if __name__ == "__main__":
    main()
