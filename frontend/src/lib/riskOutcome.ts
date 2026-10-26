import { api } from "./api"

/**
 * Transaction-risk outcomes (Phase 7). The BACKEND decides; this file only translates its structured error into something the
 * UI can show. The message text comes from the server and is deliberately non-accusatory and free of thresholds. Nothing here
 * can allow, approve or skip a payment: a "step-up" is only an authenticated user's re-confirmation recorded by the server.
 */
export type RiskKind = "STEP_UP" | "REVIEW" | "BLOCKED" | "RETRY"

export interface RiskOutcome {
  code: string
  kind: RiskKind
  assessmentId: string | null
  message: string
}

const KINDS: Record<string, RiskKind> = {
  RISK_STEP_UP_REQUIRED: "STEP_UP",
  RISK_REVIEW_PENDING: "REVIEW",
  RISK_BLOCKED: "BLOCKED",
  RISK_ASSESSMENT_EXPIRED: "RETRY",
  RISK_REASSESSMENT_REQUIRED: "RETRY",
  RISK_UNAVAILABLE: "RETRY",
  RISK_CONTEXT_MISMATCH: "BLOCKED",
  RISK_NOT_ASSESSED: "RETRY",
}

/** Parses "CODE|assessmentId|safe message" produced by the backend gate; returns null for any other error. */
export function parseRiskError(e: unknown): RiskOutcome | null {
  const raw = e instanceof Error ? e.message : typeof e === "string" ? e : ""
  const [code, id, ...rest] = raw.split("|")
  const kind = KINDS[code]
  if (!kind) return null
  return { code, kind, assessmentId: id || null, message: rest.join("|") || "We could not complete the safety check for this payment. Nothing was charged." }
}

/** Safe text for any error: the server's risk message if it is one, otherwise the original message. */
export function friendlyError(e: unknown, fallback = "Something went wrong"): string {
  return parseRiskError(e)?.message ?? (e instanceof Error ? e.message : fallback)
}

export const riskApi = {
  /** The signed-in owner re-confirms the exact amount and currency of this payment. The server verifies both. */
  confirmStepUp: (assessmentId: string, amount: number, currency: string) =>
    api.post(`/risk/assessments/${encodeURIComponent(assessmentId)}/confirm-step-up`, { amount, currency }),
}
