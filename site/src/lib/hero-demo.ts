// The data of the hero demo. Adding or swapping a photo is a change to `tiles` and nothing else: the
// component, the legend, the counter and the text alternative all read from it.
import type { ImageMetadata } from "astro";

/** The app's categories that the demo shows, in the order of the Clean screen. */
export const categories = ["exact", "similar", "blurry", "exposure", "screenshot", "document", "forwarded"] as const;
export type Category = (typeof categories)[number];

/** How a source image is made to look like its category, in CSS and with no new image file. */
export type Treatment = "blur" | "dark" | "bright" | "crop" | "screenshot";

export interface Tile {
  /** A file in `src/assets/samples/`. The same file can appear in more than one tile. */
  file: string;
  /** The category the scan puts the tile in. Leave it out for a photo to keep. */
  category?: Category;
  treatment?: Treatment;
  /** The size in MB the counter adds when the red dot eats the tile. Made up. */
  mb?: number;
}

/** The rows of the grid that the phone shows. The phone's height follows from it. */
export const rows = 4;

// Three tiles per row, in the order the scan meets them. The first `rows` rows are what the phone shows,
// and every flagged tile belongs in them, spread over the rows so that the scan finds something on its
// way down. Tiles after those rows are below the fold. They are photos to keep, and they slide up into
// the gaps when the red dot has eaten the flagged ones, so a long list fills the phone at the end.
export const tiles: Tile[] = [
  { file: "food-espresso-pastry.webp" },
  { file: "food-espresso-pastry.webp", category: "exact", mb: 6.8 },
  { file: "doc-receipt-cafe.webp", category: "document", mb: 3.4 },
  { file: "pet-cat-window.webp" },
  { file: "pet-cat-window.webp", category: "similar", treatment: "crop", mb: 7.4 },
  { file: "street-crosswalk-rain.webp", category: "blurry", treatment: "blur", mb: 5.9 },
  { file: "street-cobblestone-alley.webp", category: "exposure", treatment: "dark", mb: 4.2 },
  { file: "forwarded-greeting-flowers.webp", category: "forwarded", mb: 0.8 },
  { file: "landscape-coast-hills.webp", category: "exposure", treatment: "bright", mb: 8.3 },
  { file: "landscape-sunset-park.webp", category: "screenshot", treatment: "screenshot", mb: 1.9 },
  { file: "doc-handwritten-notes.webp", category: "document", mb: 4.1 },
  { file: "forwarded-meme-cat.webp", category: "forwarded", mb: 0.6 },
  { file: "objects-desk-flatlay.webp" },
  { file: "food-pasta-lunch.webp" },
  { file: "objects-books-plant.webp" },
  { file: "scene-fountain-pigeons.webp" },
  { file: "street-cobblestone-alley.webp" },
  { file: "landscape-coast-hills.webp" },
  { file: "landscape-sunset-park.webp" },
  { file: "street-crosswalk-rain.webp" },
  { file: "food-pasta-lunch.webp" },
  { file: "food-espresso-pastry.webp" },
];

// Load metadata eagerly to avoid dynamic-import warnings; Astro only emits images rendered by <Image>.
const sources = import.meta.glob<{ default: ImageMetadata }>("../assets/samples/*.webp", { eager: true });

export async function loadImage(file: string): Promise<ImageMetadata> {
  const mod = sources[`../assets/samples/${file}`];
  if (!mod) throw new Error(`hero demo: no sample image named "${file}" in src/assets/samples/`);
  return mod.default;
}

export function summarize() {
  const marked = tiles.filter((tile) => tile.category);
  const counts = Object.fromEntries(categories.map((c) => [c, marked.filter((tile) => tile.category === c).length])) as Record<
    Category,
    number
  >;
  const mb = marked.reduce((sum, tile) => sum + (tile.mb ?? 0), 0);
  return { total: tiles.length, marked: marked.length, kept: tiles.length - marked.length, counts, mb };
}

/** The size of the counter, in the reader's number format, with one decimal like the app. */
export function formatMb(mb: number, lang: string): string {
  return new Intl.NumberFormat(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(mb);
}
