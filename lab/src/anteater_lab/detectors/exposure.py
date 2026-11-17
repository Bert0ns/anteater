"""BAD_EXPOSURE: pocket shots, black frames, blown-out and flat frames (docs/design.md, section 5).

Each case looks at the opposite end of the histogram too, so a night shot with a
lit subject is not "dark" and a page of text is not "bright". All thresholds are
starting points, to be tuned for recall in the lab.
"""

from collections.abc import Sequence
from statistics import median

import numpy as np

from anteater_lab.detectors.base import AnalysisContext
from anteater_lab.model import Category, FrameMeasures, MediaRef, MediaType, Signal

MAX_SIDE = 256
DARK_LEVEL = 30
"""Dark scores 1 when 95% of the pixels are at or below this luminance."""
BRIGHT_LEVEL = 235
"""Bright scores 1 when 95% of the pixels are at or above this luminance."""
RAMP = 64
"""Luminance distance over which each score fades from 1 to 0."""
PERCENTILES = (1, 5, 95, 99)
"""The luminance percentiles the cases read, which a video's frames keep (`FrameMeasures`)."""


class ExposureDetector:
    id = "exposure"
    version = 1
    category = Category.BAD_EXPOSURE
    decode_side = MAX_SIDE
    media_types = frozenset({MediaType.IMAGE, MediaType.VIDEO})
    uses_frames = True
    """Of a video."""

    def analyze(self, image: MediaRef, context: AnalysisContext) -> list[Signal]:
        if image.media_type is MediaType.VIDEO:
            return self._video(context.frame_measures(image))
        gray = context.pixels(image).gray(MAX_SIDE)
        p1, p5, p95, p99 = (float(v) for v in np.percentile(gray, PERCENTILES))
        return [self._signal(cases(p1, p5, p95, p99), {})]

    def _video(self, frames: Sequence[FrameMeasures]) -> list[Signal]:
        """Each case scores as the median frame's, so a video is dark when most of its frames are,
        and the strongest case names it (docs/design.md, section 5)."""
        if not frames:
            return []
        per_frame = [cases(f.p1, f.p5, f.p95, f.p99) for f in frames]
        typical = {name: median(frame[name] for frame in per_frame) for name in per_frame[0]}
        return [self._signal(typical, {"frames": str(len(frames))})]

    def _signal(self, scores: dict[str, float], extra: dict[str, str]) -> Signal:
        # The strongest case, whether or not it is bad enough: the ranker decides that.
        case = max(scores, key=scores.get)
        evidence = {"case": case} | {
            f"{name}_score": f"{value:.3f}" for name, value in scores.items()
        }
        return Signal(self.id, self.version, Category.BAD_EXPOSURE, scores[case], evidence | extra)


def cases(p1: float, p5: float, p95: float, p99: float) -> dict[str, float]:
    return {
        "dark": _fade(p95 - DARK_LEVEL),
        "bright": _fade(BRIGHT_LEVEL - p5),
        "flat": _fade(p99 - p1),
    }


def _fade(distance: float) -> float:
    """1 at distance 0 or less, 0 at RAMP or more, linear in between."""
    return min(1.0, max(0.0, 1.0 - distance / RAMP))
