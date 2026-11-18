// Checks the links of the built site in dist/: every internal href and src must point to a
// file that exists, and every #fragment to an id on the target page. External links are not
// requested, so the check never depends on the network.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const dist = resolve(dirname(fileURLToPath(import.meta.url)), "../dist");
if (!existsSync(dist)) {
  console.error("dist/ not found, run the build first");
  process.exit(1);
}

// Pages that must exist, so a page that stops building fails here and not by being silently unchecked.
const pages = ["", "features/", "faq/", "it/", "it/features/", "it/faq/"];
const missing = pages.filter((page) => !existsSync(join(dist, page, "index.html")));
if (missing.length > 0) {
  console.error(`missing pages in dist/: ${missing.join(", ")}`);
  process.exit(1);
}

function* htmlFiles(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) yield* htmlFiles(path);
    else if (name.endsWith(".html")) yield path;
  }
}

const ids = new Map();
function idsOf(file) {
  if (!ids.has(file)) {
    const html = readFileSync(file, "utf8");
    ids.set(file, new Set([...html.matchAll(/\sid=["']([^"']+)["']/g)].map((m) => m[1])));
  }
  return ids.get(file);
}

// The file a URL path points to inside dist/, or null if there is none.
function target(path) {
  const file = join(dist, path);
  if (file !== dist && !file.startsWith(dist + sep)) return null;
  if (existsSync(file) && statSync(file).isFile()) return file;
  const index = join(file, "index.html");
  return existsSync(index) ? index : null;
}

let count = 0;
const errors = [];
for (const page of htmlFiles(dist)) {
  const html = readFileSync(page, "utf8");
  const pageUrl = "/" + relative(dist, dirname(page)).split("\\").join("/");
  const refs = [];
  for (const m of html.matchAll(/\s(?:href|src)=["']([^"']*)["']/g)) refs.push(m[1]);
  for (const m of html.matchAll(/\ssrcset=["']([^"']*)["']/g)) {
    for (const part of m[1].split(",")) refs.push(part.trim().split(/\s+/)[0]);
  }
  for (const ref of refs) {
    if (/^([a-z][a-z0-9+.-]*:|\/\/)/i.test(ref)) continue;
    count++;
    if (ref === "") {
      errors.push(`${relative(dist, page)}: an href or src is empty`);
      continue;
    }
    const [pathAndQuery, fragment] = ref.split("#");
    const path = pathAndQuery.split("?")[0];
    const abs = new URL(path || ".", `http://x${pageUrl.replace(/\/?$/, "/")}`).pathname;
    // The site uses trailing slashes, and a page link without one is redirected by the host.
    if (path !== "" && !abs.endsWith("/") && !/\.[a-z0-9]+$/i.test(abs)) {
      errors.push(`${relative(dist, page)}: ${ref} needs a trailing slash`);
      continue;
    }
    const file = path === "" ? page : target(decodeURIComponent(abs));
    if (!file) {
      errors.push(`${relative(dist, page)}: ${ref} does not exist`);
    } else if (fragment && file.endsWith(".html") && !idsOf(file).has(decodeURIComponent(fragment))) {
      errors.push(`${relative(dist, page)}: ${ref} has no #${fragment} on the target page`);
    }
  }
}

if (errors.length > 0) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log(`links: ${count} internal references checked, none broken`);
