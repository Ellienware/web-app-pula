import { api } from "./api"

export type BranchStatus = "ACTIVE" | "SUSPENDED" | "CLOSED"
export type BranchRole = "MANAGER" | "STAFF"
export type BranchRelation = "OWNER" | "MANAGER" | "STAFF"
export type BranchPermission = "MANAGE_STOCK" | "MANAGE_PRICES" | "VIEW_ORDERS" | "FULFIL_ORDERS" | "VIEW_REPORTS"
export type OperatingModel = "OWNED" | "FRANCHISED"

export const BRANCH_PERMISSION_LABELS: Record<BranchPermission, string> = {
  MANAGE_STOCK: "Move stock between branches and see stock history",
  MANAGE_PRICES: "Set which products are sold here and branch prices",
  VIEW_ORDERS: "See this branch's orders",
  FULFIL_ORDERS: "Mark orders processing, ready or completed",
  VIEW_REPORTS: "See this branch's sales report",
}
export const ALL_BRANCH_PERMISSIONS = Object.keys(BRANCH_PERMISSION_LABELS) as BranchPermission[]

/** Mirrors the backend BranchView. Nothing here is verified by PULA: it is information submitted by the business. */
export interface Branch {
  id: string
  businessId: string
  businessName: string
  name: string
  branchCode: string | null
  address: string | null
  suburb: string | null
  city: string | null
  province: string | null
  postalCode: string | null
  latitude: number | null
  longitude: number | null
  phone: string | null
  email: string | null
  operatingHours: string[]
  fulfilmentOptions: string[]
  status: BranchStatus
  temporarilyClosed: boolean
  verificationStatus: string
  yourRole: BranchRelation
  yourPermissions: BranchPermission[]
  brandName: string | null
  operatingModel: OperatingModel
  operatorName: string | null
  createdAt: string
  updatedAt: string
}

export interface BranchStaff {
  userId: string
  fullName: string
  email: string | null
  phone: string | null
  role: BranchRole
  permissions: BranchPermission[]
  assignedAt: string
}

export interface MyBranchAssignment {
  branchId: string
  branchName: string
  businessId: string
  businessName: string
  role: BranchRole
  status: BranchStatus
}

export interface BranchInput {
  name?: string
  branchCode?: string
  address?: string
  suburb?: string
  city?: string
  province?: string
  postalCode?: string
  latitude?: number | null
  longitude?: number | null
  phone?: string
  email?: string
  operatingHours?: string[]
  /** Franchise details: owner only, descriptive, NOT verified by PULA. */
  brandName?: string
  operatingModel?: OperatingModel
  operatorName?: string
}

const base = (businessId: string) => `/businesses/${encodeURIComponent(businessId)}/branches`

/** Every call is authorised by the backend; the UI only hides actions the caller's role cannot use. */
export const branchApi = {
  list: (businessId: string) => api.get<Branch[]>(base(businessId)),
  get: (businessId: string, id: string) => api.get<Branch>(`${base(businessId)}/${encodeURIComponent(id)}`),
  create: (businessId: string, body: BranchInput & { name: string }) => api.post<Branch>(base(businessId), body),
  update: (businessId: string, id: string, body: BranchInput) => api.patch<Branch>(`${base(businessId)}/${encodeURIComponent(id)}`, body),
  setStatus: (businessId: string, id: string, status: BranchStatus) =>
    api.post<Branch>(`${base(businessId)}/${encodeURIComponent(id)}/status`, { status }),
  staff: (businessId: string, id: string) => api.get<BranchStaff[]>(`${base(businessId)}/${encodeURIComponent(id)}/staff`),
  assign: (businessId: string, id: string, identifier: string, role: BranchRole, permissions?: BranchPermission[]) =>
    api.post<BranchStaff>(`${base(businessId)}/${encodeURIComponent(id)}/staff`, { identifier, role, permissions }),
  setPermissions: (businessId: string, id: string, userId: string, permissions: BranchPermission[]) =>
    api.put<BranchStaff>(`${base(businessId)}/${encodeURIComponent(id)}/staff/${encodeURIComponent(userId)}/permissions`, { permissions }),
  removeStaff: (businessId: string, id: string, userId: string) =>
    api.del<void>(`${base(businessId)}/${encodeURIComponent(id)}/staff/${encodeURIComponent(userId)}`),
  myAssignments: () => api.get<MyBranchAssignment[]>("/me/branch-assignments"),
}

export const BRANCH_STATUS_LABELS: Record<BranchStatus, string> = {
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
  CLOSED: "Closed",
}

// ---------------------------------------------------------------- stock, orders, report, directory (non-food)

export interface BranchStockItem {
  productId: string
  productName: string
  currency: string
  catalogPrice: number
  available: boolean
  priceOverride: number | null
  effectivePrice: number
  quantity: number
  /** Unallocated business stock: only the owner receives it. */
  centralStock: number | null
}
export interface StockMovement {
  id: string
  type: "ALLOCATE" | "RETURN_TO_CENTRAL" | "TRANSFER" | "SALE_RESERVE" | "SALE_RELEASE"
  productId: string
  fromBranchId: string | null
  toBranchId: string | null
  quantity: number
  orderId: string | null
  actorUserId: string | null
  note: string | null
  createdAt: string
}
export interface BranchOrder {
  id: string
  status: string
  total: number
  currency: string
  itemCount: number
  buyerName: string | null
  items: string[]
  createdAt: string
}
export interface BranchReportLine { branchId: string | null; branchName: string; orders: number; units: number; gross: number; refunded: number; net: number }
export interface BranchReport {
  from: string
  to: string
  notice: string
  report: { branches: BranchReportLine[]; orders: number; units: number; gross: number; refunded: number; net: number }
}
export interface DirectoryBranch {
  id: string; name: string; branchCode: string | null; address: string | null; suburb: string | null; city: string | null
  province: string | null; postalCode: string | null; latitude: number | null; longitude: number | null; phone: string | null
  email: string | null; operatingHours: string[]; temporarilyClosed: boolean; brandName: string | null
  operatingModel: OperatingModel; operatorName: string | null
}
export interface BranchAvailability {
  branchId: string; branchName: string; city: string | null; suburb: string | null; canFulfil: boolean
  total: number | null; currency: string | null; problems: string[]
}

const stockBase = (businessId: string, id: string) => `${base(businessId)}/${encodeURIComponent(id)}`

export const branchOpsApi = {
  stock: (b: string, id: string) => api.get<BranchStockItem[]>(`${stockBase(b, id)}/stock`),
  setItem: (b: string, id: string, productId: string, body: { available?: boolean; priceOverride?: number; clearPriceOverride?: boolean }) =>
    api.patch<BranchStockItem>(`${stockBase(b, id)}/stock/${encodeURIComponent(productId)}`, { clearPriceOverride: false, ...body }),
  allocate: (b: string, id: string, productId: string, quantity: number, note?: string) =>
    api.post<BranchStockItem>(`${stockBase(b, id)}/stock/allocate`, { productId, quantity, note }),
  returnToCentral: (b: string, id: string, productId: string, quantity: number, note?: string) =>
    api.post<BranchStockItem>(`${stockBase(b, id)}/stock/return`, { productId, quantity, note }),
  transfer: (b: string, fromId: string, toBranchId: string, productId: string, quantity: number, note?: string) =>
    api.post<BranchStockItem>(`${stockBase(b, fromId)}/stock/transfer`, { toBranchId, productId, quantity, note }),
  movements: (b: string, id: string) => api.get<StockMovement[]>(`${stockBase(b, id)}/stock/movements`),
  orders: (b: string, id: string) => api.get<BranchOrder[]>(`${stockBase(b, id)}/orders`),
  fulfil: (b: string, id: string, orderId: string, status: "PROCESSING" | "READY" | "COMPLETED") =>
    api.post<BranchOrder>(`${stockBase(b, id)}/orders/${encodeURIComponent(orderId)}/status`, { status }),
  report: (b: string, from?: string, to?: string) => {
    const q = new URLSearchParams()
    if (from) q.set("from", from)
    if (to) q.set("to", to)
    const qs = q.toString()
    return api.get<BranchReport>(`/businesses/${encodeURIComponent(b)}/branch-report${qs ? `?${qs}` : ""}`)
  },
  directory: (b: string) => api.get<DirectoryBranch[]>(`/businesses/${encodeURIComponent(b)}/branch-directory`),
  check: (b: string, items: { productId: string; quantity: number }[]) =>
    api.post<BranchAvailability[]>(`/businesses/${encodeURIComponent(b)}/branch-directory/check`, { items }),
}
