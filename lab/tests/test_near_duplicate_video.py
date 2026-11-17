from pathlib import Path

import numpy as np

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, AnalysisContext, Role
from cribrum_lab.detectors.near_duplicate import (
    VIDEO_MAX_DURATION_GAP_MS,
    NearDuplicateDetector,
)
from cribrum_lab.metadata import VideoMetadata
from cribrum_lab.model import Category, MediaRef, MediaType

CAMERA = "DCIM/Camera"
SENT = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent"
SCENE = [[1, 0, 0], [0.9, 0.4, 0], [0.6, 0.8, 0]]
OTHER = [[0, 0, 1], [0, 0.4, 0.9], [0, 0.8, 0.6]]


class Videos:
    """Videos with chosen lengths, sizes and frames, and no file behind them."""

    def __init__(self):
        self.meta = {}
        self.frames = {}

    def add(self, name, folder=CAMERA, length_ms=10000, side=1080, bitrate=8_000_000, frames=SCENE):
        video = MediaRef(Path(f"/phone/{folder}/{name}.mp4"), 1000, 0, MediaType.VIDEO)
        self.meta[video] = VideoMetadata(
            side, side * 16 // 9, length_ms, 30, bitrate, True, None, False
        )
        if frames is not None:
            self.frames[video] = [np.array(v, dtype=np.float32) / np.linalg.norm(v) for v in frames]
        return video

    def run(self):
        context = AnalysisContext(
            video_metadata=self.meta.__getitem__, frames=lambda v: self.frames.get(v, [])
        )
        return NearDuplicateDetector().analyze(list(self.meta), context)


def roles(signals):
    return {video.path.stem: found[0].evidence[ROLE_KEY] for video, found in signals.items()}


def test_a_sent_copy_of_a_camera_video_is_an_inferior_copy_of_it():
    videos = Videos()
    original = videos.add("original")
    copy = videos.add("copy", folder=SENT, side=480, bitrate=1_000_000)

    signals = videos.run()

    assert roles(signals) == {"original": Role.BEST, "copy": Role.INFERIOR}
    [kept], [sent] = signals[original], signals[copy]
    assert (kept.category, kept.score) == (Category.NEAR_DUPLICATE, 0.0)
    assert (sent.category, sent.score) == (Category.NEAR_DUPLICATE, 1.0)
    assert kept.evidence[GROUP_KEY] == sent.evidence[GROUP_KEY]


def test_lengths_up_to_the_gap_apart_link_and_further_ones_do_not():
    videos = Videos()
    videos.add("a", length_ms=10000)
    videos.add("b", folder=SENT, length_ms=10000 + VIDEO_MAX_DURATION_GAP_MS)
    videos.add("c", folder=SENT, length_ms=10000 + 2 * VIDEO_MAX_DURATION_GAP_MS + 1)

    assert roles(videos.run()) == {"a": Role.BEST, "b": Role.INFERIOR}


def test_videos_of_one_length_that_look_unalike_are_not_linked():
    videos = Videos()
    videos.add("a")
    videos.add("b", folder=SENT, frames=OTHER)

    assert videos.run() == {}


def test_a_video_with_no_frames_or_no_length_is_linked_to_none():
    videos = Videos()
    videos.add("a")
    videos.add("no-frames", folder=SENT, frames=None)
    videos.add("no-length", folder=SENT, length_ms=None)

    assert videos.run() == {}


def test_a_video_whose_header_does_not_read_is_linked_to_none():
    videos = Videos()
    videos.add("a")
    unreadable = videos.add("b", folder=SENT)
    del videos.meta[unreadable]
    context = AnalysisContext(
        video_metadata=videos.meta.__getitem__, frames=lambda v: videos.frames.get(v, [])
    )

    assert NearDuplicateDetector().analyze([*videos.meta, unreadable], context) == {}


def test_two_sent_copies_of_a_video_that_is_gone_keep_the_sharper_one():
    videos = Videos()
    videos.add("hd", folder=SENT, side=720, bitrate=2_000_000)
    videos.add("sd", folder=SENT, side=480, bitrate=3_000_000)

    assert roles(videos.run()) == {"hd": Role.BEST, "sd": Role.INFERIOR}


def test_the_camera_original_is_kept_over_a_larger_copy_elsewhere():
    videos = Videos()
    videos.add("original", side=720)
    videos.add("download", folder="Download", side=1080)
    videos.add("sent", folder=SENT, side=1080)

    assert roles(videos.run()) == {
        "original": Role.BEST,
        "download": Role.INFERIOR,
        "sent": Role.INFERIOR,
    }


def test_of_one_resolution_the_higher_bitrate_is_kept():
    videos = Videos()
    videos.add("low", folder=SENT, bitrate=1_000_000)
    videos.add("high", folder=SENT, bitrate=2_000_000)

    assert roles(videos.run()) == {"high": Role.BEST, "low": Role.INFERIOR}


def test_trashing_a_copy_keeps_the_group_and_roles_of_the_others():
    videos = Videos()
    videos.add("original")
    first = videos.add("first", folder=SENT, side=480)
    second = videos.add("second", folder=SENT, side=480)
    before = videos.run()[second][0].evidence

    del videos.meta[first]

    assert videos.run()[second][0].evidence == before


def test_an_hdr_original_with_fewer_frames_still_links_to_its_copy():
    # An HDR video's frames are keyframes: a short one can give two where its copy gives five.
    videos = Videos()
    videos.add("original", frames=[[1, 0.1, 0], [0.6, 0.8, 0]])
    videos.add(
        "copy",
        folder=SENT,
        side=480,
        frames=[[1, 0, 0], [0.95, 0.3, 0], [0.8, 0.6, 0], [0.7, 0.7, 0], [0.6, 0.8, 0]],
    )

    assert roles(videos.run()) == {"original": Role.BEST, "copy": Role.INFERIOR}
