import { api } from "./api"
import type {
  BusinessAccess, BusinessMember, BusinessOverview, BusinessPermission, BusinessRole,
  PaymentRequest, PaymentResult, Recipient, TransactionFilter, WalletOverview, WalletPage, WalletReconciliation, WalletTransaction,
} from "./walletTypes"

const W = "/money/wallet"
const BW = "/money/business-wallets"

/**
 * The operations the wallet screens need, implemented for a PERSONAL wallet (`walletApi`) and for ONE BUSINESS wallet
 * (`businessScope`). The screens take a scope, so the same reviewed money flows serve both; the server is what decides access.
 */
export interface WalletScope {
  readonly kind: "personal" | "business"
  /** Permissions the server granted for this scope. A personal wallet has all of them. */
  readonly permissions: readonly BusinessPermission[]
  overview(): Promise<WalletOverview>
  transactions(page?: number, filter?: TransactionFilter, size?: number): Promise<WalletPage<WalletTransaction>>
  demoFunding(amount: string, key: string): Promise<WalletTransaction>
  lookupRecipient(identifier: string): Promise<Recipient>
  transfer(recipientId: string, amount: string, key: string): Promise<WalletTransaction>
  requestWithdrawal(amount: string, key: string): Promise<WalletTransaction>
  cancelWithdrawal(reference: string): Promise<WalletTransaction>
  createRequest(amount: string, purpose: string, payerRecipientId?: string, expiresInMinutes?: number): Promise<PaymentRequest>
  requests(box: "outgoing" | "incoming", page?: number): Promise<WalletPage<PaymentRequest>>
  cancelRequest(requestId: string): Promise<PaymentRequest>
}

const ALL_PERMISSIONS: BusinessPermission[] = ["VIEW", "REQUEST", "SEND", "WITHDRAW", "FUND", "MANAGE_MEMBERS"]
const query = (page: number, filter: TransactionFilter, size: number) => {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (filter.type) q.set("type", filter.type)
  if (filter.status) q.set("status", filter.status)
  if (filter.direction) q.set("direction", filter.direction)
  return q
}
const idem = (key: string) => ({ "Idempotency-Key": key })

/**
 * Client for the PULA Money DEMO wallet. It sends what the user typed and shows what the server returns; it never
 * computes a balance and never sends an owner/user id (the server derives the user from the session token).
 */
export const walletApi = {
  kind: "personal" as const,
  permissions: ALL_PERMISSIONS as readonly BusinessPermission[],
  overview: () => api.get<WalletOverview>(W),

  transactions: (page = 0, filter: TransactionFilter = {}, size = 20) =>
    api.get<WalletPage<WalletTransaction>>(`${W}/transactions?${query(page, filter, size)}`),
  transaction: (reference: string) => api.get<WalletTransaction>(`${W}/transactions/${encodeURIComponent(reference)}`),

  demoFunding: (amount: string, key: string) => api.post<WalletTransaction>(`${W}/demo-funding`, { amount }, idem(key)),
  lookupRecipient: (identifier: string) => api.post<Recipient>(`${W}/recipients/lookup`, { identifier }),
  transfer: (recipientId: string, amount: string, key: string) =>
    api.post<WalletTransaction>(`${W}/transfers`, { recipientId, amount }, idem(key)),
  requestWithdrawal: (amount: string, key: string) => api.post<WalletTransaction>(`${W}/withdrawals`, { amount }, idem(key)),
  cancelWithdrawal: (reference: string) => api.post<WalletTransaction>(`${W}/withdrawals/${encodeURIComponent(reference)}/cancel`),

  createRequest: (amount: string, purpose: string, payerRecipientId?: string, expiresInMinutes?: number) =>
    api.post<PaymentRequest>(`${W}/payment-requests`, { amount, purpose, payerRecipientId: payerRecipientId || undefined, expiresInMinutes }),
  requests: (box: "outgoing" | "incoming", page = 0) =>
    api.get<WalletPage<PaymentRequest>>(`${W}/payment-requests?box=${box}&page=${page}&size=20`),
  request: (requestId: string) => api.get<PaymentRequest>(`${W}/payment-requests/${encodeURIComponent(requestId)}`),
  /** Review a scanned/pasted code. Read-only for funds. */
  resolveCode: (code: string) => api.post<PaymentRequest>(`${W}/payment-requests/resolve`, { code }),
  /** The explicit confirmation. amount/currency are what the user reviewed; the server re-checks them. */
  payRequest: (code: string, expectedAmount: string, expectedCurrency: string) =>
    api.post<PaymentResult>(`${W}/payment-requests/pay`, { code, expectedAmount, expectedCurrency }),
  rejectRequest: (code: string) => api.post<PaymentRequest>(`${W}/payment-requests/reject`, { code }),
  cancelRequest: (requestId: string) => api.post<PaymentRequest>(`${W}/payment-requests/${encodeURIComponent(requestId)}/cancel`),

  // ---- operator (ADMIN role enforced by the server) ----
  adminPendingWithdrawals: (page = 0) => api.get<WalletPage<WalletTransaction>>(`${W}/admin/withdrawals?page=${page}&size=20`),
  adminCompleteWithdrawal: (reference: string) =>
    api.post<WalletTransaction>(`${W}/admin/withdrawals/${encodeURIComponent(reference)}/complete`),
  adminFailWithdrawal: (reference: string, reason: string) =>
    api.post<WalletTransaction>(`${W}/admin/withdrawals/${encodeURIComponent(reference)}/fail`, { reason }),
  adminReconcile: (walletId: string) => api.get<WalletReconciliation>(`${W}/admin/reconcile/${encodeURIComponent(walletId)}`),
}

const idemH = (key: string) => ({ "Idempotency-Key": key })

/** Business wallets the signed-in user can act on (as owner or delegated member). */
export const businessApi = {
  list: () => api.get<BusinessAccess[]>(BW),
  overview: (walletId: string) => api.get<BusinessOverview>(`${BW}/${encodeURIComponent(walletId)}`),
  members: (walletId: string) => api.get<BusinessMember[]>(`${BW}/${encodeURIComponent(walletId)}/members`),
  addMember: (walletId: string, identifier: string, role: Exclude<BusinessRole, "OWNER">) =>
    api.post<BusinessMember>(`${BW}/${encodeURIComponent(walletId)}/members`, { identifier, role }),
  changeRole: (walletId: string, memberId: string, role: Exclude<BusinessRole, "OWNER">) =>
    api.put<BusinessMember>(`${BW}/${encodeURIComponent(walletId)}/members/${encodeURIComponent(memberId)}`, { role }),
  removeMember: (walletId: string, memberId: string) =>
    api.del<void>(`${BW}/${encodeURIComponent(walletId)}/members/${encodeURIComponent(memberId)}`),
}

/** A scope bound to one business wallet. `permissions` come from the server (businessApi.overview) and only shape the UI. */
export function businessScope(walletId: string, permissions: readonly BusinessPermission[]): WalletScope {
  const base = `${BW}/${encodeURIComponent(walletId)}`
  return {
    kind: "business",
    permissions,
    overview: () => api.get<BusinessOverview>(base).then((o) => o.wallet),
    transactions: (page = 0, filter: TransactionFilter = {}, size = 20) =>
      api.get<WalletPage<WalletTransaction>>(`${base}/transactions?${query(page, filter, size)}`),
    demoFunding: (amount, key) => api.post<WalletTransaction>(`${base}/demo-funding`, { amount }, idemH(key)),
    lookupRecipient: (identifier) => api.post<Recipient>(`${base}/recipients/lookup`, { identifier }),
    transfer: (recipientId, amount, key) => api.post<WalletTransaction>(`${base}/transfers`, { recipientId, amount }, idemH(key)),
    requestWithdrawal: (amount, key) => api.post<WalletTransaction>(`${base}/withdrawals`, { amount }, idemH(key)),
    cancelWithdrawal: (reference) => api.post<WalletTransaction>(`${base}/withdrawals/${encodeURIComponent(reference)}/cancel`),
    createRequest: (amount, purpose, payerRecipientId, expiresInMinutes) =>
      api.post<PaymentRequest>(`${base}/payment-requests`, { amount, purpose, payerRecipientId: payerRecipientId || undefined, expiresInMinutes }),
    // A business has no "requests for me" inbox: customers pay it through the normal pay-a-code flow.
    requests: (box, page = 0) => box === "incoming"
      ? Promise.resolve({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
      : api.get<WalletPage<PaymentRequest>>(`${base}/payment-requests?page=${page}&size=20`),
    cancelRequest: (requestId) => api.post<PaymentRequest>(`${base}/payment-requests/${encodeURIComponent(requestId)}/cancel`),
  }
}
