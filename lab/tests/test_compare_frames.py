import base64
import importlib.util
from pathlib import Path

import numpy as np

SCRIPT = Path(__file__).parents[1] / "scripts" / "compare_frames.py"
spec = importlib.util.spec_from_file_location("compare_frames", SCRIPT)
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


def test_reads_the_frame_lines_of_the_device_test_out_of_a_logcat_dump():
    vector = np.array([0.6, 0.8], dtype="<f4")
    embedding = base64.b64encode(vector.tobytes()).decode()
    log = (
        "11-16 13:00:00.000  1234  1234 I cribrum-frames: round 1: 900.0 ms for the video\n"
        f"11-16 13:00:01.000  1234  1234 I cribrum-frames: frame 1400 mean 120.5 98.0 77.25 "
        f"clipped 0.0312 embedding {embedding}\n"
    )

    [(time_ms, (mean, clipped, read))] = compare.phone_frames(log).items()

    assert (time_ms, mean, clipped) == (1400, [120.5, 98.0, 77.25], 0.0312)
    np.testing.assert_array_equal(read, vector)
