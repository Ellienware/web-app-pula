/**
 * Pure helpers for the PULA Money DEMO wallet screens (no React, no network) so they can be unit-tested with node:test.
 * Nothing here computes a balance: the server/ledger is the only authority. These helpers only validate what the user
 * typed, recognise a payment code, and turn server codes into plain-language messages.
 */
import type { PaymentRequest, PaymentRequestStatus, TxStatus, WalletTransaction } from "./walletTypes.ts"

export type AmountParse = { ok: true; value: string } | { ok: false; error: string }

/**
 * Normalises typed money to an exact decimal string ("12" -> "12.00"). Rejects zero, negatives, more than two decimals and
 * anything that is not plain digits with one decimal separator; it never rounds, so what the user sees is what is sent.
 */
export function parseAmountInput(raw: string, max?: string): AmountParse {
  let s = (raw ?? "").trim().replace(/\s+/g, "")
  s = s.replace(/^(zar|r)/i, "")
  if (s === "") return { ok: false, error: "Enter an amount" }
  if (s.startsWith("-")) return { ok: false, error: "Amount must be greater than zero" }
  if (s.includes(",") && s.includes(".")) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  if ((s.match(/[.,]/g) ?? []).length > 1) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  s = s.replace(",", ".")
  if (!/^\d+(\.\d+)?$/.test(s)) return { ok: false, error: "Use digits and one decimal point, e.g. 25.50" }
  const [whole, frac = ""] = s.split(".")
  if (frac.length > 2) return { ok: false, error: "At most two decimal places" }
  if (whole.replace(/^0+/, "").length > 12) return { ok: false, error: "That amount is too large" }
  const normal = `${whole.replace(/^0+(?=\d)/, "")}.${frac.padEnd(2, "0")}`
  if (/^0+\.00$/.test(normal)) return { ok: false, error: "Amount must be greater than zero" }
  if (max !== undefined && compareDecimal(normal, max) > 0) return { ok: false, error: `The limit is ${max}` }
  return { ok: true, value: normal }
}

/** Compares two non-negative decimal strings exactly (no floating point). */
export function compareDecimal(a: string, b: string): number {
  const [aw, af = ""] = a.split(".")
  const [bw, bf = ""] = b.split(".")
  const w = BigInt(aw || "0") - BigInt(bw || "0")
  if (w !== 0n) return w > 0n ? 1 : -1
  const len = Math.max(af.length, bf.length)
  const f = BigInt(af.padEnd(len, "0") || "0") - BigInt(bf.padEnd(len, "0") || "0")
  return f === 0n ? 0 : f > 0n ? 1 : -1
}

const CODE_RE = /PULAPAY1\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{16}/

/**
 * Finds a payment code inside whatever was scanned or pasted. Returns only the code, never anything around it, so a QR
 * that contains extra text or instructions cannot influence what is sent. Shape only: the server verifies the signature.
 */
export function extractPaymentCode(raw: string | null | undefined): string | null {
  if (!raw || raw.length > 400) return null
  const m = raw.match(CODE_RE)
  return m ? m[0] : null
}

/** A fresh idempotency key. Reuse it only while the outcome of the SAME attempt is unknown (see AttemptKey). */
export function newIdempotencyKey(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (c?.randomUUID) return `w-${c.randomUUID()}`
  return `w-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

export interface ErrorLike { status?: number; code?: string; message?: string }

const FRIENDLY: Record<string, string> = {
  INSUFFICIENT_FUNDS: "Your demo balance is too low for this.",
  LIMIT_EXCEEDED: "That is above the demo limit.",
  INVALID_AMOUNT: "Check the amount: it must be above zero with at most two decimals.",
  CURRENCY_MISMATCH: "Only the wallet currency is supported.",
  SELF_TRANSFER: "You cannot send demo funds to yourself.",
  SELF_PAYMENT: "You cannot pay your own request.",
  RECIPIENT_NOT_FOUND: "No matching PULA user was found. Check the wallet id, email or phone number.",
  INVALID_CODE: "This payment code is not valid. Ask for a new one.",
  REQUEST_EXPIRED: "This payment request has expired. Ask for a new one.",
  REQUEST_CANCELLED: "This payment request was cancelled.",
  REQUEST_REJECTED: "This payment request was declined.",
  ALREADY_PAID: "This request has already been paid.",
  IN_PROGRESS: "A payment for this request is being processed. Check its status shortly.",
  REQUEST_CHANGED: "The request no longer matches what you reviewed. Open it again.",
  NOT_CANCELLABLE: "This request can no longer be cancelled.",
  NOT_REJECTABLE: "Only a request addressed to you can be declined.",
  IDEMPOTENCY_CONFLICT: "This attempt was already used with different details. Start again.",
  TRY_AGAIN: "The wallet is busy. Nothing was changed. Try again.",
  ILLEGAL_STATE: "This can no longer be changed.",
  NOT_FOUND: "Not found.",
  FORBIDDEN: "You do not have access to that.",
}

/** Plain-language message for an API error. Falls back to the server's safe message, never to raw details. */
export function describeWalletError(err: unknown): string {
  const e = (err ?? {}) as ErrorLike
  if (e.code && FRIENDLY[e.code]) return FRIENDLY[e.code]
  if (e.status === 429) return "Too many attempts. Please wait a moment and try again."
  if (e.status === 401) return "Your session has expired. Please sign in again."
  if (typeof e.status !== "number") return "Could not reach the server. Nothing was confirmed; check your connection and try again."
  if (e.status >= 500) return "Something went wrong on our side. Nothing was confirmed; try again."
  return e.message || "Request failed"
}

export type Tone = "neutral" | "success" | "pending" | "failed" | "info"

export function requestStatusTone(s: PaymentRequestStatus): Tone {
  switch (s) {
    case "PAID": return "success"
    case "OPEN":
    case "PROCESSING": return "pending"
    case "REJECTED":
    case "EXPIRED":
    case "CANCELLED": return "failed"
  }
}

export function requestStatusLabel(s: PaymentRequestStatus): string {
  switch (s) {
    case "OPEN": return "Waiting for payment"
    case "PROCESSING": return "Processing"
    case "PAID": return "Paid (simulated)"
    case "REJECTED": return "Declined"
    case "CANCELLED": return "Cancelled"
    case "EXPIRED": return "Expired"
  }
}

export function txStatusTone(s: TxStatus): Tone {
  switch (s) {
    case "POSTED": return "success"
    case "PENDING": return "pending"
    case "FAILED":
    case "CANCELLED":
    case "REVERSED": return "failed"
  }
}

/** Wording for a transaction row. Always says "simulated" so a demo movement is never read as real settlement. */
export function txTitle(t: Pick<WalletTransaction, "type" | "direction" | "counterparty" | "status">): string {
  switch (t.type) {
    case "DEMO_ISSUANCE": return "Demo funds added (simulated)"
    case "INTERNAL_TRANSFER": return t.direction === "OUT" ? `Sent to ${t.counterparty} (simulated)` : `Received from ${t.counterparty} (simulated)`
    case "SIMULATED_WITHDRAWAL":
      return t.status === "PENDING" ? "Simulated withdrawal requested"
        : t.status === "POSTED" ? "Simulated withdrawal completed (no real money moved)"
        : t.status === "FAILED" ? "Simulated withdrawal failed, demo funds returned"
        : "Simulated withdrawal cancelled, demo funds returned"
    case "REVERSAL": return "Correction (simulated)"
  }
}

export function isFinal(s: PaymentRequestStatus): boolean {
  return s === "PAID" || s === "REJECTED" || s === "CANCELLED" || s === "EXPIRED"
}


// ---------------------------------------------------------------------------------------------------------------------
// UI-state logic kept out of the components so it can be unit-tested without a renderer.
// ---------------------------------------------------------------------------------------------------------------------

export type ReviewKind = "processing" | "expired" | "cancelled" | "rejected" | "paid-now" | "paid-earlier" | "own" | null

export interface ReviewView {
  /** Which explanatory line the review card shows, if any. */
  kind: ReviewKind
  message: string | null
  showPay: boolean
  showReject: boolean
  showDone: boolean
}

/**
 * What the "review a payment request" card shows for a request in a given state. {@code paidThisSession} is true only
 * when THIS screen just completed the payment (so it can show the transaction reference); a request that was already paid
 * earlier gets a plainer message. Buttons come only from the server's {@code canPay/canReject} flags, never from client guesses.
 */
export function reviewView(
  r: Pick<PaymentRequest, "status" | "direction" | "canPay" | "canReject">,
  paidThisSession: boolean,
): ReviewView {
  let kind: ReviewKind = null
  switch (r.status) {
    case "PROCESSING": kind = "processing"; break
    case "EXPIRED": kind = "expired"; break
    case "CANCELLED": kind = "cancelled"; break
    case "REJECTED": kind = "rejected"; break
    case "PAID": kind = paidThisSession ? "paid-now" : "paid-earlier"; break
    default: break
  }
  if (kind === null && r.direction === "OUTGOING" && !isFinal(r.status)) kind = "own"
  const MESSAGES: Record<Exclude<ReviewKind, null>, string> = {
    processing: "A payment for this request is being processed. Check back shortly.",
    expired: "This request has expired. Ask for a new code.",
    cancelled: "The requester cancelled this request.",
    rejected: "This request was declined.",
    "paid-now": "Paid with demo funds (simulated). No real-world settlement happened.",
    "paid-earlier": "This request has already been paid.",
    own: "This is your own request; you cannot pay it.",
  }
  return {
    kind,
    message: kind ? MESSAGES[kind] : null,
    showPay: r.canPay,
    showReject: r.canReject,
    showDone: isFinal(r.status) || !r.canPay,
  }
}

/** Badge text for a ledger transaction status ("POSTED" reads as "Completed"). */
export function txBadgeLabel(s: TxStatus): string {
  return s === "POSTED" ? "Completed" : s.charAt(0) + s.slice(1).toLowerCase()
}

/**
 * A framework-free double-submit guard: while one action runs, further calls are ignored (not queued). The React/RN hooks
 * wrap this so the behaviour that prevents a second transfer on a double tap is the same code the tests exercise.
 */
export function createGuard() {
  let busy = false
  return {
    get busy() { return busy },
    async run<T>(fn: () => Promise<T>): Promise<T | undefined> {
      if (busy) return undefined
      busy = true
      try {
        return await fn()
      } finally {
        busy = false
      }
    },
  }
}


// ---------------------------------------------------------------------------------------------------------------------
// Which wallet sections a user sees. The server enforces every permission; this only avoids showing buttons that would fail.
// ---------------------------------------------------------------------------------------------------------------------

export type WalletTab = "wallet" | "send" | "request" | "pay" | "activity" | "members"

export function visibleTabs(kind: "personal" | "business", permissions: readonly string[]): WalletTab[] {
  if (kind === "personal") return ["wallet", "send", "request", "pay", "activity"]
  const tabs: WalletTab[] = ["wallet"]
  if (permissions.includes("SEND")) tabs.push("send")
  if (permissions.includes("REQUEST")) tabs.push("request")
  if (permissions.includes("VIEW")) tabs.push("activity")
  if (permissions.includes("MANAGE_MEMBERS")) tabs.push("members")
  return tabs
}

/** Wording for a business role, so users understand what they can do. */
export function roleDescription(role: "OWNER" | "FINANCE" | "STAFF"): string {
  switch (role) {
    case "OWNER": return "Owner: full access, including managing who can use this wallet"
    case "FINANCE": return "Finance: view, request payments, send, withdraw and add demo funds"
    case "STAFF": return "Staff: view and request payments only"
  }
}
