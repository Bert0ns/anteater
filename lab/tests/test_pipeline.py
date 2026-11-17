import dataclasses
import os
import shutil
from pathlib import Path

import av
import numpy as np
import pytest
from PIL import Image

from cribrum_lab import pipeline
from cribrum_lab.detectors import blur
from cribrum_lab.detectors.base import accepted_media_types
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.detectors.burst import BurstDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.faces import FaceDetector
from cribrum_lab.frames import FRAMES_VERSION
from cribrum_lab.imaging import load_grayscale
from cribrum_lab.index import ImageIndex
from cribrum_lab.metadata import ImageMetadata
from cribrum_lab.model import Category, MediaRef, MediaType, Protection, ReviewState, Signal
from cribrum_lab.ranker import Ranker

IMAGE_GROUP_DETECTORS = sum(
    MediaType.IMAGE in accepted_media_types(d) for d in pipeline.GROUP_DETECTORS
)


def save(path, seed=0, size=(64, 48)):
    """A random pattern: flat images would all be near-duplicates of each other."""
    path.parent.mkdir(parents=True, exist_ok=True)
    noise = np.random.default_rng(seed).integers(0, 256, (6, 8, 3), np.uint8)
    Image.fromarray(noise).resize(size).save(path)
    return path


@pytest.fixture
def index(tmp_path):
    with ImageIndex(tmp_path / "index.sqlite") as index:
        yield index


@pytest.fixture
def photos(tmp_path):
    root = tmp_path / "device"
    save(root / "DCIM/Camera/a.png")
    save(root / "WhatsApp/Media/a-copy.png")  # same bytes as a.png
    save(root / "Pictures/Screenshots/Screenshot_1.png", seed=1)
    return root


def categories(index, path):
    return {s.category for s in index.signals(path)}


def test_first_run_stores_metadata_and_signals_of_every_detector(photos, index):
    stats = pipeline.run(photos, index)

    assert stats.images == 3
    assert stats.failures == []
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    assert index.metadata(screenshot).width == 64
    assert categories(index, screenshot) == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    assert {Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE} <= categories(index, copy)


def test_a_walk_longer_than_the_record_batch_records_every_file_and_counts_them(tmp_path, index):
    root = tmp_path / "device"
    files = [save(root / f"DCIM/Camera/{i}.png", seed=i).resolve() for i in range(5)]

    first = pipeline.run(root, index, detectors=[], group_detectors=[], record_batch=2)

    assert first.new_or_changed == 5
    assert {image.path for image in index.images()} == set(files)
    again = pipeline.run(root, index, detectors=[], group_detectors=[], record_batch=2)
    assert again.new_or_changed == 0
    os.utime(files[3], ns=(1, 1))
    touched = pipeline.run(root, index, detectors=[], group_detectors=[], record_batch=2)
    assert touched.new_or_changed == 1


def test_second_run_on_unchanged_data_does_no_detector_work(photos, index):
    # A sticker too: a detector it skips must not be queued, run and forgotten at every scan.
    save(photos / STICKERS / "s.png", seed=3)
    pipeline.run(photos, index)

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 0
    assert stats.detector_runs == 0


def test_a_perceptual_hash_that_failed_once_and_works_now_gets_its_image_grouped_again(
    photos, index, monkeypatch
):
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    real = pipeline.hashing.perceptual_hash

    def flaky(image, pixels=None):
        if image.path == copy:
            raise OSError("busy")
        return real(image, pixels)

    monkeypatch.setattr(pipeline.hashing, "perceptual_hash", flaky)
    stats = pipeline.run(photos, index)
    assert len(stats.failures) == 1
    assert Category.NEAR_DUPLICATE not in categories(index, copy)

    monkeypatch.setattr(pipeline.hashing, "perceptual_hash", real)
    stats = pipeline.run(photos, index)

    assert stats.failures == []
    assert Category.NEAR_DUPLICATE in categories(index, copy)


def test_a_content_hash_that_failed_once_and_works_now_gets_its_image_grouped_again(
    photos, index, monkeypatch
):
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    real = pipeline.hashing.content_hash

    def flaky(image):
        if image.path == copy:
            raise OSError("busy")
        return real(image)

    monkeypatch.setattr(pipeline.hashing, "content_hash", flaky)
    pipeline.run(photos, index)
    assert Category.EXACT_DUPLICATE not in categories(index, copy)

    monkeypatch.setattr(pipeline.hashing, "content_hash", real)
    pipeline.run(photos, index)

    assert Category.EXACT_DUPLICATE in categories(index, copy)


def test_a_content_hash_that_fails_on_every_scan_runs_the_exact_detector_once_until_it_reads(
    photos, index, monkeypatch
):
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    real = pipeline.hashing.content_hash

    def failing(image):
        if image.path == copy:
            raise OSError("busy")
        return real(image)

    monkeypatch.setattr(pipeline.hashing, "content_hash", failing)
    pipeline.run(photos, index)

    assert pipeline.run(photos, index).detector_runs == 0

    monkeypatch.setattr(pipeline.hashing, "content_hash", real)

    assert pipeline.run(photos, index).detector_runs == 1  # the exact detector only
    assert Category.EXACT_DUPLICATE in categories(index, copy)
    assert pipeline.run(photos, index).detector_runs == 0


def test_changed_image_is_analyzed_again_and_groups_are_recomputed(photos, index):
    pipeline.run(photos, index)
    changed = save(photos / "DCIM/Camera/a.png", seed=2)
    os.utime(changed, ns=(1, 1))

    stats = pipeline.run(photos, index)

    assert stats.new_or_changed == 1
    without_embeddings = 5  # blur, exposure, screenshot, faces, sticker: the others need embeddings
    phash = 1
    assert stats.detector_runs == without_embeddings + phash + IMAGE_GROUP_DETECTORS
    copy = photos.resolve() / "WhatsApp/Media/a-copy.png"
    assert Category.EXACT_DUPLICATE not in categories(index, copy)


def test_a_run_remembers_the_files_that_are_not_images_and_forgets_the_ones_that_are_gone(
    photos, index
):
    notes = photos.resolve() / "notes.txt"
    notes.write_bytes(b"not an image")

    pipeline.run(photos, index)

    assert [ref.path for ref in index.not_images()] == [notes]
    assert len(index.images()) == 3

    notes.unlink()
    pipeline.run(photos, index)

    assert index.not_images() == []


def test_removed_image_leaves_the_index(photos, index):
    pipeline.run(photos, index)
    (photos / "DCIM/Camera/a.png").unlink()

    stats = pipeline.run(photos, index)

    assert stats.forgotten == 1
    assert len(index.images()) == 2


def test_a_root_the_scan_did_not_walk_keeps_its_rows(photos, index):
    pipeline.run(photos, index)
    kept = (photos / "DCIM/Camera/a.png").resolve()
    index.set_review_state(kept, ReviewState.KEPT)
    shutil.rmtree(photos)  # the volume is out

    stats = pipeline.run(photos, index)

    assert stats.forgotten == 0
    assert index.review_state(kept) == ReviewState.KEPT
    assert len(index.images()) == 3


def test_a_folder_whose_listing_fails_keeps_its_rows(photos, index):
    pipeline.run(photos, index)
    kept = (photos / "DCIM/Camera/a.png").resolve()
    index.set_review_state(kept, ReviewState.KEPT)
    locked = photos / "DCIM/Camera"
    locked.chmod(0)
    try:
        if os.access(locked, os.R_OK):
            pytest.skip("the folder is still listable (running as root?)")

        stats = pipeline.run(photos, index)
    finally:
        locked.chmod(0o755)

    assert stats.forgotten == 0
    assert index.review_state(kept) == ReviewState.KEPT
    assert len(index.images()) == 3


def test_unreadable_image_gets_no_per_image_analysis(photos, index):
    broken = photos / "Download/broken.jpg"
    broken.parent.mkdir()
    broken.write_bytes(b"\xff\xd8\xff\xe0 not really a jpeg")

    stats = pipeline.run(photos, index)

    assert stats.unreadable == 1
    assert stats.failures == []
    assert index.signals(broken.resolve()) == []


class FailingDetector:
    id = "failing"
    version = 1

    def analyze(self, image, context):
        raise OSError("truncated")


class FixedDetector:
    id = "failing"
    version = 1

    def analyze(self, image, context):
        return [Signal(self.id, self.version, Category.BLURRY, 0.5)]


class Decoding:
    """Logs, for each image, the detectors in the order they ran and the pixels each one got."""

    version = 1
    category = None

    def __init__(self, id, decode_side, log):
        self.id = id
        self.decode_side = decode_side
        self.log = log

    def analyze(self, image, context):
        self.log.setdefault(image.path, []).append((self.id, context.pixels(image)))
        return []


def test_an_images_detectors_and_its_hash_share_one_pixels_from_the_largest_side_down(
    photos, index, monkeypatch
):
    log = {}
    detectors = [Decoding("small", 256, log), Decoding("none", None, log)]
    detectors += [Decoding("large", 640, log), Decoding("middle", 512, log)]

    def hash_logged(image, pixels=None):
        log[image.path].append(("hash", pixels))
        return 0

    monkeypatch.setattr(pipeline.hashing, "perceptual_hash", hash_logged)
    pipeline.run(photos, index, detectors=detectors, group_detectors=[])

    assert len(log) == 3
    for path, steps in log.items():
        assert [name for name, _ in steps] == ["large", "middle", "small", "none", "hash"]
        assert all(pixels is steps[0][1] for _, pixels in steps)
        assert steps[0][1].path == path
    assert len({id(steps[0][1]) for steps in log.values()}) == 3


def test_failed_analysis_is_reported_and_retried_on_the_next_run(photos, index):
    stats = pipeline.run(photos, index, detectors=[FailingDetector()], group_detectors=[])

    assert len(stats.failures) == 3
    assert "OSError: truncated" in stats.failures[0]

    stats = pipeline.run(photos, index, detectors=[FixedDetector()], group_detectors=[])

    assert stats.failures == []
    assert stats.detector_runs == 3


def test_worker_processes_give_the_same_signals(photos, tmp_path, index):
    pipeline.run(photos, index)
    with ImageIndex(tmp_path / "parallel.sqlite") as parallel:
        pipeline.run(photos, parallel, jobs=2)

        assert parallel.indexed_media() == index.indexed_media()


class StoredBlur:
    """Stores a BLURRY signal with the given peak variance for the files it names."""

    id = "blur"
    version = 1
    category = Category.BLURRY

    def __init__(self, peaks, version=1):
        self.peaks = peaks
        self.version = version

    def analyze(self, image, context):
        peak = self.peaks.get(image.path.name)
        if peak is None:
            return []
        return [Signal(self.id, self.version, Category.BLURRY, 0.5, {"peak_variance": peak})]


class SharpnessReader:
    id = "sharpness_reader"
    version = 1
    category = None

    def analyze(self, images, context):
        self.seen = {image.path.name: context.sharpness(image) for image in images}
        return {}


def test_the_sharpness_lookup_reads_what_blur_stored_and_measures_an_image_without_it(
    photos, index
):
    reader = SharpnessReader()
    blur_detector = StoredBlur({"a.png": "12.3", "a-copy.png": "45.6"})

    pipeline.run(photos, index, detectors=[blur_detector], group_detectors=[reader])

    screenshot = photos / "Pictures/Screenshots/Screenshot_1.png"
    measured = blur.measure(load_grayscale(screenshot, blur.MAX_SIDE))[1]
    assert reader.seen == {"a.png": 12.3, "a-copy.png": 45.6, "Screenshot_1.png": measured}


def test_a_new_blur_version_reruns_burst_on_an_unchanged_library(photos, index):
    def run(version):
        detectors = [StoredBlur({}, version)]
        return pipeline.run(photos, index, detectors=detectors, group_detectors=[BurstDetector()])

    run(1)

    assert run(1).detector_runs == 0
    assert run(2).detector_runs == 3 + 1  # blur on each image, then burst


class MeanColorEmbedder:
    """Embeds an image as its mean color, padded to MobileCLIP's 512 values."""

    id = "mean_color"

    def __init__(self, version=1, scale=1.0):
        self.version = version
        self.scale = scale

    def embed_pixels(self, pixels):
        vectors = np.ones((len(pixels), 512))
        vectors[:, :3] = self.scale * pixels.mean(axis=(2, 3))
        return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


class EmbeddingReader:
    """Group detector that records the embeddings it can look up."""

    id = "embedding_reader"
    version = 1

    def __init__(self):
        self.seen = {}

    def analyze(self, images, context):
        self.seen = {image.path.name: context.embedding(image) for image in images}
        return {}


def test_embeddings_are_computed_once_and_reach_group_detectors(photos, index):
    reader = EmbeddingReader()

    first = pipeline.run(photos, index, embedder=MeanColorEmbedder(), group_detectors=[reader])
    second = pipeline.run(photos, index, embedder=MeanColorEmbedder(), group_detectors=[])

    assert first.detector_runs == 3 * len(pipeline.DETECTORS) + 3 * 2 + 1  # + hashes, embeddings
    assert second.detector_runs == 0
    assert set(reader.seen) == {"a.png", "a-copy.png", "Screenshot_1.png"}
    assert len(index.embeddings("mean_color", 1)) == 3


def test_without_an_embedder_the_lookup_raises(photos, index):
    reader = EmbeddingReader()

    with pytest.raises(LookupError):
        pipeline.run(photos, index, group_detectors=[reader])


class EmbeddingDetector:
    id = "embedding_user"
    version = 1
    uses_embeddings = True

    def analyze(self, image, context):
        return [
            Signal(self.id, self.version, Category.DOCUMENT, float(context.embedding(image)[0]))
        ]


def test_detector_needing_embeddings_waits_for_a_run_with_them(photos, index):
    stats = pipeline.run(photos, index, detectors=[EmbeddingDetector()], group_detectors=[])

    assert stats.skipped == 3
    assert stats.failures == []

    stats = pipeline.run(
        photos,
        index,
        detectors=[EmbeddingDetector()],
        group_detectors=[],
        embedder=MeanColorEmbedder(),
    )

    assert stats.skipped == 0
    assert stats.detector_runs == 3 + 3  # embeddings, then the detector


def test_the_next_run_after_deleting_the_embeddings_computes_them_again(photos, index):
    class Reader(EmbeddingDetector):
        uses_embeddings = True

    def run():
        return pipeline.run(
            photos, index, detectors=[Reader()], group_detectors=[], embedder=MeanColorEmbedder()
        )

    run()
    before = index.signals(photos / "DCIM/Camera/a.png")

    index.delete_embeddings()

    assert index.signals(photos / "DCIM/Camera/a.png") == before  # candidates stay until a scan
    assert run().detector_runs == 3 + 3  # embeddings, then the detector
    assert len(index.embeddings("mean_color", 1)) == 3
    assert index.signals(photos / "DCIM/Camera/a.png") == before


def every_signal(index):
    return {
        image.path: sorted(index.signals(image.path), key=lambda s: s.detector_id)
        for image in index.images()
    }


def test_group_detectors_do_not_rerun_with_the_same_vectors_after_deleting_the_embeddings(
    photos, index
):
    pipeline.run(photos, index, embedder=MeanColorEmbedder())
    before = every_signal(index)

    index.delete_embeddings()
    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    readers = sum(getattr(d, "uses_embeddings", False) for d in pipeline.DETECTORS)
    assert stats.detector_runs == 3 + 3 * readers  # embeddings, then document and forwarded
    assert every_signal(index) == before


def test_a_new_embedder_version_reruns_the_embedding_detectors_and_the_group_detectors(
    photos, index
):
    pipeline.run(photos, index, embedder=MeanColorEmbedder())

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder(version=2))

    readers = sum(getattr(d, "uses_embeddings", False) for d in pipeline.DETECTORS)
    assert stats.detector_runs == 3 + 3 * readers + IMAGE_GROUP_DETECTORS


def test_a_detectors_signal_follows_the_embedding_it_read(photos, index):
    class Reader(EmbeddingDetector):
        uses_embeddings = True

    path = photos / "DCIM/Camera/a.png"
    pipeline.run(
        photos, index, detectors=[Reader()], group_detectors=[], embedder=MeanColorEmbedder()
    )
    [before] = index.signals(path)

    pipeline.run(
        photos,
        index,
        detectors=[Reader()],
        group_detectors=[],
        embedder=MeanColorEmbedder(version=2, scale=4.0),
    )

    [after] = index.signals(path)
    assert after.score == pytest.approx(index.embeddings("mean_color", 2)[path][0])
    assert after.score != pytest.approx(before.score)


def test_the_detectors_that_read_no_embedding_store_their_signals_before_the_first_embedding(
    photos, index
):
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    before = []

    class Recording(MeanColorEmbedder):
        def embed_pixels(self, pixels):
            before.append(categories(index, screenshot))
            return super().embed_pixels(pixels)

    pipeline.run(photos, index, embedder=Recording())

    assert before[0] == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }


class UnflaggedEmbeddingDetector:
    id = "unflagged"
    version = 1

    def analyze(self, image, context):
        return [
            Signal(self.id, self.version, Category.DOCUMENT, float(context.embedding(image)[0]))
        ]


def test_a_detector_that_reads_an_embedding_without_saying_so_fails_on_every_run(photos, index):
    def run():
        return pipeline.run(
            photos,
            index,
            detectors=[UnflaggedEmbeddingDetector()],
            group_detectors=[],
            embedder=MeanColorEmbedder(),
        )

    first, second = run(), run()

    assert first.skipped == 0
    assert len(first.failures) == 3
    assert all("without uses_embeddings" in failure for failure in first.failures)
    assert len(second.failures) == 3


def test_a_run_stopped_after_the_fast_detectors_resumes_with_the_embeddings(photos, index):
    class Stopped(MeanColorEmbedder):
        def embed_pixels(self, pixels):
            raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        pipeline.run(photos, index, embedder=Stopped())
    screenshot = photos.resolve() / "Pictures/Screenshots/Screenshot_1.png"
    assert categories(index, screenshot) == {
        Category.BLURRY,
        Category.BAD_EXPOSURE,
        Category.SCREENSHOT,
        Category.STICKER,
        Protection.FACES,
    }

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    readers = 2  # document and forwarded
    assert stats.detector_runs == 3 + 3 * readers + IMAGE_GROUP_DETECTORS


def test_the_steps_run_in_order_the_detectors_that_read_embeddings_last(photos, index):
    steps = []

    def progress(step, done, total):
        if not steps or steps[-1] != step:
            steps.append(step)

    pipeline.run(photos, index, embedder=MeanColorEmbedder(), progress=progress)

    assert steps == [
        "scan",
        "metadata",
        "detectors",
        "embeddings",
        "embedded_detectors",
        "exact_duplicate",
        "near_duplicate",
        "burst",
    ]


STICKERS = "WhatsApp/Media/WhatsApp Stickers"


def test_a_sticker_is_not_embedded_and_the_detectors_reading_embeddings_leave_it_alone(
    photos, index
):
    sticker = save(photos / STICKERS / "s.png", seed=3)

    stats = pipeline.run(photos, index, embedder=MeanColorEmbedder())

    assert stats.failures == []
    assert stats.skipped == 0
    assert len(index.embeddings("mean_color", 1)) == 3
    signals = index.signals(sticker.resolve())
    assert {s.category for s in signals} == {Category.SCREENSHOT, Category.STICKER}
    assert next(s for s in signals if s.category == Category.STICKER).score == 1.0
    assert categories(index, photos.resolve() / "DCIM/Camera/a.png") >= ALL_PER_IMAGE


ALL_PER_IMAGE = {
    Category.BLURRY,
    Category.BAD_EXPOSURE,
    Category.SCREENSHOT,
    Category.STICKER,
    Protection.FACES,
}


class Seeing:
    """Records the images it ran on, under the id of a real detector."""

    version = 1

    def __init__(self, id):
        self.id = id
        self.seen = []

    def analyze(self, image, context):
        self.seen.append(image.path.name)
        return []


def test_a_sticker_runs_only_the_hash_screenshot_and_sticker_and_a_real_image_runs_all(
    tmp_path, index
):
    root = tmp_path / "device"
    save(root / "DCIM/Camera/a.png", seed=1)
    sticker = save(root / STICKERS / "s.png", seed=3)
    detectors = [Seeing(i) for i in ["blur", "exposure", "screenshot", "faces", "sticker"]]

    stats = pipeline.run(root, index, detectors=detectors, group_detectors=[])

    assert stats.failures == []
    assert {d.id: d.seen for d in detectors} == {
        "blur": ["a.png"],
        "exposure": ["a.png"],
        "screenshot": ["a.png", "s.png"],
        "faces": ["a.png"],
        "sticker": ["a.png", "s.png"],
    }
    assert index.perceptual_hash(sticker.resolve()) is not None


def test_a_sticker_scanned_before_the_rules_loses_their_results_and_a_real_image_keeps_them(
    tmp_path, index
):
    root = tmp_path / "device"
    photo = save(root / "DCIM/Camera/a.png", seed=1).resolve()
    sticker = save(root / STICKERS / "s.png", seed=3).resolve()
    pipeline.run(root, index, embedder=MeanColorEmbedder())
    before = index.signals(photo)
    old = [
        d
        for d in pipeline.DETECTORS
        if d.id in pipeline.SKIPPED_ON_STICKERS or getattr(d, "uses_embeddings", False)
    ]
    for d in old:
        kind = d.category or Protection.FACES
        index.set_signals(sticker, d.id, d.version, [Signal(d.id, d.version, kind, 0.9)])

    pipeline.run(root, index, embedder=MeanColorEmbedder())

    assert categories(index, sticker) == {Category.SCREENSHOT, Category.STICKER}
    assert not any(index.is_analyzed(sticker, d.id, d.version) for d in old)
    assert index.signals(photo) == before


def test_a_sticker_with_no_embedding_is_still_in_a_near_duplicate_group_through_its_hash(
    tmp_path, index
):
    root = tmp_path / "device"
    save(root / STICKERS / "s.png", seed=3)
    save(root / STICKERS / "s-copy.png", seed=3)

    pipeline.run(root, index, embedder=MeanColorEmbedder())

    assert Category.NEAR_DUPLICATE in categories(index, root.resolve() / STICKERS / "s-copy.png")


def test_an_embedding_a_sticker_got_from_an_earlier_scan_is_not_used(tmp_path, index):
    root = tmp_path / "device"
    sticker = save(root / STICKERS / "s.png", seed=3)
    pipeline.run(root, index, detectors=[], group_detectors=[])
    index.set_embeddings("mean_color", 1, {sticker.resolve(): np.ones(512)})
    seen = []

    class Probe:
        id = "probe"
        version = 1

        def analyze(self, images, context):
            try:
                context.embedding(images[0])
                seen.append(True)
            except LookupError:
                seen.append(False)
            return {}

    pipeline.run(root, index, detectors=[], group_detectors=[Probe()], embedder=MeanColorEmbedder())

    assert seen == [False]


def stickers_among_photos(root):
    """In index order the stickers fall between the real images, so an order by path alone
    fails the tests below."""
    save(root / "DCIM/Camera/a.png")
    save(root / STICKERS / "s1.png", seed=3)
    save(root / STICKERS / "s2.png", seed=4)
    save(root / "WhatsApp/Media/x.png", seed=5)
    save(root / "Zoo/z.png", seed=6)
    return root


REAL_IMAGES_THEN_STICKERS = ["a.png", "x.png", "z.png", "s1.png", "s2.png"]


def test_the_metadata_step_reads_every_real_image_before_the_stickers(tmp_path, index, monkeypatch):
    root = stickers_among_photos(tmp_path / "device")
    read = []
    real = pipeline.read_metadata

    def recording(path):
        read.append(path.name)
        return real(path)

    monkeypatch.setattr(pipeline, "read_metadata", recording)

    pipeline.run(root, index, detectors=[], group_detectors=[])

    assert read == REAL_IMAGES_THEN_STICKERS


def test_the_detectors_step_analyzes_every_real_image_before_the_stickers(tmp_path, index):
    root = stickers_among_photos(tmp_path / "device")
    seen = []

    class Probe:
        id = "probe"
        version = 1

        def analyze(self, image, context):
            seen.append(image.path.name)
            return []

    pipeline.run(root, index, detectors=[Probe()], group_detectors=[])

    assert seen == REAL_IMAGES_THEN_STICKERS


def test_the_group_detectors_still_get_the_images_in_index_order(tmp_path, index):
    root = stickers_among_photos(tmp_path / "device")
    seen = []

    class Probe:
        id = "probe"
        version = 1

        def analyze(self, images, context):
            seen.extend(image.path.name for image in images)
            return {}

    pipeline.run(root, index, detectors=[], group_detectors=[Probe()])

    assert seen == ["a.png", "s1.png", "s2.png", "x.png", "z.png"]


def stored_results(index):
    return (
        {i.path: index.signals(i.path) for i in index.images()},
        {i.path: index.metadata(i.path) for i in index.images()},
        {i.path: index.perceptual_hash(i.path) for i in index.images()},
        {p: v.tolist() for p, v in index.embeddings("mean_color", 1).items()},
    )


def test_stickers_analyzed_before_the_real_images_leave_the_same_stored_results(tmp_path, index):
    root = stickers_among_photos(tmp_path / "device")
    shutil.copy(root / STICKERS / "s1.png", root / "WhatsApp/Media/s1-copy.png")  # a group across

    # Two scans: the first takes the stickers through every step, so the second reaches the
    # real images after them.
    with ImageIndex(tmp_path / "other.sqlite") as other:
        pipeline.run(root / STICKERS, other, embedder=MeanColorEmbedder())
        pipeline.run(root, other, embedder=MeanColorEmbedder())
        stats = pipeline.run(root, index, embedder=MeanColorEmbedder())

        assert stats.images == 6
        assert stats.failures == []
        assert stored_results(other) == stored_results(index)


def test_only_the_document_and_forwarded_detectors_read_embeddings():
    users = [d.id for d in pipeline.DETECTORS if getattr(d, "uses_embeddings", False)]

    assert users == ["document", "forwarded"]


def test_every_category_has_a_detector():
    detectors = [*pipeline.DETECTORS, *pipeline.GROUP_DETECTORS]

    assert {d.category for d in detectors} - {None} == set(Category)


class Seen:
    """A detector of either kind that records the paths it was given."""

    version = 1
    category = None

    def __init__(self, id, seen, uses_embeddings=False):
        self.id = id
        self.seen = seen
        self.uses_embeddings = uses_embeddings

    def analyze(self, images, context):
        if isinstance(images, MediaRef):
            self.seen.append((self.id, images.path))
            return []
        self.seen += [(self.id, image.path) for image in images]
        return {}


def test_no_image_step_receives_a_video(photos, index, monkeypatch):
    clip = photos.resolve() / "DCIM/Camera/clip.mp4"
    clip.write_bytes(b"\x00\x00\x00\x18ftypisom" + b"\x00" * 12)
    video = MediaRef(clip, clip.stat().st_size, clip.stat().st_mtime_ns, MediaType.VIDEO)
    index.record(video)
    seen = []

    def spying(step, function):
        def spy(image, *args):
            seen.append((step, getattr(image, "path", image)))
            return function(image, *args)

        return spy

    monkeypatch.setattr(pipeline, "read_metadata", spying("metadata", pipeline.read_metadata))
    for name in ("perceptual_hash", "content_hash"):
        monkeypatch.setattr(pipeline.hashing, name, spying(name, getattr(pipeline.hashing, name)))
    preprocess = pipeline.mobileclip.preprocess
    monkeypatch.setattr(pipeline.mobileclip, "preprocess", spying("embedding", preprocess))
    detectors = [Seen("fast", seen), Seen("embedded", seen, uses_embeddings=True)]
    exact = pipeline.ExactDuplicateDetector()  # the one group detector that hashes the files

    stats = pipeline.run(
        photos,
        index,
        detectors=detectors,
        group_detectors=[exact, Seen("group", seen)],
        embedder=MeanColorEmbedder(),
    )

    assert stats.failures == []
    assert stats.images == 3  # the scan log's image count leaves the video out
    assert {step for step, _ in seen} >= {"metadata", "embedding", "fast", "embedded", "group"}
    assert [step for step, path in seen if path == clip] == []
    assert index.images()[1] == video  # found, and kept with its media type


def test_a_run_reports_the_containers_the_walk_turned_away_by_an_unknown_brand(photos, index):
    (photos / "DCIM/Camera/a.mp4").write_bytes(b"\x00\x00\x00\x10ftypdash\x00\x00\x02\x00")

    stats = pipeline.run(photos, index, detectors=[], group_detectors=[])

    assert stats.unknown_brands == {"dash": 1}


VIDEOS = Path(__file__).resolve().parents[2] / "fixtures" / "videos"


@pytest.fixture
def videos(photos):
    """The photos, with the golden clip, an audio-only MP4 and a broken one beside them."""
    for name in ("clip.mp4", "audio.mp4", "broken.mp4"):
        shutil.copy(VIDEOS / name, photos / "DCIM/Camera" / name)
    return photos.resolve() / "DCIM/Camera"


def test_a_normal_run_reads_no_video(videos, index, monkeypatch):
    monkeypatch.setattr(pipeline, "read_video_metadata", lambda *_: pytest.fail("read a video"))

    pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[])

    assert len([i for i in index.images() if i.media_type is MediaType.VIDEO]) == 3
    assert index.video_metadata(videos / "clip.mp4") is None


def test_a_run_asked_for_videos_reads_their_metadata_after_the_images(videos, index):
    steps = []

    stats = pipeline.run(
        videos.parent.parent,
        index,
        detectors=[],
        group_detectors=[],
        videos=True,
        progress=lambda step, done, total: steps.append(step),
    )

    meta = index.video_metadata(videos / "clip.mp4")
    assert (meta.width, meta.height, meta.duration_ms, meta.has_audio) == (64, 48, 1000, True)
    assert steps.index("video_metadata") > max(i for i, s in enumerate(steps) if s == "metadata")
    assert stats.failures == []


def test_a_run_asked_for_both_takes_the_videos_after_every_image_step(videos, index):
    steps = []

    pipeline.run(
        videos.parent.parent,
        index,
        detectors=[],
        group_detectors=[pipeline.ExactDuplicateDetector()],
        videos=True,
        progress=lambda step, done, total: steps.append(step),
    )

    assert steps.index("video_metadata") > steps.index("exact_duplicate")


def test_a_run_for_videos_alone_walks_and_runs_no_image_step(videos, index, monkeypatch):
    monkeypatch.setattr(pipeline, "read_metadata", lambda *_: pytest.fail("read an image"))
    seen = []

    stats = pipeline.run(
        videos.parent.parent,
        index,
        detectors=[Seen("fast", seen)],
        group_detectors=[Seen("group", seen)],
        images=False,
        videos=True,
    )

    assert seen == []
    assert index.video_metadata(videos / "clip.mp4").duration_ms == 1000
    assert (stats.images, stats.videos) == (3, 2)  # the audio-only file left the index
    assert len(index.without_metadata()) == 3  # the images wait for a normal scan


def test_a_video_whose_header_does_not_read_stays_in_the_index_unreadable(videos, index):
    stats = pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[], videos=True)

    assert videos / "broken.mp4" in index.unreadable()
    assert stats.unreadable == 1
    assert index.without_metadata() == []  # not read again until it changes


def test_a_file_with_no_video_track_leaves_the_index_and_is_not_sniffed_again(videos, index):
    audio = videos / "audio.mp4"

    stats = pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[], videos=True)
    again = pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[], videos=True)

    assert audio not in {i.path for i in index.images()}
    assert audio in {f.path for f in index.not_images()}
    assert (stats.no_video_track, stats.forgotten) == (1, 0)  # still on disk, so not gone
    assert again.new_or_changed == 0


def sent_clip(videos):
    sent = videos.parent.parent / "WhatsApp/Media/WhatsApp Video/Sent"
    sent.mkdir(parents=True)
    shutil.copy(videos / "clip.mp4", sent / "VID-1.mp4")
    return sent / "VID-1.mp4"


def test_a_run_for_videos_lists_a_video_in_a_messaging_folder_as_forwarded(videos, index):
    sent = sent_clip(videos)

    stats = pipeline.run(videos.parent.parent, index, videos=True, images=False)

    [signal] = [s for s in index.signals(sent) if s.category is Category.FORWARDED]
    assert signal.evidence == {"messaging_folder": "yes"}
    assert Category.FORWARDED not in categories(index, videos / "clip.mp4")  # the camera's copy
    assert stats.failures == []


def test_a_normal_run_gives_a_video_no_signal_and_a_video_run_does_not_repeat_itself(videos, index):
    sent = sent_clip(videos)

    pipeline.run(videos.parent.parent, index)
    assert Category.FORWARDED not in categories(index, sent)
    pipeline.run(videos.parent.parent, index, videos=True, images=False)
    assert Category.FORWARDED in categories(index, sent)
    again = pipeline.run(videos.parent.parent, index, videos=True, images=False)

    assert again.detector_runs == 0


def duplicate_clip(videos):
    copy = videos / "copy.mp4"
    shutil.copy(videos / "clip.mp4", copy)
    return copy


def exact_groups(index):
    """The paths of each group the exact duplicate detector stored, by the group's id."""
    groups = {}
    for image in index.images():
        for signal in index.signals(image.path):
            if signal.category is Category.EXACT_DUPLICATE:
                groups.setdefault(signal.evidence["group"], set()).add(image.path)
    return list(groups.values())


def test_a_run_for_videos_alone_groups_identical_videos_and_leaves_the_images(videos, index):
    copy = duplicate_clip(videos)
    steps = []

    stats = pipeline.run(
        videos.parent.parent,
        index,
        group_detectors=[pipeline.ExactDuplicateDetector()],
        detectors=[],
        images=False,
        videos=True,
        progress=lambda step, done, total: steps.append(step),
    )

    assert exact_groups(index) == [{videos / "clip.mp4", copy}]
    assert steps.index("video_groups") > steps.index("video_metadata")
    assert stats.failures == []


def test_a_normal_run_hashes_no_video_and_a_video_run_hashes_no_image(videos, index, monkeypatch):
    duplicate_clip(videos)
    hashed = []
    content_hash = pipeline.hashing.content_hash
    monkeypatch.setattr(
        pipeline.hashing, "content_hash", lambda image: hashed.append(image) or content_hash(image)
    )
    exact = [pipeline.ExactDuplicateDetector()]
    root = videos.parent.parent

    pipeline.run(root, index, detectors=[], group_detectors=exact)
    assert {i.media_type for i in hashed} == {MediaType.IMAGE}
    assert exact_groups(index) == [{root / "DCIM/Camera/a.png", root / "WhatsApp/Media/a-copy.png"}]

    hashed.clear()
    pipeline.run(root, index, detectors=[], group_detectors=exact, images=False, videos=True)
    assert {i.media_type for i in hashed} == {MediaType.VIDEO}
    assert len(exact_groups(index)) == 2  # the images' group is still there


def test_the_image_run_and_the_video_run_of_a_group_detector_do_not_rerun_each_other(videos, index):
    duplicate_clip(videos)
    exact = [pipeline.ExactDuplicateDetector()]
    root = videos.parent.parent
    pipeline.run(root, index, detectors=[], group_detectors=exact)
    pipeline.run(root, index, detectors=[], group_detectors=exact, images=False, videos=True)
    steps = []

    for kwargs in ({}, {"images": False, "videos": True}):
        pipeline.run(
            root,
            index,
            detectors=[],
            group_detectors=exact,
            progress=lambda step, done, total: steps.append(step),
            **kwargs,
        )

    # Nothing changed, so neither run has work: both of their steps stay silent.
    assert {"exact_duplicate", "video_groups"}.isdisjoint(steps)


def test_a_new_copy_of_a_video_groups_it_at_the_next_video_run_only(videos, index):
    exact = [pipeline.ExactDuplicateDetector()]
    root = videos.parent.parent
    pipeline.run(root, index, detectors=[], group_detectors=exact, images=False, videos=True)
    assert exact_groups(index) == []

    copy = duplicate_clip(videos)
    pipeline.run(root, index, detectors=[], group_detectors=exact)
    assert [g for g in exact_groups(index) if copy in g] == []
    pipeline.run(root, index, detectors=[], group_detectors=exact, images=False, videos=True)

    assert [g for g in exact_groups(index) if copy in g] == [{videos / "clip.mp4", copy}]


def video_scan(videos, index, embedder=None):
    """A run of the video steps alone with an embedder, as "Scan videos" does."""
    return pipeline.run(
        videos.parent.parent, index, detectors=[], group_detectors=[],
        embedder=embedder or MeanColorEmbedder(), images=False, videos=True,
    )  # fmt: skip


def sampled(index, embedder=None):
    embedder = embedder or MeanColorEmbedder()
    times = index.sampled_times(embedder.id, embedder.version, FRAMES_VERSION)
    return {path.name: listed for path, listed in times.items()}


def test_a_video_run_embeds_the_frames_of_each_readable_video_after_its_metadata(videos, index):
    steps = []
    embedder = MeanColorEmbedder()

    stats = pipeline.run(
        videos.parent.parent,
        index,
        detectors=[],
        group_detectors=[],
        embedder=embedder,
        images=False,
        videos=True,
        progress=lambda step, done, total: steps.append(step),
    )

    # The broken video is unreadable: nothing to sample.
    assert sampled(index) == {"clip.mp4": [300, 400, 500, 600, 700]}
    frames = index.frame_embeddings(embedder.id, embedder.version, FRAMES_VERSION)
    assert len(frames[videos / "clip.mp4"][300]) == 512
    assert steps.index("video_frames") > steps.index("video_metadata")
    assert stats.failures == []


def test_frames_already_embedded_at_the_times_wanted_are_not_read_again(videos, index, monkeypatch):
    video_scan(videos, index)
    monkeypatch.setattr(pipeline, "read_frames", lambda *_: pytest.fail("read the frames again"))

    video_scan(videos, index)


def test_an_hdr_video_keeps_one_frame_a_keyframe_and_is_not_read_again(videos, index, monkeypatch):
    shutil.copy(VIDEOS / "hlg.mp4", videos / "hlg.mp4")  # one keyframe, at its start
    video_scan(videos, index)
    monkeypatch.setattr(pipeline, "read_frames", lambda *_: pytest.fail("read the frames again"))

    video_scan(videos, index)

    assert sampled(index)["hlg.mp4"] == [300]


def test_frames_stored_at_other_times_are_sampled_again(videos, index):
    embedder = MeanColorEmbedder()
    pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[], videos=True)
    clip = videos / "clip.mp4"
    index.set_frame_embeddings(
        clip, embedder.id, embedder.version, FRAMES_VERSION, {0: np.ones(512)}
    )

    video_scan(videos, index, embedder)

    assert sampled(index, embedder) == {"clip.mp4": [300, 400, 500, 600, 700]}


def test_frames_stored_at_another_frame_version_are_sampled_again(videos, index, monkeypatch):
    video_scan(videos, index)
    monkeypatch.setattr(pipeline, "FRAMES_VERSION", FRAMES_VERSION + 1)

    video_scan(videos, index)

    embedder = MeanColorEmbedder()
    assert index.sampled_times(embedder.id, embedder.version, FRAMES_VERSION) == {}
    assert list(index.sampled_times(embedder.id, embedder.version, FRAMES_VERSION + 1)) == [
        videos / "clip.mp4"
    ]


FRAME_DETECTORS = [BlurDetector(), ExposureDetector(), FaceDetector()]


def signals_of(index, path):
    return {s.detector_id: s for s in index.signals(path)}


def test_a_video_run_measures_the_frames_it_embeds_and_runs_the_detectors_that_read_them(
    videos, index
):
    pipeline.run(
        videos.parent.parent, index, detectors=FRAME_DETECTORS, group_detectors=[],
        embedder=MeanColorEmbedder(), images=False, videos=True,
    )  # fmt: skip

    assert len(index.frame_measures(FRAMES_VERSION)[videos / "clip.mp4"]) == 5
    assert set(signals_of(index, videos / "clip.mp4")) == {"blur", "exposure", "faces"}
    assert index.signals(videos / "broken.mp4") == []  # unreadable: no measures, no result


def test_a_video_run_without_an_embedder_has_no_frame_to_analyze_and_stores_nothing(videos, index):
    stats = pipeline.run(
        videos.parent.parent, index, detectors=FRAME_DETECTORS, group_detectors=[],
        images=False, videos=True,
    )  # fmt: skip

    assert stats.failures == []
    assert index.signals(videos / "clip.mp4") == []
    assert not index.is_analyzed(videos / "clip.mp4", "blur", BlurDetector.version)


def test_frames_sampled_again_make_the_detectors_that_read_them_run_again(
    videos, index, monkeypatch
):
    def scan():
        return pipeline.run(
            videos.parent.parent, index, detectors=FRAME_DETECTORS, group_detectors=[],
            embedder=MeanColorEmbedder(), images=False, videos=True,
        )  # fmt: skip

    scan()
    assert scan().detector_runs == 0
    monkeypatch.setattr(pipeline, "FRAMES_VERSION", FRAMES_VERSION + 1)
    stats = scan()

    assert stats.detector_runs == 5 + 3  # the five frames, and the three detectors again


def test_a_video_whose_frames_fail_is_a_failure_and_is_tried_again(videos, index, monkeypatch):
    def fail(*_):
        raise MemoryError("the decode ran out of memory")

    monkeypatch.setattr(pipeline, "read_frames", fail)
    stats = video_scan(videos, index)
    monkeypatch.undo()
    video_scan(videos, index)

    assert [f.split(": ")[1] for f in stats.failures] == ["video_frames"]
    assert list(sampled(index)) == ["clip.mp4"]


def test_a_video_whose_frames_do_not_read_is_not_decoded_again_until_it_changes(
    videos, index, monkeypatch
):
    def unreadable(path, _):
        raise av.error.InvalidDataError(0, "no frame", str(path))

    monkeypatch.setattr(pipeline, "read_frames", unreadable)
    stats = video_scan(videos, index)
    monkeypatch.setattr(pipeline, "read_frames", lambda *_: pytest.fail("decoded it again"))
    again = video_scan(videos, index)
    monkeypatch.undo()
    clip = videos / "clip.mp4"
    clip.write_bytes(clip.read_bytes())  # a new mtime
    os.utime(clip, ns=(0, clip.stat().st_mtime_ns + 1_000_000_000))
    video_scan(videos, index)

    # Like an unreadable image, it is counted and not a failure, since the next scan cannot help.
    assert (stats.failures, stats.unreadable_frames) == ([], 1)
    assert (again.failures, again.unreadable_frames) == ([], 0)
    assert list(sampled(index)) == ["clip.mp4"]


def test_a_video_run_without_an_embedder_embeds_no_frame(videos, index, monkeypatch):
    monkeypatch.setattr(pipeline, "read_frames", lambda *_: pytest.fail("read a frame"))

    pipeline.run(videos.parent.parent, index, detectors=[], group_detectors=[], videos=True)


def test_the_frames_come_before_the_video_group_detectors(videos, index):
    duplicate_clip(videos)
    steps = []

    pipeline.run(
        videos.parent.parent,
        index,
        detectors=[],
        group_detectors=[pipeline.ExactDuplicateDetector()],
        embedder=MeanColorEmbedder(),
        images=False,
        videos=True,
        progress=lambda step, done, total: steps.append(step),
    )

    # A video group detector may read the frames (#396).
    assert steps.index("video_metadata") < steps.index("video_frames") < steps.index("video_groups")


def takes_groups(index):
    groups = {}
    for image in index.images():
        for signal in index.signals(image.path):
            if signal.category is Category.TAKES:
                groups.setdefault(signal.evidence["group"], set()).add(image.path.name)
    return list(groups.values())


def test_a_video_run_chains_the_takes_of_a_moment_from_the_stored_metadata(videos, index):
    # Five seconds after clip.mp4 starts, and four after it ends.
    shutil.copy(VIDEOS / "rotated.mp4", videos / "rotated.mp4")

    pipeline.run(
        videos.parent.parent, index, detectors=[], group_detectors=[pipeline.TakesDetector()],
        embedder=MeanColorEmbedder(), images=False, videos=True,
    )  # fmt: skip

    assert takes_groups(index) == [{"clip.mp4", "rotated.mp4"}]


def test_the_takes_read_the_stored_frames_and_frames_stored_later_run_them_again(videos, index):
    shutil.copy(VIDEOS / "rotated.mp4", videos / "rotated.mp4")
    seen = []

    class Frames:
        id, version, category = "frames_seen", 1, None
        media_types = frozenset({MediaType.VIDEO})

        def analyze(self, videos, context):
            seen.append({v.path.name: len(context.frames(v)) for v in videos})
            return {}

    def run(embedder):
        pipeline.run(
            videos.parent.parent, index, detectors=[], group_detectors=[Frames()],
            embedder=embedder, images=False, videos=True,
        )  # fmt: skip

    run(None)
    run(MeanColorEmbedder())
    run(MeanColorEmbedder())  # nothing new: no run

    assert [s["clip.mp4"] for s in seen] == [0, 5]


def test_takes_kept_after_their_first_take_went_to_the_trash_do_not_come_back(videos, index):
    shutil.copy(VIDEOS / "rotated.mp4", videos / "rotated.mp4")
    shutil.copy(VIDEOS / "clip.mp4", videos / "clip-again.mp4")  # the same time as clip.mp4
    root = videos.parent.parent

    def scan():
        pipeline.run(
            root, index, detectors=[], group_detectors=[pipeline.TakesDetector()],
            images=False, videos=True,
        )  # fmt: skip

    scan()
    first = videos / "clip-again.mp4"  # the first by instant, then by path
    assert takes_groups(index) == [{"clip-again.mp4", "clip.mp4", "rotated.mp4"}]
    index.set_review_state(first, ReviewState.TRASHED)
    first.rename(root.parent / "trashed.mp4")
    for name in ("clip.mp4", "rotated.mp4"):
        index.set_review_state(videos / name, ReviewState.KEPT)
    scan()

    assert takes_groups(index) == [{"clip.mp4", "rotated.mp4"}]  # under another group id
    ranked = Ranker().rank(index.indexed_media())
    assert [c for c in ranked if c.is_candidate] == []


def test_a_video_run_links_a_sent_copy_to_its_original_once_both_have_frames(videos, index):
    sent = videos.parent.parent / "WhatsApp/Media/WhatsApp Video/Sent"
    sent.mkdir(parents=True)
    shutil.copy(VIDEOS / "rotated.mp4", sent / "copy.mp4")  # as long as clip.mp4
    near = [pipeline.NearDuplicateDetector()]

    def roles(embedder):
        pipeline.run(
            videos.parent.parent, index, detectors=[], group_detectors=near,
            embedder=embedder, images=False, videos=True,
        )  # fmt: skip
        return {
            image.path.name: signal.evidence["role"]
            for image in index.images()
            for signal in index.signals(image.path)
            if signal.category is Category.NEAR_DUPLICATE and image.media_type is MediaType.VIDEO
        }

    assert roles(None) == {}  # no frames, no link
    assert roles(MeanColorEmbedder()) == {"clip.mp4": "best", "copy.mp4": "inferior"}


def test_a_capture_time_read_again_runs_the_takes_again(videos, index):
    shutil.copy(VIDEOS / "rotated.mp4", videos / "rotated.mp4")
    root = videos.parent.parent

    def scan():
        steps = []
        pipeline.run(
            root, index, detectors=[], group_detectors=[pipeline.TakesDetector()],
            images=False, videos=True, progress=lambda step, done, total: steps.append(step),
        )  # fmt: skip
        return steps

    scan()
    # As an older reader would have stored it: no capture time, and no file changed.
    stored = index.video_metadata(videos / "rotated.mp4")
    index.set_video_metadata(
        videos / "rotated.mp4", dataclasses.replace(stored, captured_at=None, captured_instant=None)
    )

    assert "video_groups" in scan()
    assert takes_groups(index) == []


def test_a_photo_stored_beside_a_clip_runs_the_short_clips_again(videos, index):
    root = videos.parent.parent

    def evidence():
        pipeline.run(
            root, index, detectors=[], group_detectors=[pipeline.ShortClipsDetector()],
            images=False, videos=True,
        )  # fmt: skip
        [signal] = index.signals(videos / "clip.mp4")
        assert signal.category is Category.SHORT_CLIPS
        return signal.evidence

    assert "photo_chance" not in evidence()
    clip = index.video_metadata(videos / "clip.mp4")
    index.set_metadata(
        videos / "a.png", ImageMetadata(64, 48, "image/png", captured_at=clip.captured_at)
    )

    assert "photo_chance" in evidence()
