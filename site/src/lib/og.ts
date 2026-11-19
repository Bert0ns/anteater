import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import satori from "satori";
import sharp from "sharp";
import { strings, type Lang } from "../i18n";

export const OG_WIDTH = 1200;
export const OG_HEIGHT = 630;

// Mirrors site/src/styles/tokens.css (Satori renders standalone SVGs without CSS variables).
const COLOR = {
  bg: "#121619",
  surface: "#1b2024",
  line: "#2c3338",
  lineStrong: "#3a4249",
  text: "#f4f1ea",
  textMuted: "#b7b4ab",
  textDim: "#8f8e87",
  accent: "#e53935",
} as const;

const require = createRequire(import.meta.url);

function loadFont(specifier: string): Buffer {
  return readFileSync(require.resolve(specifier));
}

function loadLogoDataUri(): string {
  const relPath = "src/assets/logo-mark.png";
  const fromSite = resolve(process.cwd(), relPath);
  const path = existsSync(fromSite) ? fromSite : resolve(process.cwd(), "site", relPath);
  return `data:image/png;base64,${readFileSync(path).toString("base64")}`;
}

const displayBold = loadFont("@fontsource/bricolage-grotesque/files/bricolage-grotesque-latin-700-normal.woff");
const textRegular = loadFont("@fontsource/instrument-sans/files/instrument-sans-latin-400-normal.woff");
const textMedium = loadFont("@fontsource/instrument-sans/files/instrument-sans-latin-500-normal.woff");
const textSemibold = loadFont("@fontsource/instrument-sans/files/instrument-sans-latin-600-normal.woff");
const logoDataUri = loadLogoDataUri();

type SatoriElement = Exclude<Parameters<typeof satori>[0], string | number | boolean | Iterable<unknown> | null | undefined>;
type Style = Record<string, string | number>;
type Child = SatoriElement | string;

function div(style: Style, children?: Child | Child[]): SatoriElement {
  return { type: "div", key: null, props: children === undefined ? { style } : { style, children } };
}

function img(src: string, width: number, height: number): SatoriElement {
  return { type: "img", key: null, props: { src, width, height } };
}

export async function renderOgImage(lang: Lang): Promise<Buffer> {
  const t = strings[lang].og;

  const header = div({ display: "flex", alignItems: "center", justifyContent: "space-between", width: "100%" }, [
    div({ display: "flex", alignItems: "center", gap: "18px" }, [
      div(
        {
          width: "64px",
          height: "64px",
          borderRadius: "16px",
          backgroundColor: COLOR.surface,
          border: `1px solid ${COLOR.lineStrong}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        },
        img(logoDataUri, 46, 44)
      ),
      div(
        {
          fontFamily: "Bricolage Grotesque",
          fontWeight: 700,
          fontSize: "34px",
          letterSpacing: "-0.025em",
          color: COLOR.text,
        },
        "Anteater"
      ),
    ]),
    div(
      {
        display: "flex",
        alignItems: "center",
        gap: "12px",
        padding: "12px 22px",
        borderRadius: "999px",
        backgroundColor: COLOR.surface,
        border: `1px solid ${COLOR.lineStrong}`,
        fontSize: "20px",
        fontWeight: 600,
        color: COLOR.text,
      },
      [
        div({ width: "12px", height: "12px", borderRadius: "999px", backgroundColor: COLOR.accent }),
        div({}, t.badge),
      ]
    ),
  ]);

  const body = div({ display: "flex", flexDirection: "column", justifyContent: "center", gap: "22px" }, [
    div(
      {
        fontFamily: "Bricolage Grotesque",
        fontWeight: 700,
        fontSize: "64px",
        lineHeight: 1.06,
        letterSpacing: "-0.03em",
        color: COLOR.text,
        maxWidth: "800px",
      },
      t.headline
    ),
    div(
      {
        fontFamily: "Instrument Sans",
        fontWeight: 400,
        fontSize: "26px",
        lineHeight: 1.45,
        color: COLOR.textMuted,
        maxWidth: "680px",
      },
      t.subtitle
    ),
  ]);

  const footer = div(
    {
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      width: "100%",
      paddingTop: "24px",
      borderTop: `1px solid ${COLOR.line}`,
      fontSize: "20px",
      fontWeight: 500,
      color: COLOR.textDim,
    },
    [div({}, "anteater.bertons.me"), div({}, "github.com/Bert0ns/anteater")]
  );

  // Separate outer 1200x630 box from padded column so Satori's content-box padding does not overflow.
  const tree = div(
    {
      width: `${OG_WIDTH}px`,
      height: `${OG_HEIGHT}px`,
      display: "flex",
      backgroundColor: COLOR.bg,
      color: COLOR.text,
      fontFamily: "Instrument Sans",
    },
    div(
      {
        flex: 1,
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        padding: "56px 72px",
      },
      [header, body, footer]
    )
  );

  const svg = await satori(tree, {
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
