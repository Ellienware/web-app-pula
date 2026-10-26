import { api } from "./api"
import type {
  ContributionStatusView, CreateStokvelPayload, CreateStokvelRequestPayload, GoalProgress, RotationView,
  StokvelInsights, StokvelOverview, StokvelRequest, StokvelSummary,
} from "./stokvelTypes"

const base = (id: string) => `/stokvels/${id}`

export const stokvelApi = {
  list: () => api.get<StokvelSummary[]>("/stokvels"),
  create: (p: CreateStokvelPayload) => api.post<StokvelOverview>("/stokvels", p),
  overview: (id: string) => api.get<StokvelOverview>(base(id)),
  progress: (id: string) => api.get<GoalProgress>(`${base(id)}/progress`),
  contributionStatus: (id: string) => api.get<ContributionStatusView>(`${base(id)}/contributions/status`),
  /** Amount is the only money value the client sends; the server picks the period and checks everything else. */
  contribute: (id: string, amount: number, idempotencyKey: string) =>
    api.post(`${base(id)}/contributions`, { amount, idempotencyKey }),
  rotation: (id: string) => api.get<RotationView>(`${base(id)}/rotation`),
  setRotationOrder: (id: string, userIds: string[]) => api.put<RotationView>(`${base(id)}/rotation/order`, { userIds }),
  recordPayout: (id: string, expectedCycleNumber: number, reference?: string) =>
    api.post<RotationView>(`${base(id)}/rotation/payouts`, { expectedCycleNumber, reference }),
  requests: (id: string) => api.get<StokvelRequest[]>(`${base(id)}/requests`),
  createRequest: (id: string, p: CreateStokvelRequestPayload) => api.post<StokvelRequest>(`${base(id)}/requests`, p),
  approve: (id: string, rid: string, comment?: string) => api.post<StokvelRequest>(`${base(id)}/requests/${rid}/approve`, { comment }),
  reject: (id: string, rid: string, comment?: string) => api.post<StokvelRequest>(`${base(id)}/requests/${rid}/reject`, { comment }),
  cancel: (id: string, rid: string) => api.post<StokvelRequest>(`${base(id)}/requests/${rid}/cancel`),
  linkOrder: (id: string, rid: string, kind: "ORDER" | "STOCK_ORDER", orderId: string) =>
    api.post<StokvelRequest>(`${base(id)}/requests/${rid}/link-order`, { kind, orderId }),
  recordExecution: (id: string, rid: string, reference: string) =>
    api.post<StokvelRequest>(`${base(id)}/requests/${rid}/record-execution`, { reference }),
  insights: (id: string, scenarioAmount?: number) =>
    api.get<StokvelInsights>(`${base(id)}/insights${scenarioAmount ? `?scenarioAmount=${scenarioAmount}` : ""}`),
}
