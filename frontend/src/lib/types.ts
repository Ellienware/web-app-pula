// Frontend mirror of the Spring Boot DTOs in com.momocircle.dto.*
// Keep these field names in exact sync with the backend records.

export interface User {
  id: string
  email: string
  fullName: string
  phoneNumber: string | null
  /** Display hint only (shows/hides admin navigation). Every admin endpoint is still enforced by the server. */
  role?: "USER" | "ADMIN"
}

export interface AuthResponse {
  token: string
  user: User
}

export type CircleType = "SAVINGS_GOAL" | "ROTATING" | "EXPENSE_POOL" | "STOKVEL"
export type CircleRole = "OWNER" | "ADMIN" | "MEMBER" | "TREASURER" | "CHAIRPERSON"

export const CIRCLE_TYPE_LABELS: Record<CircleType, string> = {
  SAVINGS_GOAL: "Savings goal",
  ROTATING: "Rotating (susu)",
  EXPENSE_POOL: "Expense pool",
  STOKVEL: "Stokvel",
}

/** GET /api/circles and GET /api/circles/{id} — CircleResponse. */
export interface Circle {
  id: string
  name: string
  description: string | null
  type: CircleType
  currency: string
  goalAmount: number | null
  totalContributed: number
  progressPercent: number | null
  memberCount: number
  ownerId: string
  viewerRole: CircleRole | null
}

/** MemberResponse. */
export interface Member {
  membershipId: string
  userId: string
  fullName: string
  email: string
  role: CircleRole
}

export type PaymentStatus = "PENDING" | "PROCESSING" | "SUCCESSFUL" | "FAILED" | "REFUNDED"

/** ContributionResponse. */
export interface Contribution {
  id: string
  circleId: string
  contributorId: string
  contributorName: string
  amount: number
  currency: string
  status: PaymentStatus
  providerReference: string | null
  goalId: string | null
  goalTitle: string | null
  createdAt: string
}

/** ContributeRequest. */
export interface ContributeRequest {
  amount: number
  paymentRequestId?: string | null
  goalId?: string | null
  idempotencyKey?: string
}

export type PaymentRequestStatus = "OPEN" | "FULFILLED" | "CANCELLED" | "EXPIRED"

/** PaymentRequestResponse. */
export interface PaymentRequestItem {
  id: string
  circleId: string
  requestedByName: string
  amountPerMember: number
  note: string | null
  dueDate: string | null
  status: PaymentRequestStatus
  createdAt: string
}

export type GoalStatus = "ACTIVE" | "PAUSED" | "REACHED" | "CLOSED"

/** GoalResponse. */
export interface Goal {
  id: string
  circleId: string
  title: string
  description: string | null
  targetAmount: number
  raisedAmount: number
  progressPercent: number | null
  deadline: string | null
  status: GoalStatus
  createdByName: string
  createdAt: string
}

export type SplitType = "EQUAL" | "CUSTOM" | "PERCENTAGE" | "ITEM_BASED"

export type ShareStatus =
  | "PENDING"
  | "ACCEPTED"
  | "DISPUTED"
  | "DECLINED"
  | "PROCESSING"
  | "PAID"
  | "REFUNDED"
  | "REVERSED"
  | "CANCELLED"
export type SettlementMethod = "LEDGER" | "SELF" | "EXTERNAL" | "LEGACY" | "NONE"
export type ExpenseStatus = "ACTIVE" | "CANCELLED"
export type ExpenseSettlementState = "OPEN" | "PARTIALLY_SETTLED" | "SETTLED" | "DISPUTED" | "OVERDUE" | "CANCELLED"

/** ExpenseShareResponse. Every settlement is SIMULATED demo-wallet value. */
export interface ExpenseShare {
  id: string
  debtorId: string
  debtorName: string
  amount: number
  settled: boolean
  settledAt: string | null
  /** Exact amount in hundredths. Use this, never `amount`, for any comparison or arithmetic. */
  amountMinor: number
  percentage: number | null
  status: ShareStatus
  settlementMethod: SettlementMethod | null
  overdue: boolean
  mine: boolean
  debtorFormerMember: boolean
  ledgerReference: string | null
  refundReference: string | null
  responseNote: string | null
  respondedAt: string | null
  canAccept: boolean
  canDispute: boolean
  canDecline: boolean
  canPay: boolean
  payBlockedReason: string | null
  canReopen: boolean
  canMarkReceived: boolean
  canRetractReceived: boolean
  canRefund: boolean
  simulated: true
}

export interface ExpenseItem {
  id: string
  name: string
  amount: number
  amountMinor: number
  assigneeUserIds: string[]
  assigneeNames: string[]
}

/** ExpenseResponse. */
export interface Expense {
  id: string
  circleId: string
  paidById: string
  paidByName: string
  description: string
  amount: number
  currency: string
  splitType: SplitType
  settledAmount: number
  outstandingAmount: number
  shares: ExpenseShare[]
  createdAt: string
  circleName: string
  note: string | null
  amountMinor: number
  createdById: string
  createdByName: string
  expenseDate: string | null
  dueDate: string | null
  status: ExpenseStatus
  revision: number
  /** What the bill cost, as recorded. Not money held or moved by PULA. */
  recordedAmount: number
  /** Simulated demo-wallet funds actually moved through the shared ledger (net of refunds). */
  transferredAmount: number
  /** Marked as received outside PULA by the payer; PULA moved and verified nothing. */
  externalSettledAmount: number
  refundedAmount: number
  settlementState: ExpenseSettlementState
  overdue: boolean
  items: ExpenseItem[]
  canEdit: boolean
  canCancel: boolean
  cancelBlockedReason: string | null
  cancelledAt: string | null
  cancelReason: string | null
  simulated: true
  simulatedNotice: string
}

export interface ExpensePreviewShare {
  userId: string
  userName: string
  amount: number
  amountMinor: number
  percentage: number | null
}

export interface ExpensePreview {
  amount: number
  amountMinor: number
  currency: string
  splitType: SplitType
  shares: ExpensePreviewShare[]
  roundingRule: string
  simulated: true
  simulatedNotice: string
}

export interface ExpenseObligation {
  expenseId: string
  circleId: string
  circleName: string
  title: string
  role: "OWE" | "OWED"
  counterpartyName: string
  share: ExpenseShare
  currency: string
  dueDate: string | null
  expenseStatus: ExpenseStatus
  createdAt: string
}

export interface ExpenseEvent {
  id: string
  shareId: string | null
  actorName: string
  eventType: string
  revision: number
  details: string | null
  ledgerReference: string | null
  createdAt: string
}

export interface ExpenseSettlement {
  expense: Expense
  share: ExpenseShare
  transaction: { reference: string; amount: string; currency: string; status: string } | null
  replayed: boolean
  simulated: true
  simulatedNotice: string
}

export type RecurringFrequency = "WEEKLY" | "BIWEEKLY" | "MONTHLY" | "QUARTERLY" | "YEARLY"
export type RecurringStatus = "ACTIVE" | "PAUSED" | "CANCELLED" | "COMPLETED"
export type RecurringDueStatus = "PENDING" | "PAID" | "MISSED"

/** RecurringDueResponse. */
export interface RecurringDue {
  id: string
  memberId: string
  memberName: string
  amount: number
  status: RecurringDueStatus
  paidAt: string | null
}

/** RecurringPeriodResponse. */
export interface RecurringPeriod {
  id: string
  label: string
  dueDate: string
  expectedCount: number
  paidCount: number
  dues: RecurringDue[]
}

/** RecurringPlanResponse. */
export interface RecurringPlan {
  id: string
  circleId: string
  title: string
  amountPerMember: number
  currency: string
  frequency: RecurringFrequency
  status: RecurringStatus
  nextDueDate: string | null
  goalId: string | null
  goalTitle: string | null
  note: string | null
  createdByName: string
  totalCollected: number
  periods: RecurringPeriod[]
  createdAt: string
}

export type ActivityType =
  | "CIRCLE_CREATED"
  | "MEMBER_JOINED"
  | "MEMBER_INVITED"
  | "INVITATION_ACCEPTED"
  | "INVITATION_DECLINED"
  | "CONTRIBUTION_MADE"
  | "PAYMENT_REQUEST_CREATED"
  | "PAYMENT_REQUEST_REMINDED"
  | "PAYMENT_REQUEST_CANCELLED"
  | "PAYMENT_REQUEST_EXPIRED"
  | "MEMBER_REMOVED"
  | "MEMBER_LEFT"
  | "MEMBER_ROLE_CHANGED"
  | "GOAL_CREATED"
  | "GOAL_REACHED"
  | "GOAL_CLOSED"
  | "GOAL_PAUSED"
  | "GOAL_RESUMED"
  | "EXPENSE_ADDED"
  | "EXPENSE_SHARE_SETTLED"
  | "EXPENSE_EDITED"
  | "EXPENSE_CANCELLED"
  | "EXPENSE_SHARE_REFUNDED"
  | "RECURRING_CREATED"
  | "RECURRING_CANCELLED"
  | "RECURRING_EXECUTED"

/** ActivityResponse. */
export interface ActivityEvent {
  id: string
  circleId: string
  actorId: string | null
  actorName: string | null
  type: ActivityType
  message: string
  createdAt: string
}

export type InvitationStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "CANCELLED" | "EXPIRED"

/** InvitationResponse. */
export interface Invitation {
  id: string
  circleId: string
  circleName: string
  invitedEmail: string
  invitedByName: string
  role: CircleRole
  token: string
  status: InvitationStatus
  expiresAt: string | null
  createdAt: string
}

export interface AssistantAction {
  /** Null for read-only actions (SEARCH_MARKET) that never go through /assistant/execute. */
  proposalId: string | null
  intent: string
  summary: string
  confidence: number
  /** Display only — the server re-derives everything from the stored proposal on confirm, not this. */
  parameters: Record<string, unknown>
  requiresConfirmation: boolean
}

export interface AssistantResponse {
  reply: string
  action: AssistantAction | null
  source?: string
}

// ============================================================
// PULA Market
// ============================================================

/** Generic shape of a Spring Data Page<T> JSON response. */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
}

export type BusinessType =
  | "FORMAL_RETAILER"
  | "LOCAL_BUSINESS"
  | "INFORMAL_TRADER"
  | "SERVICE_PROVIDER"
  | "WHOLESALER_SUPPLIER"
  | "OTHER_SMALL_BUSINESS"
  // PULA Food — prepared-food sellers (these unlock menus, branches and the kitchen queue)
  | "RESTAURANT"
  | "CAFE"
  | "FAST_FOOD"
  | "FOOD_VENDOR"
  | "HOME_KITCHEN"
  | "SPAZA"
  | "BAKERY"
  | "CATERING"

/** True for business types that run a menu. Mirrors BusinessType.isFood() on the backend. */
export const FOOD_BUSINESS_TYPES: BusinessType[] = [
  "RESTAURANT", "CAFE", "FAST_FOOD", "FOOD_VENDOR", "HOME_KITCHEN", "SPAZA", "BAKERY", "CATERING",
]
export function isFoodBusinessType(t: BusinessType | undefined | null): boolean {
  return !!t && FOOD_BUSINESS_TYPES.includes(t)
}

/** Respectful, non-stigmatizing consumer-facing labels — see the market brief's own §24 instruction. */
export const BUSINESS_TYPE_LABELS: Record<BusinessType, string> = {
  FORMAL_RETAILER: "Retailer",
  LOCAL_BUSINESS: "Local Business",
  INFORMAL_TRADER: "Local Business",
  SERVICE_PROVIDER: "Service Provider",
  WHOLESALER_SUPPLIER: "Wholesaler / Supplier",
  OTHER_SMALL_BUSINESS: "Other Small Business",
  RESTAURANT: "Restaurant",
  CAFE: "Café",
  FAST_FOOD: "Fast Food",
  FOOD_VENDOR: "Food Vendor",
  HOME_KITCHEN: "Home Kitchen",
  SPAZA: "Spaza (prepared food)",
  BAKERY: "Bakery",
  CATERING: "Catering",
}

export type FulfilmentOption = "CUSTOMER_PICKUP" | "SELLER_DELIVERY" | "MOMORIDE_DELIVERY" | "SUPPORTED_PARTNER_DELIVERY"

export const FULFILMENT_OPTION_LABELS: Record<FulfilmentOption, string> = {
  CUSTOMER_PICKUP: "Customer pickup",
  SELLER_DELIVERY: "Seller delivery",
  MOMORIDE_DELIVERY: "PULA Ride delivery",
  SUPPORTED_PARTNER_DELIVERY: "Partner delivery",
}

/** What a PULA Business is allowed to transact — mirrors BusinessCapability.java.
 *  Independent of businessType (what kind of business) and fulfilmentOptions
 *  (how it delivers); a business may hold more than one at once. */
export type BusinessCapability =
  | "CAN_SELL_TO_CONSUMERS"
  | "CAN_BUY_STOCK"
  | "CAN_SUPPLY_BUSINESSES"
  | "CAN_MANAGE_DELIVERY_FLEET"
  | "CAN_PROVIDE_SERVICES"

export const BUSINESS_CAPABILITY_LABELS: Record<BusinessCapability, string> = {
  CAN_SELL_TO_CONSUMERS: "Sell to consumers (PULA Market — Shop)",
  CAN_BUY_STOCK: "Buy stock from suppliers (PULA Market — Stock)",
  CAN_SUPPLY_BUSINESSES: "Supply other businesses (wholesaler/supplier)",
  CAN_MANAGE_DELIVERY_FLEET: "Manage a delivery driver pool",
  CAN_PROVIDE_SERVICES: "Provide services (PULA Market — Services)",
}

/** Self-declared only — mirrors BusinessRegistrationStatus.java. Never verified against
 *  CIPC or any government registry; PULA provides registration *readiness*, not real
 *  registration (brief §7). */
export type BusinessRegistrationStatus = "NOT_REGISTERED" | "REGISTRATION_IN_PROGRESS" | "FORMALLY_REGISTERED"

export const BUSINESS_REGISTRATION_STATUS_LABELS: Record<BusinessRegistrationStatus, string> = {
  NOT_REGISTERED: "Not registered yet",
  REGISTRATION_IN_PROGRESS: "Registration in progress",
  FORMALLY_REGISTERED: "Formally registered",
}

/** BusinessResponse. */
export interface Business {
  id: string
  ownerId: string
  ownerName: string
  name: string
  description: string | null
  logoUrl: string | null
  category: string
  businessType: BusinessType
  location: string | null
  contactPhone: string | null
  contactEmail: string | null
  operatingHours: string[]
  fulfilmentOptions: FulfilmentOption[]
  capabilities: BusinessCapability[]
  registrationStatus: BusinessRegistrationStatus
  registrationNumber: string | null
  active: boolean
  ratingAverage: number
  ratingCount: number
  minimumOrderAmount: number | null
  deliveryFee: number | null
  createdAt: string
}

/**
 * PublicBusinessResponse: what GET /api/businesses and GET /api/businesses/{id} return to anyone who is not the owner or an
 * admin. No owner identity and no registration details. The owner's full record (Business) comes from /businesses/me.
 */
export type PublicBusiness = Omit<Business, "ownerId" | "ownerName" | "registrationStatus" | "registrationNumber">

export interface CreateBusinessRequest {
  name: string
  description?: string | null
  logoUrl?: string | null
  category: string
  businessType?: BusinessType
  location?: string | null
  contactPhone?: string | null
  contactEmail?: string | null
  operatingHours?: string[]
  fulfilmentOptions?: FulfilmentOption[]
  /** Omit to default to [CAN_SELL_TO_CONSUMERS]. An explicit empty array is rejected. */
  capabilities?: BusinessCapability[]
  /** Omit to default to NOT_REGISTERED. Self-declared only — never verified. */
  registrationStatus?: BusinessRegistrationStatus
  registrationNumber?: string | null
  minimumOrderAmount?: number | null
  deliveryFee?: number | null
}

export interface UpdateBusinessRequest {
  name?: string
  description?: string | null
  logoUrl?: string | null
  category?: string
  businessType?: BusinessType
  location?: string | null
  contactPhone?: string | null
  contactEmail?: string | null
  operatingHours?: string[]
  fulfilmentOptions?: FulfilmentOption[]
  /** Omitting leaves capabilities unchanged; sending a list replaces the whole set. */
  capabilities?: BusinessCapability[]
  registrationStatus?: BusinessRegistrationStatus
  /** Omitting leaves the registration number unchanged; send "" to clear it. */
  registrationNumber?: string | null
  active?: boolean
  minimumOrderAmount?: number | null
  deliveryFee?: number | null
}

/** BusinessProfileCompleteness — brief §8. Deliberately NOT a credit score. */
export interface BusinessProfileCompleteness {
  businessName: boolean
  ownerInformation: boolean
  location: boolean
  businessType: boolean
  hasProducts: boolean
  registrationDetails: boolean
  supportingDocuments: boolean
  completedCount: number
  totalCount: number
}

export type BusinessDocumentType =
  | "ID_DOCUMENT"
  | "PROOF_OF_ADDRESS"
  | "CIPC_REGISTRATION_CERTIFICATE"
  | "TAX_CLEARANCE_CERTIFICATE"
  | "BANK_CONFIRMATION_LETTER"
  | "OTHER"

export const BUSINESS_DOCUMENT_TYPE_LABELS: Record<BusinessDocumentType, string> = {
  ID_DOCUMENT: "ID document",
  PROOF_OF_ADDRESS: "Proof of address",
  CIPC_REGISTRATION_CERTIFICATE: "CIPC registration certificate",
  TAX_CLEARANCE_CERTIFICATE: "Tax clearance certificate",
  BANK_CONFIRMATION_LETTER: "Bank confirmation letter",
  OTHER: "Other document",
}

/** BusinessDocumentResponse. Metadata only — fileUrl points to wherever the owner
 *  already hosted the file (no in-app file upload/storage in this slice). */
export interface BusinessDocument {
  id: string
  businessId: string
  documentType: BusinessDocumentType
  fileUrl: string
  note: string | null
  createdAt: string
}

export interface UploadBusinessDocumentRequest {
  documentType: BusinessDocumentType
  fileUrl: string
  note?: string | null
}

export type ProductStatus = "DRAFT" | "ACTIVE" | "OUT_OF_STOCK" | "ARCHIVED"

export const PRODUCT_STATUS_LABELS: Record<ProductStatus, string> = {
  DRAFT: "Draft",
  ACTIVE: "Active",
  OUT_OF_STOCK: "Out of stock",
  ARCHIVED: "Archived",
}

export type MatchClassification = "EXACT_MATCH" | "SIMILAR_ALTERNATIVE" | "UNIQUE_PRODUCT"

/** ProductResponse. */
export interface Product {
  id: string
  businessId: string
  businessName: string
  name: string
  description: string | null
  imageUrls: string[]
  category: string
  price: number
  currency: string
  isService: boolean
  stock: number
  status: ProductStatus
  canonicalProductId: string | null
  matchClassification: MatchClassification
  ratingAverage: number
  ratingCount: number
  createdAt: string
}

/** CanonicalProductResponse. */
export interface CanonicalProduct {
  id: string
  name: string
  brand: string | null
  category: string
  subcategory: string | null
  size: string | null
  unit: string | null
  barcode: string | null
  createdAt: string
}

export interface CreateCanonicalProductRequest {
  name: string
  brand?: string | null
  category: string
  subcategory?: string | null
  size?: string | null
  unit?: string | null
  barcode?: string | null
}

/** ProductPriceComparisonEntry. */
export interface PriceComparisonEntry {
  productId: string
  businessId: string
  businessName: string
  price: number
  currency: string
  available: boolean
}

/** ProductPriceComparisonResponse. */
export interface PriceComparison {
  canonicalProductId: string
  canonicalProductName: string
  exactMatches: PriceComparisonEntry[]
}

// ---- Basket comparison ----

export interface BasketItemRequest {
  canonicalProductId: string
  quantity: number
}

/** StoreBasketTotal. */
export interface StoreBasketTotal {
  businessId: string
  businessName: string
  currency: string
  estimatedSubtotal: number
  itemsFound: number
  itemsRequested: number
  completeBasket: boolean
  missingCanonicalProductIds: string[]
  deliveryFee: number | null
  estimatedTotal: number
  minimumOrderAmount: number | null
  meetsMinimumOrder: boolean
}

export interface FewestDeliveriesStop {
  businessId: string
  businessName: string
  canonicalProductIdsCoveredHere: string[]
  subtotal: number
  deliveryFee: number | null
}

/** BasketComparisonResponse. */
export interface BasketComparison {
  storeTotals: StoreBasketTotal[]
  cheapestCompleteStore: StoreBasketTotal | null
  potentialSavings: number | null
  currency: string | null
  fewestDeliveriesPlan: FewestDeliveriesStop[] | null
  fewestDeliveriesPlanTotal: number | null
}

export interface CreateProductRequest {
  name: string
  description?: string | null
  imageUrls?: string[]
  category: string
  price: number
  currency?: string
  isService: boolean
  stock: number
}

export interface UpdateProductRequest {
  name?: string
  description?: string | null
  imageUrls?: string[]
  category?: string
  price?: number
  stock?: number
  status?: ProductStatus
}

/** CartItemResponse. */
export interface CartModifier {
  modifierId: string
  name: string
  priceDelta: number
}

export interface CartLine {
  id: string
  product: Product
  quantity: number
  lineTotal: number
  /** PULA Food: chosen extras/sides. lineTotal already includes them. Empty for retail. */
  modifiers?: CartModifier[]
}

/** CartResponse. */
export interface Cart {
  items: CartLine[]
  total: number
  currency: string | null
}

export type OrderStatus =
  | "PENDING_PAYMENT"
  | "PAYMENT_PROCESSING"
  | "PAID"
  | "PROCESSING"
  | "READY"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUND_PENDING"
  | "REFUNDED"

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: "Awaiting payment",
  PAYMENT_PROCESSING: "Processing payment",
  PAID: "Paid",
  PROCESSING: "Preparing",
  READY: "Ready",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  REFUND_PENDING: "Refund pending",
  REFUNDED: "Refunded",
}

/** Next legal status hops a seller can move an order to, mirroring OrderStatus.canTransitionTo on the backend. */
export const NEXT_ORDER_STATUSES: Record<OrderStatus, OrderStatus[]> = {
  PENDING_PAYMENT: [],
  PAYMENT_PROCESSING: [],
  PAID: ["PROCESSING", "REFUND_PENDING"],
  PROCESSING: ["READY", "REFUND_PENDING"],
  READY: ["COMPLETED", "REFUND_PENDING"],
  COMPLETED: ["REFUND_PENDING"],
  REFUND_PENDING: ["REFUNDED"],
  CANCELLED: [],
  REFUNDED: [],
}

/** OrderItemResponse. */
export interface OrderLine {
  productId: string
  productName: string
  unitPrice: number
  quantity: number
  lineTotal: number
  /** PULA Food: price snapshots of the chosen extras. */
  modifiers?: { name: string; priceDelta: number }[]
}

export type OrderKind = "RETAIL" | "FOOD"
export type RestaurantDecision = "PENDING" | "ACCEPTED" | "REJECTED" | "WITHDRAWN"

/** OrderResponse. */
export interface Order {
  id: string
  buyerId: string
  buyerName: string
  businessId: string
  businessName: string
  items: OrderLine[]
  subtotal: number
  total: number
  currency: string
  status: OrderStatus
  fulfilmentMethod: FulfilmentOption | null
  deliveryAddress: string | null
  createdAt: string
  // ---- PULA Food (absent / RETAIL on ordinary orders) ----
  kind?: OrderKind
  branchId?: string | null
  restaurantDecision?: RestaurantDecision | null
  rejectionReason?: string | null
  acceptDeadlineAt?: string | null
  deliveryFee?: number | null
  customerNote?: string | null
}

// ---- MoMoRide delivery integration ----

export type DeliveryStatus = "REQUESTED" | "ACCEPTED" | "PICKED_UP" | "DELIVERED" | "CANCELLED"

export const DELIVERY_STATUS_LABELS: Record<DeliveryStatus, string> = {
  REQUESTED: "Looking for a driver",
  ACCEPTED: "Driver assigned",
  PICKED_UP: "On the way",
  DELIVERED: "Delivered",
  CANCELLED: "Cancelled",
}

/** DeliveryRequestResponse. */
export interface DeliveryRequestInfo {
  id: string
  orderId: string
  businessName: string
  driverId: string | null
  driverName: string | null
  status: DeliveryStatus
  pickupLocation: string
  dropoffLocation: string
  estimatedFee: number | null
  currency: string
  acceptedAt: string | null
  pickedUpAt: string | null
  deliveredAt: string | null
  createdAt: string
}

export type NotificationType =
  | "PAYMENT_REQUEST"
  | "PAYMENT_SUCCESS"
  | "PAYMENT_FAILED"
  | "CONTRIBUTION_REMINDER"
  | "GOAL_COMPLETED"
  | "CIRCLE_INVITATION"
  | "ORDER_RECEIVED"
  | "ORDER_PAID"
  | "ORDER_PROCESSING"
  | "ORDER_COMPLETED"
  | "REFUND"
  | "SYSTEM"
  | "SERVICE_REQUEST_RECEIVED"
  | "SERVICE_QUOTE_UPDATE"
  | "SERVICE_BOOKING_UPDATE"
  | "EXPENSE_REQUEST"
  | "EXPENSE_UPDATE"
  | "STOKVEL_APPROVAL_REQUESTED"
  | "STOKVEL_APPROVAL_COMPLETED"
  | "STOKVEL_CONTRIBUTION_DUE"
  | "STOKVEL_CONTRIBUTION_MISSED"
  | "STOKVEL_PAYOUT_UPCOMING"

/** NotificationResponse. */
export interface AppNotification {
  id: string
  type: NotificationType
  message: string
  referenceId: string | null
  read: boolean
  createdAt: string
}

export type ReviewTargetType = "BUSINESS" | "PRODUCT"

/** ReviewResponse. */
export interface Review {
  id: string
  authorName: string
  targetType: ReviewTargetType
  targetId: string
  rating: number
  text: string | null
  createdAt: string
  // PULA Food: optional 1-5 sub-ratings, and the server-set trust labels
  taste?: number | null
  portionSize?: number | null
  valueForMoney?: number | null
  foodQuality?: number | null
  packaging?: number | null
  deliveryExperience?: number | null
  /** "Verified PULA order". Set by the server only. */
  verifiedOrder?: boolean
  /** Always "PULA": external reviews are never mixed into PULA scores. */
  source?: string
}

export interface CreateReviewRequest {
  targetType: ReviewTargetType
  targetId: string
  orderId: string
  rating: number
  text?: string | null
  taste?: number | null
  portionSize?: number | null
  valueForMoney?: number | null
  foodQuality?: number | null
  packaging?: number | null
  deliveryExperience?: number | null
}

/** ReceiptResponse. */
export interface Receipt {
  id: string
  receiptNumber: string
  payerName: string
  payeeLabel: string
  description: string
  amount: number
  currency: string
  status: PaymentStatus
  providerReference: string | null
  createdAt: string
}

/** SellerDashboardResponse. */
export interface SellerDashboard {
  totalSales: number
  todaySales: number
  weekSales: number
  monthSales: number
  totalOrders: number
  pendingOrders: number
  completedOrders: number
  cancelledOrders: number
  topProducts: Product[]
  currency: string | null
  uniqueCustomers: number
  dailyRevenue: { date: string; amount: number }[]
  deliveryStats: { requested: number; delivered: number; cancelled: number }
}

export interface Taxi { id:string; publicTaxiId:string; registrationNumber:string; paymentCode:string; qrPayload:string; makeModel:string|null; status:string; driverName:string }
export interface DriverResponse { id:string; userId:string; driverName:string; status:string; hasPin:boolean; taxi:Taxi|null }
export interface TaxiPayment { paymentId:string; taxiId:string; publicTaxiId:string; driverName:string; amount:number; currency:string; status:string; providerReference:string|null }
export interface TaxiPaymentRequestItem { id:string; taxiId:string; publicTaxiId:string; driverName:string; amount:number; currency:string; status:string; paymentId:string|null; expiresAt:string|null }
export interface Ride { id:string; driverId:string; driverName:string; origin:string; destination:string; departureTime:string; availableSeats:number; pricePerPassenger:number; currency:string; status:string }
export interface RideBooking { id:string; rideId:string; passengerName:string; status:string; paymentId:string|null; paymentStatus:string }
export interface RideRating { id:string; rideId:string; raterName:string; rating:number; comment:string|null; createdAt:string }
export interface MoMoRideReport { id:string; targetType:string; rideId:string|null; taxiId:string|null; paymentId:string|null; status:string; createdAt:string }

// ---- Money Confidence ----

export type IncomeType = "FIXED_INCOME" | "VARIABLE_INCOME" | "IRREGULAR_INCOME"

export const INCOME_TYPE_LABELS: Record<IncomeType, string> = {
  FIXED_INCOME: "Fixed income",
  VARIABLE_INCOME: "Variable income",
  IRREGULAR_INCOME: "Irregular income",
}

/** FinancialProfileResponse. */
export interface FinancialProfile {
  id: string
  userId: string
  incomeType: IncomeType
  currency: string
  createdAt: string
}

/** IncomeRecordResponse. */
export interface IncomeRecord {
  id: string
  amount: number
  currency: string
  receivedDate: string
  source: string | null
  createdAt: string
}

export interface CreateIncomeRecordRequest {
  amount: number
  currency?: string
  receivedDate: string
  source?: string | null
}

/** IncomeBucket. */
export interface IncomeBucket {
  periodStart: string
  periodEnd: string
  total: number
  recordCount: number
  isProjected: boolean
}

/** IncomeSummaryResponse. */
export interface IncomeSummary {
  currency: string
  buckets: IncomeBucket[]
  averagePerBucket: number | null
  minimumObservedPerBucket: number | null
}

/** BudgetCategoryResponse / request. */
export interface BudgetCategory {
  id?: string
  name: string
  allocatedAmount: number
}

/** BudgetResponse. */
export interface Budget {
  id: string
  label: string
  currency: string
  categories: BudgetCategory[]
  totalAllocated: number
  createdAt: string
}

export interface CreateOrUpdateBudgetRequest {
  label: string
  currency?: string
  categories: { name: string; allocatedAmount: number }[]
}

// ---- Affordability ----

export type AffordabilityResult = "AFFORDABLE" | "CAUTION" | "NOT_RECOMMENDED" | "INSUFFICIENT_INFORMATION"

export const AFFORDABILITY_LABELS: Record<AffordabilityResult, string> = {
  AFFORDABLE: "Affordable",
  CAUTION: "Use caution",
  NOT_RECOMMENDED: "Not recommended",
  INSUFFICIENT_INFORMATION: "Not enough info yet",
}

export interface AssessAffordabilityRequest {
  amount: number
  currency?: string
  context?: string | null
  category?: string | null
}

/** AffordabilityAssessmentResponse. */
export interface AffordabilityAssessment {
  id: string
  amount: number
  currency: string
  context: string | null
  category: string | null
  result: AffordabilityResult
  reasoning: string
  createdAt: string
}

// ---- Responsible credit (educational calculator only) ----

export interface CalculateLoanRequest {
  principal: number
  currency?: string
  annualInterestRatePercent: number
  termMonths: number
  fees?: number | null
}

/** LoanCalculationResponse. */
export interface LoanCalculation {
  id: string
  principal: number
  currency: string
  annualInterestRatePercent: number
  termMonths: number
  fees: number
  monthlyPayment: number
  totalRepayment: number
  totalInterest: number
  totalCostPercent: number
  warnings: string[]
  disclaimer: string
  createdAt: string
}

// ---- ScamShield ----

export type ScamRiskLevel = "LOW" | "MEDIUM" | "HIGH"

export const SCAM_RISK_LABELS: Record<ScamRiskLevel, string> = {
  LOW: "Low risk",
  MEDIUM: "Medium risk",
  HIGH: "High risk",
}

export interface AssessScamRequest {
  text: string
}

/** ScamAssessmentResponse. Note: submittedText is always the redacted version. */
export interface ScamAssessment {
  id: string
  submittedText: string
  riskLevel: ScamRiskLevel
  indicators: string[]
  guidance: string
  createdAt: string
}

// ---- PULA Market — Stock / B2B procurement (brief §12-15) ----

export type StockFulfilmentOption = "SELF_COLLECTION" | "WHOLESALER_DELIVERY" | "PULA_RIDE_DELIVERY"

export const STOCK_FULFILMENT_LABELS: Record<StockFulfilmentOption, string> = {
  SELF_COLLECTION: "Self collection (R0 delivery)",
  WHOLESALER_DELIVERY: "Supplier delivers",
  PULA_RIDE_DELIVERY: "PULA Ride delivery",
}

export type StockOrderStatus =
  | "PENDING_PAYMENT" | "PAYMENT_PROCESSING" | "PAID" | "CONFIRMED"
  | "PREPARING" | "READY_FOR_COLLECTION" | "OUT_FOR_DELIVERY" | "DELIVERED"
  | "CANCELLED" | "REFUND_PENDING" | "REFUNDED"

export const STOCK_ORDER_STATUS_LABELS: Record<StockOrderStatus, string> = {
  PENDING_PAYMENT: "Awaiting payment",
  PAYMENT_PROCESSING: "Payment processing",
  PAID: "Paid — awaiting supplier",
  CONFIRMED: "Confirmed",
  PREPARING: "Supplier preparing stock",
  READY_FOR_COLLECTION: "Ready for collection",
  OUT_FOR_DELIVERY: "Out for delivery",
  DELIVERED: "Delivered",
  CANCELLED: "Cancelled",
  REFUND_PENDING: "Refund pending",
  REFUNDED: "Refunded",
}

export interface WholesaleProduct {
  id: string
  supplierBusinessId: string
  supplierBusinessName: string
  name: string
  description: string | null
  sku: string | null
  category: string | null
  packSize: number
  moq: number
  pricePerPack: number
  currency: string
  availableStock: number
  sellingMode: "INDIVIDUAL_ONLY" | "BULK_ONLY" | "BOTH"
  pricePerUnit: number | null
  active: boolean
  createdAt: string
  imageUrls: string[]
  canonicalProductId?: string | null
  canonicalProductName?: string | null
  matchClassification?: MatchClassification
}

export interface WholesalePriceComparisonEntry {
  wholesaleProductId: string
  supplierBusinessId: string
  supplierBusinessName: string
  name: string
  pricePerPack: number | null
  currency: string
  packSize: number
  moq: number
  availableStock: number
  sellingMode: string
  pricePerUnit: number | null
  normalizedPricePerItem: number | null
  available: boolean
}

export interface WholesalePriceComparison {
  canonicalProductId: string
  canonicalProductName: string
  exactMatches: WholesalePriceComparisonEntry[]
  similarAlternatives: WholesalePriceComparisonEntry[]
}

export interface CreateWholesaleProductRequest {
  name: string
  description?: string | null
  sku?: string | null
  category?: string | null
  packSize: number
  moq: number
  pricePerPack: number
  availableStock: number
  sellingMode: "INDIVIDUAL_ONLY" | "BULK_ONLY" | "BOTH"
  pricePerUnit?: number | null
  imageUrls?: string[] | null
}

export interface StockCartItem {
  id: string
  wholesaleProductId: string
  productName: string
  sku: string | null
  packSize: number
  moq: number
  pricePerPack: number
  currency: string
  quantityPacks: number
  lineTotal: number
  moqMet: boolean
  stockSufficient: boolean
  supplierBusinessId: string
  supplierBusinessName: string
}

export interface StockCart {
  items: StockCartItem[]
  subtotal: number
  currency: string
  valid: boolean
  validationMessages: string[]
}

export interface StockOrderItem {
  wholesaleProductId: string
  productName: string
  sku: string | null
  packSizeSnapshot: number
  quantityPacks: number
  pricePerPackSnapshot: number
  lineTotal: number
}

export interface StockOrder {
  id: string
  buyerBusinessId: string
  buyerBusinessName: string
  supplierBusinessId: string
  supplierBusinessName: string
  items: StockOrderItem[]
  subtotal: number
  deliveryFee: number
  total: number
  currency: string
  status: StockOrderStatus
  fulfilmentMethod: StockFulfilmentOption
  deliveryAddress: string | null
  createdAt: string
  updatedAt: string
}

// ---- PULA Cash Flow (brief §16-17) ----

export interface BusinessIncomeEntry {
  id: string
  amount: number
  currency: string
  receivedDate: string
  description: string | null
  category: string | null
  source: "PULA_ORDER" | "MANUAL"
  createdAt: string
}

export interface BusinessExpenseEntry {
  id: string
  amount: number
  currency: string
  incurredDate: string
  description: string | null
  category: string
  source: string
  createdAt: string
}

export interface BusinessCashFlowSummary {
  periodFrom: string
  periodTo: string
  currency: string
  salesIncome: number
  otherIncome: number
  totalIncome: number
  stockSpend: number
  deliverySpend: number
  operatingExpenses: number
  totalExpenses: number
  netCashMovement: number
  saleCount: number
  stockOrderCount: number
  profileComplete: boolean
  recentIncome: BusinessIncomeEntry[]
  recentExpenses: BusinessExpenseEntry[]
  // ---- PULA Food (zero for businesses with no food orders) ----
  /** Item totals on completed food orders, excluding delivery fees. Already inside salesIncome. */
  foodSales?: number
  /** Delivery fees kept for the restaurant's own riders. Already inside salesIncome. */
  ownDeliveryIncome?: number
  /** PULA Ride delivery fees paid by customers. NOT restaurant income and NOT in salesIncome. */
  pulaRideDeliveryFees?: number
  /** Of those fees, the 80% that went to drivers. Information only. */
  pulaRideDriverEarnings?: number
  /** Of those fees, PULA's 20%. Information only. */
  pulaRideFeeIncome?: number
  /** PULA commission on completed food orders (already deducted in totalExpenses). */
  foodCommission?: number
  /** Compensation for prepared food PULA could not deliver (already in totalIncome). */
  merchantProtectionIncome?: number
  /** Customer refunds on your food orders. Information only: not part of the totals. */
  foodRefunds?: number
  /** Settlements PULA has paid out. Information only: not added to the totals again. */
  foodSettlementsPaid?: number
  /** Settlements earned but not yet paid out. Information only. */
  foodSettlementsOwed?: number
}

export const EXPENSE_CATEGORIES = ["STOCK", "DELIVERY", "OPERATING", "OTHER"] as const
export type ExpenseCategory = typeof EXPENSE_CATEGORIES[number]

// ---- PULA Market payments, funding readiness, pools, payouts, planner ----
export interface StockPaymentSession {
  orderId: string
  reference: string
  checkoutUrl: string | null
  mode: "PAYSTACK" | "SANDBOX_MOCK" | "WALLET_SIMULATED"
  sandbox: boolean
  amount: number
  currency: string
  paymentStatus: string
  orderStatus: StockOrderStatus
}

export interface ReadinessItem { key: string; label: string; met: boolean; detail: string }
export interface FundingReadiness {
  businessId: string
  businessName: string
  periodFrom: string
  periodTo: string
  items: ReadinessItem[]
  metCount: number
  totalCount: number
  missingInformation: string[]
  recommendedPreparation: string[]
  disclaimer: string
}
export interface FundingReadinessReport {
  generatedAt: string
  readiness: FundingReadiness
  businessType: string
  location: string | null
  registrationStatus: string
  registrationNumberProvided: boolean
  capabilities: string[]
  currency: string
  recordedSales: number
  otherRecordedIncome: number
  stockPurchases: number
  deliveryCosts: number
  operatingAndOtherExpenses: number
  netCashMovement: number
  completedSales: number
  stockOrders: number
  monthsWithRecords: number
  documentsOnFile: number
  preparationChecklist: string[]
  dataNote: string
}

export interface PoolMember { id: string; driverId: string; driverName: string; phoneNumber: string; active: boolean; status: string }
export interface DriverPayout {
  id: string
  deliveryRequestId: string
  amount: number
  currency: string
  status: "PENDING" | "PROCESSING" | "SUCCESSFUL" | "FAILED" | "REVERSED"
  provider: string
  sandbox: boolean
  providerReference: string | null
  failureReason: string | null
  createdAt: string
}

export interface StockPlanLine {
  wholesaleProductId: string; supplierBusinessId: string; supplierName: string
  productName: string; sku: string | null; packSize: number; quantityPacks: number
  pricePerPack: number; lineTotal: number
}
export interface StockPlan {
  budget: number; currency: string; lines: StockPlanLine[]
  suppliers: { supplierBusinessId: string; supplierName: string; subtotal: number; deliveryFee: number; total: number }[]
  estimatedTotal: number; remainingBudget: number; potentialSavings: number; notes: string[]
}

export interface CashFlowTrendPoint {
  periodFrom: string; periodTo: string
  totalIncome: number; totalExpenses: number; netCashMovement: number
  stockSpend: number; deliverySpend: number
}
export interface JourneyStage { key: string; title: string; description: string; done: boolean; route: string }
export interface BusinessJourney {
  hasBusiness: boolean; businessName: string | null; stages: JourneyStage[]
  doneCount: number; nextStageKey: string; note: string
}
export interface PoolInvite { id: string; businessId: string; businessName: string }
export interface CatalogueImportResult { created: number; updated: number; errors: { row: number; message: string }[] }
