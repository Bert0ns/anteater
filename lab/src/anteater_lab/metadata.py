"""Image and video metadata used by detectors and best-copy selection (docs/design.md, 7)."""

import logging
from dataclasses import dataclass
from datetime import datetime, tzinfo
from pathlib import Path

import av
from PIL import ExifTags, Image

_EXIF_DATETIME_FORMAT = "%Y:%m:%d %H:%M:%S"
_ROTATED_ORIENTATIONS = {5, 6, 7, 8}  # EXIF orientations that turn the image by 90 degrees


class UnreadableImage(Exception):
    """The file looks like an image but its header can't be decoded."""


class UnreadableVideo(Exception):
    """The file looks like a video but its header can't be read."""


class NoVideoTrack(Exception):
    """The container reads and holds no video track, such as an audio-only MP4."""


@dataclass(frozen=True)
class ImageMetadata:
    """`width` and `height` are as displayed, with the EXIF orientation already applied."""

    width: int
    height: int
    mime: str | None
    camera_make: str | None = None
    camera_model: str | None = None
    captured_at: datetime | None = None
    """EXIF capture time, in the camera's local time. Used for bursts, never as an age signal."""

    @property
    def has_camera_exif(self) -> bool:
        """Screenshots and forwarded media usually have no camera EXIF."""
        return self.camera_make is not None or self.camera_model is not None


@dataclass(frozen=True)
class VideoMetadata:
    """`width` and `height` are as displayed, with the rotation already applied."""

    width: int
    height: int
    duration_ms: int | None
    frame_rate: float | None
    bitrate: int | None
    """Bits per second over the whole file: its size over its duration."""
    has_audio: bool
    video_codec: str | None
    """The MIME type Android gives the codec, such as `video/avc`."""
    hdr: bool
    """The transfer function is PQ or HLG."""
    captured_at: datetime | None = None
    """The MP4 creation time, which is UTC, turned into local time as an EXIF time is."""
    captured_instant: int | None = None
    """The same moment as epoch milliseconds in UTC, which no zone moves (docs/design.md, 7)."""


# FFmpeg codec names to the MIME types of Android's MediaFormat, so both sides store one value.
_CODEC_MIME = {
    "h264": "video/avc",
    "hevc": "video/hevc",
    "vp8": "video/x-vnd.on2.vp8",
    "vp9": "video/x-vnd.on2.vp9",
    "av1": "video/av01",
    "mpeg4": "video/mp4v-es",
    "h263": "video/3gpp",
}
_log = logging.getLogger(__name__)
HDR_TRANSFERS = {16, 18}  # AVCOL_TRC_SMPTE2084 (PQ) and AVCOL_TRC_ARIB_STD_B67 (HLG)


def read_video_metadata(path: Path, zone: tzinfo | None = None) -> VideoMetadata:
    """The capture time goes to `zone`, the machine's own when None (docs/design.md, 7)."""
    try:
        with av.open(str(path)) as container:
            if not container.streams.video:
                raise NoVideoTrack(path)
            stream = container.streams.video[0]
            codec = stream.codec_context
            # PyAV exposes the display matrix only on a frame, so this one decodes the first.
            try:
                frame = next(container.decode(stream), None)
            except av.FFmpegError:
                frame = None  # the header read, which is all Android needs for it
            if frame is None:
                # The phone reads the rotation from the header: a portrait clip may differ here.
                _log.warning(
                    "%s: the first frame did not decode, so its rotation is not read", path
                )
            rotated = frame is not None and frame.rotation % 180 != 0
            width, height = (codec.height, codec.width) if rotated else (codec.width, codec.height)
            duration_ms = round(container.duration / 1000) if container.duration else None
            created = container.metadata.get("creation_time")
            mp4 = "mp4" in container.format.name.split(",")  # Android reads no Matroska date
            moment = _creation_time(created) if mp4 and created else None
            return VideoMetadata(
                width=width,
                height=height,
                duration_ms=duration_ms,
                frame_rate=_frame_rate(stream),
                bitrate=round(path.stat().st_size * 8000 / duration_ms) if duration_ms else None,
                has_audio=bool(container.streams.audio),
                video_codec=_CODEC_MIME.get(codec.name),
                hdr=codec.color_trc in HDR_TRANSFERS,
                captured_at=moment.astimezone(zone).replace(tzinfo=None) if moment else None,
                captured_instant=round(moment.timestamp() * 1000) if moment else None,
            )
    except (av.FFmpegError, OSError) as error:
        raise UnreadableVideo(path) from error


def _frame_rate(stream: av.video.stream.VideoStream) -> float | None:
    """Frames over the track's duration, as the phone computes it. A container that does not
    count its frames, such as Matroska, gives the rate it declares instead."""
    if stream.frames and stream.duration and stream.time_base:
        return round(stream.frames / float(stream.duration * stream.time_base), 2)
    return round(float(stream.average_rate), 2) if stream.average_rate else None


def _creation_time(utc: str) -> datetime | None:
    try:
        moment = datetime.fromisoformat(utc)
    except ValueError:
        return None
    if moment.year < 1971:  # a camera with no clock writes zero, which is 1904 or 1970
        return None
    return moment


def read_metadata(path: Path) -> ImageMetadata:
    """Reads the header only: the pixels are not decoded."""
    try:
        with Image.open(path) as image:
            exif, details = _exif(image)
            # pillow-heif already applies the orientation and resets the tag to 1.
            rotated = exif.get(ExifTags.Base.Orientation) in _ROTATED_ORIENTATIONS
            width, height = (image.height, image.width) if rotated else image.size
            return ImageMetadata(
                width=width,
                height=height,
                mime=image.get_format_mimetype(),
                camera_make=_text(exif.get(ExifTags.Base.Make)),
                camera_model=_text(exif.get(ExifTags.Base.Model)),
                captured_at=_datetime(
                    details.get(ExifTags.Base.DateTimeOriginal)
                    or details.get(ExifTags.Base.DateTimeDigitized)
                ),
            )
    except (OSError, SyntaxError, ValueError) as error:  # how Pillow reports undecodable files
        raise UnreadableImage(path) from error


def _exif(image: Image.Image) -> tuple[Image.Exif, dict]:
    """Main EXIF tags and the Exif sub-IFD, or empty ones when the EXIF block is malformed."""
    try:
        exif = image.getexif()
        return exif, exif.get_ifd(ExifTags.IFD.Exif)
    except (OSError, SyntaxError, ValueError):  # e.g. sticker packs storing JSON as EXIF
        return Image.Exif(), {}


def _text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return value.strip("\x00 ") or None


def _datetime(value: object) -> datetime | None:
    try:
        # Drop NUL terminators and any subsecond or timezone suffix some cameras append.
        return datetime.strptime(str(value).strip("\x00 ")[:19], _EXIF_DATETIME_FORMAT)
    except ValueError:
        return None
