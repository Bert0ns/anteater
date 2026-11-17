from pathlib import Path

import pytest

from anteater_lab.detectors import AnalysisContext
from anteater_lab.detectors.base import accepted_media_types
from anteater_lab.detectors.forwarded import ForwardedDetector
from anteater_lab.model import Category, MediaRef, MediaType

MEDIA = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"


def analyze(path):
    video = MediaRef(Path(path), 0, 0, MediaType.VIDEO)
    # A video's signal reads no frame and no model: the default context would fail on both.
    return ForwardedDetector().analyze(video, AnalysisContext())


def test_the_detector_accepts_videos():
    assert accepted_media_types(ForwardedDetector()) == {MediaType.IMAGE, MediaType.VIDEO}


@pytest.mark.parametrize(
    "path",
    [
        f"{MEDIA}/WhatsApp Video/Sent/a.mp4",
        f"{MEDIA}/WhatsApp Video/a.mp4",
        f"{MEDIA}/WhatsApp Animated Gifs/a.mp4",
        f"{MEDIA}/WhatsApp Animated Gifs/Sent/a.mp4",
        f"{MEDIA}/WhatsApp Video Notes/202618/a.mp4",
        "/sdcard/Telegram/Telegram Video/a.mp4",
    ],
)
def test_every_video_in_a_messaging_folder_scores_1(path):
    # Past every position of the slider the photos share, so raising it never hides a video.
    [signal] = analyze(path)

    assert signal.category is Category.FORWARDED
    assert signal.score == 1.0
    assert signal.evidence == {"messaging_folder": "yes"}


@pytest.mark.parametrize(
    "path",
    [
        "/sdcard/DCIM/Camera/20260501_101010.mp4",
        "/sdcard/Movies/Sent/holiday.mp4",  # a folder named Sent says nothing outside an app's
    ],
)
def test_a_video_outside_a_messaging_folder_gets_no_signal(path):
    assert analyze(path) == []
