"""Turns signals into candidates and a review order (docs/design.md, section 6)."""

from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import xxhash

from cribrum_lab.folders import is_excluded
from cribrum_lab.model import (
    Category,
    IndexedMedia,
    MediaRef,
    MediaType,
    Protection,
    ReviewState,
    Signal,
)

THRESHOLDS = {
    Category.EXACT_DUPLICATE: 0.5,
    Category.NEAR_DUPLICATE: 0.5,
    Category.BURST: 0.5,
    Category.BLURRY: 0.4,
    Category.BAD_EXPOSURE: 0.4,
    Category.SCREENSHOT: 0.5,
    Category.DOCUMENT: 0.2,
    Category.FORWARDED: 0.5,
    Category.STICKER: 0.5,
    Category.TAKES: 0.5,
}
"""Score a signal needs to make an image a candidate. Picked by the maintainer from the
evaluation report's sweep, for recall (docs/design.md, 10). Keep them equal to the app's."""

VIDEO_THRESHOLDS = {
    Category.BLURRY: 0.15,
    Category.BAD_EXPOSURE: 0.4,
    Category.SHORT_CLIPS: 0.3,
}
"""The threshold of a category for videos, where its video score is graded: a video is not held to
the photos' slider, which could hide it without a word (docs/design.md, 6.1 and 8). A category
not listed here has one threshold for both. Picked from the sweep of the video labels
(docs/design.md, 5). Keep them equal to the app's."""

PROTECTION_WEIGHTS = {Protection.FACES: 0.5, Protection.FAVORITE: 0.8, Protection.ALBUM: 0.6}
"""How much a protection signal at score 1 lowers an image's likelihood."""
UNIQUE_WEIGHT = 0.2
"""Lowers images in no duplicate, burst or takes group: nothing else keeps the same memory."""
GROUP_CATEGORIES = {
    Category.EXACT_DUPLICATE,
    Category.NEAR_DUPLICATE,
    Category.BURST,
    Category.TAKES,
}


@dataclass(frozen=True)
class Candidate:
    image: IndexedMedia
    reasons: tuple[Signal, ...]
    """Signals past their threshold, strongest first. Empty when the image is listed only
    for "review everything"."""
    likelihood: float
    """How likely the image is deletable, comparable across categories, after protection."""
    protection: float = 1.0
    """Factor protection signals applied to the likelihood: 1 when nothing protects it."""

    @property
    def is_candidate(self) -> bool:
        return bool(self.reasons)


class Ranker:
    def __init__(
        self,
        thresholds: Mapping[Category, float] = THRESHOLDS,
        video_thresholds: Mapping[Category, float] = VIDEO_THRESHOLDS,
    ) -> None:
        self.thresholds = thresholds
        self.video_thresholds = {**thresholds, **video_thresholds}

    def rank(
        self, images: Sequence[IndexedMedia], excluded_folders: Iterable[Path] = ()
    ) -> list[Candidate]:
        """The "review everything" order: every unreviewed image, most likely deletable first.

        Candidates always come before the other images. Protection signals lower an image
        within its part of the order but never move a candidate behind a non-candidate.
        A kept image is listed again only for a signal it did not have when the user kept
        it, such as a new duplicate group or a new role in its group. A newer detector
        version or a higher score is not new. The index counts a detector's first result after
        the keep as seen, so it is not new either. Trashed images are never listed, nor are
        those in an excluded folder.
        """
        excluded = list(excluded_folders)
        ranked = []
        for image in images:
            if image.review_state is ReviewState.TRASHED or is_excluded(image.ref.path, excluded):
                continue
            limits = self._limits(image)
            reasons = [s for s in image.signals if _passes(s, limits)]
            if image.review_state is ReviewState.KEPT:
                reasons = [s for s in reasons if s.identity not in image.seen_when_kept]
                if not reasons:
                    continue
            reasons.sort(key=lambda s: _likelihood(s, limits), reverse=True)
            protection = _protection(image.signals)
            likelihood = protection * max(
                (_likelihood(s, limits) for s in image.signals), default=0.0
            )
            ranked.append(Candidate(image, tuple(reasons), likelihood, protection))
        # Ties go by a hash of the path: the path itself holds the date in many file names, and
        # photo age is never a signal (docs/design.md, D5).
        ranked.sort(key=lambda c: (not c.is_candidate, -c.likelihood, _tie_break(c.image.ref.path)))
        return ranked

    @staticmethod
    def by_category(ranked: Sequence[Candidate]) -> dict[Category, list[Candidate]]:
        """Candidates under each category they have a reason for, highest score first, each
        score lowered by the candidate's protection. A video's score is also multiplied by its
        size, so of two equal videos the larger comes first, and an image's size plays no part
        (docs/design.md, 6.6). A list's order holds only within one media type, since a video's
        key is in bytes and an image's is not. A sticker is listed under STICKER only."""
        groups = defaultdict(list)
        for candidate in ranked:
            categories = {s.category for s in candidate.reasons}
            if Category.STICKER in categories:
                categories = {Category.STICKER}  # stickers would crowd every other category
            for category in categories:
                groups[category].append(candidate)
        for category, candidates in groups.items():
            candidates.sort(
                key=lambda c: (
                    -c.protection
                    * max(s.score for s in c.reasons if s.category == category)
                    * _size_factor(c.image.ref)
                )
            )
        return dict(groups)

    def _limits(self, image: IndexedMedia) -> Mapping[Category, float]:
        return self.video_thresholds if image.ref.media_type is MediaType.VIDEO else self.thresholds


def _passes(signal: Signal, limits: Mapping[Category, float]) -> bool:
    """Protection signals have no threshold, so they never pass."""
    threshold = limits.get(signal.category)
    return threshold is not None and signal.score >= threshold


def _likelihood(signal: Signal, limits: Mapping[Category, float]) -> float:
    """Score / threshold below the threshold, 1 + the margin over it above, 0 for
    protection signals."""
    threshold = limits.get(signal.category)
    if threshold is None:
        return 0.0
    if signal.score < threshold:
        return signal.score / threshold
    if threshold == 1.0:
        return 1.0
    return 1.0 + (signal.score - threshold) / (1.0 - threshold)


def _size_factor(ref: MediaRef) -> float:
    """The bytes a video frees: a 900 MB video is worth more of a review than a 5 MB clip. At
    least 1, so an empty video keeps its score and protection order."""
    return float(max(ref.size, 1)) if ref.media_type is MediaType.VIDEO else 1.0


def _tie_break(path: Path) -> str:
    return xxhash.xxh3_64(str(path).encode()).hexdigest()


def _protection(signals: Sequence[Signal]) -> float:
    factor = 1.0
    for signal in signals:
        if isinstance(signal.category, Protection):
            factor *= 1.0 - PROTECTION_WEIGHTS.get(signal.category, 0.0) * signal.score
    if not any(s.category in GROUP_CATEGORIES for s in signals):  # members always get one
        factor *= 1.0 - UNIQUE_WEIGHT
    return factor
