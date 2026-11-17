"""NEAR_DUPLICATE: resized, recompressed and edited copies (docs/design.md, section 5).

Two images are linked when their perceptual hashes are within a Hamming distance, which
catches resized and recompressed copies such as WhatsApp's, or when their embeddings are
close, which also catches crops and edits. Linked images form a group. Recall first: both
thresholds start loose.

Two videos are linked when their lengths match and their frames look alike: a copy WhatsApp
re-encoded, such as the one in `Sent`, never matches its original by content hash (#396).
"""

from collections.abc import Iterable, Sequence

import numpy as np
import xxhash

from anteater_lab.detectors.base import AnalysisContext, Role
from anteater_lab.folders import is_camera_folder, is_messaging_folder
from anteater_lab.hashing import is_flat_hash
from anteater_lab.metadata import VideoMetadata
from anteater_lab.model import GROUP_KEY, ROLE_KEY, Category, MediaRef, MediaType, Signal

MAX_DISTANCE = 10
"""Hamming distance between 64-bit pHashes that still links two images."""
NEAR_DUPLICATE_MIN_COSINE = 0.90
"""Embedding similarity that still links two images. A 75% crop of a photo scores about
0.90 with MobileCLIP-S0; different photos of the same party stay under 0.8. MobileCLIP2-S0
links about as many pairs at this threshold (docs/design.md, section 5)."""
VIDEO_MAX_DURATION_GAP_MS = 100
"""Two videos further apart in length are not compared. A WhatsApp copy and its original had the
same length on the labeled videos, and a re-encode moves it by an audio frame or two at most
(docs/design.md, section 5)."""
VIDEO_MIN_FRAME_COSINE = 0.80
"""Frame similarity that still links two videos of about one length. On the labeled videos the two
copies scored 0.887 and 0.980, and no other pair within the length gap above 0.6. Without the gap,
0.7% of the other pairs reach it."""
_CHUNK = 1024


class NearDuplicateDetector:
    id = "near_duplicate"
    version = 3
    category = Category.NEAR_DUPLICATE
    media_types = frozenset({MediaType.IMAGE, MediaType.VIDEO})

    def analyze(
        self, images: Sequence[MediaRef], context: AnalysisContext
    ) -> dict[MediaRef, list[Signal]]:
        if images and images[0].media_type is MediaType.VIDEO:  # a group run has one media type
            return self._videos(images, context)
        members, hashes, vectors = [], [], []
        for image in images:
            try:
                hashes.append(context.perceptual_hash(image))
            except Exception:  # Pillow raises many types on undecodable pixels
                continue
            try:
                vectors.append(context.embedding(image))
            except LookupError:
                vectors.append(None)  # a run without embeddings: hashes alone
            members.append(image)
        links = [*_hash_links(hashes), *_embedding_links(vectors)]
        result = {}
        for component in _components(len(members), links):
            best = min(component, key=lambda i: _preference(members[i], context))
            group_id = xxhash.xxh3_64(str(members[best].path).encode()).hexdigest()
            for i in component:
                signal = self._signal(i, best, group_id, hashes, vectors)
                result[members[i]] = [signal]
        return result

    def _videos(
        self, videos: Sequence[MediaRef], context: AnalysisContext
    ) -> dict[MediaRef, list[Signal]]:
        """A video with no length or no frames is linked to none: a length alone is no evidence,
        since many clips share one. Two camera videos captured at different instants are takes of
        a moment, not copies (docs/design.md, section 5)."""
        members = []
        for video in videos:
            meta = _video_metadata(video, context)
            frames = context.frames(video)
            if meta is not None and meta.duration_ms is not None and len(frames) > 0:
                members.append((meta.duration_ms, video, meta, np.stack(frames)))
        members.sort(key=lambda m: (m[0], str(m[1].path)))
        links = []
        for i, (length, video, meta, frames) in enumerate(members):
            for j in range(i + 1, len(members)):
                other_length, other, other_meta, other_frames = members[j]
                if other_length - length > VIDEO_MAX_DURATION_GAP_MS:
                    break
                if _takes(video, meta, other, other_meta):
                    continue
                if _frame_similarity(frames, other_frames) >= VIDEO_MIN_FRAME_COSINE:
                    links.append((i, j))
        result = {}
        for component in _components(len(members), links):
            best = min(component, key=lambda i: _video_preference(members[i][1], members[i][2]))
            group_id = xxhash.xxh3_64(str(members[best][1].path).encode()).hexdigest()
            for i in component:
                role, score = (Role.BEST, 0.0) if i == best else (Role.INFERIOR, 1.0)
                evidence = {GROUP_KEY: group_id, ROLE_KEY: role}
                signal = Signal(self.id, self.version, Category.NEAR_DUPLICATE, score, evidence)
                result[members[i][1]] = [signal]
        return result

    def _signal(
        self,
        i: int,
        best: int,
        group_id: str,
        hashes: list[int],
        vectors: list[np.ndarray | None],
    ) -> Signal:
        distance = (hashes[i] ^ hashes[best]).bit_count()
        evidence = {GROUP_KEY: group_id, ROLE_KEY: Role.INFERIOR, "distance": str(distance)}
        if is_flat_hash(hashes[i]):
            evidence["flat"] = "true"
        # Each measure scores 1 for an identical copy and 0.5 at its linking threshold.
        scores = [1.0 - distance / (2 * (MAX_DISTANCE + 1))]
        if vectors[i] is not None and vectors[best] is not None:
            cosine = float(vectors[i] @ vectors[best])
            evidence["cosine"] = f"{cosine:.3f}"
            margin = (cosine - NEAR_DUPLICATE_MIN_COSINE) / (1.0 - NEAR_DUPLICATE_MIN_COSINE)
            scores.append(0.5 + 0.5 * margin)
        score = min(1.0, max(0.0, *scores))
        if i == best:
            evidence[ROLE_KEY], score = Role.BEST, 0.0
        return Signal(self.id, self.version, Category.NEAR_DUPLICATE, score, evidence)


def _hash_links(hashes: list[int]) -> Iterable[tuple[int, int]]:
    array = np.array(hashes, dtype=np.uint64)
    flat = np.array([is_flat_hash(h) for h in hashes], dtype=bool)  # flat images share a hash
    for start in range(0, len(array), _CHUNK):
        distances = np.bitwise_count(array[start : start + _CHUNK, None] ^ array[None, :])
        close = (distances <= MAX_DISTANCE) & ~flat[start : start + _CHUNK, None] & ~flat[None, :]
        for i, j in zip(*np.nonzero(close), strict=True):
            yield start + int(i), int(j)


def _embedding_links(vectors: list[np.ndarray | None]) -> Iterable[tuple[int, int]]:
    indices = [i for i, v in enumerate(vectors) if v is not None]
    if not indices:
        return
    matrix = np.stack([vectors[i] for i in indices])
    for start in range(0, len(matrix), _CHUNK):
        similar = matrix[start : start + _CHUNK] @ matrix.T >= NEAR_DUPLICATE_MIN_COSINE
        for i, j in zip(*np.nonzero(similar), strict=True):
            yield indices[start + int(i)], indices[int(j)]


def _components(count: int, links: Iterable[tuple[int, int]]) -> list[list[int]]:
    """Groups of two or more items connected by links (union-find)."""
    parent = list(range(count))

    def root(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a, b in links:
        a, b = root(a), root(b)
        if a != b:
            parent[a] = b
    components: dict[int, list[int]] = {}
    for i in range(count):
        components.setdefault(root(i), []).append(i)
    return [members for members in components.values() if len(members) > 1]


def _preference(image: MediaRef, context: AnalysisContext) -> tuple:
    """The copy to keep: highest resolution, least compression, camera EXIF, then folder."""
    meta = context.metadata(image)
    pixels = meta.width * meta.height
    return -pixels, -image.size / pixels, not meta.has_camera_exif, _folder(image), str(image.path)


def _frame_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """The mean, over the frames of both videos, of each frame's closest cosine in the other one.
    Closest and not same-time frames, since an HDR video's frames are keyframes, fewer and up to
    half a second off the times a copy's exact frames are at."""
    cosines = a @ b.T
    return float((cosines.max(axis=1).sum() + cosines.max(axis=0).sum()) / (len(a) + len(b)))


def _takes(a: MediaRef, a_meta: VideoMetadata, b: MediaRef, b_meta: VideoMetadata) -> bool:
    """Two camera videos with capture instants that differ were filmed apart. A copy saved to the
    camera folder keeps its original's instant or has none."""
    instants = a_meta.captured_instant, b_meta.captured_instant
    both_camera = is_camera_folder(a.path) and is_camera_folder(b.path)
    return both_camera and None not in instants and instants[0] != instants[1]


def _video_preference(video: MediaRef, meta: VideoMetadata) -> tuple:
    """The copy to keep: camera folder first, messaging folders last, then the highest resolution
    and bitrate."""
    return _folder(video), -meta.width * meta.height, -(meta.bitrate or 0), str(video.path)


def _folder(media: MediaRef) -> int:
    """A copy in the camera folder is kept first and one in a messaging folder last."""
    return 2 if is_messaging_folder(media.path) else 0 if is_camera_folder(media.path) else 1


def _video_metadata(video: MediaRef, context: AnalysisContext) -> VideoMetadata | None:
    try:
        return context.video_metadata(video)
    except Exception:  # unreadable header: no length to compare
        return None
