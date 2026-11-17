from dataclasses import replace
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pytest

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.base import Photo, accepted_media_types
from cribrum_lab.detectors.short_clips import (
    DURATION_WEIGHT,
    FULL_DURATION_MS,
    GIF_WEIGHT,
    MISTAKE_WEIGHT,
    PHOTO_WEIGHT,
    PHOTO_WINDOW_MS,
    SHORT_CLIP_MAX_MS,
    TAKE_WEIGHT,
    ShortClipsDetector,
)
from cribrum_lab.metadata import VideoMetadata
from cribrum_lab.model import Category, FrameMeasures, MediaRef, MediaType

START = datetime(2024, 5, 1, 10, 0, 0)
SHARP = FrameMeasures(peak_variance=900.0, p1=5, p5=20, p95=200, p99=240)
DARK = replace(SHARP, p1=0, p5=1, p95=6, p99=9)
UP = np.array([1.0, 0.0], dtype=np.float32)
ASIDE = np.array([0.0, 1.0], dtype=np.float32)


class Clip:
    """One video with a chosen length, folder, capture time and frames, and no file behind it."""

    def __init__(self, length_s=3, folder="DCIM/Camera", audio=True, side=1080, at=START):
        self.video = MediaRef(Path(f"/phone/{folder}/a.mp4"), 1000, 0, MediaType.VIDEO)
        duration = None if length_s is None else int(length_s * 1000)
        instant = None if at is None else int(at.timestamp() * 1000)
        self.meta = {
            self.video: VideoMetadata(
                side, side * 16 // 9, duration, 30, None, audio, None, False, at, instant
            )
        }
        self.frames = {}
        self.measures = {}
        self.photos = []

    def run(self):
        context = AnalysisContext(
            video_metadata=self.meta.__getitem__,
            frames=lambda v: self.frames.get(v, []),
            frame_measures=lambda v: self.measures.get(v, []),
            photos=lambda times, window_ms: self.photos,
        )
        return ShortClipsDetector().analyze(list(self.meta), context).get(self.video, [])

    def score(self):
        [signal] = self.run()
        return signal.score


def noisy_or(*weights):
    miss = 1.0
    for weight in weights:
        miss *= 1.0 - weight
    return 1.0 - miss


def test_it_takes_only_videos_and_is_a_category_of_its_own():
    assert accepted_media_types(ShortClipsDetector()) == {MediaType.VIDEO}
    [signal] = Clip().run()
    assert signal.category is Category.SHORT_CLIPS


def test_a_short_clip_scores_by_its_length_alone():
    assert Clip(length_s=FULL_DURATION_MS / 1000).score() == pytest.approx(DURATION_WEIGHT)
    halfway = (FULL_DURATION_MS + SHORT_CLIP_MAX_MS) / 2000
    assert Clip(length_s=halfway).score() == pytest.approx(DURATION_WEIGHT / 2)


def test_a_clip_at_the_longest_length_or_with_no_length_gets_no_signal():
    assert Clip(length_s=SHORT_CLIP_MAX_MS / 1000).run() == []
    assert Clip(length_s=None).run() == []


def test_a_photo_taken_within_a_minute_of_a_clip_with_a_close_embedding_adds_a_chance():
    clip = Clip()
    clip.frames[clip.video] = [UP]
    clip.photos = [Photo(START + timedelta(milliseconds=PHOTO_WINDOW_MS), UP)]

    assert clip.score() == pytest.approx(noisy_or(DURATION_WEIGHT, PHOTO_WEIGHT))
    assert clip.run()[0].evidence["photo_chance"] == "0.500"


def test_a_photo_too_far_in_time_or_unlike_the_frames_adds_nothing():
    clip = Clip()
    clip.frames[clip.video] = [UP]
    late = START + timedelta(milliseconds=PHOTO_WINDOW_MS + 1000)
    clip.photos = [Photo(late, UP), Photo(START, ASIDE)]

    assert clip.score() == pytest.approx(DURATION_WEIGHT)


def test_without_an_embedding_to_compare_the_time_alone_decides():
    clip = Clip()
    clip.photos = [Photo(START - timedelta(seconds=20), None)]

    assert clip.score() == pytest.approx(noisy_or(DURATION_WEIGHT, PHOTO_WEIGHT))


def test_a_photo_beside_a_long_clip_is_no_reason():
    clip = Clip(length_s=60)
    clip.photos = [Photo(START, None)]

    assert clip.run() == []


def test_a_short_take_followed_by_a_longer_one_adds_a_chance():
    first = Clip(length_s=3)
    second = MediaRef(Path("/phone/DCIM/Camera/b.mp4"), 1000, 0, MediaType.VIDEO)
    later = START + timedelta(seconds=30)
    first.meta[second] = VideoMetadata(
        1080, 1920, 20000, 30, None, True, None, False, later, int(later.timestamp() * 1000)
    )

    signals = first.run()

    assert signals[0].score == pytest.approx(noisy_or(DURATION_WEIGHT, TAKE_WEIGHT))
    assert signals[0].evidence["failed_take_chance"] == "0.600"


def test_a_video_dark_on_most_frames_adds_a_chance_and_one_dark_frame_does_not():
    clip = Clip()
    clip.measures[clip.video] = [DARK, DARK, DARK, SHARP, SHARP]
    assert clip.score() == pytest.approx(noisy_or(DURATION_WEIGHT, MISTAKE_WEIGHT * 1.0))
    assert clip.run()[0].evidence["mistake"] == "dark"

    clip.measures[clip.video] = [DARK, SHARP, SHARP, SHARP, SHARP]
    assert clip.score() == pytest.approx(DURATION_WEIGHT)


def test_a_video_blurred_on_most_frames_adds_a_chance():
    clip = Clip()
    blurred = replace(SHARP, peak_variance=25.0)
    clip.measures[clip.video] = [blurred] * 3 + [SHARP] * 2

    assert clip.score() == pytest.approx(noisy_or(DURATION_WEIGHT, MISTAKE_WEIGHT * 100 / 125))
    assert clip.run()[0].evidence["mistake"] == "blurry"


def test_a_clip_with_no_sound_and_a_low_resolution_in_a_chat_folder_reads_as_a_gif():
    gif = Clip(folder="WhatsApp/Media/WhatsApp Animated Gifs", audio=False, side=480)
    assert gif.score() == pytest.approx(noisy_or(DURATION_WEIGHT, GIF_WEIGHT))
    assert gif.run()[0].evidence["gif_chance"] == "0.600"

    for other in (
        Clip(folder="WhatsApp/Media/WhatsApp Video", audio=True, side=480),
        Clip(folder="WhatsApp/Media/WhatsApp Video", audio=False, side=1080),
        Clip(folder="DCIM/Camera", audio=False, side=480),
    ):
        assert other.score() == pytest.approx(DURATION_WEIGHT)


def test_the_chances_combine_as_independent_ones():
    clip = Clip(folder="WhatsApp/Media/WhatsApp Animated Gifs", audio=False, side=480)
    clip.measures[clip.video] = [DARK] * 5
    assert clip.score() == pytest.approx(noisy_or(DURATION_WEIGHT, MISTAKE_WEIGHT, GIF_WEIGHT))
