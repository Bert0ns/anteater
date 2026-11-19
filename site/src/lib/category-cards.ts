import type { ImageMetadata } from "astro";
import booksPlant from "../assets/samples/objects-books-plant.webp";
import catWindow from "../assets/samples/pet-cat-window.webp";
import coastHills from "../assets/samples/landscape-coast-hills.webp";
import cobbleAlley from "../assets/samples/street-cobblestone-alley.webp";
import crosswalkRain from "../assets/samples/street-crosswalk-rain.webp";
import espressoPastry from "../assets/samples/food-espresso-pastry.webp";
import fountainPigeons from "../assets/samples/scene-fountain-pigeons.webp";
import greetingFlowers from "../assets/samples/forwarded-greeting-flowers.webp";
import handwrittenNotes from "../assets/samples/doc-handwritten-notes.webp";
import memeCat from "../assets/samples/forwarded-meme-cat.webp";
import pastaLunch from "../assets/samples/food-pasta-lunch.webp";
import receiptCafe from "../assets/samples/doc-receipt-cafe.webp";
import stickerCartoon from "../assets/samples/sticker-cartoon.webp";
import sunsetPark from "../assets/samples/landscape-sunset-park.webp";
import whiteboard from "../assets/samples/doc-whiteboard-diagram.webp";

/** The ids of the cards. They are the ids of the rows on the features page, so a card links to its row. */
export const photoIds = [
  "photos-exact",
  "photos-similar",
  "photos-bursts",
  "photos-blurry",
  "photos-exposure",
  "photos-screenshots",
  "photos-documents",
  "photos-forwarded",
  "photos-stickers",
] as const;

export const videoIds = [
  "videos-largest",
  "videos-exact",
  "videos-similar",
  "videos-quality",
  "videos-forwarded",
  "videos-takes",
  "videos-short",
] as const;

export type CardId = (typeof photoIds)[number] | (typeof videoIds)[number];

/**
 * The picture of each card, and the one place to swap it. A card takes the images it lists, in order.
 * Exposure takes two: the first is darkened and the second is brightened.
 */
export const cardImages: Record<CardId, ImageMetadata[]> = {
  "photos-exact": [espressoPastry],
  "photos-similar": [booksPlant],
  "photos-bursts": [fountainPigeons],
  "photos-blurry": [crosswalkRain],
  "photos-exposure": [sunsetPark, coastHills],
  "photos-screenshots": [pastaLunch],
  "photos-documents": [whiteboard, receiptCafe, handwrittenNotes],
  "photos-forwarded": [memeCat, greetingFlowers],
  "photos-stickers": [stickerCartoon],
  "videos-largest": [sunsetPark],
  "videos-exact": [cobbleAlley],
  "videos-similar": [coastHills],
  "videos-quality": [crosswalkRain],
  "videos-forwarded": [memeCat],
  "videos-takes": [fountainPigeons],
  "videos-short": [catWindow],
};

/** The badges of a video tile: length in seconds and size in bytes. A card with several tiles lists one pair each. */
export const videoMeta: Record<(typeof videoIds)[number], { seconds: number; bytes: number }[]> = {
  "videos-largest": [{ seconds: 252, bytes: 2_400_000_000 }],
  "videos-exact": [{ seconds: 48, bytes: 86_000_000 }],
  "videos-similar": [
    { seconds: 65, bytes: 410_000_000 },
    { seconds: 65, bytes: 96_000_000 },
  ],
  "videos-quality": [{ seconds: 32, bytes: 128_000_000 }],
  "videos-forwarded": [{ seconds: 21, bytes: 14_000_000 }],
  "videos-takes": [
    { seconds: 12, bytes: 38_000_000 },
    { seconds: 14, bytes: 44_000_000 },
    { seconds: 11, bytes: 35_000_000 },
  ],
  "videos-short": [{ seconds: 6, bytes: 9_000_000 }],
};

/** 252 becomes "4:12". */
export function formatLength(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
