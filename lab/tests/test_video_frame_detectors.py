"""BLURRY, BAD_EXPOSURE and FACES on a video, from the measures of its stored frames."""

from dataclasses import replace
from pathlib import Path

import pytest

from anteater_lab.detectors import AnalysisContext
from anteater_lab.detectors.base import accepted_media_types
from anteater_lab.detectors.blur import BlurDetector
from anteater_lab.detectors.exposure import ExposureDetector
from anteater_lab.detectors.faces import FaceDetector
from anteater_lab.model import Category, FrameMeasures, MediaRef, MediaType, Protection

VIDEO = MediaRef(Path("/sdcard/DCIM/Camera/a.mp4"), 0, 0, MediaType.VIDEO)
SHARP = FrameMeasures(peak_variance=900.0, p1=5, p5=20, p95=200, p99=240)
BLURRED = replace(SHARP, peak_variance=25.0)
DARK = replace(SHARP, p1=0, p5=1, p95=6, p99=9)
FACE = replace(SHARP, faces=2, face_confidence=0.8, face_area=0.1)


def analyze(detector, *frames):
    context = AnalysisContext(frame_measures=lambda video: frames)
    return detector.analyze(VIDEO, context)


@pytest.mark.parametrize("detector", [BlurDetector(), ExposureDetector(), FaceDetector()])
def test_the_detectors_accept_videos_and_read_the_frames(detector):
    assert accepted_media_types(detector) == {MediaType.IMAGE, MediaType.VIDEO}
    assert detector.uses_frames


@pytest.mark.parametrize("detector", [BlurDetector(), ExposureDetector(), FaceDetector()])
def test_a_video_with_no_frames_gets_no_signal(detector):
    assert analyze(detector) == []


def test_a_video_is_blurry_when_most_of_its_frames_are():
    [signal] = analyze(BlurDetector(), BLURRED, BLURRED, BLURRED, SHARP, SHARP)

    assert signal.category is Category.BLURRY
    assert signal.score == pytest.approx(100 / (100 + 25))  # the median frame's
    assert signal.evidence["frames"] == "5"


def test_one_blurred_frame_does_not_make_a_video_blurry():
    [signal] = analyze(BlurDetector(), SHARP, SHARP, BLURRED, SHARP, SHARP)

    assert signal.score == pytest.approx(100 / (100 + 900))


def test_an_even_number_of_frames_takes_the_mean_of_the_middle_two():
    [signal] = analyze(BlurDetector(), BLURRED, BLURRED, SHARP, SHARP)

    assert signal.score == pytest.approx(100 / (100 + (25 + 900) / 2))


def test_a_video_is_too_dark_when_most_of_its_frames_are():
    [signal] = analyze(ExposureDetector(), DARK, DARK, DARK, SHARP, SHARP)

    assert signal.category is Category.BAD_EXPOSURE
    assert signal.score == 1.0
    assert signal.evidence["case"] == "dark"


def test_a_video_with_two_dark_frames_in_five_is_not_dark():
    [signal] = analyze(ExposureDetector(), DARK, DARK, SHARP, SHARP, SHARP)

    assert signal.score < 0.5
    assert signal.evidence["frames"] == "5"


def test_a_video_of_faces_is_protected_by_the_mean_confidence_of_its_frames():
    [signal] = analyze(FaceDetector(), FACE, SHARP, SHARP, SHARP, SHARP)

    assert signal.category is Protection.FACES
    assert signal.score == pytest.approx(0.8 / 5)
    assert signal.evidence == {"faces": "2", "frames_with_faces": "1", "largest_face": "0.1000"}


def test_a_video_with_no_face_is_protected_by_nothing():
    [signal] = analyze(FaceDetector(), SHARP, SHARP)

    assert signal.score == 0.0
    assert signal.evidence["frames_with_faces"] == "0"
