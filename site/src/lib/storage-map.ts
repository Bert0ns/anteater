// The storage map demo: made-up folders, and the treemap layout of the Android app
// (android/.../ui/StorageMap.kt) ported to TypeScript. It runs at build time, so the page ships the
// finished layout and no layout code. The numbers do not come from a phone.

export interface Folder {
  name: string;
  /** Names joined with "/", unique in the tree. */
  path: string;
  /** Photos and videos together, in bytes. */
  size: number;
  count: number;
  /** The part of `size` and `count` that is video. */
  videoSize: number;
  videoCount: number;
  children: Folder[];
}

/** The files that sit directly in a folder, as the app counts them. */
export interface Direct {
  size: number;
  count: number;
  videoSize: number;
  videoCount: number;
}

export function direct(folder: Folder): Direct {
  const sum = (pick: (f: Folder) => number) => pick(folder) - folder.children.reduce((total, c) => total + pick(c), 0);
  return {
    size: sum((f) => f.size),
    count: sum((f) => f.count),
    videoSize: sum((f) => f.videoSize),
    videoCount: sum((f) => f.videoCount),
  };
}

// What a folder holds directly, with its subfolders listed apart. `build` adds them up.
interface Spec {
  name: string;
  gb: number;
  files: number;
  videoGb?: number;
  videos?: number;
  children?: Spec[];
}

const GB = 1_000_000_000;

function build(spec: Spec, parent: string): Folder {
  const path = parent ? `${parent}/${spec.name}` : spec.name;
  const children = (spec.children ?? []).map((c) => build(c, path)).sort((a, b) => b.size - a.size);
  const sum = (pick: (f: Folder) => number) => children.reduce((total, c) => total + pick(c), 0);
  return {
    name: spec.name,
    path,
    size: Math.round(spec.gb * GB) + sum((f) => f.size),
    count: spec.files + sum((f) => f.count),
    videoSize: Math.round((spec.videoGb ?? 0) * GB) + sum((f) => f.videoSize),
    videoCount: (spec.videos ?? 0) + sum((f) => f.videoCount),
    children,
  };
}

// A phone with a lot of chat apps, in round numbers. Folder names are the real Android ones, which
// the app shows as they are, so the same names appear in both languages.
export const storageMap: Folder = build(
  {
    name: "",
    gb: 0,
    files: 0,
    children: [
      {
        name: "DCIM",
        gb: 0,
        files: 0,
        children: [
          { name: "Camera", gb: 11.2, files: 1840, videoGb: 7.9, videos: 96 },
          { name: "Screenshots", gb: 1.3, files: 950 },
        ],
      },
      {
        name: "WhatsApp",
        gb: 0,
        files: 0,
        children: [
          {
            name: "WhatsApp Images",
            gb: 3.7,
            files: 5330,
            children: [{ name: "Sent", gb: 0.9, files: 880 }],
          },
          {
            name: "WhatsApp Video",
            gb: 3.2,
            files: 330,
            videoGb: 3.2,
            videos: 330,
            children: [{ name: "Sent", gb: 0.7, files: 80, videoGb: 0.7, videos: 80 }],
          },
        ],
      },
      {
        name: "Telegram",
        gb: 0,
        files: 0,
        children: [
          { name: "Telegram Images", gb: 1.4, files: 2080 },
          { name: "Telegram Video", gb: 2.1, files: 160, videoGb: 2.1, videos: 160 },
        ],
      },
      { name: "Download", gb: 2.2, files: 140, videoGb: 0.9, videos: 12 },
      {
        name: "Pictures",
        gb: 0,
        files: 0,
        children: [
          { name: "Instagram", gb: 0.8, files: 690, videoGb: 0.2, videos: 14 },
          { name: "Memes", gb: 0.6, files: 1420 },
        ],
      },
    ],
  },
  "",
);


export interface MapRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface Item {
  index: number;
  area: number;
}

/**
 * Lays out rectangles for `weights` inside `bounds` with the squarified treemap algorithm (Bruls,
 * Huizing and van Wijk, 2000). The result matches the indices of `weights`.
 */
export function squarify(weights: number[], bounds: MapRect): MapRect[] {
  const result: MapRect[] = weights.map(() => ({ left: bounds.left, top: bounds.top, width: 0, height: 0 }));
  if (bounds.width <= 0 || bounds.height <= 0) return result;
  const positive = weights.flatMap((area, index) => (area > 0 ? [{ index, area }] : []));
  if (positive.length === 0) return result;

  const scale = (bounds.width * bounds.height) / positive.reduce((total, i) => total + i.area, 0);
  const items: Item[] = positive.map((i) => ({ index: i.index, area: i.area * scale })).sort((a, b) => b.area - a.area);

  const worst = (row: Item[], side: number): number => {
    if (row.length === 0 || side <= 0) return Number.MAX_VALUE;
    const rowArea = row.reduce((total, i) => total + i.area, 0);
    const s2 = side * side;
    const r2 = rowArea * rowArea;
    return Math.max(...row.map((i) => Math.max((s2 * i.area) / r2, r2 / (s2 * i.area))));
  };

  // Places a row along the short side of `rect` and returns what is left of it.
  const layoutRow = (row: Item[], rect: MapRect): MapRect => {
    const rowArea = row.reduce((total, i) => total + i.area, 0);
    const horizontal = rect.width >= rect.height;
    const side = horizontal ? rect.height : rect.width;
    const thickness = side > 0 ? rowArea / side : 0;
    let offset = 0;
    for (const item of row) {
      const length = thickness > 0 ? item.area / thickness : 0;
      result[item.index] = horizontal
        ? { left: rect.left, top: rect.top + offset, width: thickness, height: length }
        : { left: rect.left + offset, top: rect.top, width: length, height: thickness };
      offset += length;
    }
    return horizontal
      ? { left: rect.left + thickness, top: rect.top, width: Math.max(0, rect.width - thickness), height: rect.height }
      : { left: rect.left, top: rect.top + thickness, width: rect.width, height: Math.max(0, rect.height - thickness) };
  };

  let rest = bounds;
  let row: Item[] = [];
  for (const item of items) {
    const side = Math.min(rest.width, rest.height);
    if (row.length === 0 || worst([...row, item], side) <= worst(row, side)) {
      row.push(item);
    } else {
      rest = layoutRow(row, rest);
      row = [item];
    }
  }
  if (row.length > 0) layoutRow(row, rest);
  return result;
}

/** One block of the map: a subfolder, or the files that sit directly in a folder that has subfolders. */
export interface Tile {
  rect: MapRect;
  folder: Folder;
  isDirectFiles: boolean;
  depth: number;
  hue: number;
  /** The blocks drawn inside this one. */
  children: Tile[];
}

/** Hue in degrees of the map root. */
export const ROOT_HUE = 210;
const GOLDEN_ANGLE = 137.5;

/**
 * Hue of the child at `rank` among its siblings, by size. The largest child keeps its parent's hue,
 * so a folder keeps its colour when the map zooms into it. The others spread around it.
 */
export function childHue(parentHue: number, rank: number): number {
  return (((parentHue + rank * GOLDEN_ANGLE) % 360) + 360) % 360;
}

/**
 * Lays out the blocks of `folder` inside `bounds`, with `gap` between nesting levels. A block whose
 * width or height is under `minSize` is left out. The blocks of `folder` itself have depth 0 and one
 * hue each, and the blocks inside them share it.
 */
export function layoutTiles(folder: Folder, bounds: MapRect, gap: number, minSize: number, baseHue: number): Tile[] {
  const fill = (current: Folder, area: MapRect, depth: number, hue: number | null): Tile[] => {
    const own = direct(current);
    const slots = [
      ...current.children.filter((c) => c.size > 0).map((c) => ({ folder: c, size: c.size, isDirectFiles: false })),
      // A folder without subfolders is drawn as itself, not as "files in this folder".
      ...(own.size > 0 ? [{ folder: current, size: own.size, isDirectFiles: current.children.length > 0 }] : []),
    ];
    const rects = squarify(
      slots.map((s) => s.size),
      area,
    );
    const tiles: Tile[] = [];
    slots.forEach((slot, i) => {
      const rect = rects[i];
      if (rect.width < minSize || rect.height < minSize) return;
      const blockHue = hue ?? childHue(baseHue, i);
      const inner: MapRect = {
        left: rect.left + gap,
        top: rect.top + gap,
        width: Math.max(0, rect.width - 2 * gap),
        height: Math.max(0, rect.height - 2 * gap),
      };
      const nested = !slot.isDirectFiles && slot.folder.children.length > 0 ? fill(slot.folder, inner, depth + 1, blockHue) : [];
      tiles.push({ rect, folder: slot.folder, isDirectFiles: slot.isDirectFiles, depth, hue: blockHue, children: nested });
    });
    return tiles;
  };
  return fill(folder, bounds, 0, null);
}

/** What a block says about its folder, in the reader's language. */
export interface Info {
  name: string;
  details: string;
  video: string;
  /** What a screen reader says for the block. */
  label: string;
  /** The view that opens when the block is opened, if the folder has subfolders. */
  open?: string;
}

/** A folder that has subfolders, with its blocks laid out and the hue it is drawn in. */
export interface View {
  id: string;
  folder: Folder;
  hue: number;
  parent: string | null;
  tiles: Tile[];
}

export const MAP_WIDTH = 800;
export const MAP_HEIGHT = 600;

/** The root first, then every folder that has subfolders, each one reachable by zooming into it. */
export function buildViews(top: Folder): View[] {
  const views: View[] = [];
  const add = (folder: Folder, hue: number, parent: string | null): void => {
    const id = `v${views.length}`;
    const tiles = layoutTiles(folder, { left: 0, top: 0, width: MAP_WIDTH, height: MAP_HEIGHT }, 4, 10, hue);
    views.push({ id, folder, hue, parent, tiles });
    folder.children.forEach((child, rank) => {
      if (child.children.length > 0) add(child, childHue(hue, rank), id);
    });
  };
  add(top, ROOT_HUE, null);
  return views;
}

/** Sizes read like the app's: decimal units, one decimal at most, in the reader's number format. */
export function formatSize(bytes: number, lang: string): string {
  const [value, unit] = bytes >= GB ? [bytes / GB, "GB"] : [bytes / 1_000_000, "MB"];
  return `${new Intl.NumberFormat(lang, { maximumFractionDigits: 1 }).format(value)} ${unit}`;
}

export function formatCount(count: number, lang: string): string {
  return new Intl.NumberFormat(lang).format(count);
}
