import { useState } from "react"
import { Link } from "react-router-dom"
import type { Expense } from "../../lib/types"
import { formatDate, formatMoney } from "../../lib/format"
import { stateLabel, stateTone } from "../../lib/splitBillLogic"
import { Badge } from "../ui/Badge"
import { ExpenseDetailModal } from "../splitbill/ExpenseDetailModal"

/**
 * Shared expenses of one circle. Opening an expense shows who owes what and lets each person respond and (optionally) settle
 * their own share with SIMULATED demo-wallet funds. Nothing on this tab takes money by itself.
 */
export function ExpensesTab({
  currency,
  expenses,
  currentUserId,
  onNewExpense,
  onEdit,
  reload,
}: {
  circleId: string
  currency: string
  expenses: Expense[]
  currentUserId: string
  onNewExpense: () => void
  onEdit: (e: Expense) => void
  reload: () => void
}) {
  const [openId, setOpenId] = useState<string | null>(null)

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Shared expenses</h2>
        <div className="split-actions">
          <Link className="btn btn-ghost btn-sm" to="/split-bills">My split bills</Link>
          <button className="btn btn-ghost btn-sm" onClick={onNewExpense}>+ Split a bill</button>
        </div>
      </div>
      <p className="muted small" role="note">
        Simulated demo funds only: settling a share moves demo-wallet value and does not pay the real bill.
      </p>

      {expenses.length === 0 ? (
        <p className="muted">No expenses split yet. Split a bill to track who owes what.</p>
      ) : (
        <ul className="expense-list">
          {expenses.map((e) => {
            const mine = e.shares.find((s) => s.debtorId === currentUserId)
            return (
              <li key={e.id} className="expense-card">
                <button type="button" className="expense-open" onClick={() => setOpenId(e.id)} aria-label={`Open ${e.description}`}>
                  <div className="expense-head">
                    <div>
                      <h3>{e.description}</h3>
                      <span className="muted">
                        {`Paid by ${e.paidByName} · ${formatDate(e.expenseDate ?? e.createdAt)}`}
                        {e.dueDate ? ` · due ${formatDate(e.dueDate)}` : ""}
                      </span>
                    </div>
                    <div className="expense-amounts">
                      <span className="money">{formatMoney(e.amount, currency)}</span>
                      <Badge tone={stateTone(e.settlementState)}>
                        {e.settlementState === "SETTLED" || e.settlementState === "CANCELLED"
                          ? stateLabel(e.settlementState)
                          : `${stateLabel(e.settlementState)} · ${formatMoney(e.outstandingAmount, currency)} owed`}
                      </Badge>
                    </div>
                  </div>
                  {mine && mine.status !== "PAID" && mine.status !== "CANCELLED" && e.paidById !== currentUserId && (
                    <div className="muted small">{`Your share ${formatMoney(mine.amount, currency)}: ${mine.canAccept || mine.canPay ? "needs your response" : "see details"}`}</div>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {openId && (
        <ExpenseDetailModal
          expenseId={openId}
          onClose={() => setOpenId(null)}
          onChanged={reload}
          onEdit={(e) => { setOpenId(null); onEdit(e) }}
        />
      )}
    </section>
  )
}
