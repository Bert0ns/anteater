from pathlib import Path

import av
import numpy as np
import pytest

from cribrum_lab.frames import frame_times, read_frames

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "videos"


def test_five_frames_at_the_middle_of_five_slices_away_from_the_ends():
    # 10 s: half a second off each end leaves 9 s, cut in five slices of 1.8 s.
    assert frame_times(10_000) == [1400, 3200, 5000, 6800, 8600]


def test_a_clip_shorter_than_two_seconds_keeps_a_quarter_off_each_end():
    assert frame_times(1000) == [300, 400, 500, 600, 700]


def test_a_clip_too_short_for_five_distinct_times_gets_fewer():
    assert frame_times(3) == [0, 1, 2]


def test_a_video_with_no_duration_gets_its_first_frame():
    assert frame_times(None) == [0]


def ramp(path, frames=30, rate=10, gop=10):
    """A video whose frame i is a flat gray of 8 * i, so a frame tells its index."""
    with av.open(str(path), "w") as container:
        stream = container.add_stream("libx264", rate=rate)
        stream.width, stream.height, stream.pix_fmt = 64, 48, "yuv420p"
        stream.codec_context.gop_size = gop  # a keyframe a second: the reader must seek past it
        for i in range(frames):
            frame = av.VideoFrame.from_ndarray(np.full((48, 64, 3), 8 * i, np.uint8), "rgb24")
            container.mux(stream.encode(frame))
        container.mux(stream.encode())
    return path


def index_of(image):
    return round(np.asarray(image).mean() / 8)


def test_each_time_gets_the_frame_closest_to_it(tmp_path):
    video = ramp(tmp_path / "ramp.mp4")  # frame i at 100 * i ms

    frames = list(read_frames(video, [0, 240, 1260, 1530, 2900, 5000]))

    assert [index_of(f) for f in frames] == [0, 2, 13, 15, 29, 29]  # past the end: the last
    assert all(f.size == (64, 48) and f.mode == "RGB" for f in frames)


def test_a_portrait_clip_comes_upright():
    # Coded 64 x 48, turned by its display matrix. Turned counterclockwise, as FFmpeg's own
    # autorotate shows it: checked by hand against `ffmpeg -i rotated.mp4`.
    (frame,) = read_frames(FIXTURES / "rotated.mp4", [500])

    assert frame.size == (48, 64)


def hdr_gray(path, signal, transfer):
    """A video of one flat gray frame, `signal` of the full 10-bit range, tagged with `transfer`.
    FFV1 is lossless, so the frame holds that signal exactly."""
    with av.open(str(path), "w") as container:
        stream = container.add_stream("ffv1", rate=10)
        stream.width, stream.height, stream.pix_fmt = 64, 48, "yuv420p10le"
        stream.codec_context.color_trc = transfer
        stream.codec_context.color_primaries = 9  # BT.2020
        stream.codec_context.colorspace = 9
        rgb = np.full((48, 64, 3), round(signal * 65535), np.uint16)
        frame = av.VideoFrame.from_ndarray(rgb, "rgb48le").reformat(
            format="yuv420p10le", dst_colorspace="bt2020"
        )
        container.mux(stream.encode(frame))
        container.mux(stream.encode())
    return path


@pytest.mark.parametrize("transfer, reference_white", [(18, 0.75), (16, 0.58)], ids=["hlg", "pq"])
def test_an_hdr_frame_is_tone_mapped_with_its_reference_white_at_sdr_white(
    tmp_path, transfer, reference_white
):
    # BT.2408: HDR reference white, 203 cd/m2, is 75% of the HLG signal and 58% of the PQ one.
    video = hdr_gray(tmp_path / "gray.mkv", reference_white, transfer)

    (frame,) = read_frames(video, [0])

    assert np.asarray(frame).min() >= 250  # read as SDR, the signal would be a gray of 190 or 147
