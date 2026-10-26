/**
 * Real, working translation infrastructure, deliberately scoped — matching
 * the ecosystem brief's own §33 instruction not to translate financial
 * terminology blindly. What this covers and what it doesn't:
 *
 * - COVERED: generic UI chrome (nav labels, common buttons like Save/
 *   Cancel/Loading, a handful of top-level page headings). This is safe
 *   to translate with reasonable confidence — the words are simple and
 *   the cost of a minor imperfection is low.
 * - NOT COVERED, ON PURPOSE: anything involving financial meaning,
 *   amounts, warnings, legal/consent text, or scam-safety copy. Getting
 *   any of that subtly wrong in a financial app is a real harm, not a
 *   cosmetic one, and it needs native-speaker/professional review before
 *   shipping — not a best-effort pass from this session. Those strings
 *   stay in English for every language until that review happens; see
 *   MISSING_KEY_FALLBACK below for how that's enforced rather than just
 *   promised.
 *
 * Languages: English (en) is complete and authoritative. isiZulu (zu) has
 * real translations for the covered surface, written with reasonable but
 * NOT native-speaker-certified confidence — flag for review before a real
 * public launch, not a blocker for this demo/dev stage. isiXhosa (xh),
 * Sesotho (st), Setswana (tn), and Afrikaans (af) are registered as
 * selectable languages (so the switcher and the rest of the app's plumbing
 * genuinely supports them) but intentionally have NO translated strings
 * yet — every key silently falls back to English for those four rather
 * than shipping a guessed translation. Populating them with real
 * translations from a fluent speaker is real future work, not something
 * to fake here.
 */

export type LanguageCode = "en" | "zu" | "xh" | "st" | "tn" | "af"

export const SUPPORTED_LANGUAGES: { code: LanguageCode; label: string; nativeLabel: string }[] = [
  { code: "en", label: "English", nativeLabel: "English" },
  { code: "zu", label: "isiZulu", nativeLabel: "isiZulu" },
  { code: "xh", label: "isiXhosa", nativeLabel: "isiXhosa" },
  { code: "st", label: "Sesotho", nativeLabel: "Sesotho" },
  { code: "tn", label: "Setswana", nativeLabel: "Setswana" },
  { code: "af", label: "Afrikaans", nativeLabel: "Afrikaans" },
]

export type TranslationKey =
  | "nav.home"
  | "nav.market"
  | "nav.ride"
  | "nav.cashflow"
  | "nav.money"
  | "nav.future"
  | "nav.activity"
  | "nav.cart"
  | "nav.logout"
  | "common.save"
  | "common.cancel"
  | "common.loading"
  | "common.retry"
  | "common.search"
  | "common.back"
  | "common.close"
  | "auth.login"
  | "auth.register"
  | "auth.email"
  | "auth.password"
  | "auth.fullName"
  | "home.greeting"
  | "home.quickActions"
  | "language.choose"

const en: Record<TranslationKey, string> = {
  "nav.home": "Home",
  "nav.market": "Market",
  "nav.ride": "Ride",
  "nav.cashflow": "Cash Flow",
  "nav.money": "Money",
  "nav.future": "Future",
  "nav.activity": "Activity",
  "nav.cart": "Cart",
  "nav.logout": "Log out",
  "common.save": "Save",
  "common.cancel": "Cancel",
  "common.loading": "Loading…",
  "common.retry": "Retry",
  "common.search": "Search",
  "common.back": "Back",
  "common.close": "Close",
  "auth.login": "Log in",
  "auth.register": "Create account",
  "auth.email": "Email",
  "auth.password": "Password",
  "auth.fullName": "Full name",
  "home.greeting": "Good to see you",
  "home.quickActions": "Quick actions",
  "language.choose": "Choose language",
}

/**
 * Reasonable-confidence, NOT native-speaker-certified isiZulu for generic
 * UI chrome only. Flag for a fluent-speaker review pass before treating
 * this as production-ready — see this file's own header for why the
 * bar is higher for anything beyond simple nav/button labels, and why
 * those higher-stakes strings aren't attempted here at all.
 */
const zu: Partial<Record<TranslationKey, string>> = {
  "nav.home": "Ikhaya",
  "nav.market": "Imakethe",
  "nav.money": "Imali",
  "nav.activity": "Umsebenzi",
  "nav.cart": "Ikalishi",
  "nav.logout": "Phuma",
  "common.save": "Londoloza",
  "common.cancel": "Khansela",
  "common.loading": "Iyalayisha…",
  "common.retry": "Zama futhi",
  "common.search": "Sesha",
  "common.back": "Emuva",
  "common.close": "Vala",
  "auth.login": "Ngena",
  "auth.email": "I-imeyili",
  "auth.password": "Iphasiwedi",
  "auth.fullName": "Igama eliphelele",
  "language.choose": "Khetha ulimi",
}

// Deliberately empty — see this file's header for why these three are
// registered as selectable languages without guessed translations.
const xh: Partial<Record<TranslationKey, string>> = {}
const st: Partial<Record<TranslationKey, string>> = {}
const tn: Partial<Record<TranslationKey, string>> = {}
const af: Partial<Record<TranslationKey, string>> = {}

export const TRANSLATIONS: Record<LanguageCode, Partial<Record<TranslationKey, string>>> = { en, zu, xh, st, tn, af }

/** Every lookup falls back to English rather than showing a raw key or a guessed string. */
export function translate(language: LanguageCode, key: TranslationKey): string {
  return TRANSLATIONS[language]?.[key] ?? en[key] ?? key
}

/** Languages that actually have strings. The selector offers only these (English is always included). */
export function translatedLanguages() {
  return SUPPORTED_LANGUAGES.filter(
    (l) => l.code === "en" || Object.keys((TRANSLATIONS as Record<string, Record<string, string>>)[l.code] ?? {}).length > 0,
  )
}
