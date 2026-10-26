// Mirrors StokvelDtos on the backend. The backend is authoritative for every permission and every amount;
// nothing here is trusted to decide who may do what.
import type { CircleRole } from "./types"

export type StokvelType =
  | "ROTATIONAL" | "GOAL_BASED" | "GROCERY" | "STOCK"
  | "BUSINESS_EQUIPMENT" | "EMERGENCY" | "COMMUNITY_PROJECT" | "GENERAL"

export const STOKVEL_TYPE_LABELS: Record<StokvelType, string> = {
  ROTATIONAL: "Rotational",
  GOAL_BASED: "Goal-based",
  GROCERY: "Grocery",
  STOCK: "Stock",
  BUSINESS_EQUIPMENT: "Business equipment",
  EMERGENCY: "Emergency",
  COMMUNITY_PROJECT: "Community project",
  GENERAL: "General",
}

export type Frequency = "WEEKLY" | "BIWEEKLY" | "MONTHLY" | "QUARTERLY" | "YEARLY"
export type ApprovalRuleType = "SINGLE" | "THRESHOLD" | "MAJORITY"
export type StokvelRequestType =
  | "WITHDRAWAL" | "MARKETPLACE_PURCHASE" | "STOCK_PURCHASE" | "EMERGENCY_EXPENSE" | "COMMUNITY_EXPENSE"
export type StokvelRequestStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED" | "EXPIRED" | "EXECUTED"
export type ContributionStatus = "DUE" | "PARTIAL" | "PAID" | "MISSED"

export interface StokvelSummary {
  circleId: string; name: string; stokvelType: StokvelType; currency: string
  contributionAmount: number | null; frequency: Frequency; viewerRole: CircleRole; memberCount: number
}

export interface StokvelSettings {
  stokvelType: StokvelType; contributionAmount: number | null; frequency: Frequency; startDate: string
  targetDate: string | null; goalId: string | null; approvalRule: ApprovalRuleType; approvalThreshold: number | null
  membersCanVote: boolean; requestValidityDays: number; rotationLocked: boolean; rotationLockedAt: string | null
}

export interface StokvelOverview {
  circleId: string; name: string; description: string | null; currency: string; viewerRole: CircleRole
  settings: StokvelSettings; memberCount: number; currentPeriod: number
  currentPeriodStart: string; currentPeriodEnd: string
  trackedContributions: number; trackedCommitted: number; trackedAvailable: number
  /** Always: PULA does not hold or pool this money. Show it. */
  fundsNotice: string
}

export interface MemberPeriodStatus {
  userId: string; fullName: string; periodIndex: number; expected: number; paid: number; status: ContributionStatus
}
export interface ContributionStatusView {
  currentPeriod: number; periodStart: string; periodEnd: string; expectedPerMember: number | null
  currentPeriodMembers: MemberPeriodStatus[]; missed: MemberPeriodStatus[]
}
export interface MemberProgress { userId: string; fullName: string; contributed: number }
export interface GoalProgress {
  goalId: string; target: number; current: number; remaining: number; percent: number
  targetDate: string | null; members: MemberProgress[]
}
export interface RotationSlot { position: number; userId: string; fullName: string; paidOut: boolean; paidInCycle: number | null }
export interface RotationCycle {
  cycleNumber: number; payoutPosition: number; beneficiaryId: string; beneficiaryName: string
  expectedAmount: number; collectedAmount: number; recordedByName: string; reference: string | null; paidAt: string
}
export interface RotationView {
  locked: boolean; lockedAt: string | null; completedCycles: number; currentCycleNumber: number
  currentBeneficiaryId: string | null; currentBeneficiaryName: string | null
  upcomingBeneficiaryId: string | null; upcomingBeneficiaryName: string | null
  currentCycleExpected: number; currentCycleCollected: number
  order: RotationSlot[]; history: RotationCycle[]; currentCycleContributions: MemberPeriodStatus[]
}
export interface StokvelVote { voterId: string; voterName: string; decision: "APPROVE" | "REJECT"; comment: string | null; at: string }
export interface StokvelRequest {
  id: string; circleId: string; type: StokvelRequestType; status: StokvelRequestStatus
  requesterId: string; requesterName: string; buyerId: string | null; buyerName: string | null
  amount: number; currency: string; purpose: string; goalId: string | null
  approvalRule: ApprovalRuleType; requiredApprovals: number; eligibleVoters: number
  approvals: number; rejections: number; votes: StokvelVote[]
  expiresAt: string; decidedAt: string | null; executedAt: string | null
  relatedOrderKind: "ORDER" | "STOCK_ORDER" | "EXTERNAL" | null; relatedOrderId: string | null
  externalReference: string | null; createdAt: string
}
export interface StokvelInsights {
  summary: string; rotationExplanation: string | null; goalForecast: string | null; scenarios: string[]; notice: string
}

export interface CreateStokvelPayload {
  name: string; description?: string; stokvelType: StokvelType; currency: string
  contributionAmount?: number; frequency: Frequency; startDate?: string
  targetAmount?: number; targetDate?: string
  approvalRule?: ApprovalRuleType; approvalThreshold?: number; membersCanVote?: boolean
}
export interface CreateStokvelRequestPayload {
  type: StokvelRequestType; amount: number; purpose: string; buyerUserId?: string; goalId?: string; idempotencyKey?: string
}

/** UI hints only. The server re-checks every one of these. */
export const canCreateRequest = (r: CircleRole) => ["OWNER", "ADMIN", "TREASURER", "CHAIRPERSON"].includes(r)
export const canVote = (r: CircleRole, membersCanVote: boolean) => membersCanVote || canCreateRequest(r)
export const canRecordPayout = (r: CircleRole) => ["OWNER", "ADMIN", "TREASURER"].includes(r)
export const canOverrideRotation = (r: CircleRole) => r === "OWNER"
