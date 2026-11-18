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
if (failed) process.exit(1);
