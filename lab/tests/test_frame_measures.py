import numpy as np
from PIL import Image

from cribrum_lab.frame_measures import measure_frame


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
