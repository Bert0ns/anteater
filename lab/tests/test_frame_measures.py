from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.faces import FaceDetector
from cribrum_lab.frame_measures import measure_frame
from cribrum_lab.model import MediaRef, MediaType

GOLDEN = Path(__file__).resolve().parents[2] / "fixtures" / "golden"


def test_a_black_frame_has_no_detail_no_light_and_no_face():
    measures = measure_frame(Image.new("RGB", (320, 240), "black"))

    assert measures.peak_variance == 0.0
    assert (measures.p1, measures.p5, measures.p95, measures.p99) == (0, 0, 0, 0)
    assert (measures.faces, measures.face_confidence, measures.face_area) == (0, 0.0, 0.0)


def test_a_frame_of_a_gradient_spreads_its_light_and_has_little_detail():
    row = np.linspace(0, 255, 320, dtype=np.uint8)
    frame = Image.fromarray(np.tile(row, (240, 1))).convert("RGB")

    measures = measure_frame(frame)

    assert measures.p5 < 30 and measures.p95 > 225
    assert measures.peak_variance < 1


def golden_images():
    """The golden images that are upright and that no detector decodes at a draft size, so a frame
    of their pixels is the image the detectors see (the Android twin is FrameMeasuresTest)."""
    for path in sorted(GOLDEN.rglob("*")):
        if path.suffix.lower() not in {".jpg", ".png"}:
            continue
        with Image.open(path) as image:
            upright = image.getexif().get(0x0112, 1) == 1
            drafted = max(image.size) > 2 * 512 and image.format == "JPEG"
        if upright and not drafted:
            yield path


@pytest.mark.parametrize("detector", [BlurDetector(), ExposureDetector(), FaceDetector()])
def test_a_video_of_one_frame_scores_as_the_image_of_that_frame(detector):
    paths = list(golden_images())
    assert len(paths) >= 10
    for path in paths:
        with Image.open(path) as image:
            measures = measure_frame(image.convert("RGB"))
        video = MediaRef(path, 0, 0, MediaType.VIDEO)
        as_video = AnalysisContext(frame_measures=lambda _, m=measures: [m])

        [want] = detector.analyze(MediaRef(path, 0, 0), AnalysisContext())
        [got] = detector.analyze(video, as_video)

        # A JPEG decoded for the image detectors comes out as its Y channel, a frame as RGB turned
        # to luminance again: that moves a score by about 1e-5, and a drift of the measures more.
        assert got.score == pytest.approx(want.score, abs=1e-3), path.name
        assert got.evidence.get("case") == want.evidence.get("case"), path.name
