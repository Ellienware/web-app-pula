/**
 * User-facing wording for backend money/order statuses. PULA currently runs on SIMULATED payments only (no real provider
 * is connected), so a status such as SUCCESSFUL, PAID or REFUNDED must never read as real settlement. Every money status that
 * a simulator can produce is labelled "(demo)" / "recorded" here, in ONE place, and the wording is never colour-only: each
 * label carries text, a short symbol and a family the UI maps to styling.
 *
 * Pure module: no imports, identical on web and mobile (the same tests run against both copies).
 */

export type StatusFamily = "neutral" | "success" | "warning" | "danger" | "simulated"

export interface StatusLabel {
  /** Short text for a pill/badge. */
  label: string
  family: StatusFamily
  /** A one-character cue shown next to the text (decorative: the text carries the meaning). */
  symbol: string
  /** One plain sentence for detail views. */
  description: string
}

const mk = (label: string, family: StatusFamily, symbol: string, description: string): StatusLabel => ({ label, family, symbol, description })

export function humanise(value: string | null | undefined): string {
  if (!value) return "Unknown"
  const t = value.replace(/_/g, " ").toLowerCase()
  return t.charAt(0).toUpperCase() + t.slice(1)
}

const unknown = (value: string | null | undefined): StatusLabel =>
  mk(humanise(value), "neutral", "•", "This status is not recognised by this version of the app.")

const PAYMENT: Record<string, StatusLabel> = {
  PENDING: mk("Awaiting payment", "neutral", "…", "No payment has been recorded yet."),
  PROCESSING: mk("Processing", "neutral", "…", "The payment is being processed."),
  SUCCESSFUL: mk("Demo payment recorded", "simulated", "✓", "A simulated payment was recorded. No real money moved."),
  FAILED: mk("Payment failed", "danger", "!", "The payment did not go through."),
  REFUNDED: mk("Demo refund recorded", "simulated", "↩", "A simulated refund was recorded. Nothing was sent to a real account."),
}

const REFUND: Record<string, StatusLabel> = {
  PENDING: mk("Refund requested", "warning", "…", "The refund has been requested and is waiting to be processed."),
  PROCESSING: mk("Refund processing", "warning", "…", "The refund is being processed."),
  REFUNDED: mk("Demo refund recorded", "simulated", "↩", "A simulated refund was recorded. Nothing was sent to a real account."),
  FAILED: mk("Refund failed", "danger", "!", "The refund could not be completed."),
  NEEDS_ATTENTION: mk("Refund needs review", "warning", "⚑", "The refund needs a person to review it."),
  DUE: mk("Refund due (demo)", "warning", "…", "A refund is owed from a collective purchase. This is a simulated record."),
}

const ORDER: Record<string, StatusLabel> = {
  PENDING_PAYMENT: mk("Awaiting payment", "neutral", "…", "The order is waiting for payment."),
  PAYMENT_PROCESSING: mk("Payment processing", "neutral", "…", "The payment is being processed."),
  PAID: mk("Demo payment recorded", "simulated", "✓", "A simulated payment was recorded. No real money moved."),
  PROCESSING: mk("Being prepared", "neutral", "…", "The seller is preparing the order."),
  READY: mk("Ready", "success", "✓", "The order is ready for collection or delivery."),
  COMPLETED: mk("Completed", "success", "✓", "The order is complete."),
  CANCELLED: mk("Cancelled", "neutral", "✕", "The order was cancelled."),
  REFUND_PENDING: mk("Refund requested", "warning", "…", "A refund has been requested for this order."),
  REFUNDED: mk("Demo refund recorded", "simulated", "↩", "A simulated refund was recorded. Nothing was sent to a real account."),
}

const SETTLEMENT: Record<string, StatusLabel> = {
  NOT_ELIGIBLE: mk("Not yet eligible", "neutral", "•", "This sale is not yet eligible for settlement."),
  ELIGIBLE: mk("Eligible", "neutral", "•", "This sale is eligible and waiting to be settled."),
  PROCESSING: mk("Settling", "neutral", "…", "Settlement is in progress."),
  PAID: mk("Recorded as settled (demo)", "simulated", "✓", "A simulated settlement was recorded. No real money was paid out."),
  FAILED: mk("Settlement failed", "danger", "!", "The settlement did not complete."),
  ON_HOLD: mk("On hold", "warning", "⏸", "Settlement is paused."),
  CANCELLED: mk("Cancelled", "neutral", "✕", "The settlement was cancelled."),
}

const PAYOUT: Record<string, StatusLabel> = {
  PENDING: mk("Payout pending", "neutral", "…", "The payout has not been made."),
  PROCESSING: mk("Payout processing", "neutral", "…", "The payout is being processed."),
  SUCCESSFUL: mk("Demo payout recorded", "simulated", "✓", "A simulated payout was recorded. Nothing was paid to a real account."),
  FAILED: mk("Payout failed", "danger", "!", "The payout did not complete."),
  REVERSED: mk("Payout reversed", "warning", "↩", "The payout was reversed."),
}

export const paymentStatusLabel = (s: string | null | undefined): StatusLabel => (s && PAYMENT[s]) || unknown(s)
export const refundStatusLabel = (s: string | null | undefined): StatusLabel => (s && REFUND[s]) || unknown(s)
export const orderStatusLabel = (s: string | null | undefined): StatusLabel => (s && ORDER[s]) || unknown(s)
export const settlementStatusLabel = (s: string | null | undefined): StatusLabel => (s && SETTLEMENT[s]) || unknown(s)
export const payoutStatusLabel = (s: string | null | undefined): StatusLabel => (s && PAYOUT[s]) || unknown(s)

/** Every backend value this module knows, so tests can prove the mapping is exhaustive against the backend enums. */
export const KNOWN_STATUSES = {
  payment: Object.keys(PAYMENT),
  refund: Object.keys(REFUND),
  order: Object.keys(ORDER),
  settlement: Object.keys(SETTLEMENT),
  payout: Object.keys(PAYOUT),
}

/** True when the label stands for money that was only SIMULATED. Used to decide whether a demo notice must be on screen. */
export const isSimulated = (l: StatusLabel): boolean => l.family === "simulated"
