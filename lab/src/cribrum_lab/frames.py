"""Frames sampled from a video, for the detectors images have (docs/design.md, section 5).

The app samples the same times with MediaMetadataRetriever (frames/Frames.kt).
"""

from collections.abc import Iterator, Sequence
from fractions import Fraction
from functools import cache
from pathlib import Path

import av
import numpy as np
from av.video.reformatter import Colorspace, ColorTrc, Interpolation
from PIL import Image

from cribrum_lab.metadata import HDR_TRANSFERS, UnreadableVideo

FRAMES = 5
FRAMES_VERSION = 2
"""Bump it when the frames a video gives change, such as their times or their decoding: the frames
stored at another version are sampled again."""
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


def read_frames(path: Path, times_ms: Sequence[int]) -> Iterator[tuple[int, Image.Image]]:
    """The frame closest to each time, as OPTION_CLOSEST gives it on the phone, and the last frame
    for a time past the end, each with its time. An HDR video takes the keyframe closest to each
    time instead, as OPTION_CLOSEST_SYNC does, since the phone gives no exact HDR frame: times that
    land on one keyframe give it once, under the first of them. Each frame comes as displayed:
    upright, and an HDR frame tone mapped."""
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        targets = {t: Fraction(t, 1000) for t in times_ms}
        if stream.codec_context.color_trc in HDR_TRANSFERS:
            frames, keyframes = _frame_times(container, stream, max(targets.values(), default=0))
            if not keyframes:
                raise UnreadableVideo(path)
            # As AOSP's MP4 extractor seeks for OPTION_CLOSEST_SYNC: to the frame nearest the time,
            # then to the keyframe nearest that frame, a tie going to the later one each time.
            targets = {t: _nearest(keyframes, _nearest(frames, x)) for t, x in targets.items()}
            # Only keyframes leave the decoder, so a seek that lands a keyframe early, as FFmpeg's
            # MP4 seek by decode time can, decodes one frame before the one wanted, not a GOP.
            stream.codec_context.skip_frame = "NONKEY"
        taken = set()
        for time_ms, target in targets.items():
            if target in taken:
                continue
            taken.add(target)
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
            yield time_ms, _displayed(best)


def _frame_times(container, stream, last: Fraction) -> tuple[list[Fraction], list[Fraction]]:
    """The times of the stream's frames and of its keyframes, read from the demuxer without
    decoding, up to the second keyframe at or past `last`, so the frames around it are all in."""
    frames, keyframes = [], []
    for packet in container.demux(stream):
        if packet.pts is None:
            continue
        time = packet.pts * stream.time_base
        if packet.is_keyframe:
            if keyframes and keyframes[-1] >= last:
                break
            keyframes.append(time)
        frames.append(time)
    return sorted(frames), sorted(keyframes)


def _nearest(times: list[Fraction], time: Fraction) -> Fraction:
    """The time nearest `time`, a tie going to the later one."""
    return min(times, key=lambda t: (abs(t - time), -t))


def brightness(image: Image.Image) -> tuple[list[float], float]:
    """A frame's mean red, green and blue, and the share of its pixels with a luma of 250 or more
    (Pillow's L), which a tone mapping that clips piles up there. The device test computes the
    same on the phone's frames (docs/design.md, section 5)."""
    rgb = np.asarray(image, dtype=np.float64).reshape(-1, 3)
    luma = rgb @ np.array([0.299, 0.587, 0.114])
    return rgb.mean(axis=0).tolist(), float((luma >= 250).mean())


def _displayed(frame: av.VideoFrame) -> Image.Image:
    # The rounding the equations give: swscale's default is 2 or 3 levels off them.
    options = {"interpolation": Interpolation.BILINEAR | Interpolation.ACCURATE_RND}
    if frame.colorspace == 2:  # AVCOL_SPC_UNSPECIFIED
        # What Android takes for a frame that does not say its matrix (ColorUtils'
        # setDefaultCodecColorAspectsIfNeeded): BT.601 up to PAL's size, BT.709 above.
        width, height = frame.width, frame.height
        sd = (width <= 720 and height <= 576) or (height <= 720 and width <= 576)
        options["src_colorspace"] = Colorspace.ITU601 if sd else Colorspace.ITU709
    if frame.color_trc in HDR_TRANSFERS:
        # The signal as R'G'B', with no transfer or gamut change, which the tone mapping does.
        rgb = _tone_mapped(frame.to_ndarray(format="rgb48le", **options), frame.color_trc)
    else:
        # Exact on SDR. With a 16-bit output it garbles the chroma, so SDR only.
        options["interpolation"] |= Interpolation.FULL_CHR_H_INT
        rgb = frame.to_ndarray(format="rgb24", **options)
    # The display matrix turns counterclockwise, as np.rot90 does.
    return Image.fromarray(np.ascontiguousarray(np.rot90(rgb, frame.rotation // 90)))


HDR_GAIN = 0.9
"""The share of SDR white that HDR's peak becomes: HLG's signal at 1, and 1000 cd/m2 of PQ."""
BT2020_TO_BT709 = np.array(
    [[1.6605, -0.5876, -0.0728], [-0.1246, 1.1329, -0.0083], [-0.0182, -0.1006, 1.1187]]
)
"""Linear BT.2020 RGB to linear BT.709 RGB (ITU-R BT.2087)."""


def _tone_mapped(signal: np.ndarray, transfer: int) -> np.ndarray:
    """HDR R'G'B' (16 bits, BT.2020) as sRGB in 8 bits, as the phone's decoder tone maps its own 4K
    HLG clips (docs/design.md, section 5): light from the transfer's equations of BT.2100, with no
    OOTF, times HDR_GAIN, to BT.709 primaries, clipped and sRGB encoded."""
    linear = _light(transfer)[signal] @ (HDR_GAIN * BT2020_TO_BT709.T).astype(np.float32)
    # Linear light in 16 bits is finer than a level of sRGB down to black.
    return _srgb()[np.round(np.clip(linear, 0, 1) * 65535).astype(np.uint16)]


@cache
def _light(transfer: int) -> np.ndarray:
    """Light for each 16-bit signal value: HLG's scene light from 0 to 1 (its inverse OETF), or
    PQ's cd/m2 over HLG's nominal peak of 1000 cd/m2 (its EOTF)."""
    e = np.arange(65536) / 65535
    if transfer == ColorTrc.ARIB_STD_B67:
        a, b, c = 0.17883277, 0.28466892, 0.55991073
        light = np.where(e <= 0.5, e * e / 3, (np.exp((np.maximum(e, 0.5) - c) / a) + b) / 12)
    else:
        m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
        p = e ** (1 / m2)
        light = 10 * (np.maximum(p - c1, 0) / (c2 - c3 * p)) ** (1 / m1)
    return light.astype(np.float32)


@cache
def _srgb() -> np.ndarray:
    """The 8-bit sRGB value of each 16-bit linear value."""
    linear = np.arange(65536) / 65535
    srgb = np.where(linear <= 0.0031308, 12.92 * linear, 1.055 * linear ** (1 / 2.4) - 0.055)
    return np.round(srgb * 255).astype(np.uint8)
