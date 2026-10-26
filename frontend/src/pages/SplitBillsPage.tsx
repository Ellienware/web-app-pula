import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { splitBillApi } from "../lib/splitBillApi"
import type { ExpenseObligation } from "../lib/types"
import { formatDate, formatMoney } from "../lib/format"
import { SIMULATED_NOTE, describeSplitError, minorToDecimal, shareStatusLabel, shareStatusTone, summariseObligations } from "../lib/splitBillLogic"
import { Badge } from "../components/ui/Badge"
import { ExpenseDetailModal } from "../components/splitbill/ExpenseDetailModal"

/** Everything I owe, and everything I am owed, across all my circles. Needs no circle membership to answer or pay. */
export function SplitBillsPage() {
  const [role, setRole] = useState<"OWE" | "OWED">("OWE")
  const [rows, setRows] = useState<ExpenseObligation[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openId, setOpenId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setRows(null); setError(null)
    try { setRows(await splitBillApi.mine(role)) } catch (e) { setError(describeSplitError(e)) }
  }, [role])
  useEffect(() => { void load() }, [load])

  const totals = rows ? summariseObligations(rows) : null
  const cur = rows?.[0]?.currency ?? "ZAR"

  return (
    <div className="page">
      <div className="page-head">
        <h1>Split bills</h1>
        <Link className="btn btn-ghost btn-sm" to="/money/wallet">Demo wallet</Link>
      </div>
      <p className="split-notice" role="note">{SIMULATED_NOTE}</p>

      <div className="segmented" role="tablist" aria-label="Direction">
        <button role="tab" aria-selected={role === "OWE"} className={role === "OWE" ? "active" : ""} onClick={() => setRole("OWE")}>I owe</button>
        <button role="tab" aria-selected={role === "OWED"} className={role === "OWED" ? "active" : ""} onClick={() => setRole("OWED")}>Owed to me</button>
      </div>

      {totals && totals.count > 0 && (
        <p className="split-total ok" role="status">
          {`${totals.count} open · ${formatMoney(Number(minorToDecimal(totals.outstandingMinor)), cur)}`}
          {totals.overdueMinor > 0 ? ` · ${formatMoney(Number(minorToDecimal(totals.overdueMinor)), cur)} overdue` : ""}
        </p>
      )}

      {error && <p className="form-error" role="alert">{error} <button className="btn btn-ghost btn-xs" onClick={load}>Retry</button></p>}
      {!rows && !error && <p className="muted" role="status">Loading…</p>}
      {rows && rows.length === 0 && (
        <p className="muted">{role === "OWE" ? "You do not owe anything. Nothing to pay." : "Nobody owes you anything right now."}</p>
      )}
      {rows && rows.length > 0 && (
        <ul className="expense-list">
          {rows.map((r) => (
            <li key={r.share.id} className="expense-card">
              <button type="button" className="expense-open" onClick={() => setOpenId(r.expenseId)} aria-label={`Open ${r.title}`}>
                <div className="expense-head">
                  <div>
                    <h3>{r.title}</h3>
                    <span className="muted">
                      {`${r.circleName} · ${role === "OWE" ? `pay ${r.counterpartyName}` : `${r.counterpartyName} owes you`}`}
                      {r.dueDate ? ` · due ${formatDate(r.dueDate)}` : ""}
                    </span>
                  </div>
                  <div className="expense-amounts">
                    <span className="money">{formatMoney(r.share.amount, r.currency)}</span>
                    <Badge tone={shareStatusTone(r.share.status, r.share.overdue)}>
                      {r.share.overdue ? "Overdue" : shareStatusLabel(r.share.status, r.share.settlementMethod)}
                    </Badge>
                  </div>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}

      {openId && <ExpenseDetailModal expenseId={openId} onClose={() => setOpenId(null)} onChanged={load} />}
    </div>
  )
}
