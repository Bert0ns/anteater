# /// script
# requires-python = ">=3.12"
# dependencies = ["perfetto"]
# ///
"""Prints the times of a Perfetto trace of Anteater, one line each.

Usage: uv run android/scripts/trace_times.py [--by-hand | --videos] TRACE [PACKAGE]

measure.sh calls it on the trace it records: a cold start, the home, one category, then the
Compress page, the trash and the storage map, each opened from the home. The section names are in
android/README.md. Lines:

  home ready        the process start to the end of the last Review.overview before the category opens
  category open     the first Review.page
  compress open     the first Compress.list to the end of the last tile load of the first screen
  compress tiles    how many loads that screen started, the slowest, and the most at once
  compress tile cache  Tile.hit and Tile.miss, the disk cache's lookups, while it loaded
  trash open        the first Trash.list to the end of the last tile load of the first screen
  trash tiles       as compress tiles
  trash tile cache  as compress tile cache
  storage map open  the first StorageMap.build to the end of the layout that follows it

A line says "unknown" when the trace has no such section. The tiles of the first screen are the
loads that start within a second of the first one: a list composes them together, and the script
waits several seconds before it scrolls.

--by-hand prints the other lines instead, for a trace where someone opened the full size view or
the Compress summary (measure.sh --by-hand). Each lists the sections of the whole trace in order:

  summary previews     the Compressor.preview of the summary, one after the other
  full size image      FullSize.image, the review's and the trash's view
  zoom base image      TiledImage.base, the Compress full size view and the summary's slider
  zoom prefetch        TiledImage.prefetch, the next photo's base image, loaded behind the current one
  full size placeholder  FullSize.placeholder.hit and .miss, the views that found their grid tile
  grid tile loads after the first full size view
                       Compress.tile, Review.tile and Trash.tile loads that start after the first view
  zoom tile decodes    TiledImage.tile, one for each view of a zoom

--videos prints the lines of a video section instead (measure.sh --videos): its grid, whose tiles
move, then the full size view. A video frame is a load of Coil's VideoFrameDecoder, one
MediaMetadataRetriever and one codec each. "At once" is the most loads open at one time.

  video grid open      the first grid frame load to the end of the last one of the first screen
  video grid frames    the grid's still frames (Videos.tile, Review.tile, Trash.videoTile)
  slideshow frames     Review.slideshow, the 8 frames of each tile that shows a slideshow
  video frames at once all the loads above and the full size view's, the most open together
  full size frame      the still frame of each full size page (Videos.fullSize, Review.fullSize, ...)
  full size strip      the strip's 5 frames under each video (*.fullSize.strip)
  video start          Video.start, from a video's play to the first frame the player draws
  video seek           Video.seek, from a seek to the first frame after it
  frame waits, decodes, retries
                       Frame.wait, Frame.decode and Frame.retry: a frame's wait for one of the 2
                       decode permits, its decode, and the second decode of one that failed
  video build, stop, detach, release
                       the full size player's calls on the main thread, which block it that long
  clip start, build, detach, release
                       the same for the loops of the tiles, on their own thread
  grid frame loads after the full size view
                       grid frame loads that start after the full size player's release: 0 means
                       Back found every frame in the memory cache
  main thread stalls   the app's main thread slices over 50 ms, the longest first
"""

import sys
from dataclasses import dataclass

from perfetto.trace_processor import TraceProcessor

# Suspending sections are async ones, on process tracks, and the others are on thread tracks.
# The tile-prepare thread works in the background after a scan: its Tile.miss lookups are not a screen's.
SECTIONS = """
app as (select upid, start_ts from process where name = '{package}'),
sections as (
  select s.name, s.ts, s.dur from slice s
    join thread_track t on s.track_id = t.id join thread using (utid)
    where thread.upid in (select upid from app) and coalesce(thread.name, '') != 'tile-prepare'
  union all
  select s.name, s.ts, s.dur from slice s
    join process_track t on s.track_id = t.id
    where t.upid in (select upid from app)
)
"""

HOME_QUERY = (
    "with "
    + SECTIONS
    + """,
page as (select ts, dur from sections where name = 'Review.page' order by ts limit 1)
select
  coalesce((select min(start_ts) from app), (select min(ts) from sections)) as start,
  (select max(ts + dur) from sections
    where name = 'Review.overview' and ts < coalesce((select ts from page), 1 << 62)) as home_end,
  (select dur from page) as page_dur
"""
)

# A section still open when the trace ended has no duration (dur = -1): it is left out.
SCREEN_QUERY = (
    "with "
    + SECTIONS
    + """
select name, ts, dur from sections
  where dur >= 0 and name in ('Compress.list', 'Compress.tile', 'Trash.list', 'Trash.tile',
                              'StorageMap.build', 'StorageMap.layout', 'Compressor.preview',
                              'Review.tile', 'FullSize.image', 'FullSize.placeholder.hit',
                              'FullSize.placeholder.miss', 'TiledImage.base',
                              'TiledImage.prefetch', 'TiledImage.tile', 'Tile.hit', 'Tile.miss')
  order by ts
"""
)

# A list composes its first tiles together; the next ones come when the script scrolls, seconds later.
FIRST_SCREEN_NS = 1_000_000_000


@dataclass(frozen=True)
class Section:
    name: str
    ts: int
    dur: int

    @property
    def end(self) -> int:
        return self.ts + self.dur


@dataclass(frozen=True)
class Tiles:
    open_ns: int
    count: int
    slowest_ns: int
    at_once: int
    hits: int
    misses: int


def ms(ns: int | None) -> str:
    return "unknown" if ns is None else f"{ns / 1e6:.0f} ms"


def first_screen(sections: list[Section], read: str, tile: str) -> Tiles | None:
    """From the first read of a list to the end of the last tile load of its first screen.

    None when the list was not read or no tile started after it.
    """
    reads = [s for s in sections if s.name == read]
    if not reads:
        return None
    start = min(s.ts for s in reads)
    tiles = sorted(
        (s for s in sections if s.name == tile and s.ts >= start), key=lambda s: s.ts
    )
    if not tiles:
        return None
    screen = [t for t in tiles if t.ts - tiles[0].ts < FIRST_SCREEN_NS]
    # The most loads open at once: sweep the starts (+1) and ends (-1), the end first at a tie.
    edges = sorted(
        [(t.ts, 1) for t in screen] + [(t.end, -1) for t in screen],
        key=lambda e: (e[0], e[1]),
    )
    open_now = at_once = 0
    for _, step in edges:
        open_now += step
        at_once = max(at_once, open_now)
    # A lookup is in the screen's loads; a tile served from memory has none.
    first, last = screen[0].ts, max(t.end for t in screen)

    def lookups(name: str) -> int:
        return sum(1 for s in sections if s.name == name and first <= s.ts <= last)

    return Tiles(
        last - start,
        len(screen),
        max(t.dur for t in screen),
        at_once,
        lookups("Tile.hit"),
        lookups("Tile.miss"),
    )


def map_open(sections: list[Section]) -> int | None:
    """From the first StorageMap.build to the end of the first layout after it, else to the end of the build."""
    builds = sorted(
        (s for s in sections if s.name == "StorageMap.build"), key=lambda s: s.ts
    )
    if not builds:
        return None
    build = builds[0]
    layouts = sorted(
        (s for s in sections if s.name == "StorageMap.layout" and s.ts >= build.end),
        key=lambda s: s.ts,
    )
    return (layouts[0].end if layouts else build.end) - build.ts


def screen_lines(label: str, tiles: Tiles | None) -> list[str]:
    if tiles is None:
        return [
            f"{label} open: unknown",
            f"{label} tiles: unknown",
            f"{label} tile cache: unknown",
        ]
    return [
        f"{label} open: {ms(tiles.open_ns)}",
        f"{label} tiles: {tiles.count} on the first screen, the slowest {ms(tiles.slowest_ns)}, at most {tiles.at_once} at once",
        f"{label} tile cache: {tiles.hits} hits, {tiles.misses} misses",
    ]


def listed(sections: list[Section], *names: str) -> str:
    """The durations of the sections called one of [names], in order, or unknown."""
    durs = [s.dur for s in sections if s.name in names]
    return ", ".join(ms(d) for d in durs) if durs else "unknown"


GRID_TILES = ("Compress.tile", "Review.tile", "Trash.tile")


def tile_loads_after_full_size(sections: list[Section]) -> str:
    """How many grid tile loads start after the first full size image, or unknown without one.

    Open a full size view, swipe, press Back and touch nothing else: zero means Back reloaded no tile.
    """
    views = [
        s.ts for s in sections if s.name in ("FullSize.image", "TiledImage.base")
    ]
    if not views:
        return "unknown"
    return str(sum(1 for s in sections if s.name in GRID_TILES and s.ts > min(views)))


def by_hand_lines(sections: list[Section]) -> list[str]:
    decodes = [s.dur for s in sections if s.name == "TiledImage.tile"]
    hits = sum(1 for s in sections if s.name == "FullSize.placeholder.hit")
    misses = sum(1 for s in sections if s.name == "FullSize.placeholder.miss")
    return [
        f"summary previews: {listed(sections, 'Compressor.preview')}",
        f"full size image: {listed(sections, 'FullSize.image')}",
        f"zoom base image: {listed(sections, 'TiledImage.base')}",
        f"zoom prefetch: {listed(sections, 'TiledImage.prefetch')}",
        f"full size placeholder: {hits} hits, {misses} misses",
        f"grid tile loads after the first full size view: {tile_loads_after_full_size(sections)}",
        f"zoom tile decodes: {len(decodes)}, the slowest {ms(max(decodes, default=None))}",
    ]


VIDEO_GRID = ("Videos.tile", "Review.tile", "Trash.videoTile")
VIDEO_FULL_SIZE = ("Videos.fullSize", "Review.fullSize", "Trash.fullSize")
VIDEO_STRIP = tuple(f"{name}.strip" for name in VIDEO_FULL_SIZE)
VIDEO_CALLS = ("Video.build", "Video.stop", "Video.detach", "Video.release")
CLIP_CALLS = ("Clip.start", "Clip.build", "Clip.detach", "Clip.release")

# Every section the video lines read, besides the main thread's slices.
VIDEO_QUERY = (
    "with "
    + SECTIONS
    + """
select name, ts, dur from sections
  where dur >= 0 and (name in ({names}) or name in ({calls}))
  order by ts
"""
)

# The main thread's top level slices over 50 ms: a frame, an input or a message that held it that long.
STALLS_QUERY = """
select s.name, s.ts, s.dur from slice s
  join thread_track t on s.track_id = t.id join thread using (utid) join process using (upid)
  where process.name = '{package}' and thread.tid = process.pid and s.depth = 0 and s.dur > 50000000
  order by s.dur desc
"""


def at_once(loads: list[Section]) -> int:
    """The most of [loads] open at one time: a sweep of the starts (+1) and ends (-1), the end first at a tie."""
    edges = sorted(
        [(t.ts, 1) for t in loads] + [(t.end, -1) for t in loads],
        key=lambda e: (e[0], e[1]),
    )
    open_now = most = 0
    for _, step in edges:
        open_now += step
        most = max(most, open_now)
    return most


def spread(loads: list[Section]) -> str:
    """How many, the median, the 90th percentile, the slowest and the most at once, or none."""
    if not loads:
        return "none"
    durs = sorted(s.dur for s in loads)
    p90 = durs[min(len(durs) - 1, (len(durs) * 9) // 10)]
    return (
        f"{len(durs)}, median {ms(durs[len(durs) // 2])}, p90 {ms(p90)}, "
        f"the slowest {ms(durs[-1])}, at most {at_once(loads)} at once"
    )


def video_lines(sections: list[Section], stalls: list[Section]) -> list[str]:
    def named(*names: str) -> list[Section]:
        return [s for s in sections if s.name in names]

    grid = named(*VIDEO_GRID)
    lines = []
    if grid:
        screen = [t for t in grid if t.ts - grid[0].ts < FIRST_SCREEN_NS]
        lines.append(
            f"video grid open: {ms(max(t.end for t in screen) - screen[0].ts)}, "
            f"{len(screen)} frames, the slowest {ms(max(t.dur for t in screen))}, "
            f"at most {at_once(screen)} at once"
        )
    else:
        lines.append("video grid open: unknown")
    every = named(*VIDEO_GRID, "Review.slideshow", *VIDEO_FULL_SIZE, *VIDEO_STRIP)
    lines += [
        f"video grid frames: {spread(grid)}",
        f"slideshow frames: {spread(named('Review.slideshow'))}",
        f"video frames at once: {at_once(every)}",
        f"full size frame: {listed(sections, *VIDEO_FULL_SIZE)}",
        f"full size strip: {spread(named(*VIDEO_STRIP))}",
        f"video start: {listed(sections, 'Video.start')}",
        f"video seek: {listed(sections, 'Video.seek')}",
        f"frame waits: {spread(named('Frame.wait'))}",
        f"frame decodes: {spread(named('Frame.decode'))}",
        f"frame retries: {spread(named('Frame.retry'))}",
    ]
    for name in VIDEO_CALLS:
        lines.append(f"{name.lower().replace('.', ' ')}: {listed(sections, name)}")
    for name in CLIP_CALLS:
        lines.append(f"{name.lower().replace('.', ' ')}: {spread(named(name))}")
    releases = [s.ts for s in sections if s.name == "Video.release"]
    after = (
        str(sum(1 for s in grid if s.ts > max(releases))) if releases else "unknown"
    )
    lines.append(f"grid frame loads after the full size view: {after}")
    longest = ", ".join(f"{s.name} {ms(s.dur)}" for s in stalls[:8])
    lines.append(f"main thread stalls: {len(stalls)}{': ' + longest if stalls else ''}")
    return lines


def main() -> None:
    args = sys.argv[1:]
    by_hand = "--by-hand" in args
    videos = "--videos" in args
    args = [a for a in args if a not in ("--by-hand", "--videos")]
    trace = args[0]
    package = args[1] if len(args) > 1 else "io.github.bert0ns.anteater"
    with TraceProcessor(trace=trace) as tp:
        if videos:
            quoted = ", ".join(
                f"'{n}'"
                for n in (
                    *VIDEO_GRID,
                    "Review.slideshow",
                    *VIDEO_FULL_SIZE,
                    *VIDEO_STRIP,
                    "Video.start",
                    "Video.seek",
                    "Frame.wait",
                    "Frame.decode",
                    "Frame.retry",
                )
            )
            calls = ", ".join(f"'{n}'" for n in (*VIDEO_CALLS, *CLIP_CALLS))
            found = [
                Section(r.name, r.ts, r.dur)
                for r in tp.query(
                    VIDEO_QUERY.format(package=package, names=quoted, calls=calls)
                )
            ]
            stalls = [
                Section(r.name, r.ts, r.dur)
                for r in tp.query(STALLS_QUERY.format(package=package))
            ]
            print(*video_lines(found, stalls), sep="\n")
            return
        row = next(iter(tp.query(HOME_QUERY.format(package=package))))
        sections = [
            Section(r.name, r.ts, r.dur)
            for r in tp.query(SCREEN_QUERY.format(package=package))
        ]
    if by_hand:
        print(*by_hand_lines(sections), sep="\n")
        return
    # The start is the fork of the process when the trace has it, else the app's first section.
    home = None if None in (row.start, row.home_end) else row.home_end - row.start
    print(f"home ready: {ms(home)}")
    print(f"category open: {ms(row.page_dur)}")
    print(
        *screen_lines(
            "compress", first_screen(sections, "Compress.list", "Compress.tile")
        ),
        sep="\n",
    )
    print(
        *screen_lines("trash", first_screen(sections, "Trash.list", "Trash.tile")),
        sep="\n",
    )
    print(f"storage map open: {ms(map_open(sections))}")


if __name__ == "__main__":
    main()
