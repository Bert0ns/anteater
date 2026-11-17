"""Study a video's size in the review order (issue 397, docs/design.md 6.6), on the lab's labels.

Run from lab/:

    uv run python scripts/study_video_size_order.py --index data/index.sqlite

The index and the labels are opened read-only. The index may hold no video signal yet, so the
video detectors that read no frame run in memory on its videos, with the metadata it stored, and
nothing is written back. The report prints aggregate numbers only, no paths.

It compares the GB of labeled deletes found in the first N reviews of each video category, with
the order `Ranker.by_category` gives today and with the size left out, against a random order on
average and the best order possible, and the chance that a labeled delete comes before a labeled
keep. It also orders every labeled video by size, as if every one were flagged.
"""

import argparse
import dataclasses
import sqlite3
from collections.abc import Mapping, Sequence
from pathlib import Path

from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.detectors.exact_duplicate import ExactDuplicateDetector
from cribrum_lab.detectors.forwarded import ForwardedDetector
from cribrum_lab.detectors.takes import TakesDetector
from cribrum_lab.index import ImageIndex
from cribrum_lab.labels import Decision, LabelStore
from cribrum_lab.model import IndexedMedia, MediaType, Signal
from cribrum_lab.ranker import Ranker

DATA = Path(__file__).resolve().parents[1] / "data"
FIRST = (1, 5, 10, 20)
"""Numbers of reviews the report gives the GB found in."""

Order = Sequence[tuple[Path, int]]
"""Paths with their size in bytes, in review order."""


def _read_only(cls: type, path: Path):
    """An `ImageIndex` or a `LabelStore` on a read-only connection: their constructors write."""
    store = cls.__new__(cls)
    store._db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    return store


def video_signals(
    videos: Sequence[IndexedMedia], context: AnalysisContext
) -> dict[Path, list[Signal]]:
    """The signals of the video detectors that read no frame, as a scan would store them."""
    refs = [video.ref for video in videos]
    forwarded = ForwardedDetector()
    signals = {ref.path: list(forwarded.analyze(ref, context)) for ref in refs}
    for detector in (TakesDetector(), ExactDuplicateDetector()):
        for ref, found in detector.analyze(refs, context).items():
            signals[ref.path] += found
    return signals


def found_in_first(order: Order, deletes: set[Path], n: int) -> int:
    """Bytes of labeled deletes among the first `n` reviews."""
    return sum(size for path, size in order[:n] if path in deletes)


def deletes_first(order: Order, decisions: Mapping[Path, Decision]) -> float | None:
    """Chance that a labeled delete comes before a labeled keep. None without both."""
    labeled = [decisions[path] for path, _ in order if path in decisions]
    before = keeps_after = 0
    for decision in reversed(labeled):
        if decision is Decision.KEEP:
            keeps_after += 1
        else:
            before += keeps_after
    deletes = labeled.count(Decision.DELETE)
    pairs = deletes * (len(labeled) - deletes)
    return before / pairs if pairs else None


def format_order(name: str, order: Order, decisions: Mapping[Path, Decision]) -> list[str]:
    deletes = {path for path, decision in decisions.items() if decision is Decision.DELETE}
    total = found_in_first(order, deletes, len(order))
    best = sorted(((p, s) for p, s in order if p in deletes), key=lambda x: -x[1])
    keeps = [decisions.get(path) is Decision.KEEP for path, _ in order]
    chance = deletes_first(order, decisions)
    lines = [
        f"{name}: {len(order)} listed, {_gb(total)} GB of labeled deletes, {sum(keeps)} keeps,"
        f" delete before keep {'n/a' if chance is None else f'{chance:.2f}'}",
        "  N  found  random  best  keeps shown",
    ]
    for n in FIRST:
        if n <= len(order):
            random = total * n / len(order)
            lines.append(
                f"  {n:<2} {_gb(found_in_first(order, deletes, n))}  {_gb(random)}"
                f"  {_gb(found_in_first(best, deletes, n))}  {sum(keeps[:n])}"
            )
    return lines


def report(index_path: Path, labels_path: Path) -> str:
    index = _read_only(ImageIndex, index_path)
    decisions = {
        label.path: label.decision
        for label in _read_only(LabelStore, labels_path).all()
        if label.media_type is MediaType.VIDEO
    }
    videos = index.indexed_media(MediaType.VIDEO)
    metadata = index.all_video_metadata()
    signals = video_signals(videos, AnalysisContext(video_metadata=lambda v: metadata[v.path]))
    videos = [dataclasses.replace(v, signals=tuple(signals[v.ref.path])) for v in videos]
    # As images, the videos' size plays no part: the order before #397.
    as_images = [
        dataclasses.replace(v, ref=dataclasses.replace(v.ref, media_type=MediaType.IMAGE))
        for v in videos
    ]
    lines = [f"{len(videos)} videos, {len(decisions)} labeled"]
    by_size = Ranker.by_category(Ranker().rank(videos))
    without = Ranker.by_category(Ranker().rank(as_images))
    for category, listed in by_size.items():
        for name, candidates in (("by size", listed), ("without size", without[category])):
            order = [(c.image.ref.path, c.image.ref.size) for c in candidates]
            lines += format_order(f"{category.value}, {name}", order, decisions)
    labeled = sorted(
        ((v.ref.path, v.ref.size) for v in videos if v.ref.path in decisions), key=lambda x: -x[1]
    )
    lines += format_order("every labeled video, by size", labeled, decisions)
    return "\n".join(lines)


def _gb(size: float) -> str:
    return f"{size / 1e9:.3f}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=Path, default=DATA / "index.sqlite")
    parser.add_argument("--labels", type=Path, default=DATA / "labels.sqlite")
    args = parser.parse_args()
    print(report(args.index, args.labels))


if __name__ == "__main__":
    main()
