import { api } from "./api"
import type { Expense, ExpenseEvent, ExpenseObligation, ExpensePreview, ExpenseSettlement, SplitType } from "./types"

export interface ExpenseBody {
  description: string
  note?: string
  /** Exact decimal string, e.g. "100.00". Sent as text so no floating point is ever involved. */
  amount: string
  splitType: SplitType
  paidByUserId?: string
  expenseDate?: string
  dueDate?: string
  participants: Array<{ userId: string; amount?: string; percentage?: string }>
  items?: Array<{ name: string; amount: string; assigneeUserIds: string[] }>
}

export interface ConfirmBody {
  confirm: true
  expectedAmount: string
  expectedCurrency: string
  note?: string
}

const base = (circleId: string) => `/circles/${circleId}/expenses`

/** Split bills and shared expenses. Every settlement here is SIMULATED demo-wallet value (see SIMULATED_NOTE). */
export const splitBillApi = {
  list: (circleId: string) => api.get<Expense[]>(base(circleId)),
  preview: (circleId: string, body: ExpenseBody) => api.post<ExpensePreview>(`${base(circleId)}/preview`, body),
  /** A retried create with the same key returns the first expense instead of making a second one. */
  create: (circleId: string, body: ExpenseBody, idempotencyKey: string) =>
    api.post<Expense>(base(circleId), body, { "Idempotency-Key": idempotencyKey }),
  update: (circleId: string, expenseId: string, expectedRevision: number, body: ExpenseBody) =>
    api.put<Expense>(`${base(circleId)}/${expenseId}`, { expectedRevision, ...body }),
  cancel: (circleId: string, expenseId: string, note?: string) => api.post<Expense>(`${base(circleId)}/${expenseId}/cancel`, { note }),

  get: (expenseId: string) => api.get<Expense>(`/split-bills/expenses/${expenseId}`),
  history: (expenseId: string) => api.get<ExpenseEvent[]>(`/split-bills/expenses/${expenseId}/history`),
  mine: (role: "OWE" | "OWED") => api.get<ExpenseObligation[]>(`/split-bills/mine?role=${role}`),

  accept: (shareId: string, note?: string) => api.post<Expense>(`/split-bills/shares/${shareId}/accept`, { note }),
  dispute: (shareId: string, note: string) => api.post<Expense>(`/split-bills/shares/${shareId}/dispute`, { note }),
  decline: (shareId: string, note?: string) => api.post<Expense>(`/split-bills/shares/${shareId}/decline`, { note }),
  reopen: (shareId: string, note?: string) => api.post<Expense>(`/split-bills/shares/${shareId}/reopen`, { note }),
  markReceived: (shareId: string) => api.post<Expense>(`/split-bills/shares/${shareId}/mark-received`),
  retractReceived: (shareId: string) => api.post<Expense>(`/split-bills/shares/${shareId}/retract-received`),
  /** The ONLY call that moves the participant's demo funds. Needs confirm=true and the amount they reviewed. */
  pay: (shareId: string, body: ConfirmBody) => api.post<ExpenseSettlement>(`/split-bills/shares/${shareId}/pay`, body),
  refund: (shareId: string, body: ConfirmBody) => api.post<ExpenseSettlement>(`/split-bills/shares/${shareId}/refund`, body),
}
