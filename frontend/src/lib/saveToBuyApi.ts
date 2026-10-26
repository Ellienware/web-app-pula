import { api } from "./api"
import type {
  CreateSaveToBuyPayload, EligibleGoal, SaveToBuyInsights, SaveToBuyVertical, SaveToBuyView,
} from "./saveToBuyTypes"

export const saveToBuyApi = {
  goals: (all = false) => api.get<EligibleGoal[]>(`/save-to-buy/goals${all ? "?all=true" : ""}`),
  designate: (goalId: string, vertical: SaveToBuyVertical, purpose?: string) =>
    api.put<EligibleGoal>(`/save-to-buy/goals/${goalId}/intent`, { vertical, purpose }),
  create: (p: CreateSaveToBuyPayload) => api.post<SaveToBuyView>("/save-to-buy", p),
  mine: () => api.get<SaveToBuyView[]>("/save-to-buy"),
  forCircle: (circleId: string) => api.get<SaveToBuyView[]>(`/save-to-buy/circle/${circleId}`),
  get: (id: string) => api.get<SaveToBuyView>(`/save-to-buy/${id}`),
  confirm: (id: string) => api.post<SaveToBuyView>(`/save-to-buy/${id}/confirm`),
  submit: (id: string) => api.post<SaveToBuyView>(`/save-to-buy/${id}/submit`),
  cancel: (id: string) => api.post<SaveToBuyView>(`/save-to-buy/${id}/cancel`),
  complete: (id: string, reference: string) => api.post<SaveToBuyView>(`/save-to-buy/${id}/complete`, { reference }),
  insights: (id: string) => api.get<SaveToBuyInsights>(`/save-to-buy/${id}/insights`),
}
