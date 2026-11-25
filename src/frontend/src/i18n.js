/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import i18n from "i18next";
import { initReactI18next } from "react-i18next";

export const FALLBACK_LOCALE = "en";

// Each src/locales/<locale>.json is a language; en is the reference catalog.
const catalogs = import.meta.glob("./locales/*.json", {
  import: "default",
  eager: true,
});
const resources = Object.fromEntries(
  Object.entries(catalogs).map(([path, translation]) => [
    /\/([^/]+)\.json$/.exec(path)[1],
    { translation },
  ]),
);

export const supportedLocales = Object.keys(resources);

/** The first supported browser language, matched on the full tag and then on its language. */
export function detectLocale(
  preferred = globalThis.navigator?.languages ?? [],
  supported = supportedLocales,
) {
  for (const tag of preferred) {
    if (supported.includes(tag)) return tag;
    const language = tag.split("-")[0];
    if (supported.includes(language)) return language;
  }
  return FALLBACK_LOCALE;
}

i18n.use(initReactI18next).init({
  resources,
  lng: detectLocale(),
  fallbackLng: FALLBACK_LOCALE,
  initAsync: false,
  // React escapes rendered text already.
  interpolation: { escapeValue: false },
});

/** The text direction of a UI language: right to left for Arabic, Hebrew, Persian and Urdu. */
export function textDirection(lng) {
  try {
    return new Intl.Locale(lng).getTextInfo().direction;
  } catch {
    return ["ar", "fa", "he", "ur"].includes(lng?.split("-")[0])
      ? "rtl"
      : "ltr";
  }
}

function syncDocumentLanguage(lng) {
  if (!globalThis.document) return;
  document.documentElement.lang = lng;
  document.documentElement.dir = textDirection(lng);
}
syncDocumentLanguage(i18n.language);
i18n.on("languageChanged", syncDocumentLanguage);

export function locale() {
  return i18n.language || FALLBACK_LOCALE;
}

/** Formats a date and time in the UI language; invalid input is returned as given. */
export function formatDateTime(value, options) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString(locale(), options);
}

export function formatDate(value) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString(locale());
}

export function formatNumber(value, options) {
  return new Intl.NumberFormat(locale(), options).format(value);
}

export function relativeTimeFormat(options) {
  return new Intl.RelativeTimeFormat(locale(), options);
}

export default i18n;
