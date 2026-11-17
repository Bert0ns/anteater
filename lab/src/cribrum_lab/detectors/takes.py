"""TAKES: camera videos of one moment, filmed one after the other (docs/design.md, section 5).

A video joins the takes of the one before it when it starts at most TAKES_MAX_GAP_MS after that one
ends, by `captured_instant`, as long as the two look alike when both have frames. A group of takes
has no best copy: which take to keep is not obvious, so every take is a candidate and the review
preselects none.
"""

from collections.abc import Sequence
from pathlib import Path

import numpy as np
import xxhash

from cribrum_lab.detectors.base import AnalysisContext, Role
from cribrum_lab.folders import is_camera_folder
from cribrum_lab.metadata import VideoMetadata
from cribrum_lab.model import GROUP_KEY, ROLE_KEY, Category, MediaRef, MediaType, Signal

TAKES_MAX_GAP_MS = 300000
"""Five minutes, from the end of a take to the start of the next. On the maintainer's phone it
groups 95 camera videos in 34 groups, against 53 in 18 at one minute (#172)."""
TAKES_MIN_COSINE = 0.50
"""Two takes whose closest frames are below this are different subjects. Recall first: the halves
of one clip, the closest thing to two takes of a moment, were all above it on the labeled videos."""
SHORT_TAKE_RATIO = 0.5
"""A take at most this share of the next take's length reads as a failed start."""

Take = tuple[int, int | None, MediaRef]
"""Start as epoch milliseconds, length in milliseconds when known, video."""


class TakesDetector:
    id = "takes"
    version = 1
    category = Category.TAKES
    media_types = frozenset({MediaType.VIDEO})

    def analyze(
        self, videos: Sequence[MediaRef], context: AnalysisContext
    ) -> dict[MediaRef, list[Signal]]:
        takes = []
        for video in videos:
            if not is_camera_folder(video.path) or _is_screen_recording(video.path):
                continue  # a sent or saved copy is no take, whatever its time, nor a recording
            meta = _metadata(video, context)
            if meta is not None and meta.captured_instant is not None:
                takes.append((meta.captured_instant, meta.duration_ms, video))
        takes.sort(key=lambda take: (take[0], str(take[2].path)))
        result = {}
        for moment in _moments(takes, context):
            result.update(self._signals(moment))
        return result

    def _signals(self, moment: list[Take]) -> dict[MediaRef, list[Signal]]:
        group_id = xxhash.xxh3_64(str(moment[0][2].path).encode()).hexdigest()
        result = {}
        for i, (_, length, video) in enumerate(moment):
            following = moment[i + 1][1] if i + 1 < len(moment) else None
            short = None not in (length, following) and length <= following * SHORT_TAKE_RATIO
            evidence = {
                GROUP_KEY: group_id,
                ROLE_KEY: Role.SHORT_TAKE if short else Role.TAKE,
                "takes": str(len(moment)),
            }
            result[video] = [Signal(self.id, self.version, Category.TAKES, 1.0, evidence)]
        return result


def _moments(takes: list[Take], context: AnalysisContext) -> list[list[Take]]:
    moments, current = [], []
    for take in takes:
        if current and _follows(current[-1], take, context):
            current.append(take)
        else:
            moments.append(current)
            current = [take]
    moments.append(current)
    return [moment for moment in moments if len(moment) > 1]


def _follows(previous: Take, take: Take, context: AnalysisContext) -> bool:
    end = previous[0] + (previous[1] or 0)
    return take[0] - end <= TAKES_MAX_GAP_MS and _alike(previous[2], take[2], context)


def _alike(a: MediaRef, b: MediaRef, context: AnalysisContext) -> bool:
    """Recall first: without the frames of both, time alone decides."""
    frames_a, frames_b = context.frames(a), context.frames(b)
    if len(frames_a) == 0 or len(frames_b) == 0:
        return True
    closest = max(float(np.dot(x, y)) for x in frames_a for y in frames_b)
    return closest >= TAKES_MIN_COSINE


def _is_screen_recording(path: Path) -> bool:
    """A Galaxy saves screen recordings to DCIM/Screen recordings."""
    return any(part.lower() == "screen recordings" for part in path.parent.parts)


def _metadata(video: MediaRef, context: AnalysisContext) -> VideoMetadata | None:
    try:
        return context.video_metadata(video)
    except Exception:  # unreadable header: no capture time to chain on
        return None
