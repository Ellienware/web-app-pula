// Mirrors SettlementDtos on the backend. Every figure is calculated by the server; nothing here edits a settlement directly.
export type SourceType = "RETAIL_ORDER" | "FOOD_ORDER" | "STOCK_ORDER" | "SERVICE_BOOKING"
export type SettlementStatus = "NOT_ELIGIBLE" | "ELIGIBLE" | "PROCESSING" | "PAID" | "FAILED" | "ON_HOLD" | "CANCELLED"
export type ReconciliationState =
  | "MATCHED" | "MISMATCH" | "MISSING_PAYMENT" | "MISSING_SETTLEMENT" | "MISSING_PAYOUT" | "AMOUNT_MISMATCH" | "REFERENCE_MISMATCH"

export const SOURCE_LABELS: Record<SourceType, string> = {
  RETAIL_ORDER: "Retail", FOOD_ORDER: "Food", STOCK_ORDER: "Stock", SERVICE_BOOKING: "Services",
}

export interface Adjustment { id: string; kind: "CREDIT" | "DEBIT"; amount: number; reason: string; createdBy: string; createdAt: string }
export interface Settlement {
  id: string; sourceType: SourceType; sourceId: string; sellerKind: string; businessId: string; businessName: string
  type: "SALE" | "MERCHANT_PROTECTION"; status: SettlementStatus; currency: string
  gross: number; refunded: number; eligibleRevenue: number; commissionRate: number; commission: number; fixedFee: number
  paymentFee: number; adjustments: number; net: number; compensation: number; pulaOperationalLoss: number
  eligibleAt: string | null; paidAt: string | null; payoutProvider: string | null; payoutReference: string | null
  holdReason: string | null; failureReason: string | null; payoutAttempts: number; createdAt: string; adjustmentHistory: Adjustment[]
}
export interface SourceTotals {
  sourceType: SourceType; count: number; gross: number; commission: number; paymentFees: number
  adjustments: number; refunds: number; sellerNet: number
}
export interface MarketplaceSummary {
  grossMarketplaceVolume: number; pulaCommissionRevenue: number; paymentFees: number; refunds: number; sellerNetTotal: number
  pulaOperationalLoss: number; bySource: SourceTotals[]; byStatus: { status: SettlementStatus; count: number; net: number }[]
  driverPayouts: { status: string; count: number; amount: number }[]; failedSettlements: number; onHoldSettlements: number
}
export interface SellerSummary { businessId: string; bySource: SourceTotals[]; paid: number; owed: number; onHold: number }
export interface ReconciliationRow {
  sourceType: SourceType; sourceId: string; state: ReconciliationState; detail: string; sourceTotal: number | null
  paymentAmount: number | null; settlementGross: number | null; paymentReference: string | null
  payoutReference: string | null; settlementId: string | null
}
export interface ReconciliationReport { generatedAt: string; checked: number; matched: number; problems: number; rows: ReconciliationRow[] }
export interface SettlementInsights { summary: string; points: string[]; notice: string }
