// Checks the colour pairs of src/styles/tokens.css against WCAG AA. Text needs 4.5:1, and the
// red dot, which is a shape and never text, needs 3:1. A new pair goes in the list below.
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../src/styles/tokens.css", import.meta.url), "utf8");
const color = Object.fromEntries([...css.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6});/gi)].map((m) => [m[1], m[2]]));

function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const lin = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function ratio(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const TEXT = 4.5;
const SHAPE = 3;
// [foreground, background, minimum]
const pairs = [
  ["text", "bg", TEXT],
  ["text", "surface", TEXT],
  ["text", "raised", TEXT],
  ["text-muted", "bg", TEXT],
  ["text-muted", "surface", TEXT],
  ["text-muted", "raised", TEXT],
  ["text-dim", "bg", TEXT],
  ["text-dim", "surface", TEXT],
  ["accent-text", "bg", TEXT],
  ["accent-text", "surface", TEXT],
  ["accent-text", "raised", TEXT],
  ["bg", "text", TEXT],
  ["accent", "bg", SHAPE],
  ["accent", "surface", SHAPE],
  ["accent", "text", SHAPE],
];

let failed = false;
for (const [fg, bg, min] of pairs) {
  if (!color[fg] || !color[bg]) {
    console.error(`--${fg} or --${bg} is not a hex colour in tokens.css`);
    failed = true;
    continue;
  }
  const r = ratio(color[fg], color[bg]);
  const ok = r >= min;
  if (!ok) failed = true;
  console.log(`${ok ? "ok  " : "FAIL"} ${r.toFixed(2).padStart(5)}:1 (min ${min})  --${fg} ${color[fg]} on --${bg} ${color[bg]}`);
}

// The storage map colours its blocks by hue, so its names are checked over every hue and depth the
// map can draw: the page's dark ink on a block, and the light ink on a block faded to 35%.
const map = readFileSync(new URL("../src/components/StorageMap.astro", import.meta.url), "utf8");
const mapVar = (name) => parseFloat(map.match(new RegExp(`--${name}:\\s*([\\d.]+)%`))?.[1]);
const [sat, light, step] = ["map-sat", "map-light", "map-step"].map(mapVar);
if ([sat, light, step].some(Number.isNaN)) {
  console.error("--map-sat, --map-light or --map-step not found in StorageMap.astro");
  process.exit(1);
}
function hsl(h, s, l) {
  s /= 100;
  l /= 100;
  const a = s * Math.min(l, 1 - l);
  const f = (n) => {
    const k = (n + h / 30) % 12;
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))));
  };
  return "#" + [f(0), f(8), f(4)].map((v) => v.toString(16).padStart(2, "0")).join("");
}
function mix(hex, other, share) {
  const c = (h, i) => parseInt(h.slice(i, i + 2), 16);
  return "#" + [1, 3, 5].map((i) => Math.round(c(hex, i) * share + c(other, i) * (1 - share)).toString(16).padStart(2, "0")).join("");
}
let worstDark = Infinity;
let worstLight = Infinity;
for (let rank = 0; rank < 12; rank++) {
  const hue = (210 + rank * 137.5) % 360;
  for (let depth = 0; depth <= 4; depth++) {
    const block = hsl(hue, sat, light + depth * step);
    worstDark = Math.min(worstDark, ratio(color.bg, block));
    worstLight = Math.min(worstLight, ratio(color.text, mix(block, color.bg, 0.35)));
  }
}
for (const [label, r] of [["--bg on a map block", worstDark], ["--text on a faded map block", worstLight]]) {
  const ok = r >= TEXT;
  if (!ok) failed = true;
  console.log(`${ok ? "ok  " : "FAIL"} ${r.toFixed(2).padStart(5)}:1 (min ${TEXT})  ${label}, worst of 12 hues and 5 depths`);
}
if (failed) process.exit(1);
