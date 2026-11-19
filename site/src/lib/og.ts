import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import satori from "satori";
import sharp from "sharp";
import { strings, type Lang } from "../i18n";

export const OG_WIDTH = 1200;
export const OG_HEIGHT = 630;

const require = createRequire(import.meta.url);

function loadAsset(relPath: string): Buffer {
  const fromSite = resolve(process.cwd(), relPath);
  if (existsSync(fromSite)) return readFileSync(fromSite);
  return readFileSync(resolve(process.cwd(), "site", relPath));
}

const displayBold = readFileSync(
  require.resolve("@fontsource/bricolage-grotesque/files/bricolage-grotesque-latin-700-normal.woff")
);
const textRegular = readFileSync(
  require.resolve("@fontsource/instrument-sans/files/instrument-sans-latin-400-normal.woff")
);
const textMedium = readFileSync(
  require.resolve("@fontsource/instrument-sans/files/instrument-sans-latin-500-normal.woff")
);
const textSemibold = readFileSync(
  require.resolve("@fontsource/instrument-sans/files/instrument-sans-latin-600-normal.woff")
);

const logoDataUri = `data:image/png;base64,${loadAsset("src/assets/logo-mark.png").toString("base64")}`;

export async function renderOgImage(lang: Lang): Promise<Buffer> {
  const t = strings[lang].og;

  const tree = {
    type: "div",
    props: {
      style: {
        width: `${OG_WIDTH}px`,
        height: `${OG_HEIGHT}px`,
        display: "flex",
        backgroundColor: "#121619",
        color: "#f4f1ea",
        fontFamily: "Instrument Sans",
      },
      children: {
        type: "div",
        props: {
          style: {
            flex: 1,
            display: "flex",
            flexDirection: "column",
            justifyContent: "space-between",
            padding: "56px 72px",
          },
          children: [
        // Header lockup: logo tile + wordmark on left, signature red-dot pill on right
        {
          type: "div",
          props: {
            style: {
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              width: "100%",
            },
            children: [
              {
                type: "div",
                props: {
                  style: {
                    display: "flex",
                    alignItems: "center",
                    gap: "18px",
                  },
                  children: [
                    {
                      type: "div",
                      props: {
                        style: {
                          width: "64px",
                          height: "64px",
                          borderRadius: "16px",
                          backgroundColor: "#1b2024",
                          border: "1px solid rgba(244, 241, 234, 0.14)",
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "center",
                        },
                        children: {
                          type: "img",
                          props: {
                            src: logoDataUri,
                            width: 46,
                            height: 44,
                          },
                        },
                      },
                    },
                    {
                      type: "div",
                      props: {
                        style: {
                          fontFamily: "Bricolage Grotesque",
                          fontWeight: 700,
                          fontSize: "34px",
                          letterSpacing: "-0.025em",
                          color: "#f4f1ea",
                        },
                        children: "Anteater",
                      },
                    },
                  ],
                },
              },
              {
                type: "div",
                props: {
                  style: {
                    display: "flex",
                    alignItems: "center",
                    gap: "12px",
                    padding: "12px 22px",
                    borderRadius: "999px",
                    backgroundColor: "#1b2024",
                    border: "1px solid rgba(244, 241, 234, 0.16)",
                    fontSize: "20px",
                    fontWeight: 600,
                    color: "#f4f1ea",
                  },
                  children: [
                    {
                      type: "div",
                      props: {
                        style: {
                          width: "12px",
                          height: "12px",
                          borderRadius: "999px",
                          backgroundColor: "#e53935",
                        },
                      },
                    },
                    {
                      type: "div",
                      props: {
                        children: t.badge,
                      },
                    },
                  ],
                },
              },
            ],
          },
        },

        // Center hero copy
        {
          type: "div",
          props: {
            style: {
              display: "flex",
              flexDirection: "column",
              justifyContent: "center",
              gap: "22px",
              marginTop: "12px",
              marginBottom: "12px",
            },
            children: [
              {
                type: "div",
                props: {
                  style: {
                    fontFamily: "Bricolage Grotesque",
                    fontWeight: 700,
                    fontSize: "64px",
                    lineHeight: 1.06,
                    letterSpacing: "-0.03em",
                    color: "#f4f1ea",
                    maxWidth: "800px",
                  },
                  children: t.headline,
                },
              },
              {
                type: "div",
                props: {
                  style: {
                    fontFamily: "Instrument Sans",
                    fontWeight: 400,
                    fontSize: "26px",
                    lineHeight: 1.45,
                    color: "#b7b4ab",
                    maxWidth: "680px",
                  },
                  children: t.subtitle,
                },
              },
            ],
          },
        },

        // Footer hairline + URL
        {
          type: "div",
          props: {
            style: {
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              width: "100%",
              paddingTop: "24px",
              borderTop: "1px solid rgba(244, 241, 234, 0.12)",
              fontSize: "20px",
              fontWeight: 500,
              color: "#8f8e87",
            },
            children: [
              {
                type: "div",
                props: {
                  children: "anteater.bertons.me",
                },
              },
              {
                type: "div",
                props: {
                  children: "github.com/Bert0ns/anteater",
                },
              },
            ],
          },
        },
      ],
        },
      },
    },
  };

  const svg = await satori(tree as unknown as Parameters<typeof satori>[0], {
    width: OG_WIDTH,
    height: OG_HEIGHT,
    fonts: [
      { name: "Bricolage Grotesque", data: displayBold, weight: 700, style: "normal" },
      { name: "Instrument Sans", data: textRegular, weight: 400, style: "normal" },
      { name: "Instrument Sans", data: textMedium, weight: 500, style: "normal" },
      { name: "Instrument Sans", data: textSemibold, weight: 600, style: "normal" },
    ],
  });

  return sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toBuffer();
}
