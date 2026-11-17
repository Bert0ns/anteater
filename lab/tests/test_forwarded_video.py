from pathlib import Path

import pytest

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.base import accepted_media_types
from cribrum_lab.detectors.forwarded import ForwardedDetector
from cribrum_lab.folders import is_sent_folder
from cribrum_lab.model import Category, MediaRef, MediaType
from cribrum_lab.ranker import THRESHOLDS

MEDIA = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"


def analyze(path):
    video = MediaRef(Path(path), 0, 0, MediaType.VIDEO)
    # A video's signal reads no frame and no model: the default context would fail on both.
    return ForwardedDetector().analyze(video, AnalysisContext())


def test_the_detector_accepts_videos():
    assert accepted_media_types(ForwardedDetector()) == {MediaType.IMAGE, MediaType.VIDEO}


def test_a_video_in_a_sent_folder_is_a_stronger_signal_than_a_received_one():
    [sent] = analyze(f"{MEDIA}/WhatsApp Video/Sent/VID-20260501-WA0001.mp4")
    [received] = analyze(f"{MEDIA}/WhatsApp Video/VID-20260501-WA0002.mp4")

    assert sent.category is received.category is Category.FORWARDED
    assert (sent.score, received.score) == pytest.approx((0.8, 0.6))
    assert sent.evidence == {"messaging_folder": "yes", "sent_folder": "yes"}
    assert received.evidence == {"messaging_folder": "yes", "sent_folder": "no"}


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
def test_every_video_in_a_messaging_folder_is_a_candidate(path):
    [signal] = analyze(path)

    assert signal.score >= THRESHOLDS[Category.FORWARDED]


@pytest.mark.parametrize(
    "path",
    [
        "/sdcard/DCIM/Camera/20260501_101010.mp4",
        "/sdcard/Movies/Sent/holiday.mp4",  # a folder named Sent says nothing outside an app's
    ],
)
def test_a_video_outside_a_messaging_folder_gets_no_signal(path):
    assert analyze(path) == []


def test_only_a_messaging_apps_sent_folder_is_a_sent_folder():
    assert is_sent_folder(Path(f"{MEDIA}/WhatsApp Video/Sent/a.mp4"))
    assert is_sent_folder(Path("/sdcard/WhatsApp/Media/WhatsApp Video/sent/a.mp4"))
    assert not is_sent_folder(Path(f"{MEDIA}/WhatsApp Video/a.mp4"))
    assert not is_sent_folder(Path("/sdcard/Movies/Sent/a.mp4"))
    assert not is_sent_folder(Path(f"{MEDIA}/WhatsApp Video/Sent"))  # a file called Sent
