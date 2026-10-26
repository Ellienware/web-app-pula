/**
 * Pure helpers for the split-bill screens (no React, no network) so they can be unit-tested with node:test.
 *
 * Money rule: the SERVER is the only calculation authority (POST .../expenses/preview). Nothing here rounds or splits money.
 * These helpers only (a) turn typed text into exact strings or integer hundredths without floating point, (b) check that what
 * the user typed adds up before asking the server, and (c) turn statuses into plain language that never implies real money.
 */
import type { ExpenseObligation, ExpenseSettlementState, ShareStatus, SplitType } from "./types.ts"

export const SIMULATED_NOTE =
  "Simulated demo funds. Paying a share moves demo-wallet value only. It does not pay the real bill and is not real-world settlement."

/** Typed money to integer hundredths. Rejects blanks, negatives, more than two decimals; never rounds. */
export function toMinor(raw: string): number | null {
  const s = (raw ?? "").trim().replace(/\s+/g, "").replace(",", ".")
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null
  const [whole, frac = ""] = s.split(".")
  if (whole.length > 12) return null
  return Number(whole) * 100 + Number(frac.padEnd(2, "0"))
}

/** Integer hundredths to an exact decimal string with two places ("2000" -> "20.00"). */
export function minorToDecimal(minor: number): string {
  const sign = minor < 0 ? "-" : ""
  const abs = Math.abs(Math.trunc(minor))
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`
}

/** Typed percentage ("33.34") to integer basis points (3334); at most two decimals, 0..100. */
export function toBasisPoints(raw: string): number | null {
  const s = (raw ?? "").trim().replace(",", ".")
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null
  const [whole, frac = ""] = s.split(".")
  const bp = Number(whole) * 100 + Number(frac.padEnd(2, "0"))
  return bp > 10000 ? null : bp
}

export interface AllocationCheck {
  ok: boolean
  /** Plain-language reason when not ok. */
  message: string
  /** Difference to the target in the unit of the split (hundredths or basis points); positive = too much. */
  difference: number
}

/** Do the typed custom amounts add up to the total? (Exact integer arithmetic.) */
export function checkCustomAllocation(totalMinor: number, amounts: Array<number | null>): AllocationCheck {
  if (amounts.some((a) => a === null)) return { ok: false, message: "Enter an amount for everyone included", difference: 0 }
  const sum = (amounts as number[]).reduce((s, a) => s + a, 0)
  const diff = sum - totalMinor
  return diff === 0
    ? { ok: true, message: "Balanced", difference: 0 }
    : { ok: false, message: diff > 0 ? `${minorToDecimal(diff)} too much` : `${minorToDecimal(-diff)} still to allocate`, difference: diff }
}

/** Do the typed percentages total exactly 100.00? */
export function checkPercentAllocation(bps: Array<number | null>): AllocationCheck {
  if (bps.some((b) => b === null)) return { ok: false, message: "Enter a percentage for everyone included", difference: 0 }
  const sum = (bps as number[]).reduce((s, b) => s + b, 0)
  const diff = sum - 10000
  return diff === 0
    ? { ok: true, message: "Totals 100%", difference: 0 }
    : { ok: false, message: diff > 0 ? `${minorToDecimal(diff)}% over 100` : `${minorToDecimal(-diff)}% still to allocate`, difference: diff }
}

/** Do the item amounts add up to the total and does every item have someone assigned? */
export function checkItems(totalMinor: number, items: Array<{ amountMinor: number | null; assignees: string[] }>): AllocationCheck {
  if (items.length === 0) return { ok: false, message: "Add at least one item", difference: 0 }
  if (items.some((i) => i.amountMinor === null || i.amountMinor <= 0)) return { ok: false, message: "Every item needs an amount above zero", difference: 0 }
  if (items.some((i) => i.assignees.length === 0)) return { ok: false, message: "Every item needs at least one person", difference: 0 }
  const diff = items.reduce((s, i) => s + (i.amountMinor as number), 0) - totalMinor
  return diff === 0
    ? { ok: true, message: "Items add up to the total", difference: 0 }
    : { ok: false, message: diff > 0 ? `Items are ${minorToDecimal(diff)} over the total` : `${minorToDecimal(-diff)} of the total is not on any item`, difference: diff }
}

export type Tone = "neutral" | "success" | "pending" | "failed" | "info"

export function shareStatusLabel(status: ShareStatus, method?: string | null): string {
  switch (status) {
    case "PENDING": return "Awaiting response"
    case "ACCEPTED": return "Accepted, unpaid"
    case "DISPUTED": return "Disputed"
    case "DECLINED": return "Declined"
    case "PROCESSING": return "Payment processing"
    case "PAID":
      return method === "LEDGER" ? "Paid (demo wallet)" : method === "EXTERNAL" ? "Repaid outside PULA" : method === "LEGACY" ? "Marked paid" : "Settled"
    case "REFUNDED": return "Refunded (demo wallet)"
    case "REVERSED": return "Reversed by operator"
    case "CANCELLED": return "Cancelled"
  }
}

export function shareStatusTone(status: ShareStatus, overdue = false): Tone {
  if (overdue) return "failed"
  switch (status) {
    case "PAID": return "success"
    case "PENDING":
    case "ACCEPTED":
    case "PROCESSING": return "pending"
    case "DISPUTED":
    case "DECLINED": return "failed"
    case "REFUNDED":
    case "REVERSED": return "info"
    case "CANCELLED": return "neutral"
  }
}

export function stateLabel(state: ExpenseSettlementState): string {
  switch (state) {
    case "OPEN": return "Open"
    case "PARTIALLY_SETTLED": return "Partly settled"
    case "SETTLED": return "Settled"
    case "DISPUTED": return "Disputed"
    case "OVERDUE": return "Overdue"
    case "CANCELLED": return "Cancelled"
  }
}

export function stateTone(state: ExpenseSettlementState): Tone {
  return state === "SETTLED" ? "success" : state === "OVERDUE" || state === "DISPUTED" ? "failed" : state === "CANCELLED" ? "neutral" : "pending"
}

export function splitTypeLabel(t: SplitType): string {
  return t === "EQUAL" ? "Equal" : t === "CUSTOM" ? "Custom amounts" : t === "PERCENTAGE" ? "Percentages" : "By item"
}

/** Server codes and statuses to plain language. Never says anything that sounds like a bank or real-money failure. */
export function describeSplitError(err: unknown): string {
  const e = err as { status?: number; code?: string; message?: string } | null
  const code = e?.code
  if (code === "INSUFFICIENT_FUNDS") return "Not enough demo funds in your wallet. Nothing was taken. Add demo funds in your wallet, then try again."
  if (code === "LIMIT_EXCEEDED") return "That is above the demo wallet's per-payment limit. Nothing was taken."
  if (code === "TRY_AGAIN") return "The wallet is busy. Nothing was changed: please try again."
  if (code === "SELF_TRANSFER") return "You cannot send demo funds to yourself."
  if (e?.status === 409) return e.message || "This changed while you were looking at it. Reload and review it again."
  if (e?.status === 403) return e.message || "You are not allowed to do that."
  if (e?.status === 404) return "That expense or share could not be found."
  return e?.message || "Something went wrong. Nothing was taken."
}

/** Totals for the "I owe / owed to me" header, in exact hundredths, excluding settled, cancelled and refunded-away rows. */
export function summariseObligations(rows: ExpenseObligation[]): { outstandingMinor: number; overdueMinor: number; count: number } {
  let outstandingMinor = 0
  let overdueMinor = 0
  let count = 0
  for (const r of rows) {
    if (r.expenseStatus === "CANCELLED") continue
    const s = r.share.status
    if (s === "PAID" || s === "CANCELLED") continue
    outstandingMinor += r.share.amountMinor
    count++
    if (r.share.overdue) overdueMinor += r.share.amountMinor
  }
  return { outstandingMinor, overdueMinor, count }
}
