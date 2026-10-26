// Mirrors ServiceDtos on the backend. The backend is authoritative for every price, total and status.
export type PricingModel = "FIXED" | "HOURLY" | "QUOTE_REQUIRED" | "STARTING_FROM"
export type MaterialsResponsibility = "NONE" | "CUSTOMER" | "PROVIDER" | "MIXED"
export type MaterialSupplier = "CUSTOMER" | "PROVIDER"
export type ListingStatus = "DRAFT" | "ACTIVE" | "PAUSED" | "REMOVED"
export type Verification = "UNVERIFIED" | "PENDING" | "VERIFIED" | "REJECTED"
export type ServiceRequestStatus =
  | "DRAFT" | "SUBMITTED" | "QUOTED" | "BOOKED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "DECLINED" | "EXPIRED"
export type QuoteStatus = "SUBMITTED" | "CLARIFICATION_REQUESTED" | "ACCEPTED" | "REJECTED" | "SUPERSEDED" | "EXPIRED"
export type BookingStatus = "BOOKED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED"

export const PRICING_LABELS: Record<PricingModel, string> = {
  FIXED: "Fixed price", HOURLY: "Per hour", QUOTE_REQUIRED: "Quote required", STARTING_FROM: "Starting from",
}
export const MATERIALS_LABELS: Record<MaterialsResponsibility, string> = {
  NONE: "No materials", CUSTOMER: "Customer supplies", PROVIDER: "Provider supplies", MIXED: "Either / mixed",
}

export interface ServiceCategory { id: string; parentId: string | null; name: string; slug: string; sortOrder: number; active: boolean }
export interface ServiceListing {
  id: string; businessId: string; businessName: string; categoryId: string; categoryName: string; name: string
  description: string | null; pricingModel: PricingModel; price: number | null; currency: string; durationMinutes: number | null
  materialsResponsibility: MaterialsResponsibility; status: ListingStatus; hiddenByAdmin: boolean
  serviceAreas: string[]; imageUrls: string[]; ratingAverage: number; ratingCount: number
  providerVerification: Verification; acceptingRequests: boolean
}
export interface ProviderProfile {
  businessId: string; businessName: string; contactPhone: string | null; contactEmail: string | null; headline: string | null
  about: string | null; availabilityNote: string | null; acceptingRequests: boolean; verificationStatus: Verification
  suspended: boolean; serviceAreas: string[]; portfolioUrls: string[]; ratingAverage: number; ratingCount: number
  listings: ServiceListing[]
}
export interface ServiceQuote {
  id: string; requestId: string; versionNo: number; status: QuoteStatus; hours: number | null; labourAmount: number
  materialsAmount: number; deliveryAmount: number; otherAmount: number; otherDescription: string | null; total: number
  currency: string; validUntil: string; notes: string | null; customerMessage: string | null; createdAt: string
}
export interface MaterialLine {
  id: string; wholesaleProductId: string; productName: string; supplierBusinessId: string; quantityPacks: number
  suppliedBy: MaterialSupplier; unitPrice: number; lineTotal: number; stockOrderId: string | null; addedById: string
}
export interface ServiceRequest {
  id: string; listingId: string; listingName: string; businessId: string; businessName: string; customerId: string
  customerName: string; status: ServiceRequestStatus; description: string; location: string; preferredDate: string | null
  preferredTime: string | null; requirements: string | null; materialsResponsibility: MaterialsResponsibility
  pricingModel: PricingModel; listingPrice: number | null; currency: string; quotes: ServiceQuote[]; materials: MaterialLine[]
  bookingId: string | null; createdAt: string
}
export interface ServiceBooking {
  id: string; requestId: string; quoteId: string; listingId: string; listingName: string; businessId: string
  businessName: string; customerId: string; customerName: string; status: BookingStatus; total: number; currency: string
  scheduledDate: string | null; scheduledTime: string | null; startedAt: string | null; completedAt: string | null
  cancelledAt: string | null; cancelReason: string | null; paymentStatus: "UNPAID" | "PROCESSING" | "PAID" | "FAILED"
  saveToBuyRequestId: string | null; canReview: boolean; reviewed: boolean; materials: MaterialLine[]; createdAt: string
}
export interface PaymentSession { bookingId: string; paymentReference: string; checkoutUrl: string | null; paymentStatus: string; amount: number; currency: string }
export interface ServiceInsights { summary: string; points: string[]; notice: string }
export interface Page<T> { content: T[]; totalElements: number }

export interface ListingPayload {
  categoryId: string; name: string; description?: string; pricingModel: PricingModel; price?: number
  durationMinutes?: number; materialsResponsibility: MaterialsResponsibility; serviceAreas?: string[]
  imageUrls?: string[]; status?: ListingStatus
}
export interface QuotePayload {
  hours?: number; labourAmount?: number; deliveryAmount?: number; otherAmount?: number
  otherDescription?: string; validUntil: string; notes?: string
}
export interface SearchFilters {
  q?: string; categoryId?: string; pricingModel?: PricingModel; maxPrice?: number; minRating?: number
  verifiedOnly?: boolean; area?: string
}
