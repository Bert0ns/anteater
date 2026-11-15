import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

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
