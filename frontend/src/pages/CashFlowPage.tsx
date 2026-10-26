import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { TrendPanel } from "../components/market/TrendPanel"
import { TrendingDown, TrendingUp } from "lucide-react"
import { api } from "../lib/api"
import type {
  BusinessCashFlowSummary,
  BusinessExpenseEntry,
  BusinessIncomeEntry,
  ExpenseCategory,
} from "../lib/types"
import { EXPENSE_CATEGORIES } from "../lib/types"
import { formatMoney } from "../lib/format"
import { useCurrencyConfig } from "../lib/currencyConfig"
import { PageHeader } from "../components/ui/PageHeader"
import { Button } from "../components/ui/Button"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"

const MONTH_OPTIONS = Array.from({ length: 6 }, (_, i) => {
  const d = new Date()
  d.setMonth(d.getMonth() - i)
  return { label: d.toLocaleString("default", { month: "long", year: "numeric" }),
           from: new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10),
           to:   new Date(d.getFullYear(), d.getMonth() + 1, 0).toISOString().slice(0, 10) }
})

/**
 * PULA Cash Flow (brief §16-17): lightweight cash-flow awareness for small
 * businesses. NOT accounting software — shows real PULA transaction data plus
 * manually entered records in a simple income/expense view.
 */
export default function CashFlowPage() {
  const { defaultCurrency: currency } = useCurrencyConfig()
  const [monthIdx, setMonthIdx] = useState(0)
  const [summary, setSummary] = useState<BusinessCashFlowSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Add income form
  const [showAddIncome, setShowAddIncome] = useState(false)
  const [incomeAmount, setIncomeAmount] = useState("")
  const [incomeDate, setIncomeDate] = useState(new Date().toISOString().slice(0, 10))
  const [incomeDesc, setIncomeDesc] = useState("")

  // Add expense form
  const [showAddExpense, setShowAddExpense] = useState(false)
  const [expenseAmount, setExpenseAmount] = useState("")
  const [expenseDate, setExpenseDate] = useState(new Date().toISOString().slice(0, 10))
  const [expenseDesc, setExpenseDesc] = useState("")
  const [expenseCat, setExpenseCat] = useState<ExpenseCategory>("OPERATING")
  const [formBusy, setFormBusy] = useState(false)

  const { from, to } = MONTH_OPTIONS[monthIdx]

  const load = useCallback(() => {
    setLoading(true)
    api
      .get<BusinessCashFlowSummary>(`/cashflow/summary?from=${from}&to=${to}`)
      .then((res) => { setSummary(res); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }, [from, to])

  useEffect(load, [load])

  async function addIncome() {
    if (!incomeAmount) return
    setFormBusy(true)
    try {
      await api.post<BusinessIncomeEntry>("/cashflow/income", {
        amount: Number(incomeAmount), currency: "ZAR",
        receivedDate: incomeDate, description: incomeDesc || null, category: null,
      })
      setIncomeAmount(""); setIncomeDesc(""); setShowAddIncome(false)
      load()
    } catch (e) { setError(e instanceof Error ? e.message : "Could not save") }
    finally { setFormBusy(false) }
  }

  async function addExpense() {
    if (!expenseAmount) return
    setFormBusy(true)
    try {
      await api.post<BusinessExpenseEntry>("/cashflow/expenses", {
        amount: Number(expenseAmount), currency: "ZAR",
        incurredDate: expenseDate, description: expenseDesc || null, category: expenseCat,
      })
      setExpenseAmount(""); setExpenseDesc(""); setShowAddExpense(false)
      load()
    } catch (e) { setError(e instanceof Error ? e.message : "Could not save") }
    finally { setFormBusy(false) }
  }

  async function deleteIncome(id: string) {
    try { await api.del(`/cashflow/income/${id}`); load() }
    catch (e) { setError(e instanceof Error ? e.message : "Could not delete") }
  }

  async function deleteExpense(id: string) {
    try { await api.del(`/cashflow/expenses/${id}`); load() }
    catch (e) { setError(e instanceof Error ? e.message : "Could not delete") }
  }

  const net = summary?.netCashMovement ?? 0
  const netPositive = net >= 0

  return (
    <div className="page stack">
      <PageHeader
        title="PULA Cash Flow"
        subtitle="A simple view of money coming in and going out of your business — not accounting software."
        actions={<Link to="/funding" className="btn btn-ghost">Funding readiness</Link>}
      />
      <TrendPanel />

      {/* Period selector */}
      <div className="row wrap" style={{ gap: 8 }}>
        {MONTH_OPTIONS.map((m, i) => (
          <button
            key={m.from}
            className={`category-pill ${monthIdx === i ? "active" : ""}`}
            onClick={() => setMonthIdx(i)}
          >
            {m.label}
          </button>
        ))}
      </div>

      {error && <ErrorState description={error} onRetry={load} />}
      {loading && <p className="muted">Loading cash-flow data…</p>}

      {summary && !loading && (
        <>
          {/* Summary table — mirrors brief §16 example exactly */}
          <div className="card stack" style={{ maxWidth: 520 }}>
            <h2>
              {MONTH_OPTIONS[monthIdx].label} summary
              {!summary.profileComplete && (
                <span className="badge badge-muted" style={{ marginLeft: 8, fontSize: "0.75rem" }}>
                  Profile incomplete
                </span>
              )}
            </h2>

            <table className="cashflow-table">
              <tbody>
                <tr className="cashflow-income">
                  <td><TrendingUp size={14} aria-hidden="true" /> Sales ({summary.saleCount} order{summary.saleCount !== 1 ? "s" : ""})</td>
                  <td className="amount">{formatMoney(summary.salesIncome, currency)}</td>
                </tr>
                {(summary.foodSales ?? 0) > 0 && (
                  <tr className="cashflow-income">
                    <td style={{ paddingLeft: "1.6rem" }} className="muted">of which food sales</td>
                    <td className="amount muted">{formatMoney(summary.foodSales ?? 0, currency)}</td>
                  </tr>
                )}
                {(summary.ownDeliveryIncome ?? 0) > 0 && (
                  <tr className="cashflow-income">
                    <td style={{ paddingLeft: "1.6rem" }} className="muted">of which delivery fees you kept (your own riders)</td>
                    <td className="amount muted">{formatMoney(summary.ownDeliveryIncome ?? 0, currency)}</td>
                  </tr>
                )}
                {summary.otherIncome > 0 && (
                  <tr className="cashflow-income">
                    <td>Other recorded income</td>
                    <td className="amount">{formatMoney(summary.otherIncome, currency)}</td>
                  </tr>
                )}
                <tr className="cashflow-subtotal">
                  <td><strong>Total income</strong></td>
                  <td className="amount"><strong>{formatMoney(summary.totalIncome, currency)}</strong></td>
                </tr>
                <tr><td colSpan={2} style={{ height: 8 }} /></tr>
                {summary.stockSpend > 0 && (
                  <tr className="cashflow-expense">
                    <td><TrendingDown size={14} aria-hidden="true" /> Stock purchases ({summary.stockOrderCount} order{summary.stockOrderCount !== 1 ? "s" : ""})</td>
                    <td className="amount">−{formatMoney(summary.stockSpend, currency)}</td>
                  </tr>
                )}
                {summary.deliverySpend > 0 && (
                  <tr className="cashflow-expense">
                    <td>Delivery</td>
                    <td className="amount">−{formatMoney(summary.deliverySpend, currency)}</td>
                  </tr>
                )}
                {summary.operatingExpenses > 0 && (
                  <tr className="cashflow-expense">
                    <td>Operating expenses</td>
                    <td className="amount">−{formatMoney(summary.operatingExpenses, currency)}</td>
                  </tr>
                )}
                <tr className="cashflow-subtotal">
                  <td><strong>Total expenses</strong></td>
                  <td className="amount"><strong>−{formatMoney(summary.totalExpenses, currency)}</strong></td>
                </tr>
                <tr style={{ borderTop: "2px solid var(--line-strong)" }}>
                  <td><strong>Net cash movement</strong></td>
                  <td className="amount" style={{ color: netPositive ? "var(--success)" : "var(--danger)", fontWeight: 800 }}>
                    {netPositive ? "+" : "−"}{formatMoney(Math.abs(net), currency)}
                  </td>
                </tr>
              </tbody>
            </table>

            {(summary.pulaRideDeliveryFees ?? 0) > 0 && (
              <div className="food-note" style={{ marginTop: "0.75rem" }}>
                <strong>PULA Ride deliveries (not part of your income)</strong>
                <div>Customers paid {formatMoney(summary.pulaRideDeliveryFees ?? 0, currency)} in delivery fees on your completed orders.
                  PULA Ride drivers earned {formatMoney(summary.pulaRideDriverEarnings ?? 0, currency)} (80%) and PULA kept{" "}
                  {formatMoney(summary.pulaRideFeeIncome ?? 0, currency)} (20%). These fees aren't food sales, so they aren't counted in your sales above.</div>
              </div>
            )}

            {((summary.foodCommission ?? 0) > 0 || (summary.merchantProtectionIncome ?? 0) > 0 ||
              (summary.foodSettlementsPaid ?? 0) > 0 || (summary.foodSettlementsOwed ?? 0) > 0) && (
              <div className="food-note" style={{ marginTop: "0.75rem" }}>
                <strong>PULA Food settlements</strong>
                <div>
                  PULA commission {formatMoney(summary.foodCommission ?? 0, currency)} is included in your expenses.
                  {(summary.merchantProtectionIncome ?? 0) > 0 && (
                    <> Compensation of {formatMoney(summary.merchantProtectionIncome ?? 0, currency)} for prepared food PULA could not deliver is included in your income.</>
                  )}
                  {" "}Paid out so far: {formatMoney(summary.foodSettlementsPaid ?? 0, currency)}; still to be paid:{" "}
                  {formatMoney(summary.foodSettlementsOwed ?? 0, currency)}. Payouts and refunds are shown for information and are
                  not counted twice.
                </div>
              </div>
            )}

            {summary.totalIncome === 0 && summary.totalExpenses === 0 && (
              <p className="muted" style={{ fontSize: "0.85rem" }}>
                No recorded activity for this period. Once you complete sales on PULA Market or add manual records, they'll appear here.
              </p>
            )}
          </div>

          {/* Quick-add actions */}
          <div className="row wrap" style={{ gap: 8 }}>
            <Button variant="ghost" onClick={() => setShowAddIncome((v) => !v)}>
              + Record income
            </Button>
            <Button variant="ghost" onClick={() => setShowAddExpense((v) => !v)}>
              + Record expense
            </Button>
          </div>

          {showAddIncome && (
            <div className="card stack" style={{ maxWidth: 480 }}>
              <h3>Record income</h3>
              <p className="muted" style={{ fontSize: "0.82rem" }}>
                Use this for income that didn't come through PULA Market (e.g. a cash sale, catering job).
              </p>
              <div className="field-row">
                <label className="field"><span>Amount (R)</span>
                  <input type="number" min="0" step="0.01" value={incomeAmount} onChange={(e) => setIncomeAmount(e.target.value)} />
                </label>
                <label className="field"><span>Date</span>
                  <input type="date" value={incomeDate} onChange={(e) => setIncomeDate(e.target.value)} />
                </label>
              </div>
              <label className="field"><span>Description (optional)</span>
                <input value={incomeDesc} onChange={(e) => setIncomeDesc(e.target.value)} placeholder="e.g. Cash sale, catering job" />
              </label>
              <Button onClick={addIncome} disabled={formBusy || !incomeAmount}>{formBusy ? "Saving…" : "Save income"}</Button>
            </div>
          )}

          {showAddExpense && (
            <div className="card stack" style={{ maxWidth: 480 }}>
              <h3>Record expense</h3>
              <p className="muted" style={{ fontSize: "0.82rem" }}>
                For expenses not captured by PULA (e.g. packaging, electricity, transport). Stock orders placed on PULA are auto-recorded.
              </p>
              <div className="field-row">
                <label className="field"><span>Amount (R)</span>
                  <input type="number" min="0" step="0.01" value={expenseAmount} onChange={(e) => setExpenseAmount(e.target.value)} />
                </label>
                <label className="field"><span>Date</span>
                  <input type="date" value={expenseDate} onChange={(e) => setExpenseDate(e.target.value)} />
                </label>
              </div>
              <div className="field-row">
                <label className="field"><span>Category</span>
                  <select value={expenseCat} onChange={(e) => setExpenseCat(e.target.value as ExpenseCategory)}>
                    {EXPENSE_CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                </label>
                <label className="field"><span>Description (optional)</span>
                  <input value={expenseDesc} onChange={(e) => setExpenseDesc(e.target.value)} placeholder="e.g. Packaging materials" />
                </label>
              </div>
              <Button onClick={addExpense} disabled={formBusy || !expenseAmount}>{formBusy ? "Saving…" : "Save expense"}</Button>
            </div>
          )}

          {/* Recent ledger */}
          {(summary.recentIncome.length > 0 || summary.recentExpenses.length > 0) && (
            <div className="card stack" style={{ maxWidth: 520 }}>
              <h3>Recent activity</h3>
              {[...summary.recentIncome.map((e) => ({ ...e, _type: "income" as const })),
                ...summary.recentExpenses.map((e) => ({ ...e, _type: "expense" as const }))]
                .sort((a, b) => {
                  const da = "_type" in a && a._type === "income" ? a.receivedDate : (a as BusinessExpenseEntry).incurredDate
                  const db = "_type" in b && b._type === "income" ? b.receivedDate : (b as BusinessExpenseEntry).incurredDate
                  return db.localeCompare(da)
                })
                .slice(0, 10)
                .map((row) => (
                  <div key={row.id} className="row" style={{ justifyContent: "space-between", alignItems: "center", padding: "6px 0", borderBottom: "1px solid var(--line)" }}>
                    <div>
                      <span className="muted" style={{ fontSize: "0.78rem", marginRight: 8 }}>
                        {"receivedDate" in row ? row.receivedDate : row.incurredDate}
                      </span>
                      <span style={{ fontSize: "0.9rem" }}>
                        {row.description || ("category" in row && row.category ? row.category : "Sale")}
                        {row.source === "MANUAL" && <span className="badge badge-muted" style={{ marginLeft: 4, fontSize: "0.7rem" }}>manual</span>}
                      </span>
                    </div>
                    <div className="row" style={{ gap: 8, alignItems: "center" }}>
                      <span style={{ fontWeight: 700, color: row._type === "income" ? "var(--success)" : "var(--danger)" }}>
                        {row._type === "income" ? "+" : "−"}{formatMoney(row.amount, currency)}
                      </span>
                      {row.source === "MANUAL" && (
                        <button
                          className="btn btn-ghost"
                          style={{ fontSize: "0.72rem", padding: "2px 6px", color: "var(--danger)" }}
                          onClick={() => row._type === "income" ? deleteIncome(row.id) : deleteExpense(row.id)}
                        >
                          ✕
                        </button>
                      )}
                    </div>
                  </div>
                ))}
            </div>
          )}
        </>
      )}

      {!summary && !loading && !error && (
        <EmptyState title="No cash-flow data" description="Start selling on PULA Market to see your sales here." />
      )}
    </div>
  )
}
