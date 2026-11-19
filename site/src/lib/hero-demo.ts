// The screens of the hero showcase. Each id names a PNG in `site/screenshots/<lang>/<id>.png` and
// a matching entry under `hero.demo.screens.<id>` in the string files.
import type { ImageMetadata } from "astro";
import type { Lang } from "../i18n";

export const screenIds = ["home", "category", "group", "compress-summary", "trash"] as const;
export type ScreenId = (typeof screenIds)[number];

export const SCREEN_WIDTH = 822;
export const SCREEN_HEIGHT = 1782;

// Load metadata eagerly so Astro emits only the screenshots rendered by <Image>.
const sources = import.meta.glob<{ default: ImageMetadata }>("../../screenshots/*/*.png", { eager: true });

export function loadScreenshot(lang: Lang, id: ScreenId): ImageMetadata {
  const path = `../../screenshots/${lang}/${id}.png`;
  const mod = sources[path];
  if (!mod) throw new Error(`hero demo: no screenshot at "${path}"`);
  return mod.default;
}
