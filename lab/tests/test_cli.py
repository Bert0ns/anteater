import shutil
from datetime import datetime
from pathlib import Path

from PIL import Image

from cribrum_lab.cli import main
from cribrum_lab.index import ImageIndex
from cribrum_lab.labels import Decision, Label, LabelStore
from cribrum_lab.model import MediaType

VIDEOS = Path(__file__).resolve().parents[2] / "fixtures/videos"


def test_scan_then_report_lists_candidates_per_category(tmp_path, capsys):
    folder = tmp_path / "device/Pictures/Screenshots"
    folder.mkdir(parents=True)
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    index = str(tmp_path / "index.sqlite")

    main(["--index", index, "scan", str(tmp_path / "device"), "--jobs", "1", "--no-embeddings"])
    main(["--index", index, "report"])

    out = capsys.readouterr().out
    assert "1 images, 1 to review, 1 candidates" in out
    assert "screenshot: 1" in out
    assert "Screenshot_1.png" in out


def test_evaluate_prints_recall(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    index, labels = str(tmp_path / "index.sqlite"), tmp_path / "labels.sqlite"
    main(["--index", index, "scan", str(folder), "--jobs", "1", "--no-embeddings"])
    with LabelStore(labels) as store:
        path = (folder / "Screenshot_1.png").resolve()
        store.set(Label("h", path, Decision.DELETE))

    main(["--index", index, "evaluate", "--labels", str(labels), "--out", str(tmp_path / "r.txt")])

    assert "recall: 100% (1/1)" in capsys.readouterr().out
    assert "recall: 100%" in (tmp_path / "r.txt").read_text()


def test_evaluate_reports_the_videos_apart_from_the_images(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    shutil.copy(VIDEOS / "clip.mp4", folder / "clip.mp4")
    index, labels = str(tmp_path / "index.sqlite"), tmp_path / "labels.sqlite"
    main(["--index", index, "scan", str(folder), "--jobs", "1", "--no-embeddings"])
    with LabelStore(labels) as store:
        store.set(Label("h1", (folder / "Screenshot_1.png").resolve(), Decision.DELETE))
        clip = (folder / "clip.mp4").resolve()
        store.set(Label("h2", clip, Decision.DELETE, media_type=MediaType.VIDEO))

    main(["--index", index, "evaluate", "--labels", str(labels)])

    images, videos = capsys.readouterr().out.split("\nvideos\n")
    assert "1 labels (0 no longer in the index), 1 labeled delete" in images
    assert "recall: 100% (1/1)" in images
    assert "1 labels (0 no longer in the index), 1 labeled delete" in videos
    assert "recall: 0% (0/1)" in videos  # no video detector yet


def test_a_labeled_video_gone_from_the_index_stays_in_the_video_report(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    Image.new("RGB", (1080, 2400), "white").save(folder / "Screenshot_1.png")
    index, labels = str(tmp_path / "index.sqlite"), tmp_path / "labels.sqlite"
    main(["--index", index, "scan", str(folder), "--jobs", "1", "--no-embeddings"])
    with LabelStore(labels) as store:
        store.set(Label("h1", (folder / "Screenshot_1.png").resolve(), Decision.DELETE))
        store.set(Label("h2", folder / "gone.mp4", Decision.DELETE, media_type=MediaType.VIDEO))

    main(["--index", index, "evaluate", "--labels", str(labels)])

    images, videos = capsys.readouterr().out.split("\nvideos\n")
    assert "1 labels (0 no longer in the index)" in images
    assert "1 labels (1 no longer in the index)" in videos


def test_scan_prints_the_brands_the_walk_did_not_know(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    for name in ("a.mp4", "b.mp4"):
        (folder / name).write_bytes(b"\x00\x00\x00\x10ftypdash\x00\x00\x02\x00")
    index = str(tmp_path / "index.sqlite")

    main(["--index", index, "scan", str(folder), "--jobs", "1", "--no-embeddings"])

    assert "unknown containers: 2 ('dash' 2)" in capsys.readouterr().err


def test_scan_reads_the_videos_only_with_the_flag(tmp_path):
    folder = tmp_path / "device"
    folder.mkdir()
    clip = folder / "clip.mp4"
    shutil.copy(Path(__file__).resolve().parents[2] / "fixtures/videos/clip.mp4", clip)
    index = tmp_path / "index.sqlite"
    scan = ["--index", str(index), "scan", str(folder), "--jobs", "1", "--no-embeddings"]

    main(scan)
    with ImageIndex(index) as stored:
        assert stored.video_metadata(clip.resolve()) is None
    main([*scan, "--videos"])

    with ImageIndex(index) as stored:
        assert stored.video_metadata(clip.resolve()).duration_ms == 1000


def test_scan_with_only_videos_reads_the_videos_and_no_image(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    clip = folder / "clip.mp4"
    shutil.copy(Path(__file__).resolve().parents[2] / "fixtures/videos/clip.mp4", clip)
    Image.new("RGB", (64, 48)).save(folder / "a.png")
    index = tmp_path / "index.sqlite"

    scan = ["--index", str(index), "scan", str(folder), "--jobs", "1", "--no-embeddings"]

    main([*scan, "--only-videos"])

    with ImageIndex(index) as stored:
        assert stored.video_metadata(clip.resolve()).duration_ms == 1000
        assert stored.metadata(folder.resolve() / "a.png") is None
    assert "1 images, 1 videos" in capsys.readouterr().err


def test_scan_reads_the_capture_times_in_the_zone_it_is_given_and_warns_about_utc(tmp_path, capsys):
    folder = tmp_path / "device"
    folder.mkdir()
    clip = folder / "clip.mp4"
    shutil.copy(Path(__file__).resolve().parents[2] / "fixtures/videos/clip.mp4", clip)
    index = tmp_path / "index.sqlite"
    scan = ["--index", str(index), "scan", str(folder), "--jobs", "1", "--no-embeddings"]

    main([*scan, "--videos", "--zone", "UTC"])

    with ImageIndex(index) as stored:
        assert stored.video_metadata(clip.resolve()).captured_at == datetime(2024, 5, 1, 10, 20, 30)
    assert "warning: capture times go to UTC" in capsys.readouterr().err
