// Mirrors SaveToBuyDtos on the backend. Savings are TRACKED against a goal; PULA does not hold them.
export type SaveToBuyVertical = "RETAIL" | "FOOD" | "STOCK" | "SERVICE" | "TAXI" | "GENERAL"
export type SaveToBuyScope = "PERSONAL" | "STOKVEL"
export type SaveToBuyStatus =
  | "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "CHECKOUT_STARTED" | "PURCHASED" | "CANCELLED" | "EXPIRED"

export const VERTICAL_LABELS: Record<SaveToBuyVertical, string> = {
  RETAIL: "Retail", FOOD: "Food", STOCK: "Stock", SERVICE: "Service", TAXI: "Taxi", GENERAL: "General purchase",
}
export const STATUS_LABELS: Record<SaveToBuyStatus, string> = {
  DRAFT: "Draft", PENDING_APPROVAL: "Waiting for approval", APPROVED: "Approved",
  CHECKOUT_STARTED: "Checkout started", PURCHASED: "Purchased", CANCELLED: "Cancelled", EXPIRED: "Expired",
}

export interface EligibleGoal {
  goalId: string; goalTitle: string; circleId: string; circleName: string; scope: SaveToBuyScope
  vertical: SaveToBuyVertical | null; purpose: string | null; currency: string; target: number; saved: number
  remaining: number; percent: number; goalReached: boolean; targetDate: string | null; canCreateRequest: boolean
  canDesignate: boolean
}
export interface SaveToBuyView {
  id: string; scope: SaveToBuyScope; status: SaveToBuyStatus; vertical: SaveToBuyVertical; purpose: string
  goalId: string; goalTitle: string; circleId: string; circleName: string; currency: string
  goalTarget: number; saved: number; goalReached: boolean; requestedAmount: number
  shortfall: number; differenceFromTarget: number; fullyFunded: boolean
  namedBuyerId: string; namedBuyerName: string; ownerId: string; ownerName: string
  approval: { required: number; approvals: number; rejections: number; eligibleVoters: number; stokvelRequestId: string } | null
  expiresAt: string | null; orderKind: "ORDER" | "STOCK_ORDER" | "EXTERNAL" | null; orderId: string | null
  purchasedAmount: number | null; purchasedAt: string | null; completionReference: string | null
  createdAt: string; notice: string
}
export interface SaveToBuyInsights {
  summary: string; shortfallExplanation: string; savingAdvice: string | null; suggestions: string[]; notice: string
}
export interface CreateSaveToBuyPayload {
  goalId: string; vertical?: SaveToBuyVertical; purpose: string; requestedAmount: number
  namedBuyerUserId?: string; idempotencyKey?: string
}

/** Where the existing marketplace area for each vertical lives (no Save-to-Buy marketplace screens exist). */
export const MARKETPLACE_ROUTE: Record<SaveToBuyVertical, string> = {
  RETAIL: "/market", FOOD: "/market/food", STOCK: "/market/stock", SERVICE: "/services", TAXI: "/momoride", GENERAL: "/market",
}
/** UI hint only: the server decides whether the request can be used at checkout. */
export const canCheckOut = (v: SaveToBuyView, userId: string) =>
  v.status === "APPROVED" && v.namedBuyerId === userId && (v.vertical === "RETAIL" || v.vertical === "FOOD" || v.vertical === "STOCK" || v.vertical === "SERVICE")
