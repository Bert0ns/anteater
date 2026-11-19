import { writeFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const src = resolve(root, "..", "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp");
const pub = resolve(root, "public");
const assets = resolve(root, "src/assets");

const BG = "#121619";

// Build a PNG-wrapped multi-size .ico buffer (valid in all modern browsers and Windows).
function buildIco(images) {
  const count = images.length;
  const headerSize = 6 + count * 16;
  const header = Buffer.alloc(headerSize);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(count, 4);

  let offset = headerSize;
  for (let i = 0; i < count; i++) {
    const { size, data } = images[i];
    const base = 6 + i * 16;
    header.writeUInt8(size === 256 ? 0 : size, base);
    header.writeUInt8(size === 256 ? 0 : size, base + 1);
    header.writeUInt8(0, base + 2);
    header.writeUInt8(0, base + 3);
    header.writeUInt16LE(1, base + 4);
    header.writeUInt16LE(32, base + 6);
    header.writeUInt32LE(data.length, base + 8);
    header.writeUInt32LE(offset, base + 12);
    offset += data.length;
  }

  return Buffer.concat([header, ...images.map((img) => img.data)]);
}

async function renderSquareIcon(foreground, size, { paddingRatio = 0.1, radiusRatio = 0 } = {}) {
  const inner = Math.round(size * (1 - 2 * paddingRatio));
  const mark = await sharp(foreground)
    .resize(inner, inner, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();

  const base = sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: BG,
    },
  }).composite([{ input: mark, gravity: "center" }]);

  if (radiusRatio <= 0) {
    return base.png().toBuffer();
  }

  const radius = Math.round(size * radiusRatio);
  const mask = Buffer.from(
    `<svg width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg"><rect width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="#fff"/></svg>`
  );
  const flat = await base.png().toBuffer();
  return sharp(flat)
    .composite([{ input: mask, blend: "dest-in" }])
    .png()
    .toBuffer();
}

mkdirSync(assets, { recursive: true });

// Extract the trimmed foreground mark at full resolution for OG cards and icon rendering.
const trimmedMark = await sharp(src).trim().png().toBuffer();
writeFileSync(resolve(assets, "logo-mark.png"), trimmedMark);

// Browser favicons (rounded dark badge so the mark reads clearly on both light and dark tabs).
const icon16 = await renderSquareIcon(trimmedMark, 16, { paddingRatio: 0.08, radiusRatio: 0.22 });
const icon32 = await renderSquareIcon(trimmedMark, 32, { paddingRatio: 0.09, radiusRatio: 0.22 });
const icon64 = await renderSquareIcon(trimmedMark, 64, { paddingRatio: 0.1, radiusRatio: 0.2 });

writeFileSync(resolve(pub, "favicon.ico"), buildIco([{ size: 16, data: icon16 }, { size: 32, data: icon32 }]));
writeFileSync(resolve(pub, "favicon-32x32.png"), icon32);
writeFileSync(resolve(pub, "favicon.png"), icon64);

// Apple touch icon and PWA manifest icons (opaque #121619 canvas; OS applies its own mask).
const apple180 = await renderSquareIcon(trimmedMark, 180, { paddingRatio: 0.13 });
const icon192 = await renderSquareIcon(trimmedMark, 192, { paddingRatio: 0.12 });
const icon512 = await renderSquareIcon(trimmedMark, 512, { paddingRatio: 0.12 });

writeFileSync(resolve(pub, "apple-touch-icon.png"), apple180);
writeFileSync(resolve(pub, "icon-192.png"), icon192);
writeFileSync(resolve(pub, "icon-512.png"), icon512);

console.log("Generated favicons and src/assets/logo-mark.png");
