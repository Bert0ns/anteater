"""Detector interfaces. Every analysis technique implements one of them."""

from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Protocol

import numpy as np

from cribrum_lab import hashing
from cribrum_lab.imaging import Pixels
from cribrum_lab.metadata import ImageMetadata, VideoMetadata, read_metadata, read_video_metadata
from cribrum_lab.model import Category, FrameMeasures, MediaRef, MediaType, Signal
from cribrum_lab.screens import SCREEN_SIZES

IMAGES_ONLY = frozenset({MediaType.IMAGE})


def accepted_media_types(detector: "Detector | GroupDetector") -> frozenset[MediaType]:
    """The media types the pipeline gives `detector`: its `media_types`, images only without."""
    return getattr(detector, "media_types", IMAGES_ONLY)


class Role(StrEnum):
    BEST = "best"
    INFERIOR = "inferior"
    TAKE = "take"
    """A take of one moment: a group of takes has no best copy (docs/design.md, section 5)."""
    SHORT_TAKE = "short_take"
    """A take much shorter than the next one of the same moment, which reads as a failed take."""


@dataclass(frozen=True)
class Photo:
    """A camera photo, as a video detector that looks for a photo beside a clip sees it."""

    captured_at: datetime
    """Local time with no zone, as `VideoMetadata.captured_at` is, so the two compare as wall
    clocks."""
    embedding: np.ndarray | None
    """L2-normalized MobileCLIP embedding, None when the photo has none stored."""


@dataclass(frozen=True)
class AnalysisContext:
    """Shared, read-only information available to detectors.

    Per-image lookups compute their value from the file by default; the pipeline
    replaces them with lookups backed by the index, so each value is computed once.
    Grows as detectors need it (e.g. cached embeddings).
    """

    content_hash: Callable[[MediaRef], str] = hashing.content_hash
    perceptual_hash: Callable[[MediaRef], int] = hashing.perceptual_hash
    embedding: Callable[[MediaRef], np.ndarray] = lambda image: _embed(image)
    """L2-normalized MobileCLIP embedding."""
    metadata: Callable[[MediaRef], ImageMetadata] = lambda image: read_metadata(image.path)
    """Raises `UnreadableImage` when the header can't be decoded."""
    sharpness: Callable[[MediaRef], float] = lambda image: _peak_variance(image)
    """The BLURRY measure of the image, the Laplacian variance of its sharpest region: 0 when it
    can't be decoded."""
    video_metadata: Callable[[MediaRef], VideoMetadata] = lambda video: read_video_metadata(
        video.path
    )
    """Raises `UnreadableVideo` when the header can't be read."""
    frames: Callable[[MediaRef], Sequence[np.ndarray]] = lambda video: ()
    """The L2-normalized embeddings of the video's sampled frames, in the order of their times:
    none when it has no frames stored."""
    frame_measures: Callable[[MediaRef], Sequence[FrameMeasures]] = lambda video: ()
    """What `frame_measures.measure_frame` kept of the video's sampled frames, in the order of their
    times: none when it has no frames stored."""
    photos: Callable[[Sequence[datetime], int], Sequence[Photo]] = lambda times, window_ms: ()
    """The camera photos (a photo with a capture time in a camera folder, screenshots left out)
    taken within `window_ms` of any of the times, with their embeddings when the run has a model:
    none when it has none to give. It loads only those, not every photo."""
    stored_inputs: Callable[[], str] = lambda: ""
    """Counts of the frame measures and the camera photos a group detector that reads them sees,
    which change when they do. Cheap queries: a detector that is up to date loads nothing."""
    screen_sizes: Collection[tuple[int, int]] = SCREEN_SIZES
    """Known screen resolutions as (short side, long side)."""
    pixels: Callable[[MediaRef], Pixels] = lambda image: Pixels(image.path)
    """The image's pixels. The pipeline gives the detectors of one image the same `Pixels`, so
    they share what it loads, and drops it with the image. By default each call loads anew."""


def _peak_variance(image: MediaRef) -> float:
    from cribrum_lab.detectors import blur  # blur imports this module

    return blur.peak_variance(image)


def _embed(image: MediaRef) -> np.ndarray:
    from cribrum_lab import mobileclip  # loads ONNX Runtime only when a detector needs it

    return mobileclip.default_model().embed_images([image.path])[0]


class Detector(Protocol):
    """Analyzes one image and emits zero or more signals.

    Bump `version` whenever the output for the same input can change, so stored
    signals from older versions get recomputed.

    A detector that reads the embedding also sets `uses_embeddings = True`, so a new
    embedding makes its stored result stale. A Protocol can't make an attribute optional:
    the pipeline reads it with `getattr`, and a detector without it is false.

    A detector that decodes the image sets `decode_side` to the draft side it decodes at, read
    the same way. The pipeline runs an image's detectors from the largest side down, as the
    app does, where the sides that give one sample size share a decode.

    A detector that reads a video's frames sets `uses_frames = True`, read the same way. The
    pipeline runs it after the frames step and only on a video with measures stored, and a new
    sampling of the frames makes its stored result stale.

    A detector gets only the media types in `media_types`, read with `accepted_media_types`:
    images only when it does not set it.
    """

    id: str
    version: int
    category: Category | None
    """The category its signals are about, so the app can tell which categories it looks for."""

    def analyze(self, image: MediaRef, context: AnalysisContext) -> list[Signal]: ...


class GroupDetector(Protocol):
    """Analyzes all images at once and emits signals for groups of them (duplicates, bursts).

    Every member of a group gets a signal, so the review screen can show the whole
    group. Its evidence holds the group id under `GROUP_KEY` and the member's role
    under `ROLE_KEY`: the copy to keep is `Role.BEST` with score 0, so no threshold
    ever suggests it; the others are `Role.INFERIOR`. A group of takes has no copy to keep,
    and its members are `Role.TAKE` or `Role.SHORT_TAKE`. Images in no group are left out of
    the result.

    `version` follows the same rule as `Detector.version`, and `media_types` the one of
    `Detector.media_types`. A detector that reads more than the files it is given, such as the
    results of other detectors, may define `inputs_key(context) -> str`: the pipeline adds it to
    the detector's own fingerprint, so only that detector runs again when it changes. Per-image
    data they need (content hashes, metadata, embeddings) comes from the lookups in
    `AnalysisContext`.
    """

    id: str
    version: int
    category: Category | None
    """As in `Detector`."""

    def analyze(
        self, images: Sequence[MediaRef], context: AnalysisContext
    ) -> dict[MediaRef, list[Signal]]: ...
