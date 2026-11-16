"""Frames sampled from a video, for the detectors images have (docs/design.md, section 5).

The app samples the same times with MediaMetadataRetriever (frames/Frames.kt).
"""

from collections.abc import Iterator, Sequence
from fractions import Fraction
from pathlib import Path

import av
import numpy as np
from av.video.reformatter import ColorPrimaries, ColorTrc
from PIL import Image

from cribrum_lab.metadata import HDR_TRANSFERS, UnreadableVideo

FRAMES = 5
MARGIN_MS = 500
"""Kept off each end, where a clip fades in, starts black or catches the hand on the button."""


def frame_times(duration_ms: int | None) -> list[int]:
    """The times to sample, in milliseconds: the middle of each of FRAMES equal slices of the
    video, MARGIN_MS off each end, a quarter of the clip off each end when it is shorter than two
    margins. A clip too short for distinct times gets fewer, and one with no duration its start."""
    duration = duration_ms or 0
    margin = min(MARGIN_MS, duration // 4)
    span = duration - 2 * margin
    return sorted({margin + span * (2 * i + 1) // (2 * FRAMES) for i in range(FRAMES)})


def read_frames(path: Path, times_ms: Sequence[int]) -> Iterator[Image.Image]:
    """The frame closest to each time, as OPTION_CLOSEST gives it on the phone, and the last frame
    for a time past the end. Each comes as displayed: upright, and an HDR frame tone mapped."""
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        for time_ms in times_ms:
            target = Fraction(time_ms, 1000)
            # To the keyframe at or before the time, then decode up to it.
            container.seek(int(target / stream.time_base), stream=stream)
            best = None
            for frame in container.decode(stream):
                if frame.pts is None:
                    continue
                if best is None or abs(frame.pts * stream.time_base - target) < abs(
                    best.pts * stream.time_base - target
                ):
                    best = frame  # a tie goes to the earlier frame
                if frame.pts * stream.time_base >= target:
                    break
            if best is None:
                raise UnreadableVideo(path)
            yield _displayed(best)


def _displayed(frame: av.VideoFrame) -> Image.Image:
    # FFmpeg's own tone mapping, which maps HDR reference white (BT.2408, 203 cd/m2) to SDR white
    # and clips above it. An SDR frame keeps its values, as the phone's bitmap does.
    tone_map = (
        {"dst_color_trc": ColorTrc.IEC61966_2_1, "dst_color_primaries": ColorPrimaries.BT709}
        if frame.color_trc in HDR_TRANSFERS
        else {}
    )
    rgb = frame.to_ndarray(format="rgb24", **tone_map)
    # The display matrix turns counterclockwise, as np.rot90 does.
    return Image.fromarray(np.ascontiguousarray(np.rot90(rgb, frame.rotation // 90)))
