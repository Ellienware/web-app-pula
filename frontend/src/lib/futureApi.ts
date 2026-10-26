import { api } from "./api"
import type { IncomeSourceKey } from "./futureLogic"

// Mirrors com.momocircle.future.FutureDtos. Amounts are rand as JSON numbers; all figures are computed by the backend.
export interface Spring<T> { content: T[]; totalElements: number; number: number; size: number }

export interface FutureGoal {
  goalId: string; circleId: string; circleName: string; circleType: string; title: string
  category: string | null; priority: string | null; status: string; deadline: string | null
  targetAmount: number; recordedProgress: number; remaining: number; myRecordedContributions: number
  scope: "PERSONAL" | "GROUP_TRACKED"; saveToBuy: boolean; progressNote: string
}
export interface FuturePlanSummary { id: string; name: string; status: PlanStatus; businessId: string | null }
export interface FutureDashboard { goals: FutureGoal[]; plans: FuturePlanSummary[]; notice: string; disclosures: string[] }

export type PlanStatus = "DRAFT" | "ACTIVE" | "PAUSED" | "ARCHIVED"
export type LineType = "FIXED" | "PERCENT"
export interface PlanLineInput { goalId?: string; label: string; type: LineType; fixedAmount?: string; percent?: string }
export interface CreatePlanInput {
  name: string; businessId?: string; reserves?: string; commitments?: string; minOperatingBuffer?: string; lines: PlanLineInput[]
}
export interface PlanItem { id: string; kind: string; label: string; amount: number; treatedAs: "RESERVE" | "COMMITMENT" }
export interface PlanItemInput { kind: string; label: string; amount: string }
export interface BusinessSummary { id: string; name: string }
export interface IncomePreview {
  basis: number; gross: number; refunded: number; feesAndCommission: number; pendingExcluded: number
  paidCount: number; pendingCount: number; notes: string[]
}
export interface CreateGoalInput {
  title: string; description?: string; targetAmount: string; deadline?: string; category?: string; priority?: string; circleId?: string
}
export interface StokvelView {
  circleId: string; name: string; stokvelType: string; myRole: string
  permissions: { canCreateRequest: boolean; canVote: boolean; canRecordPayout: boolean; canUpdateSettings: boolean }
  contributionAmount: number | null; contributionFrequency: string | null; currentPeriodEnds: string | null
  goalId: string | null; myRecordedContributions: number | null; groupRecordedProgress: number | null; groupTarget: number | null
  notes: string[]
}
export interface ReminderSetting { kind: string; enabled: boolean; description: string }
export interface ReminderSettings { settings: ReminderSetting[]; notes: string[] }
export interface CoachAnswer { answer: string; source: "ai" | "fallback"; disclaimer: string }
export interface CalculateBody {
  idempotencyKey: string; incomeSource: IncomeSourceKey; incomeAmount?: string; from?: string; to?: string
}
export interface PlanLine { id: string; goalId: string | null; label: string; type: LineType; fixedAmount: number | null; percent: number | null }
export interface FuturePlan {
  id: string; name: string; businessId: string | null; status: PlanStatus; reserves: number; commitments: number
  minOperatingBuffer: number; lines: PlanLine[]; items: PlanItem[]; effectiveReserves: number; effectiveCommitments: number
  createdAt: string; notice: string
}
export interface CalcLine { goalId: string | null; label: string; amount: number }
export interface FutureCalculation {
  id: string | null; planId: string; valid: boolean; persisted: boolean; simulated: boolean
  incomeSource: IncomeSourceKey; incomeSourceNote: string; incomeNotes: string[]; incomeAsOf: string
  incomeBasis: number; reserves: number; commitments: number; allocatable: number
  totalAllocated: number; remaining: number; lines: CalcLine[]; warnings: string[]; createdAt: string | null; notice: string
}

export interface SimulateInput {
  startingAmount: string; monthlyContribution: string; years: number; annualReturnPct: string
  annualInflationPct?: string; annualFeePct?: string; contributionGrowthPct?: string
}
export interface ProjectionResult {
  name: string; annualReturnPct: number; netAnnualRatePct: number; totalContributions: number
  futureValue: number; growth: number; futureValueAfterInflation: number
}
export interface Simulation { scenarios: ProjectionResult[]; assumptions: string[]; notice: string }
export interface SavedScenario {
  id: string; label: string; inputs: Record<string, number>; totalContributions: number
  futureValue: number; futureValueAfterInflation: number; createdAt: string; notice: string
}

export interface Schedule {
  goalId: string; frequency: string; targetAmount: number; recordedProgress: number; remaining: number
  periods: number | null; suggestedPerPeriod: number | null; readyForExistingPurchaseWorkflow: boolean
  purchaseNote: string | null; warnings: string[]; notice: string
}
export interface LessonSummary { id: string; title: string; level: string; summary: string }
export interface Term { term: string; meaning: string }
export interface EducationIndex { lessons: LessonSummary[]; glossary: Term[]; disclaimer: string }
export interface LessonDetail { lesson: LessonSummary & { body: string[]; tryIt: string }; disclaimer: string }

export const futureApi = {
  dashboard: () => api.get<FutureDashboard>("/future/dashboard"),
  plans: () => api.get<Spring<FuturePlan>>("/future/plans?size=50"),
  createPlan: (p: CreatePlanInput) => api.post<FuturePlan>("/future/plans", p),
  setPlanStatus: (id: string, status: PlanStatus) => api.post<FuturePlan>(`/future/plans/${id}/status`, { status }),
  calculate: (id: string, body: CalculateBody) => api.post<FutureCalculation>(`/future/plans/${id}/calculations`, body),
  history: (id: string) => api.get<Spring<FutureCalculation>>(`/future/plans/${id}/calculations?size=10`),
  simulate: (s: SimulateInput) => api.post<Simulation>("/future/simulate", s),
  saveScenario: (label: string, inputs: SimulateInput) => api.post<SavedScenario>("/future/scenarios", { label, inputs }),
  scenarios: () => api.get<Spring<SavedScenario>>("/future/scenarios?size=20"),
  goalMeta: (goalId: string, body: { category?: string; priority?: string }) => api.patch<FutureGoal>(`/future/goals/${goalId}/meta`, body),
  schedule: (goalId: string, frequency: "WEEKLY" | "MONTHLY") => api.get<Schedule>(`/future/goals/${goalId}/schedule?frequency=${frequency}`),
  businesses: () => api.get<BusinessSummary[]>("/future/businesses"),
  settlementIncome: (businessId: string, from: string, to: string) =>
    api.get<IncomePreview>(`/future/businesses/${businessId}/settlement-income?from=${from}&to=${to}`),
  addItem: (planId: string, item: PlanItemInput) => api.post<FuturePlan>(`/future/plans/${planId}/items`, item),
  removeItem: (planId: string, itemId: string) => api.del<FuturePlan>(`/future/plans/${planId}/items/${itemId}`),
  createGoal: (g: CreateGoalInput) => api.post<FutureGoal>("/future/goals", g),
  pauseGoal: (goalId: string) => api.post<FutureGoal>(`/future/goals/${goalId}/pause`),
  resumeGoal: (goalId: string) => api.post<FutureGoal>(`/future/goals/${goalId}/resume`),
  stokvels: () => api.get<StokvelView[]>("/future/stokvels"),
  reminders: () => api.get<ReminderSettings>("/future/reminders"),
  setReminder: (kind: string, enabled: boolean) => api.put<ReminderSettings>("/future/reminders", { kind, enabled }),
  coach: (question: string, calculationId?: string) => api.post<CoachAnswer>("/future/coach", { question, calculationId }),
  education: () => api.get<EducationIndex>("/future/education"),
  lesson: (id: string) => api.get<LessonDetail>(`/future/education/${id}`),
}
