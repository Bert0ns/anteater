import en from "./en.json";
import it from "./it.json";

export const strings = { en, it };
export type Lang = keyof typeof strings;
export type Strings = typeof en;

export const languages: Lang[] = ["en", "it"];

/** The path of a page in a language. English lives at `/`, Italian at `/it/`. */
export function homePath(lang: Lang): string {
  return lang === "en" ? "/" : `/${lang}/`;
}

export function otherLang(lang: Lang): Lang {
  return lang === "en" ? "it" : "en";
}
