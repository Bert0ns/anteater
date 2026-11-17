"""What the detectors that read frames keep of a video's sampled frame (docs/design.md, section 5).

The frame is measured while it is decoded for its embedding and then dropped, so a video's
BLURRY, BAD_EXPOSURE and FACES signals come from these numbers and not from pixels.
"""

import numpy as np
from PIL import Image

from anteater_lab.detectors import blur, exposure, faces
from anteater_lab.model import FrameMeasures


def measure_frame(image: Image.Image, runtime: faces.ModelRuntime | None = None) -> FrameMeasures:
    """The measures of an upright RGB frame, each as the detector of an image takes it on the
    image's own pixels: the grayscale is a Lanczos thumbnail, and the faces a letterbox."""
    peak_variance = blur.measure(_gray(image, blur.MAX_SIDE))
    p1, p5, p95, p99 = (
        float(v) for v in np.percentile(_gray(image, exposure.MAX_SIDE), exposure.PERCENTILES)
    )
    pixels, (width, height) = faces.letterboxed(image)
    found = faces.detect(runtime or faces._default_runtime(), pixels)
    return FrameMeasures(
        peak_variance=peak_variance[1] if peak_variance else 0.0,
        p1=p1,
        p5=p5,
        p95=p95,
        p99=p99,
        faces=len(found),
        face_confidence=max((confidence for confidence, _ in found), default=0.0),
        face_area=max((w * h for _, (_, _, w, h) in found), default=0.0) / (width * height),
    )


def _gray(image: Image.Image, max_side: int) -> np.ndarray:
    """`imaging.load_grayscale` of a frame already decoded."""
    gray = image.convert("L")
    gray.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
    return np.asarray(gray, dtype=np.float32)
