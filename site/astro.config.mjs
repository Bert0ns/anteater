import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://anteater.bertons.me",
  trailingSlash: "always",
  build: { format: "directory" },
  i18n: {
    locales: ["en", "it"],
    defaultLocale: "en",
    routing: { prefixDefaultLocale: false },
  },
});
