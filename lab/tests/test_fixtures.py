import json

import av
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

    assert FRAMES["videos"].keys() == videos.keys()
    for name, entry in FRAMES["videos"].items():
        times = frame_times(videos[name]["duration_ms"])
        if entry["hdr"]:  # one frame a keyframe, under the first time that took it
            assert entry["times_ms"] == sorted(set(entry["times_ms"]) & set(times)), name
        else:
            assert entry["times_ms"] == times, name


def test_the_hdr_fixtures_give_their_only_keyframe_once():
    for name in ("hdr.mp4", "hlg.mp4"):
        assert FRAMES["videos"][name]["times_ms"] == [300], name


@pytest.mark.skipif(not HAS_MODEL, reason="MobileCLIP files not downloaded into lab/data/models")
def test_lab_still_embeds_the_frames_of_the_fixture_videos_as_they_record():
    actual = fixtures.frames_expected()["videos"]

    for name, entry in FRAMES["videos"].items():
        for got, want in zip(actual[name]["embeddings"], entry["embeddings"], strict=True):
            assert fixtures.decode_vector(got) @ fixtures.decode_vector(want) > 0.99999, name
        assert actual[name]["mean_rgb"] == entry["mean_rgb"], name
        assert actual[name]["clipped"] == entry["clipped"], name


def test_the_ramp_s_frames_are_the_ones_its_times_point_to():
    # Frame i of ramp.mp4 is at 100 * i ms with a luma of 16 + 5 * i, which BT.601 in limited range
    # reads as a gray of 1.164 * 5 * i. A keyframe instead would be frame 0, 10, 20, 20 or 30.
    ramp = FRAMES["videos"]["ramp.mp4"]

    for time_ms, mean_rgb in zip(ramp["times_ms"], ramp["mean_rgb"], strict=True):
        gray = 1.164 * 5 * round(time_ms / 100)
        assert max(abs(channel - gray) for channel in mean_rgb) <= 1.5, time_ms


def test_a_missing_tone_map_moves_the_mean_of_an_hlg_frame_past_the_tolerance():
    # What a phone that hands over the HLG signal as it is would show.
    hlg = FRAMES["videos"]["hlg.mp4"]
    with av.open(str(fixtures.VIDEOS / "hlg.mp4")) as container:
        raw = next(container.decode(video=0)).to_ndarray(format="rgb24").reshape(-1, 3).mean(axis=0)

    gap = max(abs(a - b) for a, b in zip(raw, hlg["mean_rgb"][0], strict=True))
    assert gap > 3 * FRAMES["tolerance"]["mean_rgb"]


def test_the_takes_fixture_still_matches_the_lab():
    expected = json.loads(fixtures.TAKES.read_text())

    assert fixtures.takes_expected() == expected
    roles = {s[0]["evidence"]["role"] for s in expected["signals"].values()}
    assert roles == {"take", "short_take"}


def test_the_near_duplicates_fixture_still_matches_the_lab():
    expected = json.loads(fixtures.NEAR_DUPLICATES.read_text())

    assert fixtures.near_duplicates_expected() == expected
    roles = {s[0]["evidence"]["role"] for s in expected["signals"].values()}
    assert roles == {"best", "inferior"}


def test_the_frame_detectors_fixture_still_matches_the_lab():
    expected = json.loads(fixtures.FRAME_DETECTORS.read_text())

    assert fixtures.frame_detectors_expected() == expected
    # It covers a video blurry, dark and protected, and one with no frame to read.
    scores = {
        path: {s["detector"]: s["score"] for s in found}
        for path, found in expected["signals"].items()
    }
    assert scores["blurred.mp4"]["blur"] > 0.5 > scores["one-blurred-frame.mp4"]["blur"]
    assert scores["dark.mp4"]["exposure"] == 1.0
    assert scores["faces.mp4"]["faces"] > 0
    assert scores["no-frames.mp4"] == {}
    # One frame is its own median: the video scores as that frame does.
    assert scores["one-frame.mp4"]["blur"] == pytest.approx(0.8)
    assert expected["signals"]["one-frame.mp4"][0]["evidence"]["frames"] == "1"


def test_the_short_clips_fixture_still_matches_the_lab():
    expected = json.loads(fixtures.SHORT_CLIPS.read_text())

    assert fixtures.short_clips_expected() == expected
    # Every piece of evidence shows in some clip, and the clips that get none have no signal.
    pieces = {
        key.removesuffix("_chance")
        for found in expected["signals"].values()
        for key in found[0]["evidence"]
        if key.endswith("_chance")
    }
    assert pieces == {"duration", "photo", "failed_take", "mistake", "gif"}
    assert "DCIM/Camera/at-the-limit.mp4" not in expected["signals"]
