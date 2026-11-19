import type { APIRoute } from "astro";
import { languages, type Lang } from "../../i18n";
import { renderOgImage } from "../../lib/og";

export function getStaticPaths() {
  return languages.map((lang) => ({ params: { lang } }));
}

export const GET: APIRoute = async ({ params }) => {
  const png = await renderOgImage(params.lang as Lang);
  return new Response(new Uint8Array(png), {
    headers: {
      "Content-Type": "image/png",
    },
  });
};
