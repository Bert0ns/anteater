"""Media sources: where images and videos come from. Mirrors docs/design.md, sections 4 and 7."""

import stat
from collections import Counter
from collections.abc import Callable, Iterable, Iterator
from dataclasses import replace
from pathlib import Path
from typing import Protocol

from cribrum_lab.model import MediaRef, MediaType

# ISO base media brands of HEIF and AVIF images, which win wherever the ftyp box names them.
_HEIF_BRANDS = {
    b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx",
    b"mif1", b"mif2", b"msf1", b"miaf", b"avif", b"avis",
}  # fmt: skip
# Major brands of MP4, MOV, 3GP and M4V. Audio-only files (M4A, M4B) and raw photos such as Canon's
# CR3 name isom among their compatible brands too, so only the major brand counts.
_VIDEO_BRANDS = {
    b"isom", b"iso2", b"iso3", b"iso4", b"iso5", b"iso6", b"mp41", b"mp42", b"avc1", b"mmp4",
    b"XAVC", b"qt  ", b"3gp4", b"3gp5", b"3gp6", b"3gp7", b"3g2a", b"M4V ", b"M4VH", b"M4VP",
}  # fmt: skip
_NOT_VIDEO_BRANDS = {b"M4A ", b"M4B ", b"M4P ", b"crx "}
"""Major brands of what is known not to be a video: audio, and Canon's raw photos. The walk turns
them away without counting them as an unknown brand."""
_EBML = b"\x1a\x45\xdf\xa3"
"""The start of a Matroska or WebM file, whose DocType element follows within a few bytes."""
_HEAD_SIZE = 64
"""Enough for the magic bytes of every format and the brand list of a typical ftyp box."""


class MediaSource(Protocol):
    def scan(self) -> Iterator[MediaRef]: ...

    @property
    def walked_roots(self) -> list[Path]:
        """The roots the last `scan` listed in full.

        An image under one of them that `scan` did not yield is gone; under any other root the
        scan could not look, so it says nothing.
        """
        ...


class NotImages(Protocol):
    """What the walk remembers of the files it sniffed and found to be neither images nor videos,
    so it does not read their head again while their size and mtime stay the same."""

    def not_images(self) -> Iterable[MediaRef]: ...

    def add_not_images(self, files: list[MediaRef]) -> None:
        """Stores the files; one already stored with the same path is replaced."""
        ...

    def forget_not_images(self, paths: list[Path]) -> None: ...


NOT_IMAGES_BATCH = 300
"""Files the walk hands to `add_not_images` at once."""


class FilesystemMediaSource:
    """Walks a folder tree and yields every image and video file, like the Android deep scan.

    Hidden folders and folders with a `.nomedia` file are included: that is where
    messaging apps keep media the gallery never shows. Files are recognized by their
    content, not their extension, and yielded with the media type their head gave, except a
    file that `indexed` returns with the same path, size and mtime: the index took it, so its
    head is not read again and it keeps the media type the index has. `indexed` is called once
    per `scan`, before the walk. The same goes for a file `not_images` has: an earlier walk found
    it to be neither an image nor a video. The walk adds the non-images it sniffs, and drops
    the ones it no longer finds when it listed the whole root. Symlinks are skipped so no file
    is yielded twice, and so are FIFOs and other special files, which would block or fail on
    read. Left out on purpose: files in the system trash or still being written (`.trashed-*`,
    `.pending-*`), and `Android/.Trash` at the root, which is Samsung Gallery's recycle bin.
    """

    SKIPPED_PREFIXES = (".trashed-", ".pending-")
    SKIPPED_FOLDERS = (Path("Android/.Trash"),)

    def __init__(
        self,
        root: Path,
        indexed: Callable[[], Iterable[MediaRef]] = list,
        *,
        not_images: NotImages | None = None,
    ) -> None:
        self.root = root
        self._indexed = indexed
        self._not_images = not_images
        self.walked_roots: list[Path] = []
        """The root once the walk listed every folder; a folder that cannot be listed, or a
        missing root, leaves it out."""
        self.unknown_brands: Counter[str] = Counter()
        """The files the last `scan` sniffed and turned away although they are an ftyp or EBML
        container, by the brand it did not know: a video format the list misses shows here."""

    def scan(self) -> Iterator[MediaRef]:
        self.walked_roots = []
        self.unknown_brands = Counter()
        listed_all = True
        # By path, so a file the index holds is yielded with the media type it was recorded with.
        known = {ref.path: ref for ref in self._indexed()}
        remembered = list(self._not_images.not_images()) if self._not_images is not None else []
        remembered_set = set(remembered)
        met: set[Path] = set()  # non-images this walk met, remembered or newly sniffed
        sniffed: list[MediaRef] = []

        def on_error(_: OSError) -> None:
            nonlocal listed_all
            listed_all = False

        for folder, dirs, files in self.root.walk(on_error=on_error):
            dirs[:] = sorted(
                d for d in dirs if (folder / d).relative_to(self.root) not in self.SKIPPED_FOLDERS
            )
            for name in sorted(files):
                if name.startswith(self.SKIPPED_PREFIXES):
                    continue
                path = folder / name
                try:
                    info = path.lstat()
                    if not stat.S_ISREG(info.st_mode):
                        continue
                    image = MediaRef(path, info.st_size, info.st_mtime_ns)
                    indexed = known.get(path)
                    if indexed and (indexed.size, indexed.mtime_ns) == (image.size, image.mtime_ns):
                        image = indexed
                    else:
                        again = image in remembered_set
                        media_type = None
                        if not again:
                            head = _head(path)
                            media_type = _media_type(head)
                            if media_type is None and (brand := _unknown_brand(head)):
                                self.unknown_brands[brand] += 1
                        if media_type is None:
                            met.add(path)
                            if self._not_images is not None and not again:
                                sniffed.append(image)
                                if len(sniffed) == NOT_IMAGES_BATCH:
                                    self._not_images.add_not_images(sniffed)
                                    sniffed = []  # the store may still hold the list it was given
                            continue
                        image = replace(image, media_type=media_type)
                except OSError:
                    continue  # unreadable or vanished during the scan
                yield image
        if self._not_images is not None:
            if sniffed:
                self._not_images.add_not_images(sniffed)
            if listed_all:  # only a root listed in full can tell a gone file from an unseen one
                self._not_images.forget_not_images(
                    [
                        r.path
                        for r in remembered
                        if r.path not in met and r.path.is_relative_to(self.root)
                    ]
                )
        if listed_all:
            self.walked_roots = [self.root]


def _head(path: Path) -> bytes:
    with path.open("rb") as file:
        return file.read(_HEAD_SIZE)


def _media_type(head: bytes) -> MediaType | None:
    """What the head of a file says it is, or None for neither an image nor a video."""
    # The walk remembers the files this refused, by size and mtime (docs/design.md, section 4).
    # A change that lets the head accept more, such as a new format or HEIF brand, must empty
    # `not_images` in the same change: bump SCHEMA_VERSION in index.py, which refuses older
    # files. Otherwise those files are never sniffed again.
    ftyp = head[4:8] == b"ftyp"
    if (
        head.startswith(b"\xff\xd8\xff")  # JPEG
        or head.startswith(b"\x89PNG\r\n\x1a\n")
        or head.startswith((b"GIF87a", b"GIF89a"))
        or (head.startswith(b"RIFF") and head[8:12] == b"WEBP")
        or (ftyp and not _HEIF_BRANDS.isdisjoint(_ftyp_brands(head)))
    ):
        return MediaType.IMAGE
    if (ftyp and head[8:12] in _VIDEO_BRANDS) or (
        head.startswith(_EBML) and (b"matroska" in head or b"webm" in head)
    ):
        return MediaType.VIDEO
    return None


def _unknown_brand(head: bytes) -> str | None:
    """For a head `_media_type` turned away, the brand of its ftyp or EBML container when it is
    not one known to be something other than a video: an ftyp major brand, or `ebml/` and the
    DocType. None for a file that is no such container."""
    if head[4:8] == b"ftyp" and len(head) >= 12:
        brand = head[8:12]
        return None if brand in _NOT_VIDEO_BRANDS else _printable(brand)
    if head.startswith(_EBML):
        return "ebml/" + _printable(_doc_type(head))
    return None


def _doc_type(head: bytes) -> bytes:
    """The DocType element of an EBML header (ID 0x4282, a one-byte size), or b"?"."""
    at = head.find(b"\x42\x82")
    if at < 0 or at + 2 >= len(head) or not head[at + 2] & 0x80:
        return b"?"
    return head[at + 3 : at + 3 + (head[at + 2] & 0x7F)]


def _printable(text: bytes) -> str:
    return "".join(c if " " <= c <= "~" else "?" for c in text.decode("latin-1"))


def _ftyp_brands(head: bytes) -> list[bytes]:
    """Major brand plus compatible brands: HEIF files may declare their brand in either."""
    box_end = min(int.from_bytes(head[:4]), len(head))
    return [head[8:12]] + [head[i : i + 4] for i in range(16, box_end - 3, 4)]
