"""Core types shared by every component. Mirrors docs/design.md, section 7."""

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path

GROUP_KEY = "group"
"""Evidence key holding the id of the group an image belongs to."""

ROLE_KEY = "role"
"""Evidence key holding the image's role within its group (`detectors.Role`)."""


class Category(Enum):
    EXACT_DUPLICATE = "exact_duplicate"
    NEAR_DUPLICATE = "near_duplicate"
    BURST = "burst"
    BLURRY = "blurry"
    BAD_EXPOSURE = "bad_exposure"
    SCREENSHOT = "screenshot"
    DOCUMENT = "document"
    FORWARDED = "forwarded"
    STICKER = "sticker"
    TAKES = "takes"


class Protection(Enum):
    """Hints that an image is a memory. They lower it in the review order, never remove it."""

    FACES = "faces"
    FAVORITE = "favorite"  # Android only: MediaStore's IS_FAVORITE
    ALBUM = "album"  # Android only: the image is in an album the user made


def kind(value: str) -> "Category | Protection":
    """The Category or Protection a stored value names."""
    try:
        return Category(value)
    except ValueError:
        return Protection(value)


class ReviewState(Enum):
    UNREVIEWED = "unreviewed"
    KEPT = "kept"
    TRASHED = "trashed"


class MediaType(Enum):
    """What a file holds, as the index's `media_type` column stores it."""

    IMAGE = "image"
    VIDEO = "video"


@dataclass(frozen=True)
class MediaRef:
    """Identifies an image or video file. Size and mtime detect changes between scans."""

    path: Path
    size: int
    mtime_ns: int
    media_type: MediaType = MediaType.IMAGE


@dataclass(frozen=True)
class FrameMeasures:
    """What the detectors that read frames keep of one sampled frame of a video: the frame itself
    is never stored (docs/design.md, section 5). The detectors turn the measures of a video's
    frames into one signal, so a rule can change without decoding the video again."""

    peak_variance: float
    """BLURRY's measure: the Laplacian variance of the frame's sharpest region."""
    p1: float
    p5: float
    p95: float
    p99: float
    """Percentiles of the frame's luminance, which BAD_EXPOSURE reads."""
    faces: int = 0
    face_confidence: float = 0.0
    """The most confident face's, 0 with none."""
    face_area: float = 0.0
    """The largest face's share of the frame."""


@dataclass(frozen=True)
class Signal:
    """A detector's evidence that an image may belong to a deletable category, or, with a
    `Protection` instead, that it may be a memory.

    Signals never decide on their own: the ranker combines them.
    """

    detector_id: str
    detector_version: int
    category: Category | Protection
    score: float
    evidence: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0.0 <= self.score <= 1.0:
            raise ValueError(f"score must be in [0, 1], got {self.score}")

    @property
    def identity(self) -> tuple[Category | Protection, str, str]:
        """What the signal says, whatever the detector version and score: category, group
        and role. The ranker uses it to tell a new signal from one the user already saw.

        A take's is its category alone: trashing one take changes the group id and the roles of
        the others, and a take the user kept must not come back for that (docs/design.md, 6.3).
        """
        if self.category is Category.TAKES:
            return self.category, "", ""
        return self.category, self.evidence.get(GROUP_KEY, ""), self.evidence.get(ROLE_KEY, "")


@dataclass(frozen=True)
class IndexedMedia:
    """An image as the ranker sees it."""

    ref: MediaRef
    review_state: ReviewState = ReviewState.UNREVIEWED
    signals: tuple[Signal, ...] = ()
    seen_when_kept: frozenset[tuple[Category | Protection, str, str]] = frozenset()
    """Identities of the signals the image had when the user kept it."""
