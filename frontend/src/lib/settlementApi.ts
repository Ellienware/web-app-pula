import { api } from "./api"
import type {
  MarketplaceSummary, ReconciliationReport, SellerSummary, Settlement, SettlementInsights, SettlementStatus, SourceType,
} from "./settlementTypes"

const qs = (o: Record<string, unknown>) => {
  const p = new URLSearchParams()
  Object.entries(o).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)) })
  const s = p.toString()
  return s ? `?${s}` : ""
}

export const settlementApi = {
  // seller (own business only)
  mine: () => api.get<Settlement[]>("/marketplace/settlements/mine"),
  mineSummary: () => api.get<SellerSummary>("/marketplace/settlements/mine/summary"),
  explainMine: (id: string) => api.get<SettlementInsights>(`/marketplace/settlements/mine/${id}/explain`),

  // admin (the server requires ADMIN on every call). No call here can mark anything paid, refund, or override reconciliation.
  summary: () => api.get<MarketplaceSummary>("/admin/marketplace/summary"),
  list: (f: { status?: SettlementStatus; source?: SourceType } = {}) => api.get<Settlement[]>(`/admin/marketplace/settlements${qs(f)}`),
  failed: () => api.get<Settlement[]>("/admin/marketplace/settlements/failed"),
  explain: (id: string) => api.get<SettlementInsights>(`/admin/marketplace/settlements/${id}/explain`),
  hold: (id: string, reason?: string) => api.post<Settlement>(`/admin/marketplace/settlements/${id}/hold`, { reason }),
  release: (id: string) => api.post<Settlement>(`/admin/marketplace/settlements/${id}/release`),
  adjust: (id: string, kind: "CREDIT" | "DEBIT", amount: number, reason: string, idempotencyKey: string) =>
    api.post<Settlement>(`/admin/marketplace/settlements/${id}/adjustments`, { kind, amount, reason, idempotencyKey }),
  reconciliation: () => api.get<ReconciliationReport>("/admin/marketplace/reconciliation"),
  explainReconciliation: (source: SourceType, id: string) =>
    api.get<SettlementInsights>(`/admin/marketplace/reconciliation/${source}/${id}/explain`),
}
