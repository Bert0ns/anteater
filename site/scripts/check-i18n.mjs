// Checks that the English and Italian string files have the same keys.
// A missing or an extra key in either file fails the check.
import { readFileSync } from "node:fs";

const dir = new URL("../src/i18n/", import.meta.url);
const files = { en: "en.json", it: "it.json" };

// Flattens nested objects and arrays into dotted paths, such as "photos.items.0.term".
function keys(value, prefix = "") {
  if (value === null || typeof value !== "object") return [prefix];
  return Object.entries(value).flatMap(([k, v]) => keys(v, prefix ? `${prefix}.${k}` : k));
}

const sets = Object.fromEntries(
  Object.entries(files).map(([lang, file]) => [lang, new Set(keys(JSON.parse(readFileSync(new URL(file, dir), "utf8"))))]),
);

let failed = false;
for (const [lang, set] of Object.entries(sets)) {
  for (const [otherLang, other] of Object.entries(sets)) {
    if (lang === otherLang) continue;
    for (const key of set) {
      if (!other.has(key)) {
        console.error(`${files[otherLang]} is missing "${key}" (present in ${files[lang]})`);
        failed = true;
      }
    }
  }
}

if (failed) process.exit(1);
console.log(`i18n: ${sets.en.size} keys, the same in ${Object.values(files).join(" and ")}`);
