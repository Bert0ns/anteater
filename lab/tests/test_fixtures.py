import json

import pytest

from cribrum_lab import fixtures, mobileclip
from cribrum_lab.frames import frame_times

EXPECTED = json.loads((fixtures.ROOT / "expected.json").read_text())
HAS_MODEL = all((mobileclip.MODELS / name).exists() for name in mobileclip._FILES)
WITHOUT_EMBEDDINGS = {"blur", "exposure", "screenshot", "faces", "sticker", "exact_duplicate"}
"""Detectors whose output does not depend on embeddings, so CI can check them."""


def signals(images, detectors=None):
    return {
        path: [s for s in entry["signals"] if detectors is None or s["detector"] in detectors]
        for path, entry in images.items()
    }


def assert_close(actual, expected):
    assert actual.keys() == expected.keys()
    for path in expected:
        assert [
            (s["detector"], s["category"], s["evidence"].get("role")) for s in actual[path]
        ] == [(s["detector"], s["category"], s["evidence"].get("role")) for s in expected[path]], (
            path
        )
        for a, e in zip(actual[path], expected[path], strict=True):
            assert a["score"] == pytest.approx(e["score"], abs=1e-3), (path, e["detector"])


def test_fixtures_are_synthetic_and_cover_every_detector():
    detectors = {s["detector"] for e in EXPECTED["images"].values() for s in e["signals"]}

    assert detectors == {
        "blur", "exposure", "screenshot", "document", "forwarded", "faces", "sticker",
        "exact_duplicate", "near_duplicate", "burst",
    }  # fmt: skip


def test_lab_still_matches_the_fixtures_without_embeddings():
    actual = fixtures.expected(fixtures.ROOT, embed=False)["images"]

    for path, entry in EXPECTED["images"].items():
        assert actual[path]["content_hash"] == entry["content_hash"]
        assert actual[path]["perceptual_hash"] == entry["perceptual_hash"]
        assert (actual[path]["width"], actual[path]["height"]) == (entry["width"], entry["height"])
    assert_close(
        signals(actual, WITHOUT_EMBEDDINGS), signals(EXPECTED["images"], WITHOUT_EMBEDDINGS)
    )


@pytest.mark.skipif(not HAS_MODEL, reason="MobileCLIP files not downloaded into lab/data/models")
def test_lab_still_matches_every_fixture_signal():
    actual = fixtures.expected(fixtures.ROOT, embed=True)["images"]

    assert_close(signals(actual), signals(EXPECTED["images"]))


FRAMES = json.loads((fixtures.VIDEOS / "frames.json").read_text())


def test_the_frame_fixtures_cover_every_video_the_lab_reads_at_the_times_of_the_rule():
    videos = json.loads((fixtures.VIDEOS / "expected.json").read_text())["videos"]

    assert {name: entry["times_ms"] for name, entry in FRAMES["videos"].items()} == {
        name: frame_times(video["duration_ms"]) for name, video in videos.items()
    }


@pytest.mark.skipif(not HAS_MODEL, reason="MobileCLIP files not downloaded into lab/data/models")
def test_lab_still_embeds_the_frames_of_the_fixture_videos_as_they_record():
    actual = fixtures.frames_expected()["videos"]

    for name, entry in FRAMES["videos"].items():
        for got, want in zip(actual[name]["embeddings"], entry["embeddings"], strict=True):
            assert fixtures.decode_vector(got) @ fixtures.decode_vector(want) > 0.99999, name
