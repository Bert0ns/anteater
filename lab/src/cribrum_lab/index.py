"""Persistent image index, so re-runs only process new or changed files.

Mirrors the planned Room schema (docs/design.md, section 7). Keep the database
under lab/data/: it holds paths and metadata of personal images.
"""

import dataclasses
import json
import sqlite3
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from datetime import datetime
from pathlib import Path
from typing import Self

import numpy as np

from cribrum_lab.metadata import ImageMetadata, VideoMetadata
from cribrum_lab.model import (
    FrameMeasures,
    IndexedMedia,
    MediaRef,
    MediaType,
    ReviewState,
    Signal,
    kind,
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS images (
    path TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    mtime_ns INTEGER NOT NULL,
    review_state TEXT NOT NULL DEFAULT 'unreviewed',
    width INTEGER,
    height INTEGER,
    mime TEXT,
    camera_make TEXT,
    camera_model TEXT,
    captured_at TEXT,
    unreadable INTEGER NOT NULL DEFAULT 0,
    content_hash TEXT,
    perceptual_hash TEXT,
    media_type TEXT NOT NULL DEFAULT 'image',
    -- A video's own metadata (docs/design.md, section 7). Its capture time goes in captured_at.
    duration_ms INTEGER,
    frame_rate REAL,
    bitrate INTEGER,
    has_audio INTEGER,
    video_codec TEXT,
    hdr INTEGER,
    -- The moment captured_at holds, as epoch milliseconds in UTC: a video's MP4 creation time.
    captured_instant INTEGER,
    -- The frame version at which a video's frames did not read: not tried again until the file or
    -- the version changes (docs/design.md, section 4).
    frames_unreadable INTEGER
);
-- Which detector version last analyzed an image, even if it emitted no signal.
CREATE TABLE IF NOT EXISTS analyses (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    detector_id TEXT NOT NULL,
    detector_version INTEGER NOT NULL,
    PRIMARY KEY (path, detector_id)
);
CREATE TABLE IF NOT EXISTS signals (
    path TEXT NOT NULL,
    detector_id TEXT NOT NULL,
    detector_version INTEGER NOT NULL,
    category TEXT NOT NULL,
    score REAL NOT NULL,
    evidence TEXT NOT NULL,
    FOREIGN KEY (path, detector_id) REFERENCES analyses ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS signals_by_analysis ON signals (path, detector_id);
-- Signal identities (Signal.identity) an image had when the user kept it.
CREATE TABLE IF NOT EXISTS kept_signals (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    category TEXT NOT NULL,
    group_id TEXT NOT NULL,
    role TEXT NOT NULL,
    PRIMARY KEY (path, category, group_id, role)
);
-- Ids of the detectors that had stored a result when the user kept it.
CREATE TABLE IF NOT EXISTS kept_detectors (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    detector_id TEXT NOT NULL,
    PRIMARY KEY (path, detector_id)
);
CREATE TABLE IF NOT EXISTS embeddings (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    model TEXT NOT NULL,
    model_version INTEGER NOT NULL,
    vector BLOB NOT NULL,  -- float32, little-endian
    PRIMARY KEY (path, model)
);
-- A video's frames, embedded at the times frames.frame_times gives (docs/design.md, section 7).
CREATE TABLE IF NOT EXISTS frame_embeddings (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    model TEXT NOT NULL,
    model_version INTEGER NOT NULL,
    frames_version INTEGER NOT NULL,
    time_ms INTEGER NOT NULL,
    vector BLOB NOT NULL,  -- float32, little-endian
    PRIMARY KEY (path, model, time_ms)
);
-- What the detectors that read frames keep of each of a video's frames, at the same times.
CREATE TABLE IF NOT EXISTS frame_measures (
    path TEXT NOT NULL REFERENCES images ON DELETE CASCADE,
    frames_version INTEGER NOT NULL,
    time_ms INTEGER NOT NULL,
    peak_variance REAL NOT NULL,
    p1 REAL NOT NULL,
    p5 REAL NOT NULL,
    p95 REAL NOT NULL,
    p99 REAL NOT NULL,
    faces INTEGER NOT NULL,
    face_confidence REAL NOT NULL,
    face_area REAL NOT NULL,
    PRIMARY KEY (path, time_ms)
);
-- The image set a group detector version last ran on, so unchanged sets are skipped, with the
-- paths whose content hash failed in that run.
CREATE TABLE IF NOT EXISTS group_runs (
    detector_id TEXT PRIMARY KEY,
    detector_version INTEGER NOT NULL,
    fingerprint TEXT NOT NULL
);
-- Folders the user excluded (docs/design.md, section 6.3): scanned, but never candidates.
CREATE TABLE IF NOT EXISTS excluded_folders (
    path TEXT PRIMARY KEY
);
-- Files the walk sniffed and found to be neither images nor videos (docs/design.md, section 4).
CREATE TABLE IF NOT EXISTS not_images (
    path TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    mtime_ns INTEGER NOT NULL
);
"""
SCHEMA_VERSION = 10
"""Bump on every schema change: an index from another version is refused, not misread."""


class IndexVersionError(Exception):
    """The database was written with another schema version."""


class ImageIndex:
    """Methods taking a path raise KeyError for an image that was never recorded."""

    def __init__(self, database: Path | str) -> None:
        self._db = sqlite3.connect(database)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.execute("PRAGMA journal_mode = WAL")  # one commit per image must stay cheap
        self._db.execute("PRAGMA synchronous = NORMAL")
        tables = self._db.execute("SELECT count(*) FROM sqlite_master").fetchone()[0]
        version = self._db.execute("PRAGMA user_version").fetchone()[0]
        if tables and version != SCHEMA_VERSION:
            self._db.close()
            raise IndexVersionError(
                f"{database} has schema version {version}, expected {SCHEMA_VERSION}"
            )
        self._db.executescript(_SCHEMA)
        self._db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        self._db.close()

    def record(self, image: MediaRef) -> bool:
        """Adds or updates an image. Returns True if it is new or changed.

        A changed image (different size or mtime) loses its metadata, hashes, embeddings,
        unreadable mark and signals, which must be computed again. It keeps its review
        state: the file may only have been touched, and a decision the user made must not
        be lost. A real edit shows up as new signals, which the ranker treats as such.
        """
        return self.record_all([image]) > 0

    def record_all(self, images: Iterable[MediaRef]) -> int:
        """`record` for many images in one transaction. Returns how many are new or changed.

        If it raises, none of them is recorded.
        """
        with self._db:
            return sum(self._record(image) for image in images)

    def _record(self, image: MediaRef) -> bool:
        path = str(image.path)
        row = self._db.execute(
            "SELECT size, mtime_ns FROM images WHERE path = ?", (path,)
        ).fetchone()
        if row == (image.size, image.mtime_ns):
            return False
        if row is None:
            self._db.execute(
                "INSERT INTO images (path, size, mtime_ns, media_type) VALUES (?, ?, ?, ?)",
                (path, image.size, image.mtime_ns, image.media_type.value),
            )
        else:
            self._db.execute(
                """UPDATE images SET size = ?, mtime_ns = ?, width = NULL, height = NULL,
                   mime = NULL, camera_make = NULL, camera_model = NULL, captured_at = NULL,
                   unreadable = 0, content_hash = NULL, perceptual_hash = NULL, media_type = ?,
                   duration_ms = NULL, frame_rate = NULL, bitrate = NULL, has_audio = NULL,
                   video_codec = NULL, hdr = NULL, captured_instant = NULL, frames_unreadable = NULL
                   WHERE path = ?""",
                (image.size, image.mtime_ns, image.media_type.value, path),
            )
            self._db.execute("DELETE FROM analyses WHERE path = ?", (path,))
            self._db.execute("DELETE FROM embeddings WHERE path = ?", (path,))
            self._db.execute("DELETE FROM frame_embeddings WHERE path = ?", (path,))
            self._db.execute("DELETE FROM frame_measures WHERE path = ?", (path,))
        return True

    def images(self) -> list[MediaRef]:
        """Every indexed file, videos included."""
        rows = self._db.execute("SELECT path, size, mtime_ns, media_type FROM images ORDER BY path")
        return [_ref(*row) for row in rows]

    def forget(self, paths: Iterable[Path]) -> None:
        """Removes images from the index, with everything stored about them. Files are untouched."""
        with self._db:
            self._db.executemany("DELETE FROM images WHERE path = ?", [(str(p),) for p in paths])

    def not_images(self) -> list[MediaRef]:
        rows = self._db.execute("SELECT path, size, mtime_ns FROM not_images")
        return [MediaRef(Path(path), size, mtime_ns) for path, size, mtime_ns in rows]

    def add_not_images(self, files: Iterable[MediaRef]) -> None:
        """Stores the files; one already stored with the same path is replaced."""
        with self._db:
            self._db.executemany(
                "INSERT OR REPLACE INTO not_images (path, size, mtime_ns) VALUES (?, ?, ?)",
                [(str(f.path), f.size, f.mtime_ns) for f in files],
            )

    def forget_not_images(self, paths: Iterable[Path]) -> None:
        with self._db:
            self._db.executemany(
                "DELETE FROM not_images WHERE path = ?", [(str(p),) for p in paths]
            )

    def without_metadata(self) -> list[MediaRef]:
        """Files whose metadata was never read, videos included, leaving out the unreadable ones."""
        rows = self._db.execute(
            """SELECT path, size, mtime_ns, media_type FROM images
               WHERE width IS NULL AND NOT unreadable ORDER BY path"""
        )
        return [_ref(*row) for row in rows]

    def set_unreadable(self, path: Path) -> None:
        """Marks an image whose header can't be decoded, so it isn't retried until it changes."""
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET unreadable = 1 WHERE path = ?", (str(path),)
            )
        _require(updated.rowcount, path)

    def unreadable(self) -> list[Path]:
        rows = self._db.execute("SELECT path FROM images WHERE unreadable ORDER BY path")
        return [Path(path) for (path,) in rows]

    def set_metadata(self, path: Path, metadata: ImageMetadata) -> None:
        captured_at = metadata.captured_at.isoformat() if metadata.captured_at else None
        with self._db:
            updated = self._db.execute(
                """UPDATE images SET width = ?, height = ?, mime = ?, camera_make = ?,
                   camera_model = ?, captured_at = ? WHERE path = ?""",
                (
                    metadata.width,
                    metadata.height,
                    metadata.mime,
                    metadata.camera_make,
                    metadata.camera_model,
                    captured_at,
                    str(path),
                ),
            )
        _require(updated.rowcount, path)

    def metadata(self, path: Path) -> ImageMetadata | None:
        row = self._db.execute(
            """SELECT width, height, mime, camera_make, camera_model, captured_at
               FROM images WHERE path = ? AND width IS NOT NULL""",
            (str(path),),
        ).fetchone()
        if row is None:
            return None
        *fields, captured_at = row
        return ImageMetadata(
            *fields, captured_at=datetime.fromisoformat(captured_at) if captured_at else None
        )

    def set_video_metadata(self, path: Path, metadata: VideoMetadata) -> None:
        captured_at = metadata.captured_at.isoformat() if metadata.captured_at else None
        with self._db:
            updated = self._db.execute(
                """UPDATE images SET width = ?, height = ?, duration_ms = ?, frame_rate = ?,
                   bitrate = ?, has_audio = ?, video_codec = ?, hdr = ?, captured_at = ?,
                   captured_instant = ? WHERE path = ?""",
                (
                    metadata.width,
                    metadata.height,
                    metadata.duration_ms,
                    metadata.frame_rate,
                    metadata.bitrate,
                    metadata.has_audio,
                    metadata.video_codec,
                    metadata.hdr,
                    captured_at,
                    metadata.captured_instant,
                    str(path),
                ),
            )
        _require(updated.rowcount, path)

    def video_metadata(self, path: Path) -> VideoMetadata | None:
        row = self._db.execute(
            f"SELECT {_VIDEO_COLUMNS} FROM images WHERE path = ? AND width IS NOT NULL",
            (str(path),),
        ).fetchone()
        return None if row is None else _video_metadata(row)

    def all_video_metadata(self) -> dict[Path, VideoMetadata]:
        """The metadata of every video that has it, by path: one query, where a group detector
        would make one per video."""
        rows = self._db.execute(
            f"""SELECT path, {_VIDEO_COLUMNS} FROM images
               WHERE media_type = 'video' AND width IS NOT NULL"""
        )
        return {Path(path): _video_metadata(row) for path, *row in rows}

    def photo_captures(self) -> dict[Path, datetime]:
        """The capture time of every image that has one, by path, in one query."""
        rows = self._db.execute(
            """SELECT path, captured_at FROM images
               WHERE media_type = 'image' AND captured_at IS NOT NULL"""
        )
        return {Path(path): datetime.fromisoformat(at) for path, at in rows}

    def read_videos(self, frames_version: int) -> list[tuple[MediaRef, int | None]]:
        """The videos whose metadata was read, readable and out of the trash, with their
        duration, leaving out those whose frames did not read at `frames_version`: the ones the
        frames step samples."""
        rows = self._db.execute(
            """SELECT path, size, mtime_ns, media_type, duration_ms FROM images
               WHERE media_type = 'video' AND width IS NOT NULL AND NOT unreadable
               AND review_state != 'trashed' AND frames_unreadable IS NOT ?
               ORDER BY path""",
            (frames_version,),
        )
        return [(_ref(*row), duration_ms) for *row, duration_ms in rows]

    def set_frames_unreadable(self, path: Path, frames_version: int) -> None:
        """Marks a video whose frames do not read, so it is not decoded again at this version."""
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET frames_unreadable = ? WHERE path = ?",
                (frames_version, str(path)),
            )
        _require(updated.rowcount, path)

    def set_content_hash(self, path: Path, digest: str) -> None:
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET content_hash = ? WHERE path = ?", (digest, str(path))
            )
        _require(updated.rowcount, path)

    def content_hash(self, path: Path) -> str | None:
        row = self._db.execute(
            "SELECT content_hash FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return row[0]

    def set_perceptual_hash(self, path: Path, phash: int) -> None:
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET perceptual_hash = ? WHERE path = ?", (f"{phash:016x}", str(path))
            )
        _require(updated.rowcount, path)

    def perceptual_hash(self, path: Path) -> int | None:
        """Stored as hex text: SQLite integers are signed and a hash may use all 64 bits."""
        row = self._db.execute(
            "SELECT perceptual_hash FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return None if row[0] is None else int(row[0], 16)

    def hashed_paths(self) -> set[Path]:
        rows = self._db.execute("SELECT path FROM images WHERE perceptual_hash IS NOT NULL")
        return {Path(path) for (path,) in rows}

    def set_embeddings(
        self, model: str, model_version: int, vectors: Mapping[Path, np.ndarray]
    ) -> None:
        with self._db:
            for path, vector in vectors.items():
                exists = self._db.execute(
                    "SELECT 1 FROM images WHERE path = ?", (str(path),)
                ).fetchone()
                _require(exists, path)
                self._db.execute(
                    "INSERT OR REPLACE INTO embeddings VALUES (?, ?, ?, ?)",
                    (str(path), model, model_version, vector.astype("<f4").tobytes()),
                )

    def set_frame_embeddings(
        self,
        path: Path,
        model: str,
        model_version: int,
        frames_version: int,
        vectors: Mapping[int, np.ndarray],
        measures: Mapping[int, FrameMeasures] | None = None,
        forget: Iterable[str] = (),
    ) -> None:
        """Replaces the video's frames of this model, by their time in milliseconds, and the
        measures of the same frames in the same transaction, with what the detectors in `forget`
        stored for the video, which belongs to the frames they read."""
        with self._db:
            exists = self._db.execute("SELECT 1 FROM images WHERE path = ?", (str(path),))
            _require(exists.fetchone(), path)
            self._db.executemany(
                "DELETE FROM analyses WHERE path = ? AND detector_id = ?",
                [(str(path), detector_id) for detector_id in forget],
            )
            self._db.execute(
                "DELETE FROM frame_embeddings WHERE path = ? AND model = ?", (str(path), model)
            )
            self._db.execute("DELETE FROM frame_measures WHERE path = ?", (str(path),))
            self._db.executemany(
                "INSERT INTO frame_measures VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (str(path), frames_version, time_ms, *dataclasses.astuple(frame))
                    for time_ms, frame in (measures or {}).items()
                ],
            )
            self._db.executemany(
                "INSERT INTO frame_embeddings VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (
                        str(path),
                        model,
                        model_version,
                        frames_version,
                        time_ms,
                        vector.astype("<f4").tobytes(),
                    )
                    for time_ms, vector in vectors.items()
                ],
            )

    def frame_embeddings(
        self, model: str, model_version: int, frames_version: int
    ) -> dict[Path, dict[int, np.ndarray]]:
        """Every video's frames of these versions, by time, in the order of their times."""
        rows = self._db.execute(
            """SELECT path, time_ms, vector FROM frame_embeddings
               WHERE model = ? AND model_version = ? AND frames_version = ?
               ORDER BY path, time_ms""",
            (model, model_version, frames_version),
        )
        frames: dict[Path, dict[int, np.ndarray]] = defaultdict(dict)
        for path, time_ms, vector in rows:
            frames[Path(path)][time_ms] = np.frombuffer(vector, dtype="<f4")
        return dict(frames)

    def frame_measures(self, frames_version: int) -> dict[Path, list[FrameMeasures]]:
        """Every video's frame measures of this frames version, in the order of their times."""
        rows = self._db.execute(
            """SELECT path, peak_variance, p1, p5, p95, p99, faces, face_confidence, face_area
               FROM frame_measures WHERE frames_version = ? ORDER BY path, time_ms""",
            (frames_version,),
        )
        measures: dict[Path, list[FrameMeasures]] = defaultdict(list)
        for path, *values in rows:
            measures[Path(path)].append(FrameMeasures(*values))
        return dict(measures)

    def sampled_times(
        self, model: str, model_version: int, frames_version: int
    ) -> dict[Path, list[int]]:
        """`frame_embeddings` without the vectors: what the frames step compares with the rule."""
        rows = self._db.execute(
            """SELECT path, time_ms FROM frame_embeddings
               WHERE model = ? AND model_version = ? AND frames_version = ?
               ORDER BY path, time_ms""",
            (model, model_version, frames_version),
        )
        times: dict[Path, list[int]] = defaultdict(list)
        for path, time_ms in rows:
            times[Path(path)].append(time_ms)
        return dict(times)

    def delete_embeddings(self) -> None:
        """Deletes every stored embedding, a video's frames included, and nothing else: the next
        run computes them again."""
        with self._db:
            self._db.execute("DELETE FROM embeddings")
            self._db.execute("DELETE FROM frame_embeddings")

    def forget_analyses(self, paths: Iterable[Path], detector_ids: Iterable[str]) -> None:
        """Drops what these detectors stored for these images, signals included."""
        with self._db:
            self._db.executemany(
                "DELETE FROM analyses WHERE path = ? AND detector_id = ?",
                [(str(p), d) for p in paths for d in detector_ids],
            )

    def embeddings(self, model: str, model_version: int) -> dict[Path, np.ndarray]:
        """Every stored embedding of this model version, loaded at once for group detectors."""
        rows = self._db.execute(
            "SELECT path, vector FROM embeddings WHERE model = ? AND model_version = ?",
            (model, model_version),
        )
        return {Path(path): np.frombuffer(vector, dtype="<f4") for path, vector in rows}

    def set_signals(
        self, path: Path, detector_id: str, detector_version: int, signals: list[Signal]
    ) -> None:
        """Replaces whatever this detector stored for the image, from any version."""
        self.set_signals_of_many(detector_id, detector_version, {path: signals})

    def set_signals_of_many(
        self, detector_id: str, detector_version: int, signals: Mapping[Path, list[Signal]]
    ) -> None:
        """`set_signals` for many images in one transaction, as group detectors need."""
        with self._db:
            for path, image_signals in signals.items():
                exists = self._db.execute(
                    "SELECT 1 FROM images WHERE path = ?", (str(path),)
                ).fetchone()
                _require(exists, path)
                self._db.execute(
                    "DELETE FROM analyses WHERE path = ? AND detector_id = ?",
                    (str(path), detector_id),
                )
                self._db.execute(
                    "INSERT INTO analyses VALUES (?, ?, ?)",
                    (str(path), detector_id, detector_version),
                )
                # A detector that had not run when the image was kept has nothing to be new
                # against: its first result is seen.
                if self._kept_without_detector(path, detector_id):
                    self._db.executemany(
                        "INSERT OR IGNORE INTO kept_signals VALUES (?, ?, ?, ?)",
                        [
                            (str(path), c.value, g, r)
                            for c, g, r in (s.identity for s in image_signals)
                        ],
                    )
                    self._db.execute(
                        "INSERT INTO kept_detectors VALUES (?, ?)", (str(path), detector_id)
                    )
                self._db.executemany(
                    "INSERT INTO signals VALUES (?, ?, ?, ?, ?, ?)",
                    [
                        (
                            str(path),
                            s.detector_id,
                            s.detector_version,
                            s.category.value,
                            s.score,
                            json.dumps(s.evidence),
                        )
                        for s in image_signals
                    ],
                )

    def _kept_without_detector(self, path: Path, detector_id: str) -> bool:
        (found,) = self._db.execute(
            """SELECT EXISTS(SELECT 1 FROM images WHERE path = ?1 AND review_state = 'kept')
               AND NOT EXISTS(SELECT 1 FROM kept_detectors WHERE path = ?1 AND detector_id = ?2)""",
            (str(path), detector_id),
        ).fetchone()
        return bool(found)

    def group_fingerprint(self, detector_id: str, detector_version: int) -> str | None:
        run = self._group_run(detector_id, detector_version)
        return run[0] if run else None

    def group_hash_failed(self, detector_id: str, detector_version: int) -> list[str]:
        """The paths whose content hash failed in the run that stored the fingerprint."""
        return self._group_run(detector_id, detector_version)[1:]

    def set_group_fingerprint(
        self,
        detector_id: str,
        detector_version: int,
        fingerprint: str,
        hash_failed: Iterable[str] = (),
    ) -> None:
        """The paths go in the fingerprint's column, as a JSON array with it, so the schema
        stays as it is."""
        failed = list(hash_failed)
        stored = json.dumps([fingerprint, *failed]) if failed else fingerprint
        with self._db:
            self._db.execute(
                "INSERT OR REPLACE INTO group_runs VALUES (?, ?, ?)",
                (detector_id, detector_version, stored),
            )

    def _group_run(self, detector_id: str, detector_version: int) -> list[str]:
        row = self._db.execute(
            "SELECT fingerprint FROM group_runs WHERE detector_id = ? AND detector_version = ?",
            (detector_id, detector_version),
        ).fetchone()
        if row is None:
            return []
        # A fingerprint is hex, so a value that starts with "[" is the array written above.
        return json.loads(row[0]) if row[0].startswith("[") else [row[0]]

    def is_analyzed(self, path: Path, detector_id: str, detector_version: int) -> bool:
        row = self._db.execute(
            "SELECT 1 FROM analyses WHERE path = ? AND detector_id = ? AND detector_version = ?",
            (str(path), detector_id, detector_version),
        ).fetchone()
        return row is not None

    def signals(self, path: Path) -> list[Signal]:
        rows = self._db.execute(
            """SELECT detector_id, detector_version, category, score, evidence
               FROM signals WHERE path = ?""",
            (str(path),),
        )
        return [
            Signal(detector_id, version, kind(category), score, json.loads(evidence))
            for detector_id, version, category, score, evidence in rows
        ]

    def set_review_state(self, path: Path, state: ReviewState) -> None:
        """Keeping an image also records the signals it has now: only a different one
        brings it back among the candidates (docs/design.md, section 6.3)."""
        with self._db:
            updated = self._db.execute(
                "UPDATE images SET review_state = ? WHERE path = ?", (state.value, str(path))
            )
            _require(updated.rowcount, path)
            self._db.execute("DELETE FROM kept_signals WHERE path = ?", (str(path),))
            self._db.execute("DELETE FROM kept_detectors WHERE path = ?", (str(path),))
            if state is ReviewState.KEPT:
                self._db.execute(
                    """INSERT INTO kept_detectors
                       SELECT path, detector_id FROM analyses WHERE path = ?""",
                    (str(path),),
                )
                self._db.executemany(
                    "INSERT OR IGNORE INTO kept_signals VALUES (?, ?, ?, ?)",
                    [
                        (str(path), category.value, group, role)
                        for category, group, role in (s.identity for s in self.signals(path))
                    ],
                )

    def review_state(self, path: Path) -> ReviewState:
        row = self._db.execute(
            "SELECT review_state FROM images WHERE path = ?", (str(path),)
        ).fetchone()
        _require(row, path)
        return ReviewState(row[0])

    def exclude_folder(self, path: Path) -> None:
        with self._db:
            self._db.execute("INSERT OR IGNORE INTO excluded_folders VALUES (?)", (str(path),))

    def remove_excluded_folder(self, path: Path) -> None:
        with self._db:
            self._db.execute("DELETE FROM excluded_folders WHERE path = ?", (str(path),))

    def excluded_folders(self) -> list[Path]:
        rows = self._db.execute("SELECT path FROM excluded_folders ORDER BY path")
        return [Path(path) for (path,) in rows]

    def indexed_media(self, media_type: MediaType | None = None) -> list[IndexedMedia]:
        """Every image and video with its review state and signals, as the ranker takes them and
        as the app's `indexedMedia` gives them, or only those of `media_type`. The lab's report,
        labeling and evaluation are about images, and ask for those."""
        signals = defaultdict(list)
        for path, *fields, evidence in self._db.execute(
            """SELECT path, detector_id, detector_version, category, score, evidence
               FROM signals ORDER BY rowid"""
        ):
            detector_id, version, category, score = fields
            signals[path].append(
                Signal(detector_id, version, kind(category), score, json.loads(evidence))
            )
        seen = defaultdict(set)
        for path, category, group, role in self._db.execute("SELECT * FROM kept_signals"):
            seen[path].add((kind(category), group, role))
        rows = self._db.execute(
            """SELECT path, size, mtime_ns, review_state, media_type FROM images
               WHERE :type IS NULL OR media_type = :type ORDER BY path""",
            {"type": media_type.value if media_type else None},
        )
        return [
            IndexedMedia(
                _ref(path, size, mtime_ns, type_),
                ReviewState(state),
                tuple(signals[path]),
                frozenset(seen[path]),
            )
            for path, size, mtime_ns, state, type_ in rows
        ]


_VIDEO_COLUMNS = """width, height, duration_ms, frame_rate, bitrate, has_audio, video_codec, hdr,
    captured_at, captured_instant"""


def _video_metadata(row: Sequence) -> VideoMetadata:
    width, height, duration, frame_rate, bitrate, has_audio, codec, hdr, captured_at, instant = row
    captured = datetime.fromisoformat(captured_at) if captured_at else None
    return VideoMetadata(
        width, height, duration, frame_rate, bitrate, bool(has_audio), codec, bool(hdr), captured,
        instant,
    )  # fmt: skip


def _ref(path: str, size: int, mtime_ns: int, media_type: str) -> MediaRef:
    return MediaRef(Path(path), size, mtime_ns, MediaType(media_type))


def _require(found: object, path: Path) -> None:
    if not found:
        raise KeyError(f"image not in the index: {path}")
