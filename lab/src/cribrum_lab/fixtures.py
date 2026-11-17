"""Golden fixtures for the Kotlin port (docs/design.md, section 10, step 5).

Synthetic images only, never personal photos, generated from fixed seeds into
fixtures/golden/ at the repository root, together with expected.json: what the lab's
detectors say about them, and fixtures/videos/frames.json: the embeddings of the frames the lab
samples from the synthetic videos there, and fixtures/videos/takes.json: capture times, lengths
and frames of made-up videos and the takes the lab chains them into, and
fixtures/videos/near_duplicates.json: lengths, sizes and frames of made-up videos and the copies the
lab links, and fixtures/videos/frame_detectors.json: the measures of the frames of made-up videos
and the signals the lab's BLURRY, BAD_EXPOSURE and FACES give them. The Android tests run their
port on the same files and compare. Regenerate after a detector change, and commit them:

    uv run python -m cribrum_lab.fixtures
"""

import base64
import contextlib
import json
import shutil
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from cribrum_lab import frames, hashing, mobileclip
from cribrum_lab.detectors import AnalysisContext
from cribrum_lab.detectors.blur import BlurDetector
from cribrum_lab.detectors.exposure import ExposureDetector
from cribrum_lab.detectors.faces import FaceDetector
from cribrum_lab.detectors.near_duplicate import NearDuplicateDetector
from cribrum_lab.detectors.takes import TakesDetector
from cribrum_lab.metadata import VideoMetadata, read_metadata
from cribrum_lab.model import FrameMeasures, MediaRef, MediaType
from cribrum_lab.pipeline import DETECTORS, GROUP_DETECTORS

ROOT = Path(__file__).resolve().parents[3] / "fixtures" / "golden"
FORMAT_VERSION = 1
TOLERANCE = {"score": 0.05, "embedding_cosine": 0.99}
"""Decoders and resamplers differ between Pillow and Android: numbers match only closely."""
VIDEOS = ROOT.parent / "videos"
FRAME_TOLERANCE = {
    "embedding_cosine": 0.99,
    "mean_rgb": 3,
    "lowered_embedding_cosine": {"clip.mp4": 0.96, "ntsc.mp4": 0.96, "rotated.mp4": 0.96},
}
"""What an SDR frame of the phone may differ from the lab's by: FFmpeg and the phone turn YUV into
RGB each their own way. A mean off by more is another frame or another matrix. The 64 x 48 test
patterns get a lower cosine floor: the phone gave 0.970 to 0.980 on them, where chroma upsampling
moves a large share of their pixels, and 0.9976 on the same pattern at 1280 x 720. HDR frames have
no floor: the phone tone maps these small fixtures another way than its camera's clips
(docs/design.md, section 5)."""


def _texture(seed: int, size: tuple[int, int] = (640, 480)) -> Image.Image:
    """Sharp colored blocks: plenty of detail for the blur measure."""
    blocks = np.random.default_rng(seed).integers(0, 256, (size[1] // 8, size[0] // 8, 3), np.uint8)
    return Image.fromarray(blocks).resize(size, Image.Resampling.NEAREST)


def _scene(shift: int = 0) -> Image.Image:
    """Simple shapes on a sky: the burst's subject, moved by `shift` px between shots."""
    scene = Image.new("RGB", (640, 480), (120, 170, 230))
    draw = ImageDraw.Draw(scene)
    draw.rectangle((0, 330, 640, 480), fill=(70, 140, 60))
    draw.ellipse((200 + shift, 150, 360 + shift, 310), fill=(230, 60, 40))
    draw.rectangle((420, 200 - shift, 520, 330), fill=(240, 220, 90), outline=(40, 40, 40), width=4)
    return scene


def _frame(level: int) -> Image.Image:
    """A dark or bright frame with faint detail, as a pocket shot or blown-out frame."""
    detail = np.random.default_rng(level).integers(0, 8, (480, 640, 3))
    return Image.fromarray(np.clip(level + detail - 4, 0, 255).astype(np.uint8))


def _page() -> Image.Image:
    page = Image.new("RGB", (600, 800), "white")
    draw = ImageDraw.Draw(page)
    for y in range(60, 760, 28):
        draw.text((50, y), "The quick brown fox jumps over the lazy dog " * 2, fill="black")
    return page


def _screenshot() -> Image.Image:
    screen = Image.new("RGB", (1080, 2400), (245, 245, 245))
    draw = ImageDraw.Draw(screen)
    draw.rectangle((0, 0, 1080, 160), fill=(30, 110, 200))
    for y in range(260, 2200, 180):
        draw.rounded_rectangle(
            (60, y, 900, y + 120), 30, fill=(255, 255, 255), outline=(200, 200, 200)
        )
    return screen


def _sticker() -> Image.Image:
    """A WhatsApp sticker: a shape on a transparent background, 512 px, as WebP."""
    sticker = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    draw = ImageDraw.Draw(sticker)
    draw.ellipse((56, 56, 456, 456), fill=(250, 200, 40, 255), outline=(60, 30, 10, 255), width=12)
    draw.ellipse((170, 180, 220, 250), fill=(60, 30, 10, 255))
    draw.ellipse((292, 180, 342, 250), fill=(60, 30, 10, 255))
    draw.arc((150, 230, 362, 390), 20, 160, fill=(60, 30, 10, 255), width=14)
    return sticker


def _exif_camera() -> bytes:
    exif = Image.Exif()
    exif[0x010F] = "Google"
    exif[0x0110] = "Pixel 8"
    exif.get_ifd(0x8769)[0x9003] = "2024:05:01 10:00:00"
    return exif.tobytes()


def _burst_exif(second: int) -> bytes:
    exif = Image.Exif()
    exif[0x010F] = "Google"
    exif[0x0110] = "Pixel 8"
    exif.get_ifd(0x8769)[0x9003] = f"2024:05:01 11:00:{second:02d}"
    return exif.tobytes()


def write_images(root: Path) -> None:
    """Every fixture image, at a path that matters for detectors that look at folders."""

    def save(relative: str, image: Image.Image, **params) -> Path:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path, **params)
        return path

    camera = save("DCIM/Camera/texture.jpg", _texture(1), quality=92, exif=_exif_camera())
    copy = root / "WhatsApp/Media/WhatsApp Images/IMG-20240501-WA0001.jpg"
    copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(camera, copy)  # exact duplicate
    save("Download/texture-small.jpg", _texture(1).resize((320, 240)), quality=60)  # near
    save("DCIM/Camera/blurred.jpg", _texture(2).filter(ImageFilter.GaussianBlur(6)), quality=92)
    save("DCIM/Camera/dark.jpg", _frame(6), quality=92)
    save("DCIM/Camera/bright.jpg", _frame(250), quality=92)
    save("Pictures/Screenshots/Screenshot_20240501-120000.png", _screenshot())
    save("Documents/page.png", _page())
    sticker = "WhatsApp/Media/WhatsApp Stickers/STK-20240501-WA0001.webp"
    save(sticker, _sticker(), lossless=True, exact=True)  # exact: transparent pixels stay black
    for second, blur in [(0, 4), (1, 0), (2, 2)]:
        image = _scene(shift=6 * second).filter(ImageFilter.GaussianBlur(blur))
        save(f"DCIM/Camera/burst-{second}.jpg", image, quality=92, exif=_burst_exif(second))


def expected(root: Path, embed: bool) -> dict:
    """What the lab says about the fixture images. Embeddings need the MobileCLIP model."""
    paths = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix != ".json")
    images = [MediaRef(p, p.stat().st_size, 0) for p in paths]
    metadata = {image: read_metadata(image.path) for image in images}
    model = mobileclip.MobileClip() if embed else None
    vectors = {}
    if model:
        vectors = {i: model.embed_images([i.path])[0] for i in images}

    def embedding(image: MediaRef) -> np.ndarray:
        if image not in vectors:
            raise LookupError(image.path)
        return vectors[image]

    context = AnalysisContext(metadata=metadata.__getitem__, embedding=embedding)
    signals = {image: [] for image in images}
    for detector in DETECTORS:
        for image in images:
            with contextlib.suppress(LookupError):  # the detector needs embeddings
                signals[image] += detector.analyze(image, context)
    for detector in GROUP_DETECTORS:
        for image, found in detector.analyze(images, context).items():
            signals[image] += found

    result = {}
    for image in images:
        meta = metadata[image]
        entry = {
            "width": meta.width,
            "height": meta.height,
            "has_camera_exif": meta.has_camera_exif,
            "content_hash": hashing.content_hash(image),
            "perceptual_hash": f"{hashing.perceptual_hash(image):016x}",
            "signals": [
                {
                    "detector": s.detector_id,
                    "version": s.detector_version,
                    "category": s.category.value,
                    "score": round(s.score, 4),
                    "evidence": s.evidence,
                }
                for s in signals[image]
            ],
        }
        relative = image.path.relative_to(root).as_posix()
        if image in vectors:
            entry["embedding"] = encode_vector(vectors[image])
        result[relative] = entry
    return {
        "format_version": FORMAT_VERSION,
        "tolerance": TOLERANCE,
        "embedding_model": mobileclip.MODEL_ID if embed else None,
        "images": result,
    }


def frames_expected() -> dict:
    """The frames the lab samples from each fixture video it reads, and their embeddings."""
    model = mobileclip.MobileClip()
    videos = {}
    for name, video in json.loads((VIDEOS / "expected.json").read_text())["videos"].items():
        taken = dict(frames.read_frames(VIDEOS / name, frames.frame_times(video["duration_ms"])))
        pixels = np.stack([mobileclip.model_input(image) for image in taken.values()])
        stats = [frames.brightness(image) for image in taken.values()]
        videos[name] = {
            "times_ms": list(taken),
            "hdr": video["hdr"],
            "embeddings": [encode_vector(v) for v in model.embed_pixels(pixels)],
            "mean_rgb": [[round(c, 2) for c in mean] for mean, _ in stats],
            "clipped": [round(clipped, 4) for _, clipped in stats],
        }
    return {
        "tolerance": FRAME_TOLERANCE,
        "embedding_model": mobileclip.MODEL_ID,
        "videos": videos,
    }


TAKES = VIDEOS / "takes.json"


def _at(clock: str) -> int:
    """A moment of 2024-05-01 in UTC+2, as epoch milliseconds: what `captured_instant` holds."""
    return int(datetime.fromisoformat(f"2024-05-01T{clock}+02:00").timestamp() * 1000)


TAKE_CLIPS = [
    # A failed start, the take it was for, one exactly the gap after that one ends, the last one.
    {"path": "DCIM/Camera/a1.mp4", "captured_instant": _at("10:00:00"), "duration_ms": 2000},
    {"path": "DCIM/Camera/a2.mp4", "captured_instant": _at("10:00:10"), "duration_ms": 30000},
    {"path": "DCIM/Camera/a3.mp4", "captured_instant": _at("10:05:40"), "duration_ms": 5000},
    {"path": "DCIM/Camera/a4.mp4", "captured_instant": _at("10:05:50"), "duration_ms": 10000},
    # A second past the gap after a4 ends: a moment of its own, alone.
    {"path": "DCIM/Camera/b.mp4", "captured_instant": _at("10:11:01"), "duration_ms": 4000},
    # A sent copy and a clip with no time are no takes.
    {"path": "WhatsApp/Media/WhatsApp Video/Sent/a2.mp4", "captured_instant": _at("10:00:10"),
     "duration_ms": 30000},
    {"path": "DCIM/Camera/no-time.mp4", "captured_instant": None, "duration_ms": 30000},
    # A Galaxy's screen recordings are in DCIM too, and are no takes.
    {"path": "DCIM/Screen recordings/rec1.mp4", "captured_instant": _at("10:00:20"),
     "duration_ms": 10000},
    {"path": "DCIM/Screen recordings/rec2.mp4", "captured_instant": _at("10:00:40"),
     "duration_ms": 10000},
    # Frames: c2 has one frame like c1's, c3 looks like neither, and c4 has none.
    {"path": "DCIM/Camera/c1.mp4", "captured_instant": _at("12:00:00"), "duration_ms": 10000,
     "frames": [[1, 0, 0], [0.6, 0.8, 0]]},
    {"path": "DCIM/Camera/c2.mp4", "captured_instant": _at("12:01:00"), "duration_ms": 10000,
     "frames": [[0, 0.8, 0.6]]},
    {"path": "DCIM/Camera/c3.mp4", "captured_instant": _at("12:02:00"), "duration_ms": None,
     "frames": [[1, 0, 0]]},
    {"path": "DCIM/Camera/c4.mp4", "captured_instant": _at("12:03:00"), "duration_ms": 10000},
]  # fmt: skip


def takes_expected() -> dict:
    """The made-up clips of TAKE_CLIPS and the signals TAKES gives them."""
    return _video_group_expected(TAKE_CLIPS, TakesDetector())


def write_takes() -> None:
    TAKES.write_text(json.dumps(takes_expected(), indent=1, sort_keys=True) + "\n")


NEAR_DUPLICATES = VIDEOS / "near_duplicates.json"
_SENT = "WhatsApp/Media/WhatsApp Video/Sent"
NEAR_DUPLICATE_CLIPS = [
    # A camera original, its WhatsApp copy, a copy exactly the length gap longer, and one past it.
    {"path": "DCIM/Camera/trip.mp4", "duration_ms": 20000, "width": 2160, "height": 3840,
     "bitrate": 50000000, "frames": [[1, 0, 0], [0.8, 0.6, 0], [0.6, 0.8, 0]]},
    {"path": f"{_SENT}/trip-sd.mp4", "duration_ms": 20000, "width": 480, "height": 848,
     "bitrate": 1200000, "frames": [[0.99, 0.1, 0], [0.8, 0.6, 0.05], [0.6, 0.8, 0]]},
    {"path": f"{_SENT}/trip-hd.mp4", "duration_ms": 20100, "width": 720, "height": 1280,
     "bitrate": 2000000, "frames": [[1, 0, 0], [0.8, 0.6, 0], [0.6, 0.8, 0]]},
    {"path": f"{_SENT}/trip-late.mp4", "duration_ms": 20201, "width": 720, "height": 1280,
     "bitrate": 2000000, "frames": [[1, 0, 0], [0.8, 0.6, 0], [0.6, 0.8, 0]]},
    # The same length, but other frames, no frames or no length: linked to none.
    {"path": f"{_SENT}/other.mp4", "duration_ms": 20000, "width": 480, "height": 848,
     "bitrate": 1200000, "frames": [[0, 0, 1], [0, 0.6, 0.8], [0, 0.8, 0.6]]},
    {"path": f"{_SENT}/no-frames.mp4", "duration_ms": 20000, "width": 480, "height": 848,
     "bitrate": 1200000},
    {"path": f"{_SENT}/no-length.mp4", "duration_ms": None, "width": 480, "height": 848,
     "bitrate": 1200000, "frames": [[1, 0, 0], [0.8, 0.6, 0], [0.6, 0.8, 0]]},
    # A video sent twice, its original gone: of one resolution, the higher bitrate is kept.
    {"path": f"{_SENT}/party-1.mp4", "duration_ms": 8000, "width": 720, "height": 1280,
     "bitrate": 1500000, "frames": [[0, 1, 0], [0, 0.6, 0.8]]},
    {"path": f"{_SENT}/party-2.mp4", "duration_ms": 8000, "width": 720, "height": 1280,
     "bitrate": 2000000, "frames": [[0, 1, 0], [0, 0.6, 0.8]]},
    # An HDR original gives fewer frames, keyframes, than its copy.
    {"path": "DCIM/Camera/hdr.mp4", "duration_ms": 3000, "width": 2160, "height": 3840,
     "bitrate": 40000000, "frames": [[0.5, 0, 0.87], [0, 0.5, 0.87]]},
    {"path": f"{_SENT}/hdr.mp4", "duration_ms": 3000, "width": 480, "height": 848,
     "bitrate": 1000000,
     "frames": [[0.5, 0, 0.87], [0.4, 0.1, 0.9], [0.25, 0.25, 0.93], [0.1, 0.4, 0.9],
                [0, 0.5, 0.87]]},
    # One frame each: a cosine of 0.81 links, and 0.79 does not.
    {"path": "DCIM/Camera/bar.mp4", "duration_ms": 5000, "width": 1080, "height": 1920,
     "bitrate": 8000000, "frames": [[1, 0, 0]]},
    {"path": f"{_SENT}/bar-over.mp4", "duration_ms": 5000, "width": 480, "height": 848,
     "bitrate": 1000000, "frames": [[0.81, 0.5864, 0]]},
    {"path": "DCIM/Camera/under.mp4", "duration_ms": 6000, "width": 1080, "height": 1920,
     "bitrate": 8000000, "frames": [[1, 0, 0]]},
    {"path": f"{_SENT}/bar-under.mp4", "duration_ms": 6000, "width": 480, "height": 848,
     "bitrate": 1000000, "frames": [[0.79, 0.6131, 0]]},
    # The first and last are further apart than the gap, and link through the middle one.
    {"path": f"{_SENT}/chain-1.mp4", "duration_ms": 30000, "width": 720, "height": 1280,
     "bitrate": 2000000, "frames": [[0, 0.6, 0.8]]},
    {"path": f"{_SENT}/chain-2.mp4", "duration_ms": 30060, "width": 480, "height": 848,
     "bitrate": 1000000, "frames": [[0, 0.6, 0.8]]},
    {"path": f"{_SENT}/chain-3.mp4", "duration_ms": 30120, "width": 480, "height": 848,
     "bitrate": 900000, "frames": [[0, 0.6, 0.8]]},
    # Two takes, camera videos captured at different instants, are no copies. A re-export that
    # keeps the first one's instant is.
    {"path": "DCIM/Camera/take-1.mp4", "duration_ms": 12000, "width": 2160, "height": 3840,
     "bitrate": 50000000, "captured_instant": 1714550400000, "frames": [[0.8, 0, 0.6]]},
    {"path": "DCIM/Camera/take-2.mp4", "duration_ms": 12000, "width": 2160, "height": 3840,
     "bitrate": 50000000, "captured_instant": 1714550460000, "frames": [[0.8, 0, 0.6]]},
    {"path": "DCIM/Camera/take-1-export.mp4", "duration_ms": 12000, "width": 1080,
     "height": 1920, "bitrate": 8000000, "captured_instant": 1714550400000,
     "frames": [[0.8, 0, 0.6]]},
]  # fmt: skip


def near_duplicates_expected() -> dict:
    """The made-up clips of NEAR_DUPLICATE_CLIPS and the signals NEAR_DUPLICATE gives them."""
    return _video_group_expected(NEAR_DUPLICATE_CLIPS, NearDuplicateDetector())


def write_near_duplicates() -> None:
    text = json.dumps(near_duplicates_expected(), indent=1, sort_keys=True) + "\n"
    NEAR_DUPLICATES.write_text(text)


def _video_group_expected(made_up: list[dict], detector) -> dict:
    """Runs a video group detector on made-up clips, each with no file behind it."""
    clips = {MediaRef(Path(c["path"]), 0, 0, MediaType.VIDEO): c for c in made_up}

    def metadata(video: MediaRef) -> VideoMetadata:
        clip = clips[video]
        width, height, length = clip.get("width", 0), clip.get("height", 0), clip["duration_ms"]
        bitrate, instant = clip.get("bitrate"), clip.get("captured_instant")
        return VideoMetadata(
            width, height, length, None, bitrate, False, None, False, None, instant
        )

    def frames(video: MediaRef) -> list[np.ndarray]:
        return [np.array(f, dtype=np.float32) for f in clips[video].get("frames", [])]

    context = AnalysisContext(video_metadata=metadata, frames=frames)
    found = detector.analyze(list(clips), context)
    return {
        "clips": made_up,
        "signals": {
            str(video.path): [
                {"detector": s.detector_id, "version": s.detector_version,
                 "category": s.category.value, "score": s.score, "evidence": s.evidence}
                for s in signals
            ]
            for video, signals in sorted(found.items(), key=lambda item: str(item[0].path))
        },
    }  # fmt: skip


FRAME_DETECTORS = VIDEOS / "frame_detectors.json"

_SHARP = {"peak_variance": 900.0, "p1": 5, "p5": 20, "p95": 200, "p99": 240}
_BLURRED = _SHARP | {"peak_variance": 25.0}
_DARK = _SHARP | {"p1": 0, "p5": 1, "p95": 6, "p99": 9}
_BRIGHT = _SHARP | {"p1": 200, "p5": 230, "p95": 254, "p99": 255}
_FACE = _SHARP | {"faces": 2, "face_confidence": 0.8, "face_area": 0.1}
_SMALL_FACE = _SHARP | {"faces": 1, "face_confidence": 0.65, "face_area": 0.02}
FRAME_CLIPS = {
    "sharp.mp4": [_SHARP] * 5,
    "blurred.mp4": [_BLURRED, _BLURRED, _BLURRED, _SHARP, _SHARP],
    "one-blurred-frame.mp4": [_SHARP, _SHARP, _BLURRED, _SHARP, _SHARP],
    "four-frames.mp4": [_BLURRED, _BLURRED, _SHARP, _SHARP],
    "dark.mp4": [_DARK, _DARK, _DARK, _SHARP, _SHARP],
    "two-dark-frames.mp4": [_DARK, _DARK, _SHARP, _SHARP, _SHARP],
    "bright.mp4": [_BRIGHT] * 4,
    "faces.mp4": [_FACE, _SMALL_FACE, _SHARP, _SHARP, _FACE],
    "one-frame.mp4": [_BLURRED],
    "no-frames.mp4": [],
}
"""Made-up clips with the measures of the frames they would have, in the order of their times."""


def frame_detectors_expected() -> dict:
    """The measures of FRAME_CLIPS and the signals BLURRY, BAD_EXPOSURE and FACES give them."""
    clips = {MediaRef(Path(name), 0, 0, MediaType.VIDEO): m for name, m in FRAME_CLIPS.items()}
    context = AnalysisContext(
        frame_measures=lambda video: [FrameMeasures(**m) for m in clips[video]]
    )
    detectors = [BlurDetector(), ExposureDetector(), FaceDetector()]
    return {
        "clips": FRAME_CLIPS,
        "signals": {
            str(video.path): [
                {"detector": s.detector_id, "version": s.detector_version,
                 "category": s.category.value, "score": s.score, "evidence": s.evidence}
                for d in detectors
                for s in d.analyze(video, context)
            ]
            for video in clips
        },
    }  # fmt: skip


def write_frame_detectors() -> None:
    text = json.dumps(frame_detectors_expected(), indent=1, sort_keys=True) + "\n"
    FRAME_DETECTORS.write_text(text)


def encode_vector(vector: np.ndarray) -> str:
    """Base64 of little-endian float32, as expected.json stores an embedding."""
    return base64.b64encode(vector.astype("<f4").tobytes()).decode()


def decode_vector(text: str) -> np.ndarray:
    return np.frombuffer(base64.b64decode(text), dtype="<f4")


def main() -> None:
    if ROOT.exists():
        shutil.rmtree(ROOT)
    write_images(ROOT)
    data = expected(ROOT, embed=True)
    (ROOT / "expected.json").write_text(json.dumps(data, indent=1, sort_keys=True) + "\n")
    frames_json = json.dumps(frames_expected(), indent=1, sort_keys=True) + "\n"
    (VIDEOS / "frames.json").write_text(frames_json)
    write_takes()
    write_near_duplicates()
    write_frame_detectors()
    print(f"wrote {ROOT} and {VIDEOS / 'frames.json'}")


if __name__ == "__main__":
    main()
