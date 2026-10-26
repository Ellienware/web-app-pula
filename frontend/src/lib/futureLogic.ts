/**
 * PULA Future — UI-side helpers. These are for FORM VALIDATION and DISPLAY ONLY.
 * Every financial figure shown to the user (allocations, remaining, projections) comes from the backend.
 * Nothing here calculates money that is stored or acted upon.
 */

export type AmountResult = { ok: true; value: string } | { ok: false; error: string }

/** Parses a rand amount into an exact two-decimal string. Zero is allowed (unlike wallet amounts). */
export function parseNonNegativeAmount(raw: string, label = "Amount"): AmountResult {
  let s = (raw ?? "").trim().replace(/\s+/g, "").replace(/^(zar|r)/i, "")
  if (s === "") return { ok: false, error: `Enter ${label.toLowerCase()}` }
  if (s.startsWith("-")) return { ok: false, error: `${label} cannot be negative` }
  if (s.includes(",") && s.includes(".")) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  if ((s.match(/[.,]/g) ?? []).length > 1) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  s = s.replace(",", ".")
  if (!/^\d+(\.\d+)?$/.test(s)) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  const [whole, frac = ""] = s.split(".")
  if (frac.length > 2) return { ok: false, error: "At most two decimal places" }
  if (whole.replace(/^0+/, "").length > 11) return { ok: false, error: "That amount is too large" }
  return { ok: true, value: `${whole.replace(/^0+(?=\d)/, "")}.${frac.padEnd(2, "0")}` }
}

/** Parses a percentage 0–100 with at most two decimals; returns the normalised string. */
export function parsePercent(raw: string, label = "Percentage"): AmountResult {
  const s = (raw ?? "").trim().replace(/%$/, "").replace(",", ".")
  if (s === "") return { ok: false, error: `Enter ${label.toLowerCase()}` }
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return { ok: false, error: `${label} must be a number with at most two decimals` }
  const n = Number(s)
  if (n > 100) return { ok: false, error: `${label} cannot be more than 100` }
  return { ok: true, value: String(n) }
}

/** Signed percentage (e.g. a return assumption). Range-checked to the same bounds the backend enforces. */
export function parseSignedPercent(raw: string, min: number, max: number, label: string): AmountResult {
  const s = (raw ?? "").trim().replace(/%$/, "").replace(",", ".")
  if (s === "") return { ok: false, error: `Enter ${label.toLowerCase()}` }
  if (!/^-?\d+(\.\d{1,2})?$/.test(s)) return { ok: false, error: `${label} must be a number with at most two decimals` }
  const n = Number(s)
  if (n < min || n > max) return { ok: false, error: `${label} must be between ${min}% and ${max}%` }
  return { ok: true, value: String(n) }
}

/** Sum of percentage strings in basis points (integers — no float drift). Used only for an inline hint. */
export function percentTotalBp(percents: string[]): number {
  return percents.reduce((acc, p) => acc + Math.round(Number(p) * 100), 0)
}

export function percentHint(totalBp: number): string | null {
  return totalBp > 10_000 ? "Percentages add up to more than 100%. This scenario cannot be saved as an active plan." : null
}

/** For a progress bar only. Clamped 0–100; never used to decide anything financial. */
export function progressPercent(recorded: number, target: number): number {
  if (!(target > 0) || !(recorded > 0)) return 0
  return Math.min(100, Math.floor((recorded / target) * 100))
}

export function newCalcKey(): string {
  return `calc-${globalThis.crypto.randomUUID()}`
}

export const GOAL_CATEGORIES: { value: string; label: string }[] = [
  { value: "EMERGENCY_FUND", label: "Emergency fund" },
  { value: "EDUCATION", label: "Education" },
  { value: "HOUSING", label: "Housing" },
  { value: "VEHICLE_TRANSPORT", label: "Vehicle or transport" },
  { value: "BUSINESS_EQUIPMENT", label: "Business equipment" },
  { value: "BUSINESS_EXPANSION", label: "Business expansion" },
  { value: "STOCK_INVENTORY", label: "Stock and inventory" },
  { value: "RETIREMENT", label: "Retirement" },
  { value: "GENERAL_SAVINGS", label: "General savings" },
  { value: "SAVE_TO_BUY", label: "Save-to-Buy" },
  { value: "OTHER", label: "Other" },
]

export function categoryLabel(value?: string | null): string {
  return GOAL_CATEGORIES.find((c) => c.value === value)?.label ?? "Uncategorised"
}

/** Labels that keep planning, simulation and recorded money visibly different. */
export const SCOPE_LABEL: Record<string, string> = {
  PERSONAL: "Personal",
  GROUP_TRACKED: "Group-tracked (not your personal balance)",
}

export function planStatusLabel(status: string): string {
  switch (status) {
    case "DRAFT": return "Draft"
    case "ACTIVE": return "Active planning rule"
    case "PAUSED": return "Paused"
    case "ARCHIVED": return "Archived"
    default: return status
  }
}

/** Never shows raw HTTP details; falls back to a safe message. */
export function describeFutureError(e: unknown): string {
  const msg = e instanceof Error ? e.message : ""
  return msg && !/^\s*<|stack|exception/i.test(msg) ? msg : "Something went wrong. Please try again."
}

// ---------------------------------------------------------------- phase 2 additions

export type IncomeSourceKey = "USER_ENTERED" | "BUSINESS_MANUAL_ENTRIES" | "MERCHANT_SIMULATED_SETTLEMENTS"

export const INCOME_SOURCES: { value: IncomeSourceKey; label: string; needsBusiness: boolean }[] = [
  { value: "USER_ENTERED", label: "A figure I enter", needsBusiness: false },
  { value: "BUSINESS_MANUAL_ENTRIES", label: "My manual business income entries", needsBusiness: true },
  { value: "MERCHANT_SIMULATED_SETTLEMENTS", label: "Paid merchant settlements (simulated)", needsBusiness: true },
]

export function incomeSourceLabel(v: string): string {
  return INCOME_SOURCES.find((s) => s.value === v)?.label ?? v
}

/** Plain-language label that never presents simulated or manual income as verified. */
export function incomeTrustLabel(v: string): string {
  switch (v) {
    case "MERCHANT_SIMULATED_SETTLEMENTS": return "Settled in PULA (simulated) — not bank-confirmed"
    case "BUSINESS_MANUAL_ENTRIES": return "Manually recorded — unverified"
    default: return "Entered by you — unverified"
  }
}

const ISO = /^\d{4}-\d{2}-\d{2}$/
/** Returns an error message or null. Real calendar dates only, from <= to, at most 366 days. */
export function validateDateRange(from: string, to: string): string | null {
  if (!ISO.test(from) || !ISO.test(to)) return "Choose a start and end date"
  const f = new Date(`${from}T00:00:00Z`), t = new Date(`${to}T00:00:00Z`)
  if (Number.isNaN(f.getTime()) || Number.isNaN(t.getTime()) || f.toISOString().slice(0, 10) !== from || t.toISOString().slice(0, 10) !== to) {
    return "Choose real calendar dates"
  }
  if (t < f) return "The end date must not be before the start date"
  if ((t.getTime() - f.getTime()) / 86_400_000 > 366) return "Choose a period of one year or less"
  return null
}

export const ITEM_KINDS: { value: string; label: string; treatedAs: "Reserve" | "Commitment" }[] = [
  { value: "OPERATING_EXPENSES", label: "Operating expenses", treatedAs: "Commitment" },
  { value: "SUPPLIER_COMMITMENTS", label: "Supplier commitments", treatedAs: "Commitment" },
  { value: "RENT_UTILITIES", label: "Rent and utilities", treatedAs: "Commitment" },
  { value: "PAYROLL", label: "Payroll", treatedAs: "Commitment" },
  { value: "TAXES", label: "Taxes and declared obligations", treatedAs: "Commitment" },
  { value: "EMERGENCY_RESERVE", label: "Emergency cash reserve", treatedAs: "Reserve" },
  { value: "PLANNED_INVENTORY", label: "Planned inventory purchases", treatedAs: "Commitment" },
]

export function itemKindLabel(v: string): string {
  return ITEM_KINDS.find((k) => k.value === v)?.label ?? v
}

/** Goal status wording. PAUSED means "not accepting contributions", nothing about money moving. */
export function goalStatusLabel(status: string): string {
  switch (status) {
    case "ACTIVE": return "Active"
    case "PAUSED": return "Paused — not accepting contributions"
    case "REACHED": return "Target recorded as reached"
    case "CLOSED": return "Closed"
    default: return status
  }
}

export function validateGoalForm(title: string, target: string, deadline: string): { ok: true; target: string } | { ok: false; error: string } {
  if (!title.trim()) return { ok: false, error: "Give your goal a name" }
  const a = parseNonNegativeAmount(target, "Target amount")
  if (!a.ok) return { ok: false, error: a.error }
  if (Number(a.value) <= 0) return { ok: false, error: "Target amount must be more than zero" }
  if (deadline && !ISO.test(deadline)) return { ok: false, error: "Use the date format YYYY-MM-DD" }
  return { ok: true, target: a.value }
}

export function reminderLabel(kind: string): string {
  switch (kind) {
    case "GOAL_DEADLINE": return "Goal target dates"
    case "PLAN_REVIEW": return "Monthly plan review"
    case "SAVE_TO_BUY_PROGRESS": return "Save-to-Buy progress"
    default: return kind
  }
}

export const COACH_STARTERS = [
  "Why does my plan show a warning?",
  "How do fees affect my savings?",
  "What does inflation do to my goal?",
  "How can I spot an investment scam?",
]

/** Coach source label: tells the user whether an AI model or the built-in rules wrote the answer. */
export function coachSourceLabel(source: string): string {
  return source === "ai" ? "Written by an AI model from your PULA figures" : "Built-in explanation (no AI used)"
}
