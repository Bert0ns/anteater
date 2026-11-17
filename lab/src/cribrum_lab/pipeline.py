"""Analysis pipeline: scan → metadata → detectors that read no embedding → embeddings →
detectors that read one → group detectors, then the video steps when a run asks for them: the
video metadata, then the frames.

Every step stores its results as it goes and skips what the index already holds for the
same detector version, so an interrupted run resumes where it stopped and a second run on
unchanged data does no detector work.
"""

import contextlib
import multiprocessing
from collections.abc import Callable, Sequence
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field
from datetime import tzinfo
from pathlib import Path
from typing import Protocol

import av
import numpy as np
import xxhash

from cribrum_lab import hashing, mobileclip
from cribrum_lab.detectors import AnalysisContext, Detector, GroupDetector, blur
from cribrum_lab.detectors.base import accepted_media_types
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.detectors.burst import BurstDetector
from cribrum_lab.detectors.document import DocumentDetector
from cribrum_lab.detectors.exact_duplicate import ExactDuplicateDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.faces import FaceDetector
from cribrum_lab.detectors.forwarded import ForwardedDetector
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.detectors.screenshot import ScreenshotDetector
from cribrum_lab.detectors.sticker import StickerDetector
from cribrum_lab.folders import is_sticker_folder
from cribrum_lab.frames import FRAMES_VERSION, frame_times, read_frames
from cribrum_lab.imaging import Pixels
from cribrum_lab.index import ImageIndex
from cribrum_lab.metadata import (
    ImageMetadata,
    NoVideoTrack,
    UnreadableImage,
    UnreadableVideo,
    read_metadata,
    read_video_metadata,
)
from cribrum_lab.model import Category, MediaRef, MediaType, Signal
from cribrum_lab.sources import FilesystemMediaSource

DETECTORS: list[Detector] = [
    BlurDetector(),
    ExposureDetector(),
    ScreenshotDetector(),
    DocumentDetector(),
    ForwardedDetector(),
    FaceDetector(),
    StickerDetector(),
]
GROUP_DETECTORS: list[GroupDetector] = [
    ExactDuplicateDetector(),
    NearDuplicateDetector(),
    BurstDetector(),
]

SKIPPED_ON_STICKERS = frozenset({"blur", "exposure", "faces"})
"""The detectors that read no embedding and still skip a sticker. A sticker is listed under
STICKER only and its signal there already gives the highest likelihood, so blur and exposure
cannot change its place, and faces were not shown to order stickers (docs/design.md, 5)."""


def runs_on_stickers(detector: Detector) -> bool:
    """Whether a sticker runs `detector`: not one that reads the embedding, which a sticker
    never gets, nor one in SKIPPED_ON_STICKERS."""
    return (
        not getattr(detector, "uses_embeddings", False) and detector.id not in SKIPPED_ON_STICKERS
    )


Progress = Callable[[str, int, int], None]
"""Called with a step name, the items done and the items in total."""

VIDEO_GROUPS_STEP = "video_groups"
"""The step of the group detectors on videos, one for them all as in the app."""

EMBEDDING_CHUNK = 256
"""Images decoded per round before the model runs: bounds the memory held by decoded pixels."""
EMBEDDING_BATCH = 32
RECORD_BATCH = 300
"""Images the walk records in one transaction."""


class MissingEmbedding(LookupError):
    """The run has no embedding for this image: no embedder, or it could not decode it."""


class Embedder(Protocol):
    """Embeds images preprocessed by `mobileclip.preprocess`, as `MobileClip` does."""

    id: str
    version: int

    def embed_pixels(self, pixels: np.ndarray) -> np.ndarray: ...


@dataclass
class Stats:
    images: int = 0
    new_or_changed: int = 0
    forgotten: int = 0
    unreadable: int = 0
    detector_runs: int = 0
    """Per-image analyses, perceptual hashes and embeddings computed, plus group detector runs."""
    skipped: int = 0
    """Per-image analyses left for a run with embeddings."""
    failures: list[str] = field(default_factory=list)
    """Analyses that raised. They are not stored, so the next run retries them."""
    videos: int = 0
    """The videos the walk found."""
    no_video_track: int = 0
    """Files the video step found to have no video track, which left the index. They are still on
    disk, so they are not among the `forgotten`."""
    unreadable_frames: int = 0
    """Videos the frames step marked because their frames do not read."""
    unknown_brands: dict[str, int] = field(default_factory=dict)
    """The ftyp and EBML files the walk sniffed and turned away, by the brand it did not know."""


def run(
    root: Path,
    index: ImageIndex,
    detectors: Sequence[Detector] = DETECTORS,
    group_detectors: Sequence[GroupDetector] = GROUP_DETECTORS,
    embedder: Embedder | None = None,
    jobs: int = 1,
    progress: Progress | None = None,
    record_batch: int = RECORD_BATCH,
    images: bool = True,
    videos: bool = False,
    zone: tzinfo | None = None,
) -> Stats:
    """Analyzes every image under `root`.

    Images under it that are gone leave the index, unless the scan could not list the root in
    full: then it cannot tell a deleted file from a folder it could not read.

    Every run walks and records images and videos. `images` and `videos` pick the steps after
    the walk, as the app's scans do (docs/design.md, section 4): the image steps alone by
    default, the video steps alone for "Scan videos", and both, videos after images, for a night
    pass after "Scan videos". A video's capture time goes to the local time of `zone`, the
    machine's own when None.

    Without an `embedder` no embeddings are computed. Per-image detectors that need one
    are skipped, without storing anything, so a later run with an embedder runs them.
    """
    progress = progress or (lambda step, done, total: None)
    root = root.resolve()
    stats = Stats()

    found = set()
    source = FilesystemMediaSource(root, index.images, not_images=index)
    batch: list[MediaRef] = []
    for image in source.scan():
        found.add(image.path)
        batch.append(image)
        if len(batch) == record_batch:
            stats.new_or_changed += index.record_all(batch)
            batch.clear()
        progress("scan", len(found), 0)
    stats.new_or_changed += index.record_all(batch)
    walked = source.walked_roots
    stats.unknown_brands = dict(source.unknown_brands)
    gone = [
        p
        for p in (i.path for i in index.images())
        if p not in found and any(p.is_relative_to(r) for r in walked)
    ]
    index.forget(gone)
    stats.forgotten = len(gone)

    if images:
        _image_steps(found, index, detectors, group_detectors, embedder, jobs, stats, progress)
    if videos:
        _video_steps(
            found, index, detectors, group_detectors, embedder, jobs, stats, progress, zone
        )
    media = [i for i in index.images() if i.path in found]
    stats.images = sum(i.media_type is MediaType.IMAGE for i in media)
    stats.videos = len(media) - stats.images
    stats.unreadable = len(set(index.unreadable()) & found)
    return stats


def _image_steps(
    found: set[Path],
    index: ImageIndex,
    detectors: Sequence[Detector],
    group_detectors: Sequence[GroupDetector],
    embedder: Embedder | None,
    jobs: int,
    stats: Stats,
    progress: Progress,
) -> None:
    """Every step after the walk for the images found, the group detectors last."""
    # The metadata read from an image header, and every step after it but the group detectors,
    # take images only: a video is recorded and nothing more (docs/design.md, section 4).
    pending = _stickers_last(
        [i for i in index.without_metadata() if i.media_type is MediaType.IMAGE]
    )
    for done, image in enumerate(pending, 1):
        try:
            index.set_metadata(image.path, read_metadata(image.path))
        except UnreadableImage:
            index.set_unreadable(image.path)
        progress("metadata", done, len(pending))
    unreadable = set(index.unreadable())
    media = [i for i in index.images() if i.path in found]
    readable = [i for i in media if i.media_type is MediaType.IMAGE and i.path not in unreadable]
    # The home screen lists candidates as soon as these are stored; the embeddings come after.
    fast = [d for d in detectors if not getattr(d, "uses_embeddings", False)]
    _analyze_each(
        _stickers_last(readable),
        fast,
        {},
        "detectors",
        index,
        jobs,
        stats,
        progress,
        before_embeddings=True,
    )
    # A sticker is listed under STICKER only (docs/design.md, 8), so neither MobileCLIP nor
    # text coverage runs on it.
    embeddable = [i for i in readable if not is_sticker_folder(i.path)]
    # A sticker scanned before those rules keeps results they no longer produce: drop them so
    # a library gives the same result either way.
    index.forget_analyses(
        [i.path for i in readable if is_sticker_folder(i.path)],
        [d.id for d in detectors if not runs_on_stickers(d)],
    )
    embeddings = {}
    if embedder is not None:
        _embed(embedder, index, detectors, embeddable, jobs, stats, progress)
        kept = {i.path for i in embeddable}
        embeddings = {
            path: vector
            for path, vector in index.embeddings(embedder.id, embedder.version).items()
            if path in kept
        }
    reading = [d for d in detectors if d not in fast]
    _analyze_each(
        embeddable,
        reading,
        embeddings,
        "embedded_detectors",
        index,
        jobs,
        stats,
        progress,
        before_embeddings=False,
    )

    _group_steps(
        MediaType.IMAGE,
        found,
        index,
        group_detectors,
        detectors,
        embedder,
        embeddings,
        stats,
        progress,
    )


def _group_steps(
    media_type: MediaType,
    found: set[Path],
    index: ImageIndex,
    group_detectors: Sequence[GroupDetector],
    detectors: Sequence[Detector],
    embedder: Embedder | None,
    embeddings: dict[Path, np.ndarray],
    stats: Stats,
    progress: Progress,
) -> None:
    """Runs the group detectors that accept `media_type` on the files of that type, so a group
    never mixes images and videos, and an image run and a video run each keep their own
    fingerprint: neither runs the other's detectors again."""
    inputs = [i for i in index.images() if i.path in found and i.media_type is media_type]
    # The exact detector skips an image whose content hash fails: the run stores those paths
    # with its fingerprint.
    hash_failed: set[str] = set()
    cached_content_hash = _cached_content_hash(index)

    def content_hash(image: MediaRef) -> str:
        try:
            return cached_content_hash(image)
        except OSError:
            hash_failed.add(str(image.path))
            raise

    def hashes(image: MediaRef) -> bool:
        try:
            cached_content_hash(image)
        except OSError:
            return False
        return True

    blur_detector = next(
        (d for d in detectors if getattr(d, "category", None) == Category.BLURRY), None
    )
    context = AnalysisContext(
        content_hash=content_hash,
        perceptual_hash=_stored_perceptual_hash(index),
        metadata=_metadata(index),
        embedding=_stored_embedding(embeddings),
        sharpness=_stored_sharpness(index, blur_detector),
    )
    # Embeddings change what group detectors can see: a run with them is a different run.
    model = f"{embedder.id}:{embedder.version}:{len(embeddings)}" if embedder else ""
    # A hash that failed on an earlier run is a result a group detector lacked.
    hashed = index.hashed_paths()
    # Burst reads blur's stored measure: a new blur version is new input, though no file changed.
    blur_input = f"{blur_detector.id}:{blur_detector.version}" if blur_detector else ""
    for detector in group_detectors:
        if media_type not in accepted_media_types(detector):
            continue
        # The images keep the run they had, which a video run must not overwrite.
        images = media_type is MediaType.IMAGE
        run_id = detector.id if images else f"{detector.id}:{media_type.value}"
        step = detector.id if images else VIDEO_GROUPS_STEP
        fingerprint = _fingerprint(
            inputs, f"{model}:{sum(i.path in hashed for i in inputs)}:{blur_input}"
        )
        # Hashing again only what failed in the last run groups a file that has become
        # readable, without a run on every scan.
        failed = set(index.group_hash_failed(run_id, detector.version))
        if index.group_fingerprint(run_id, detector.version) == fingerprint and not any(
            hashes(i) for i in inputs if str(i.path) in failed
        ):
            continue
        progress(step, 0, 1)
        hash_failed.clear()
        signals = detector.analyze(inputs, context)
        index.set_signals_of_many(
            detector.id, detector.version, {i.path: signals.get(i, []) for i in inputs}
        )
        index.set_group_fingerprint(run_id, detector.version, fingerprint, sorted(hash_failed))
        stats.detector_runs += 1
        progress(step, 1, 1)


def _video_steps(
    found: set[Path],
    index: ImageIndex,
    detectors: Sequence[Detector],
    group_detectors: Sequence[GroupDetector],
    embedder: Embedder | None,
    jobs: int,
    stats: Stats,
    progress: Progress,
    zone: tzinfo | None,
) -> None:
    """The video steps, which only a run asked for videos takes: the metadata, the per-video
    detectors, which read no frame, then the frames and the group detectors that accept videos,
    which may read them. The detectors go before the frames, the longest step, so a run stopped
    there still has their signals."""
    pending = [i for i in index.without_metadata() if i.media_type is MediaType.VIDEO]
    for done, video in enumerate(pending, 1):
        try:
            index.set_video_metadata(video.path, read_video_metadata(video.path, zone))
        except NoVideoTrack:
            # Neither an image nor a video: the walk does not sniff it again until it changes.
            index.forget([video.path])
            index.add_not_images([video])
            stats.no_video_track += 1
        except UnreadableVideo:
            index.set_unreadable(video.path)
        progress("video_metadata", done, len(pending))
    _video_detectors(found, index, detectors, stats)
    if embedder is not None:
        _embed_frames(embedder, index, jobs, stats, progress)
    _group_steps(MediaType.VIDEO, found, index, group_detectors, (), None, {}, stats, progress)


def _video_detectors(
    found: set[Path], index: ImageIndex, detectors: Sequence[Detector], stats: Stats
) -> None:
    """Runs the detectors that accept videos on each video that lacks them. They read no frame
    yet: a video is given no embedding, so one that asks for it fails and is counted."""
    selected = [d for d in detectors if MediaType.VIDEO in accepted_media_types(d)]
    unreadable = set(index.unreadable())
    context = AnalysisContext()
    for video in index.images():
        if video.media_type is not MediaType.VIDEO or video.path not in found:
            continue
        if video.path in unreadable:
            continue
        for detector in selected:
            if index.is_analyzed(video.path, detector.id, detector.version):
                continue
            try:
                signals = detector.analyze(video, context)
            except Exception as error:
                stats.failures.append(f"{video.path}: {detector.id}: {_describe(error)}")
                continue
            index.set_signals(video.path, detector.id, detector.version, signals)
            stats.detector_runs += 1


def _embed_frames(
    embedder: Embedder, index: ImageIndex, jobs: int, stats: Stats, progress: Progress
) -> None:
    """Embeds the frames of each readable video with no frames at FRAMES_VERSION, or with one at a
    time `frame_times` does not give now. An HDR video stores one frame a keyframe, so some of the
    times may be missing. A rule that adds times must bump FRAMES_VERSION, or the videos sampled
    before it keep their frames.

    A video whose frames do not read is marked, as an unreadable image is, and not decoded again
    until the file or FRAMES_VERSION changes. Any other error is a failure, and the next run tries
    it again, as an image whose embedding fails."""
    stored = index.sampled_times(embedder.id, embedder.version, FRAMES_VERSION)
    todo = []
    for video, duration_ms in index.read_videos(FRAMES_VERSION):
        times = frame_times(duration_ms)
        if not (video.path in stored and set(stored[video.path]) <= set(times)):
            todo.append((video, times))
    for done, ((video, _), pixels) in enumerate(
        zip(todo, _map(_frame_pixels, todo, jobs), strict=True), 1
    ):
        if pixels is None:
            index.set_frames_unreadable(video.path, FRAMES_VERSION)
            stats.unreadable_frames += 1
        elif isinstance(pixels, str):
            stats.failures.append(f"{video.path}: video_frames: {pixels}")
        else:
            vectors = embedder.embed_pixels(np.stack(list(pixels.values())))
            index.set_frame_embeddings(
                video.path,
                embedder.id,
                embedder.version,
                FRAMES_VERSION,
                dict(zip(pixels, vectors, strict=True)),
            )
            stats.detector_runs += len(pixels)
        progress("video_frames", done, len(todo))


def _frame_pixels(task: tuple[MediaRef, list[int]]) -> dict[int, np.ndarray] | str | None:
    """Runs in a worker process: a video's frames by time, each preprocessed as an image is, None
    when the file is there and its frames do not read, or the error as text."""
    video, times = task
    try:
        return {t: mobileclip.model_input(frame) for t, frame in read_frames(video.path, times)}
    except (UnreadableVideo, av.FFmpegError) as error:
        # A file moved since the walk, such as one the user trashed, is not the video's fault.
        return None if video.path.is_file() else _describe(error)
    except Exception as error:
        return _describe(error)


def _stickers_last(images: list[MediaRef]) -> list[MediaRef]:
    """The real images first: a sticker only delays them, since the home screen lists
    candidates as they are stored."""
    return sorted(images, key=lambda i: is_sticker_folder(i.path))


def _analyze_each(
    images: list[MediaRef],
    detectors: Sequence[Detector],
    embeddings: dict[Path, np.ndarray],
    step: str,
    index: ImageIndex,
    jobs: int,
    stats: Stats,
    progress: Progress,
    *,
    before_embeddings: bool,
) -> None:
    """Runs `detectors` on the images that lack them, a sticker only the ones that
    `runs_on_stickers`.

    The pass `before_embeddings` also computes the perceptual hashes, and a detector in it that
    asks for an embedding fails: it should have set `uses_embeddings`.
    """
    tasks = []
    for image in images:
        sticker = is_sticker_folder(image.path)
        todo = [
            d
            for d in detectors
            if image.media_type in accepted_media_types(d)
            and (not sticker or runs_on_stickers(d))
            and not index.is_analyzed(image.path, d.id, d.version)
        ]
        needs_phash = before_embeddings and index.perceptual_hash(image.path) is None
        if todo or needs_phash:
            tasks.append(
                (
                    image,
                    index.metadata(image.path),
                    embeddings.get(image.path),
                    todo,
                    needs_phash,
                    before_embeddings,
                )
            )
    for done, (image, results, phash) in enumerate(_map(_analyze, tasks, jobs), 1):
        for detector_id, version, outcome in results:
            if outcome is None:
                stats.skipped += 1
            elif isinstance(outcome, str):
                stats.failures.append(f"{image.path}: {detector_id}: {outcome}")
            else:
                index.set_signals(image.path, detector_id, version, outcome)
                stats.detector_runs += 1
        if isinstance(phash, str):
            stats.failures.append(f"{image.path}: perceptual hash: {phash}")
        elif phash is not None:
            index.set_perceptual_hash(image.path, phash)
            stats.detector_runs += 1
        progress(step, done, len(tasks))


def _analyze(
    task: tuple[MediaRef, ImageMetadata, np.ndarray | None, list[Detector], bool, bool],
) -> tuple[MediaRef, list[tuple[str, int, list[Signal] | str | None]], int | str | None]:
    """Runs in a worker process: metadata and embedding come along, the index stays in the
    parent.

    Errors come back as text, so one broken file does not stop a run over thousands. A
    detector that needs the missing embedding comes back as None.
    """
    image, metadata, embedding, detectors, needs_phash, before_embeddings = task
    # The task's pixels, shared by its detectors and the perceptual hash, and dropped with it.
    pixels = Pixels(image.path)
    context = AnalysisContext(
        metadata=lambda _: metadata,
        embedding=_no_embedding_yet
        if before_embeddings
        else _stored_embedding({image.path: embedding} if embedding is not None else {}),
        pixels=lambda other: pixels if other.path == image.path else Pixels(other.path),
    )
    outcomes = {}
    # From the largest draft side down, as in the app, where the sides that give one sample
    # size share a decode. The hash comes last, at 256.
    for i in sorted(
        range(len(detectors)),
        key=lambda i: getattr(detectors[i], "decode_side", 0) or 0,
        reverse=True,
    ):
        try:
            outcomes[i] = detectors[i].analyze(image, context)
        except MissingEmbedding:
            outcomes[i] = None
        except Exception as error:
            outcomes[i] = _describe(error)
    # Stored in the detectors' own order, so the index lists an image's signals as before.
    results = [(d.id, d.version, outcomes[i]) for i, d in enumerate(detectors)]
    phash = None
    if needs_phash:
        try:
            phash = hashing.perceptual_hash(image, pixels)
        except Exception as error:
            phash = _describe(error)
    return image, results, phash


def _no_embedding_yet(image: MediaRef) -> np.ndarray:
    raise RuntimeError(f"{image.path}: reads an embedding without uses_embeddings")


def _embed(
    embedder: Embedder,
    index: ImageIndex,
    detectors: Sequence[Detector],
    images: list[MediaRef],
    jobs: int,
    stats: Stats,
    progress: Progress,
) -> None:
    """Decodes in the worker processes; the model runs here, on all cores, in batches."""
    stored = index.embeddings(embedder.id, embedder.version).keys()
    todo = [image for image in images if image.path not in stored]
    for start in range(0, len(todo), EMBEDDING_CHUNK):
        chunk = todo[start : start + EMBEDDING_CHUNK]
        decoded = []
        for image, pixels in zip(chunk, _map(_preprocess, chunk, jobs), strict=True):
            if isinstance(pixels, str):
                stats.failures.append(f"{image.path}: embedding: {pixels}")
            else:
                decoded.append((image, pixels))
        for batch_start in range(0, len(decoded), EMBEDDING_BATCH):
            batch = decoded[batch_start : batch_start + EMBEDDING_BATCH]
            vectors = embedder.embed_pixels(np.stack([pixels for _, pixels in batch]))
            # A detector's result belongs to the embedding it read: a new one means analyzing again.
            index.forget_analyses(
                [image.path for image, _ in batch],
                [d.id for d in detectors if getattr(d, "uses_embeddings", False)],
            )
            index.set_embeddings(
                embedder.id,
                embedder.version,
                {image.path: v for (image, _), v in zip(batch, vectors, strict=True)},
            )
            stats.detector_runs += len(batch)
            progress("embeddings", start + batch_start + len(batch), len(todo))


def _preprocess(image: MediaRef) -> np.ndarray | str:
    try:
        return mobileclip.preprocess(image.path)
    except Exception as error:
        return _describe(error)


def _describe(error: Exception) -> str:
    return f"{type(error).__name__}: {error}"


def _map(function, tasks, jobs):
    if jobs == 1:
        yield from map(function, tasks)
        return
    # Not fork: ONNX Runtime's threads would be copied mid-state into the children.
    with ProcessPoolExecutor(jobs, mp_context=multiprocessing.get_context("forkserver")) as pool:
        yield from pool.map(function, tasks, chunksize=4)


def _cached_content_hash(index: ImageIndex) -> Callable[[MediaRef], str]:
    def content_hash(image: MediaRef) -> str:
        digest = index.content_hash(image.path)
        if digest is None:
            digest = hashing.content_hash(image)
            index.set_content_hash(image.path, digest)
        return digest

    return content_hash


def _stored_perceptual_hash(index: ImageIndex) -> Callable[[MediaRef], int]:
    """Hashes come from the worker step; an image without one could not be decoded."""

    def perceptual_hash(image: MediaRef) -> int:
        phash = index.perceptual_hash(image.path)
        if phash is None:
            raise UnreadableImage(image.path)
        return phash

    return perceptual_hash


def _stored_embedding(embeddings: dict[Path, np.ndarray]) -> Callable[[MediaRef], np.ndarray]:
    def embedding(image: MediaRef) -> np.ndarray:
        found = embeddings.get(image.path)
        if found is None:
            raise MissingEmbedding(image.path)
        return found

    return embedding


def _stored_sharpness(
    index: ImageIndex, blur_detector: Detector | None
) -> Callable[[MediaRef], float]:
    """Burst ranks its shots by the measure blur stored, and decodes a shot again only when
    blur left none."""

    def sharpness(image: MediaRef) -> float:
        if blur_detector is not None:
            for signal in index.signals(image.path):
                if (signal.detector_id, signal.detector_version) == (
                    blur_detector.id,
                    blur_detector.version,
                ):
                    with contextlib.suppress(KeyError, ValueError):
                        return float(signal.evidence[blur.PEAK_VARIANCE_KEY])
        return blur.peak_variance(image)

    return sharpness


def _metadata(index: ImageIndex) -> Callable[[MediaRef], ImageMetadata]:
    def metadata(image: MediaRef) -> ImageMetadata:
        found = index.metadata(image.path)
        if found is None:
            raise UnreadableImage(image.path)
        return found

    return metadata


def _fingerprint(images: Sequence[MediaRef], inputs: str) -> str:
    digest = xxhash.xxh3_64(inputs.encode())
    for image in sorted(images, key=lambda i: str(i.path)):
        digest.update(f"{image.path}\0{image.size}\0{image.mtime_ns}\n".encode())
    return digest.hexdigest()
