import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import sharp from "sharp";

/** Matches Anteater's Balanced compression level (`Saving.kt`): JPEG 85, up to 50 MP, 4:2:0 libjpeg. */
export const BEFORE_QUALITY = 95;
export const BALANCED_QUALITY = 85;
export const LAB_MEDIAN_SAVING_PERCENT = 47;

export interface CompressionSample {
  width: number;
  height: number;
  before: Buffer;
  after: Buffer;
  beforeBytes: number;
  afterBytes: number;
  savingPercent: number;
  labMedianPercent: number;
}

function sourcePath(): string {
  const rel = "src/assets/samples/compression-foliage-detail.png";
  const fromSite = resolve(process.cwd(), rel);
  return existsSync(fromSite) ? fromSite : resolve(process.cwd(), "site", rel);
}

let cached: Promise<CompressionSample> | undefined;

/**
 * Generates the high-quality JPEG original and the Balanced (JPEG 85) copy from the lossless
 * sample image once per process, returning both buffers and their exact byte sizes.
 */
export function getCompressionSample(): Promise<CompressionSample> {
  if (cached) return cached;
  cached = (async () => {
    const input = readFileSync(sourcePath());
    const meta = await sharp(input).metadata();
    const width = meta.width ?? 1200;
    const height = meta.height ?? 896;

    // Encode a realistic camera-grade JPEG first, then re-encode from it just as Anteater does.
    const before = await sharp(input)
      .jpeg({ quality: BEFORE_QUALITY, chromaSubsampling: "4:2:0", mozjpeg: false })
      .toBuffer();

    // `compression-foliage-detail.png` is 1200x896 (~1.08 MP), well under Balanced's 50 MP limit,
    // so `target()` keeps the full resolution.
    const after = await sharp(before)
      .jpeg({ quality: BALANCED_QUALITY, chromaSubsampling: "4:2:0", mozjpeg: false })
      .toBuffer();

    const beforeBytes = before.length;
    const afterBytes = after.length;
    const savingPercent = Math.round(((beforeBytes - afterBytes) / beforeBytes) * 100);

    return {
      width,
      height,
      before,
      after,
      beforeBytes,
      afterBytes,
      savingPercent,
      labMedianPercent: LAB_MEDIAN_SAVING_PERCENT,
    };
  })();
  return cached;
}

/** Decimal kilobytes with a no-break space, matching Android's short file size formatting. */
export function formatKb(bytes: number, lang: string): string {
  const kb = Math.round(bytes / 1000);
  return `${new Intl.NumberFormat(lang).format(kb)}\u00a0KB`;
}
