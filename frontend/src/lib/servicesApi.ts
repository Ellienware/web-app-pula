import { api } from "./api"
import type {
  ListingPayload, MaterialLine, Page, PaymentSession, ProviderProfile, QuotePayload, SearchFilters, ServiceBooking,
  ServiceCategory, ServiceInsights, ServiceListing, ServiceQuote, ServiceRequest, Verification,
} from "./servicesTypes"

const qs = (o: Record<string, unknown>) => {
  const p = new URLSearchParams()
  Object.entries(o).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)) })
  const s = p.toString()
  return s ? `?${s}` : ""
}

export const servicesApi = {
  categories: () => api.get<ServiceCategory[]>("/services/categories"),
  search: (f: SearchFilters, page = 0) => api.get<Page<ServiceListing>>(`/services/search${qs({ ...f, page })}`),
  listing: (id: string) => api.get<ServiceListing>(`/services/listings/${id}`),
  compare: (ids: string[]) => api.get<ServiceListing[]>(`/services/compare${qs({ ids: ids.join(",") })}`),
  compareInsights: (ids: string[]) => api.get<ServiceInsights>(`/services/compare/insights${qs({ ids: ids.join(",") })}`),
  provider: (businessId: string) => api.get<ProviderProfile>(`/services/providers/${businessId}`),

  // customer
  createRequest: (p: { listingId: string; description: string; location: string; preferredDate?: string; preferredTime?: string;
    requirements?: string; materialsResponsibility?: string; submit?: boolean; idempotencyKey: string }) =>
    api.post<ServiceRequest>("/services/requests", p),
  myRequests: () => api.get<ServiceRequest[]>("/services/requests"),
  request: (id: string) => api.get<ServiceRequest>(`/services/requests/${id}`),
  submitRequest: (id: string) => api.post<ServiceRequest>(`/services/requests/${id}/submit`),
  cancelRequest: (id: string) => api.post<ServiceRequest>(`/services/requests/${id}/cancel`),
  acceptQuote: (rid: string, qid: string) => api.post<ServiceBooking>(`/services/requests/${rid}/quotes/${qid}/accept`),
  rejectQuote: (rid: string, qid: string) => api.post<ServiceQuote>(`/services/requests/${rid}/quotes/${qid}/reject`),
  clarifyQuote: (rid: string, qid: string, message: string) =>
    api.post<ServiceQuote>(`/services/requests/${rid}/quotes/${qid}/clarify`, { message }),
  explainQuote: (rid: string, qid: string) => api.get<ServiceInsights>(`/services/requests/${rid}/quotes/${qid}/explain`),
  materials: (rid: string) => api.get<MaterialLine[]>(`/services/requests/${rid}/materials`),
  addMaterial: (rid: string, wholesaleProductId: string, quantityPacks: number, suppliedBy: "CUSTOMER" | "PROVIDER") =>
    api.post<MaterialLine>(`/services/requests/${rid}/materials`, { wholesaleProductId, quantityPacks, suppliedBy }),
  linkStockOrder: (rid: string, lineId: string, stockOrderId: string) =>
    api.put<MaterialLine>(`/services/requests/${rid}/materials/${lineId}/stock-order`, { stockOrderId }),
  myBookings: () => api.get<ServiceBooking[]>("/services/bookings"),
  booking: (id: string) => api.get<ServiceBooking>(`/services/bookings/${id}`),
  cancelBooking: (id: string, reason: string) => api.post<ServiceBooking>(`/services/bookings/${id}/cancel`, { reason }),
  /** No amount is sent: the server charges the booking total. Optional Save-to-Buy reference. */
  pay: (id: string, saveToBuyRequestId?: string) => api.post<PaymentSession>(`/services/bookings/${id}/pay`, { saveToBuyRequestId }),
  /** SIMULATED demo-wallet payment, two steps: start charges nothing and returns the server amount; pay debits after that exact amount is confirmed. */
  walletStart: (id: string, saveToBuyRequestId?: string) =>
    api.post<PaymentSession>(`/services/bookings/${id}/pay/wallet/start`, { saveToBuyRequestId }),
  walletPay: (id: string, expectedAmount: number) =>
    api.post<{ bookingId: string; paymentStatus: string; bookingStatus: string; paid: boolean; replayed: boolean;
      ledgerReference: string | null; amount: number; currency: string; simulated: true; notice: string }>(
      `/services/bookings/${id}/pay/wallet`, { confirm: true, expectedAmount }),
  review: (id: string, rating: number, text: string, rateListing: boolean) =>
    api.post<unknown>(`/services/bookings/${id}/review`, { rating, text, rateListing }),

  // provider
  myProfile: () => api.get<ProviderProfile>("/services/provider/profile"),
  saveProfile: (p: { headline?: string; about?: string; contactEmail?: string; availabilityNote?: string;
    acceptingRequests?: boolean; serviceAreas?: string[]; portfolioUrls?: string[] }) => api.put<ProviderProfile>("/services/provider/profile", p),
  requestVerification: () => api.post<ProviderProfile>("/services/provider/verification-request"),
  myListings: () => api.get<ServiceListing[]>("/services/provider/listings"),
  createListing: (p: ListingPayload) => api.post<ServiceListing>("/services/provider/listings", p),
  updateListing: (id: string, p: ListingPayload) => api.put<ServiceListing>(`/services/provider/listings/${id}`, p),
  removeListing: (id: string) => api.del<ServiceListing>(`/services/provider/listings/${id}`),
  inbox: () => api.get<ServiceRequest[]>("/services/provider/requests"),
  decline: (id: string) => api.post<ServiceRequest>(`/services/provider/requests/${id}/decline`),
  quote: (id: string, p: QuotePayload) => api.post<ServiceQuote>(`/services/provider/requests/${id}/quotes`, p),
  providerAddMaterial: (rid: string, wholesaleProductId: string, quantityPacks: number) =>
    api.post<MaterialLine>(`/services/provider/requests/${rid}/materials`, { wholesaleProductId, quantityPacks, suppliedBy: "PROVIDER" }),
  providerBookings: () => api.get<ServiceBooking[]>("/services/provider/bookings"),
  start: (id: string) => api.post<ServiceBooking>(`/services/provider/bookings/${id}/start`),
  complete: (id: string) => api.post<ServiceBooking>(`/services/provider/bookings/${id}/complete`),

  // admin (server enforces ADMIN)
  adminCategories: () => api.get<ServiceCategory[]>("/admin/services/categories"),
  adminCreateCategory: (name: string, parentId?: string) => api.post<ServiceCategory>("/admin/services/categories", { name, parentId, active: true }),
  adminSetCategoryActive: (c: ServiceCategory, active: boolean) =>
    api.put<ServiceCategory>(`/admin/services/categories/${c.id}`, { name: c.name, slug: c.slug, parentId: c.parentId, sortOrder: c.sortOrder, active }),
  adminPending: () => api.get<ProviderProfile[]>("/admin/services/providers/pending"),
  adminVerify: (businessId: string, status: Verification) => api.put<ProviderProfile>(`/admin/services/providers/${businessId}/verification`, { status }),
  adminSuspend: (businessId: string, suspended: boolean, reason?: string) =>
    api.put<ProviderProfile>(`/admin/services/providers/${businessId}/suspension`, { suspended, reason }),
  adminHideListing: (id: string, hidden: boolean) => api.put<ServiceListing>(`/admin/services/listings/${id}/visibility`, { hidden }),
  adminBookings: () => api.get<ServiceBooking[]>("/admin/services/bookings"),
}
