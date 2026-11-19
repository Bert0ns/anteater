import type { APIRoute } from "astro";
import { languages, pagePath, pages } from "../i18n";

export const GET: APIRoute = ({ site }) => {
  const origin = site!.origin;

  const entries = pages.flatMap((page) => {
    const alternates = [
      ...languages.map(
        (alt) => `    <xhtml:link rel="alternate" hreflang="${alt}" href="${origin}${pagePath(alt, page)}" />`
      ),
      `    <xhtml:link rel="alternate" hreflang="x-default" href="${origin}${pagePath("en", page)}" />`,
    ].join("\n");

    return languages.map(
      (lang) => `  <url>
    <loc>${origin}${pagePath(lang, page)}</loc>
${alternates}
  </url>`
    );
  });

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${entries.join("\n")}
</urlset>
`;

  return new Response(xml, {
    headers: {
      "Content-Type": "application/xml; charset=utf-8",
    },
  });
};
