/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import i18n, {
  detectLocale,
  formatNumber,
  supportedLocales,
  textDirection,
} from "./i18n";
import { manualLocale } from "./manual";
import en from "./locales/en.json";
import ar from "./locales/ar.json";

describe("detectLocale", () => {
  it("matches the full tag, then the language, then falls back to English", () => {
    expect(detectLocale(["de-AT", "fr"], ["en", "de-AT", "fr"])).toBe("de-AT");
    expect(detectLocale(["fr-CA"], ["en", "fr"])).toBe("fr");
    expect(detectLocale(["ja"], ["en"])).toBe("en");
    expect(detectLocale([], ["en"])).toBe("en");
  });
});

describe("i18n", () => {
  it("ships English and translates with plurals", () => {
    expect(supportedLocales).toContain("en");
    expect(i18n.t("projects.count", { count: 1 })).toBe("1 project");
    expect(i18n.t("projects.count", { count: 2 })).toBe("2 projects");
  });

  it("has no empty English strings", () => {
    const leaves = (o) =>
      Object.values(o).flatMap((v) =>
        typeof v === "object" ? leaves(v) : [v],
      );
    expect(leaves(en).every((v) => typeof v === "string" && v.length > 0)).toBe(
      true,
    );
  });

  it("formats numbers for the UI language", () => {
    expect(formatNumber(1234567)).toBe("1,234,567");
  });
});

describe("manualLocale", () => {
  const paths = [
    "../../../docs/manual/en/01-introduction.md",
    "../../../docs/manual/de/01-introduction.md",
  ];

  it("uses the translated manual when there is one, else English", () => {
    expect(manualLocale("de", paths)).toBe("de");
    expect(manualLocale("de-AT", paths)).toBe("de");
    expect(manualLocale("fr", paths)).toBe("en");
  });
});

describe("Arabic", () => {
  const keys = (o, prefix = "") =>
    Object.entries(o).flatMap(([k, v]) =>
      typeof v === "object" ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
    );
  const base = (key) => key.replace(/_(zero|one|two|few|many|other)$/, "");

  it("translates every English string", () => {
    const translated = new Set(keys(ar).map(base));
    expect(
      keys(en)
        .map(base)
        .filter((k) => !translated.has(k)),
    ).toEqual([]);
  });

  it("uses the Arabic plural forms and right-to-left direction", async () => {
    expect(supportedLocales).toContain("ar");
    expect(textDirection("ar")).toBe("rtl");
    expect(textDirection("en")).toBe("ltr");
    await i18n.changeLanguage("ar");
    expect(i18n.t("projects.count", { count: 2 })).toBe("مشروعان");
    expect(i18n.t("projects.count", { count: 3 })).toBe("3 مشاريع");
    expect(document.documentElement.dir).toBe("rtl");
    await i18n.changeLanguage("en");
    expect(document.documentElement.dir).toBe("ltr");
  });
});
