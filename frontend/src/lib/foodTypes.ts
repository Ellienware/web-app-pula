// PULA Food — API shapes. They mirror FoodDiscoveryDtos / FoodDtos / ReviewDtos on the backend.
// Responses give comparable facts; none of them carries a "winner", and customer coordinates are
// never part of any response.
import type { BusinessType, FulfilmentOption } from "./types"

export type FoodSort = "RELEVANCE" | "DISTANCE" | "PRICE" | "RATING" | "NAME"
export type FulfilmentFilter = "COLLECTION" | "DELIVERY"
export type MatchLevel = "EXACT_MATCH" | "SIMILAR_MEAL" | "ALTERNATIVE"

export const MATCH_LEVEL_LABELS: Record<MatchLevel, string> = {
  EXACT_MATCH: "Exact match",
  SIMILAR_MEAL: "Similar meal",
  ALTERNATIVE: "Alternative",
}

export interface FoodOffer {
  productId: string
  name: string
  description: string | null
  imageUrl: string | null
  businessId: string
  businessName: string
  businessType: BusinessType
  branchId: string | null
  branchName: string | null
  area: string | null
  address: string | null
  price: number
  currency: string
  foodType: string | null
  protein: string | null
  starch: string | null
  portionSize: string | null
  portionDescription: string | null
  sides: string[]
  allergens: string[]
  dietaryTags: string[]
  hasExtras: boolean
  itemRating: number
  itemRatingCount: number
  restaurantRating: number
  restaurantRatingCount: number
  collectionAvailable: boolean
  deliveryAvailable: boolean
  deliveryFee: number | null
  prepMinutes: number | null
  distanceKm: number | null
  available: boolean
  unavailableReason: string | null
  openingHours: string[]
  canonicalProductId: string | null
  matchLevel: MatchLevel | null
  matchReasons: string[]
  matchUncertain: boolean
}

export interface FoodSearchResponse {
  offers: FoodOffer[]
  total: number
  page: number
  size: number
  interpretedAs: string[]
  excludedForUnknownDistance: number
}

export interface FoodComparison {
  referenceName: string
  referenceCanonicalId: string | null
  referenceProductId: string | null
  exactMatches: FoodOffer[]
  similarMeals: FoodOffer[]
  alternatives: FoodOffer[]
  excludedForUnknownDistance: number
  note: string
}

export interface RestaurantSummary {
  id: string
  name: string
  businessType: BusinessType
  description: string | null
  logoUrl: string | null
  area: string | null
  branchCount: number
  rating: number
  ratingCount: number
  collectionAvailable: boolean
  deliveryAvailable: boolean
  deliveryFeeFrom: number | null
  minimumOrder: number | null
  prepMinutesFrom: number | null
  nearestDistanceKm: number | null
  open: boolean
  closedReason: string | null
}

export interface RestaurantList {
  restaurants: RestaurantSummary[]
  total: number
  page: number
  size: number
  excludedForUnknownDistance: number
}

export interface BranchView {
  id: string
  name: string
  address: string | null
  area: string | null
  latitude: number | null
  longitude: number | null
  operatingHours: string[]
  fulfilmentOptions: FulfilmentOption[]
  deliveryAreas: string[]
  deliveryFee: number | null
  minimumOrder: number | null
  prepMinutes: number | null
  open: boolean
  closedReason: string | null
  distanceKm: number | null
}

export interface RestaurantDetail {
  id: string
  name: string
  businessType: BusinessType
  description: string | null
  logoUrl: string | null
  location: string | null
  contactPhone: string | null
  contactEmail: string | null
  operatingHours: string[]
  fulfilmentOptions: FulfilmentOption[]
  deliveryFee: number | null
  minimumOrder: number | null
  rating: number
  ratingCount: number
  open: boolean
  closedReason: string | null
  branches: BranchView[]
}

export interface ModifierView { id: string; name: string; priceDelta: number; available: boolean }
export interface ModifierGroupView {
  id: string
  name: string
  minSelect: number
  maxSelect: number
  required: boolean
  modifiers: ModifierView[]
}

export interface MenuItem {
  id: string
  name: string
  description: string | null
  imageUrl: string | null
  price: number
  currency: string
  foodType: string | null
  protein: string | null
  starch: string | null
  portionSize: string | null
  portionDescription: string | null
  sides: string[]
  allergens: string[]
  dietaryTags: string[]
  spiceLevel: string | null
  prepMinutes: number | null
  rating: number
  ratingCount: number
  available: boolean
  unavailableReason: string | null
  canonicalProductId: string | null
  modifierGroups: ModifierGroupView[]
}

export interface MenuCategoryView { id: string | null; name: string; items: MenuItem[] }
export interface Menu {
  businessId: string
  businessName: string
  branchId: string | null
  branchName: string | null
  categories: MenuCategoryView[]
}

// ---- checkout quote ----
export interface CheckoutQuoteLine {
  productId: string
  name: string
  quantity: number
  unitPrice: number
  modifiers: { name: string; priceDelta: number }[]
  lineTotal: number
}
export interface CheckoutQuote {
  businessId: string
  businessName: string
  branchId: string | null
  branchName: string | null
  fulfilmentMethod: FulfilmentOption
  deliveryAddress: string | null
  lines: CheckoutQuoteLine[]
  subtotal: number
  deliveryFee: number
  total: number
  currency: string
  customerNote: string | null
}
export interface FoodCheckoutRequest {
  businessId: string
  branchId?: string | null
  fulfilmentMethod: FulfilmentOption
  deliveryAddress?: string | null
  customerNote?: string | null
  /** The total the customer reviewed. If it has changed by checkout the server refuses instead of charging it. */
  expectedTotal?: number | null
}

// ---- reviews ----
export interface DimensionSummary { dimension: string; average: number | null; count: number }
export interface ReviewSummary {
  targetType: string
  targetId: string
  overallAverage: number
  overallCount: number
  foodDimensions: DimensionSummary[]
  deliveryExperience: DimensionSummary
  verifiedOrderCount: number
  source: string
}

// ---- merchant ----
export interface MenuCategory { id: string; businessId: string; name: string; sortOrder: number }
export interface FoodDetails {
  productId: string
  foodType: string | null
  protein: string | null
  starch: string | null
  portionSize: string | null
  servingSize: string | null
  spiceLevel: string | null
  portionDescription: string | null
  prepTimeMinutes: number | null
  dailyQuantity: number | null
  sides: string[]
  dietaryTags: string[]
  allergens: string[]
}
export interface BranchRecord {
  id: string
  businessId: string
  name: string
  address: string | null
  area: string | null
  latitude: number | null
  longitude: number | null
  active: boolean
  temporarilyClosed: boolean
  prepTimeMinutes: number | null
  deliveryFee: number | null
  minimumOrderAmount: number | null
  operatingHours: string[]
  fulfilmentOptions: FulfilmentOption[]
  deliveryAreas: string[]
}
export interface BranchItem { branchId: string; productId: string; available: boolean; priceOverride: number | null }
export interface ModifierGroupRecord {
  id: string
  businessId: string
  name: string
  minSelect: number
  maxSelect: number
  required: boolean
  modifiers: { id: string; groupId: string; name: string; priceDelta: number; available: boolean }[]
}
export interface FoodSettings {
  temporarilyClosed: boolean
  acceptTimeoutMinutes: number | null
  effectiveAcceptTimeoutMinutes: number
  maxAcceptTimeoutMinutes: number
}
export interface CanonicalSuggestion { canonicalProductId: string; name: string; score: number }

/** "BEEF_STEW" -> "beef stew". Food tokens are stored upper-case with underscores. */
export function prettyToken(t: string | null | undefined): string {
  return t ? t.toLowerCase().replace(/_/g, " ") : ""
}

/** Shown wherever a rating appears: the item's own rating when it has reviews, else the restaurant's, else nothing invented. */
export function ratingLabel(itemRating: number, itemCount: number, restRating: number, restCount: number): string {
  if (itemCount > 0) return `★ ${itemRating.toFixed(1)} (${itemCount})`
  if (restCount > 0) return `Restaurant ★ ${restRating.toFixed(1)} (${restCount})`
  return "No reviews yet"
}
