/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import i18n, { detectLocale, formatNumber, supportedLocales } from "./i18n";
import { manualLocale } from "./manual";
import en from "./locales/en.json";

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
