/** Contracts of /api/money/wallet/*. Every value-bearing response is SIMULATED demo money; amounts are exact decimal strings. */

export interface WalletLimits {
  transferMax: string
  demoFundingPerRequestMax: string
  demoFundingPerPeriodMax: string
  demoFundingPeriodDays: number
  withdrawalMax: string
}

export interface WalletOverview {
  simulated: true
  notice: string
  walletId: string
  currency: string
  balance: string
  balanceMinor: number
  limits: WalletLimits
}

export type TxType = "DEMO_ISSUANCE" | "INTERNAL_TRANSFER" | "SIMULATED_WITHDRAWAL" | "REVERSAL"
export type TxStatus = "PENDING" | "POSTED" | "FAILED" | "REVERSED" | "CANCELLED"

export interface WalletTransaction {
  reference: string
  type: TxType
  status: TxStatus
  direction: "IN" | "OUT"
  amount: string
  currency: string
  counterparty: string
  reversesReference: string | null
  failureReason: string | null
  createdAt: string
  simulated: true
  notice: string
}

export interface WalletPage<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface Recipient {
  recipientId: string
  displayName: string
  simulated: true
}

export type PaymentRequestStatus = "OPEN" | "PROCESSING" | "PAID" | "REJECTED" | "CANCELLED" | "EXPIRED"

export interface PaymentRequest {
  requestId: string
  /** Share/QR code: only present for the requester and for the payer a targeted request is addressed to. It contains no amount or identity. */
  code: string | null
  direction: "OUTGOING" | "INCOMING" | "OPEN_LINK"
  status: PaymentRequestStatus
  amount: string
  currency: string
  purpose: string
  requesterName: string
  targetPayerName: string | null
  expiresAt: string
  createdAt: string
  resolvedAt: string | null
  transactionReference: string | null
  canPay: boolean
  canReject: boolean
  canCancel: boolean
  simulated: true
  notice: string
}

export interface PaymentResult {
  request: PaymentRequest
  transaction: WalletTransaction | null
  replayed: boolean
  simulated: true
  notice: string
}

export interface TransactionFilter {
  type?: TxType
  status?: TxStatus
  direction?: "IN" | "OUT"
}

export interface WalletReconciliation {
  consistent: boolean
  balanceMinor: number
  postingsMinor: number
}

// ---- business wallets ----

export type BusinessRole = "OWNER" | "FINANCE" | "STAFF"
export type BusinessPermission = "VIEW" | "REQUEST" | "SEND" | "WITHDRAW" | "FUND" | "MANAGE_MEMBERS"

/** A business wallet the user can act on. `walletId` (BW-...) is the only handle; no internal ids are exposed. */
export interface BusinessAccess {
  walletId: string
  businessName: string
  role: BusinessRole
  permissions: BusinessPermission[]
}

export interface BusinessOverview {
  wallet: WalletOverview
  businessName: string
  role: BusinessRole
  permissions: BusinessPermission[]
}

export interface BusinessMember {
  memberId: string
  displayName: string
  role: BusinessRole
  owner: boolean
}
