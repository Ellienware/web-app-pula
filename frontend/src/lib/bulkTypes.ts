// Mirrors com.momocircle.dto.BulkDtos. The server computes every price, discount and total; the client only displays them
// and sends back the quantity and price a business is ACKNOWLEDGING.

export type BulkGroupStatus =
  | "DRAFT" | "OPEN" | "TARGET_REACHED" | "CONFIRMATION" | "PAYMENT_PENDING" | "ORDER_PLACED"
  | "SUPPLIER_CONFIRMED" | "FULFILMENT" | "COMPLETED" | "PARTIALLY_FULFILLED" | "CANCELLED" | "FAILED"

export type BulkAllocationStatus =
  | "JOINED" | "CONFIRMED" | "PENDING_RECONFIRMATION" | "WITHDRAWN" | "LAPSED"
  | "ORDERED" | "PAID" | "CANCELLED" | "REFUND_DUE"

export type ShortfallPolicy = "CANCEL" | "EXTEND" | "ASK_PARTICIPANTS"
export type FulfilmentMethod = "SELF_COLLECTION" | "WHOLESALER_DELIVERY" | "PULA_RIDE_DELIVERY"
export type ViewerRole = "CREATOR" | "PARTICIPANT" | "SUPPLIER" | "BROWSER"

export interface TierView { minPacks: number; pricePerPack: number }

export interface Quote {
  packs: number
  expectedAggregatePacks: number
  basePricePerPack: number
  pricePerPack: number
  discountPerPack: number
  subtotal: number
  deliveryFee: number
  total: number
  currency: string
  nextTier: TierView | null
  packsToNextTier: number
  meetsMoq: boolean
  reachesTarget: boolean
}

export interface GroupSummary {
  id: string
  procurementReference: string
  name: string
  description: string | null
  status: BulkGroupStatus
  supplierBusinessId: string
  supplierBusinessName: string
  creatorBusinessId: string
  creatorBusinessName: string
  wholesaleProductId: string
  productName: string
  packSize: number
  moqPacks: number
  targetPacks: number
  aggregatePacks: number
  participantCount: number
  basePricePerPack: number
  currentPricePerPack: number
  currency: string
  tiers: TierView[]
  deadline: string
  shortfallPolicy: ShortfallPolicy
  allowSelfCollection: boolean
  allowSupplierDelivery: boolean
  allowPulaRide: boolean
  collectionNote: string | null
}

export interface ParticipantView {
  businessId: string
  businessName: string
  status: BulkAllocationStatus
  requestedPacks: number
  confirmedPacks: number | null
}

export interface AllocationView {
  id: string
  groupId: string
  procurementReference: string
  groupName: string
  businessId: string
  businessName: string
  status: BulkAllocationStatus
  requestedPacks: number
  confirmedPacks: number | null
  allocatedPacks: number | null
  shortagePacks: number
  issuePacks: number
  agreedMaxPricePerPack: number | null
  pricePerPack: number | null
  discountPerPack: number | null
  subtotal: number | null
  deliveryFee: number | null
  total: number | null
  refundDueAmount: number
  currency: string
  fulfilmentMethod: FulfilmentMethod
  deliveryAddress: string | null
  stockOrderId: string | null
  fulfilmentStatus: string | null
  paymentStatus: string
  /** True when the payment provider is the sandbox: no real money moves. */
  paymentSimulated: boolean
  saveToBuyRequestId: string | null
  quote: Quote | null
}

export interface GroupDetail {
  group: GroupSummary
  viewerRole: ViewerRole
  myAllocation: AllocationView | null
  participants: ParticipantView[] | null
  finalPricePerPack: number | null
  finalAggregatePacks: number | null
  supplierConfirmedPacks: number | null
  supplierReference: string | null
  supplierNote: string | null
  cancelReason: string | null
  finalisedAt: string | null
  orderPlacedAt: string | null
}

export interface ConfirmOutcome {
  confirmed: boolean
  reason: "PRICE_CHANGED" | "QUANTITY_CHANGED" | null
  allocation: AllocationView
  currentQuote: Quote
}

export interface FinaliseOutcome {
  outcome: "FINALISED" | "UNCONFIRMED_PARTICIPANTS" | "RECONFIRMATION_REQUIRED" | "BELOW_MINIMUM" | "BELOW_TARGET"
  message: string
  group: GroupDetail
  unconfirmedCount: number
  reconfirmationCount: number
}

export interface PlaceOrderOutcome {
  outcome: "ORDER_PLACED" | "NOT_ALL_PAID" | "BELOW_MINIMUM" | "PRICE_WOULD_INCREASE" | "CANCELLED"
  message: string
  group: GroupDetail
  unpaidCount: number
}

export interface SupplierLine {
  businessName: string
  packs: number
  fulfilmentMethod: FulfilmentMethod
  deliveryAddress: string | null
  stockOrderId: string | null
  orderStatus: string | null
  paymentStatus: string
  shortagePacks: number
  issuePacks: number
}

export interface SupplierGroupView { group: GroupDetail; orderedPacks: number; paidPacks: number; lines: SupplierLine[] }

export interface BulkEvent {
  id: string
  type: string
  message: string | null
  packs: number | null
  amount: number | null
  businessId: string | null
  createdAt: string
}

export interface CreateGroupRequest {
  name: string
  description?: string
  wholesaleProductId: string
  targetPacks: number
  deadline: string
  shortfallPolicy: ShortfallPolicy
  extensionHours?: number
  allowSelfCollection: boolean
  allowSupplierDelivery: boolean
  allowPulaRide: boolean
  collectionNote?: string
}

export interface JoinRequest {
  requestedPacks: number
  fulfilmentMethod: FulfilmentMethod
  deliveryAddress?: string
  saveToBuyRequestId?: string
}

export interface PageOf<T> { content: T[]; totalElements: number; totalPages: number; number: number; size: number }

export const GROUP_STATUS_LABELS: Record<BulkGroupStatus, string> = {
  DRAFT: "Draft", OPEN: "Open for participation", TARGET_REACHED: "Target reached", CONFIRMATION: "Confirming quantities & price",
  PAYMENT_PENDING: "Waiting for payments", ORDER_PLACED: "Order placed with supplier", SUPPLIER_CONFIRMED: "Supplier confirmed",
  FULFILMENT: "Being fulfilled", COMPLETED: "Completed", PARTIALLY_FULFILLED: "Completed with shortages/issues",
  CANCELLED: "Cancelled", FAILED: "Failed",
}

export const ALLOCATION_STATUS_LABELS: Record<BulkAllocationStatus, string> = {
  JOINED: "Joined (not yet confirmed)", CONFIRMED: "Confirmed", PENDING_RECONFIRMATION: "Please confirm again",
  WITHDRAWN: "Withdrawn", LAPSED: "Dropped (never confirmed)", ORDERED: "Awaiting your payment", PAID: "Paid",
  CANCELLED: "Cancelled", REFUND_DUE: "Refund owed to you",
}
