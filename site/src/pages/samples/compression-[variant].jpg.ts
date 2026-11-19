import type { APIRoute } from "astro";
import { getCompressionSample } from "../../lib/compression-sample";

const variants = ["before", "after"] as const;
type Variant = (typeof variants)[number];

export function getStaticPaths() {
  return variants.map((variant) => ({ params: { variant } }));
}

export const GET: APIRoute = async ({ params }) => {
  const sample = await getCompressionSample();
  const variant = params.variant as Variant;
  const jpeg = variant === "before" ? sample.before : sample.after;
  return new Response(new Uint8Array(jpeg), {
    headers: {
      "Content-Type": "image/jpeg",
    },
  });
};
