/**
 * Presentation helpers for the PULA Future plan view. Pure and identical on web and mobile.
 * Nothing here calculates money: every figure shown is one the backend already computed. This module only chooses wording
 * and flags situations the user should notice. A plan is a planning estimate and never moves money.
 */

export interface PlanLineLike { label: string; type: string; fixedAmount: number | null; percent: number | null }
export interface PlanItemLike { id: string; label: string; amount: number; treatedAs: string }
export interface PlanLike {
  id: string
  name: string
  status: string
  reserves: number
  commitments: number
  minOperatingBuffer: number
  effectiveReserves: number
  effectiveCommitments: number
  lines: readonly PlanLineLike[]
  items: readonly PlanItemLike[]
}
export interface CalcLike {
  valid: boolean
  persisted?: boolean
  incomeBasis: number
  allocatable: number
  totalAllocated: number
  remaining: number
  warnings: readonly string[]
  createdAt: string | null
}

/** "R500.00 fixed" / "20% of what is available". `fmt` formats a rand amount (the app's own money formatter). */
export function planLineDescription(line: PlanLineLike, fmt: (n: number) => string): string {
  if (line.type === "PERCENT") return line.percent == null ? "Percent (not set)" : `${trimPercent(line.percent)}% of what is available`
  if (line.type === "FIXED") return line.fixedAmount == null ? "Fixed amount (not set)" : `${fmt(line.fixedAmount)} fixed`
  return "Custom line"
}

function trimPercent(p: number): string {
  return Number.isInteger(p) ? String(p) : p.toFixed(2).replace(/0+$/, "").replace(/\.$/, "")
}

export type PlanOutcomeTone = "none" | "ok" | "under" | "over" | "invalid"
export interface PlanOutcome { tone: PlanOutcomeTone; headline: string; detail: string }

/** Explains the latest saved calculation in words, never colour alone. A scenario is a suggestion, not an instruction. */
export function planOutcome(calc: CalcLike | null | undefined, fmt: (n: number) => string): PlanOutcome {
  if (!calc) {
    return { tone: "none", headline: "No calculation yet", detail: "Run a calculation in Smart Allocation to see how your income could be divided. Nothing moves until you decide to act." }
  }
  if (!calc.valid) {
    return { tone: "invalid", headline: "This scenario doesn't add up yet", detail: calc.warnings.length ? calc.warnings.join(" ") : "Check the amounts and run it again." }
  }
  if (calc.remaining < 0) {
    return { tone: "over", headline: `Over-planned by ${fmt(Math.abs(calc.remaining))}`, detail: "The lines add up to more than is available after reserves and commitments. Reduce a line or add income." }
  }
  if (calc.remaining > 0) {
    return { tone: "under", headline: `${fmt(calc.remaining)} not yet planned`, detail: "Part of what is available has no line yet. You could add a goal or keep it as spare cash." }
  }
  return { tone: "ok", headline: "Fully planned", detail: "Every rand that is available has a line. This is a planning estimate, not an instruction to move money." }
}

/** Gentle checklist of what is missing from a plan. */
export function planNotes(plan: PlanLike): string[] {
  const notes: string[] = []
  if (plan.lines.length === 0) notes.push("This plan has no lines yet. Add goals or fixed amounts in Smart Allocation.")
  if (plan.status === "DRAFT") notes.push("This plan is a draft.")
  if (plan.status === "PAUSED") notes.push("This plan is paused.")
  if (plan.status === "ARCHIVED") notes.push("This plan is archived.")
  const percentTotal = plan.lines.filter((l) => l.type === "PERCENT").reduce((s, l) => s + (l.percent ?? 0), 0)
  if (percentTotal > 100) notes.push(`Percent lines add up to ${trimPercent(percentTotal)}%, which is more than 100%.`)
  return notes
}

export function planStatusWord(status: string): string {
  switch (status) {
    case "DRAFT": return "Draft"
    case "ACTIVE": return "Active planning rule"
    case "PAUSED": return "Paused"
    case "ARCHIVED": return "Archived"
    default: return status
  }
}
