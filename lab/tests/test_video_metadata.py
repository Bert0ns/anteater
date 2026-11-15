import json
from datetime import datetime
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import av
import pytest

from cribrum_lab import metadata
from cribrum_lab.metadata import NoVideoTrack, UnreadableVideo, read_video_metadata

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "videos"
EXPECTED = json.loads((FIXTURES / "expected.json").read_text())
ZONE = ZoneInfo(EXPECTED["zone"])


@pytest.mark.parametrize("name", sorted(EXPECTED["videos"]))
def test_reads_the_golden_values(name):
    want = EXPECTED["videos"][name]

    meta = read_video_metadata(FIXTURES / name, ZONE)

    captured_at = want["captured_at"] and datetime.fromisoformat(want["captured_at"])
    assert (meta.width, meta.height) == (want["width"], want["height"])
    assert meta.duration_ms == want["duration_ms"]
    assert meta.frame_rate == want["frame_rate"]
    assert meta.bitrate == want["bitrate"]
    assert meta.has_audio == want["has_audio"]
    assert meta.video_codec == want["video_codec"]
    assert meta.hdr == want["hdr"]
    assert meta.captured_at == captured_at


def test_the_capture_time_is_the_local_time_of_the_zone_at_that_instant():
    # 10:20:30 UTC on 1 May is 12:20:30 in Rome (summer time) and 06:20:30 in New York.
    meta = read_video_metadata(FIXTURES / "clip.mp4", ZoneInfo("America/New_York"))

    assert meta.captured_at == datetime(2024, 5, 1, 6, 20, 30)


@pytest.mark.parametrize("name", EXPECTED["no_video_track"])
def test_a_file_with_only_audio_has_no_video_track(name):
    with pytest.raises(NoVideoTrack):
        read_video_metadata(FIXTURES / name, ZONE)


@pytest.mark.parametrize("name", EXPECTED["unreadable"])
def test_a_header_that_does_not_read_is_unreadable(name):
    with pytest.raises(UnreadableVideo):
        read_video_metadata(FIXTURES / name, ZONE)


def test_the_frame_rate_is_the_frames_over_the_tracks_duration_or_the_declared_rate():
    # A clip that declares 30 and holds 30 frames over 1.001 s, as a Galaxy's variable rate may.
    counted = SimpleNamespace(
        frames=30, duration=1001, time_base=Fraction(1, 1000), average_rate=30
    )
    uncounted = SimpleNamespace(frames=0, duration=None, time_base=None, average_rate=Fraction(25))

    assert metadata._frame_rate(counted) == 29.97
    assert metadata._frame_rate(uncounted) == 25.0


def test_a_clip_whose_first_frame_does_not_decode_is_logged(monkeypatch, caplog):
    class NoFrames:
        def __init__(self, container):
            self._container = container

        def __getattr__(self, name):
            return getattr(self._container, name)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self._container.close()

        def decode(self, *streams):
            raise av.InvalidDataError(1094995529, "no frame")

    real_open = av.open
    monkeypatch.setattr(metadata.av, "open", lambda path: NoFrames(real_open(path)))

    meta = read_video_metadata(FIXTURES / "rotated.mp4", ZONE)

    assert (meta.width, meta.height) == (64, 48)  # the rotation is lost, as the warning says
    assert "rotated.mp4: the first frame did not decode" in caplog.text
