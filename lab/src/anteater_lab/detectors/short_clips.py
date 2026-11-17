"""SHORT_CLIPS: short videos recorded by mistake or beside a photo (docs/design.md, section 5).

A video shorter than SHORT_CLIP_MAX_MS gets a graded score from independent pieces of evidence,
combined as chances (noisy OR) like SCREENSHOT: its length, a camera photo taken beside it, a
failed take, a dark or blurred video and a GIF-style clip. Past the longest length it gets no
signal, whatever else it has. Faces and favourites protect it in the ranker, so they are not read
here.
"""

from bisect import bisect_left, bisect_right
from collections.abc import Sequence
from datetime import datetime, timedelta

import numpy as np

from anteater_lab.detectors.base import AnalysisContext, Photo, Role
from anteater_lab.detectors.blur import BlurDetector
from anteater_lab.detectors.exposure import ExposureDetector
from anteater_lab.detectors.takes import TakesDetector
from anteater_lab.folders import is_messaging_folder
from anteater_lab.metadata import VideoMetadata
from anteater_lab.model import ROLE_KEY, Category, MediaRef, MediaType, Signal

SHORT_CLIP_MAX_MS = 15000
"""A video this long or longer gets no signal. On the 49 video labels of #390, 23 of the 26 labeled
videos up to 10 s were deleted, and 7 of the 16 between 10 s and 30 s."""
FULL_DURATION_MS = 5000
"""The length term is at its full weight up to here, and fades to 0 at SHORT_CLIP_MAX_MS."""
DURATION_WEIGHT = 0.4
"""A clip up to FULL_DURATION_MS scores this alone, above the default threshold, so a short clip is
found with no other evidence, as the maintainer asked. The slider can go past it to ask for some."""
PHOTO_WINDOW_MS = 60000
"""A camera photo this close in time to the clip, either side, reads as the photo the clip was
meant to be."""
PHOTO_MIN_COSINE = 0.6
"""The closest frame of the clip and the photo must be this alike, when both have an embedding. The
99th percentile of the cosine between a frame and a photo that are not related is 0.62."""
PHOTO_WEIGHT = 0.5
TAKE_WEIGHT = 0.6
"""A failed take: `TAKES` found a longer take of the same moment after it."""
MISTAKE_WEIGHT = 0.8
MISTAKE_MIN_SCORE = 0.15
"""Dark, bright, flat or blurred on most frames, from `BAD_EXPOSURE` and `BLURRY` on a video, and
under this score the frames say nothing: a camera clip's median blur score is about 0.1."""
GIF_WEIGHT = 0.6
GIF_MAX_SIDE = 480
"""The short side of a GIF-style clip at most."""


class ShortClipsDetector:
    id = "short_clips"
    version = 1
    category = Category.SHORT_CLIPS
    media_types = frozenset({MediaType.VIDEO})

    def inputs_key(self, context: AnalysisContext) -> str:
        """What it reads besides its videos: the versions of TAKES, BLURRY and BAD_EXPOSURE, whose
        results on videos it takes, and the counts of the measures and camera photos."""
        versions = (TakesDetector.version, BlurDetector.version, ExposureDetector.version)
        return f"{':'.join(map(str, versions))}:{context.stored_inputs()}"

    def analyze(
        self, videos: Sequence[MediaRef], context: AnalysisContext
    ) -> dict[MediaRef, list[Signal]]:
        failed = {
            video
            for video, found in TakesDetector().analyze(videos, context).items()
            if found[0].evidence[ROLE_KEY] == Role.SHORT_TAKE
        }
        short = {}
        for video in videos:
            meta = _metadata(video, context)
            if meta is not None and 0 < (meta.duration_ms or 0) < SHORT_CLIP_MAX_MS:
                short[video] = meta
        times = [meta.captured_at for meta in short.values() if meta.captured_at is not None]
        photos = sorted(context.photos(times, PHOTO_WINDOW_MS), key=lambda p: p.captured_at)
        stamps = [photo.captured_at for photo in photos]
        result = {}
        for video, meta in short.items():
            chances = {"duration": DURATION_WEIGHT * _fade(meta.duration_ms)}
            evidence = {"duration_ms": str(meta.duration_ms)}
            if _photo_beside(video, meta, photos, stamps, context):
                chances["photo"] = PHOTO_WEIGHT
            if video in failed:
                chances["failed_take"] = TAKE_WEIGHT
            if mistake := _mistake(video, context):
                chances["mistake"] = MISTAKE_WEIGHT * mistake[1]
                evidence["mistake"] = mistake[0]
            if _is_gif(video, meta):
                chances["gif"] = GIF_WEIGHT
            miss = 1.0
            for chance in chances.values():
                miss *= 1.0 - chance
            # What each piece added, which the review's reason line reads.
            evidence |= {f"{name}_chance": f"{chance:.3f}" for name, chance in chances.items()}
            result[video] = [
                Signal(self.id, self.version, Category.SHORT_CLIPS, 1.0 - miss, evidence)
            ]
        return result


def _fade(duration_ms: int) -> float:
    """1 up to FULL_DURATION_MS, 0 at SHORT_CLIP_MAX_MS, linear in between."""
    return min(1.0, (SHORT_CLIP_MAX_MS - duration_ms) / (SHORT_CLIP_MAX_MS - FULL_DURATION_MS))


def _photo_beside(
    video: MediaRef,
    meta: VideoMetadata,
    photos: Sequence[Photo],
    stamps: Sequence[datetime],
    context: AnalysisContext,
) -> bool:
    """Recall first: without an embedding of the photo or frames of the clip, time alone decides.
    `photos` are sorted by time, `stamps` their times: the window is found by bisection."""
    if meta.captured_at is None:
        return False
    window = timedelta(milliseconds=PHOTO_WINDOW_MS)
    beside = photos[
        bisect_left(stamps, meta.captured_at - window) : bisect_right(
            stamps, meta.captured_at + window
        )
    ]
    frames = context.frames(video)
    return any(
        photo.embedding is None
        or len(frames) == 0
        or max(float(np.dot(photo.embedding, frame)) for frame in frames) >= PHOTO_MIN_COSINE
        for photo in beside
    )


def _mistake(video: MediaRef, context: AnalysisContext) -> tuple[str, float] | None:
    """What is wrong with most of the frames, and how much, from the video's own BAD_EXPOSURE and
    BLURRY signals, which read the median frame."""
    found = []
    for detector in (ExposureDetector(), BlurDetector()):
        for signal in detector.analyze(video, context):
            name = signal.evidence.get("case", "blurry")
            found.append((signal.score, name))
    best = max(found, key=lambda f: f[0], default=(0.0, ""))  # the first of equals
    return (best[1], best[0]) if best[0] >= MISTAKE_MIN_SCORE else None


def _is_gif(video: MediaRef, meta: VideoMetadata) -> bool:
    low_resolution = min(meta.width, meta.height) <= GIF_MAX_SIDE
    return not meta.has_audio and low_resolution and is_messaging_folder(video.path)


def _metadata(video: MediaRef, context: AnalysisContext) -> VideoMetadata | None:
    try:
        return context.video_metadata(video)
    except Exception:  # unreadable header: no length to judge
        return None
