from datetime import datetime, timedelta
from pathlib import Path

import numpy as np

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.takes import TAKES_MAX_GAP, TakesDetector
from cribrum_lab.metadata import VideoMetadata
from cribrum_lab.model import Category, MediaRef, MediaType

START = datetime(2024, 5, 1, 10, 0, 0)


class Clips:
    """Videos with chosen capture times, lengths and frames, and no file behind them."""

    def __init__(self):
        self.meta = {}
        self.frames = {}

    def add(self, name, seconds, length_s=10, folder="DCIM/Camera", frames=None):
        video = MediaRef(Path(f"/phone/{folder}/{name}.mp4"), 1000, 0, MediaType.VIDEO)
        captured = START + timedelta(seconds=seconds) if seconds is not None else None
        duration = None if length_s is None else int(length_s * 1000)
        self.meta[video] = VideoMetadata(64, 48, duration, 30, None, True, None, False, captured)
        if frames is not None:
            self.frames[video] = [np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in frames]
        return video

    def run(self):
        context = AnalysisContext(
            video_metadata=self.meta.__getitem__, frames=lambda v: self.frames.get(v, [])
        )
        return TakesDetector().analyze(list(self.meta), context)


def groups(signals):
    by_group = {}
    for video, found in signals.items():
        by_group.setdefault(found[0].evidence[GROUP_KEY], set()).add(video)
    return sorted(by_group.values(), key=len)


def test_a_clip_that_starts_within_the_gap_of_the_end_of_the_last_one_joins_its_takes():
    clips = Clips()
    first = clips.add("1", 0, length_s=60)
    # Starts later than the gap after the first one starts, but within it of its end.
    second = clips.add("2", 60 + TAKES_MAX_GAP.total_seconds() - 1)

    signals = clips.run()

    assert groups(signals) == [{first, second}]
    for found in signals.values():
        (signal,) = found
        assert signal.category is Category.TAKES
        assert signal.evidence[ROLE_KEY] == Role.TAKE
        assert signal.evidence["takes"] == "2"
        assert signal.score == 1.0  # a candidate, with no best copy to keep


def test_a_clip_past_the_gap_starts_another_moment_and_a_lone_clip_gets_no_signal():
    clips = Clips()
    a, b = clips.add("a", 0), clips.add("b", 30)
    lone = clips.add("lone", 10 + 30 + TAKES_MAX_GAP.total_seconds() + 1)

    signals = clips.run()

    assert groups(signals) == [{a, b}]
    assert lone not in signals


def test_a_short_take_before_a_longer_one_has_a_role_of_its_own():
    clips = Clips()
    failed = clips.add("1", 0, length_s=2)
    good = clips.add("2", 10, length_s=30)
    last = clips.add("3", 60, length_s=5)  # shorter than the take before it, not the one after

    signals = clips.run()

    assert signals[failed][0].evidence[ROLE_KEY] == Role.SHORT_TAKE
    assert signals[good][0].evidence[ROLE_KEY] == Role.TAKE
    assert signals[last][0].evidence[ROLE_KEY] == Role.TAKE


def test_frames_that_look_nothing_alike_break_the_chain_and_missing_frames_leave_it_to_time():
    clips = Clips()
    a = clips.add("a", 0, frames=[[1, 0, 0], [0.9, 0.1, 0]])
    b = clips.add("b", 20, frames=[[0.1, 1, 0], [1, 0.2, 0]])  # one frame like a's is enough
    c = clips.add("c", 40, frames=[[0, 0, 1]])  # another subject
    d = clips.add("d", 60)  # no frames: time alone decides

    signals = clips.run()

    assert groups(signals) == [{a, b}, {c, d}]


def test_only_camera_videos_with_a_capture_time_are_takes():
    clips = Clips()
    a, b = clips.add("a", 0), clips.add("b", 20)
    clips.add("sent", 10, folder="WhatsApp/Media/WhatsApp Video/Sent")
    clips.add("no-time", None)

    assert groups(clips.run()) == [{a, b}]


def test_a_clip_with_no_length_ends_where_it_starts():
    clips = Clips()
    a = clips.add("a", 0, length_s=None)
    b = clips.add("b", TAKES_MAX_GAP.total_seconds())

    signals = clips.run()

    assert groups(signals) == [{a, b}]
    assert all(s[0].evidence[ROLE_KEY] == Role.TAKE for s in signals.values())
