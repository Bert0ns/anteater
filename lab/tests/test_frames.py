from pathlib import Path

import av
import numpy as np
import pytest

from anteater_lab.frames import frame_times, read_frames

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

    assert [(t, index_of(f)) for t, f in frames] == [
        (0, 0), (240, 2), (1260, 13), (1530, 15), (2900, 29), (5000, 29)  # past the end: the last
    ]  # fmt: skip
    assert all(f.size == (64, 48) and f.mode == "RGB" for _, f in frames)


def hlg_ramp(path):
    """ramp's frames in 10-bit HEVC tagged HLG, with a keyframe every 10 frames: 0, 1 and 2 s."""
    with av.open(str(path), "w") as container:
        stream = container.add_stream("libx265", rate=10)
        stream.width, stream.height, stream.pix_fmt = 64, 48, "yuv420p10le"
        stream.codec_context.color_trc = 18  # HLG
        stream.codec_context.color_primaries = stream.codec_context.colorspace = 9  # BT.2020
        stream.options = {"x265-params": "keyint=10:min-keyint=10:scenecut=0:log-level=error"}
        for i in range(30):
            rgb = np.full((48, 64, 3), 8 * i * 257, np.uint16)
            frame = av.VideoFrame.from_ndarray(rgb, "rgb48le").reformat(format="yuv420p10le")
            container.mux(stream.encode(frame))
        container.mux(stream.encode())
    return path


def test_an_hdr_video_takes_the_keyframe_nearest_each_time_once(tmp_path):
    video = hlg_ramp(tmp_path / "hlg.mp4")  # keyframes at frames 0, 10 and 20

    frames = list(read_frames(video, [300, 400, 700, 1400, 1600]))

    # 300 and 400 land on frame 0, 700 and 1400 on frame 10, 1600 on frame 20: each keyframe
    # once, under the first time that took it.
    signal = [np.asarray(f).mean() for _, f in frames]
    assert [t for t, _ in frames] == [300, 700, 1600]
    assert signal == sorted(signal) and len(set(signal)) == 3


def test_an_hdr_video_snaps_to_a_frame_then_to_its_keyframe_and_a_tie_goes_later(tmp_path):
    # As AOSP's MP4 extractor does for SEEK_CLOSEST_SYNC: the frame nearest the time, a tie to
    # the later one, then the keyframe nearest that frame, a tie to the later one again.
    video = hlg_ramp(tmp_path / "hlg.mp4")  # keyframes at frames 0, 10 and 20
    keyframe = {k: np.asarray(f) for k in (0, 1000, 2000) for _, f in read_frames(video, [k])}

    frames = list(read_frames(video, [500, 1495, 2900]))

    # 500 is a tie between 0 and 1000. 1495 snaps to the frame at 1500, a tie between 1000 and
    # 2000. 2900, past the last keyframe, takes it, and it is already taken.
    assert [t for t, _ in frames] == [500, 1495]
    np.testing.assert_array_equal(np.asarray(frames[0][1]), keyframe[1000])
    np.testing.assert_array_equal(np.asarray(frames[1][1]), keyframe[2000])


def test_a_portrait_clip_comes_upright():
    # Coded 64 x 48, turned by its display matrix. Turned counterclockwise, as FFmpeg's own
    # autorotate shows it: checked by hand against `ffmpeg -i rotated.mp4`.
    ((_, frame),) = read_frames(FIXTURES / "rotated.mp4", [500])

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


@pytest.mark.parametrize(
    "transfer, signal, gray",
    [(18, 0.5, 77), (18, 0.75, 134), (18, 1.0, 243), (16, 0.58, 118), (16, 0.75, 242)],
    ids=["hlg-half", "hlg-reference-white", "hlg-peak", "pq-reference-white", "pq-1000-nits"],
)
def test_an_hdr_frame_is_tone_mapped_as_the_phone_does(tmp_path, transfer, signal, gray):
    # By hand from BT.2100: HLG's inverse OETF gives scene light, 1 at the peak, and PQ's EOTF
    # cd/m2, over HLG's nominal peak of 1000. Times 0.9, then sRGB: 0.75 of HLG is 0.265, so 0.239
    # and a gray of 134, and 0.58 of PQ is 202 cd/m2, so 0.181 and a gray of 118.
    video = hdr_gray(tmp_path / "gray.mkv", signal, transfer)

    ((_, frame),) = read_frames(video, [0])

    assert np.abs(np.asarray(frame, dtype=float) - gray).max() <= 2


def flat_yuv(path, size, y, u, v):
    """One frame of a flat YUV color, lossless and untagged, as many re-encodes leave it."""
    width, height = size
    with av.open(str(path), "w") as container:
        stream = container.add_stream("ffv1", rate=10)
        stream.width, stream.height, stream.pix_fmt = width, height, "yuv420p"
        planes = np.concatenate(
            [
                np.full((height, width), y, np.uint8),
                np.full((height // 4, width), u, np.uint8),
                np.full((height // 4, width), v, np.uint8),
            ]
        )
        container.mux(stream.encode(av.VideoFrame.from_ndarray(planes, "yuv420p")))
        container.mux(stream.encode())
    return path


@pytest.mark.parametrize(
    "size, rgb",
    [((1280, 720), (191, 76, 39)), ((640, 480), (181, 67, 41))],
    ids=["hd-bt709", "sd-bt601"],
)
def test_an_untagged_frame_takes_the_matrix_android_takes_for_its_size(tmp_path, size, rgb):
    # Y 100, U 100, V 180 in limited range, by the BT.709 and BT.601 equations.
    video = flat_yuv(tmp_path / "flat.mkv", size, 100, 100, 180)

    ((_, frame),) = read_frames(video, [0])

    assert np.abs(np.asarray(frame).reshape(-1, 3).mean(axis=0) - rgb).max() <= 2
