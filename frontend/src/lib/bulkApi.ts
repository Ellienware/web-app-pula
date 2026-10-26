import { api } from "./api"
import type {
  AllocationView, BulkEvent, ConfirmOutcome, CreateGroupRequest, FinaliseOutcome, FulfilmentMethod, GroupDetail,
  GroupSummary, JoinRequest, PageOf, PlaceOrderOutcome, Quote, SupplierGroupView, TierView,
} from "./bulkTypes"

const g = (id: string) => `/bulk/groups/${encodeURIComponent(id)}`

/**
 * Bulk purchasing client. Nothing here computes a price: it sends quantities (and the price being acknowledged) and shows
 * what the server returns. Payment is NOT here: each business pays its own allocation through the existing stock order flow.
 */
export const bulkApi = {
  // discovery & mine
  open: (page = 0) => api.get<PageOf<GroupSummary>>(`/bulk/groups?page=${page}&size=20`),
  mine: (page = 0) => api.get<PageOf<GroupSummary>>(`/bulk/groups/mine?page=${page}&size=20`),
  forSupplier: (page = 0) => api.get<PageOf<GroupSummary>>(`/bulk/groups/supplier?page=${page}&size=20`),
  myAllocations: (page = 0) => api.get<PageOf<AllocationView>>(`/bulk/allocations/mine?page=${page}&size=20`),
  get: (id: string) => api.get<GroupDetail>(g(id)),
  events: (id: string) => api.get<PageOf<BulkEvent>>(`${g(id)}/events?size=50`),

  // organiser
  create: (req: CreateGroupRequest) => api.post<GroupDetail>("/bulk/groups", req),
  update: (id: string, req: Partial<Pick<CreateGroupRequest, "name" | "description" | "targetPacks" | "deadline" | "shortfallPolicy" | "collectionNote">>) =>
    api.patch<GroupDetail>(g(id), req),
  openGroup: (id: string) => api.post<GroupDetail>(`${g(id)}/open`),
  startConfirmation: (id: string) => api.post<GroupDetail>(`${g(id)}/start-confirmation`),
  finalise: (id: string, acceptBelowTarget: boolean, dropUnconfirmed: boolean) =>
    api.post<FinaliseOutcome>(`${g(id)}/finalise`, { acceptBelowTarget, dropUnconfirmed }),
  placeOrder: (id: string, proceedWithPaidOnly: boolean) => api.post<PlaceOrderOutcome>(`${g(id)}/place-order`, { proceedWithPaidOnly }),
  cancel: (id: string, reason?: string) => api.post<GroupDetail>(`${g(id)}/cancel`, { reason }),
  refresh: (id: string) => api.post<GroupDetail>(`${g(id)}/refresh`),

  // participation (always the caller's own allocation)
  quote: (id: string, packs: number, method?: FulfilmentMethod) =>
    api.get<Quote>(`${g(id)}/quote?packs=${packs}${method ? `&fulfilmentMethod=${method}` : ""}`),
  join: (id: string, req: JoinRequest) => api.post<AllocationView>(`${g(id)}/join`, req),
  myAllocation: (id: string) => api.get<AllocationView>(`${g(id)}/allocation`),
  updateAllocation: (id: string, req: { requestedPacks?: number; fulfilmentMethod?: FulfilmentMethod; deliveryAddress?: string }) =>
    api.patch<AllocationView>(`${g(id)}/allocation`, req),
  confirm: (id: string, acceptedPacks: number, acceptedPricePerPack: number) =>
    api.post<ConfirmOutcome>(`${g(id)}/allocation/confirm`, { acceptedPacks, acceptedPricePerPack }),
  withdraw: (id: string) => api.post<AllocationView>(`${g(id)}/allocation/withdraw`),
  reportIssue: (id: string, type: "DAMAGED" | "MISSING" | "PARTIAL_DELIVERY", packs: number, note?: string) =>
    api.post<AllocationView>(`${g(id)}/allocation/issues`, { type, packs, note }),

  // supplier
  supplierView: (id: string) => api.get<SupplierGroupView>(`${g(id)}/supplier`),
  supplierConfirm: (id: string, confirmedPacks: number, supplierReference?: string, note?: string) =>
    api.post<GroupDetail>(`${g(id)}/supplier/confirm`, { confirmedPacks, supplierReference, note }),
  tiers: (productId: string) => api.get<TierView[]>(`/stock/products/${encodeURIComponent(productId)}/volume-tiers`),
  setTiers: (productId: string, tiers: TierView[]) =>
    api.put<TierView[]>(`/stock/products/${encodeURIComponent(productId)}/volume-tiers`, { tiers }),

  // admin (read-only oversight)
  adminRefundDue: () => api.get<PageOf<AllocationView>>("/admin/bulk/refund-due?size=50"),
}
