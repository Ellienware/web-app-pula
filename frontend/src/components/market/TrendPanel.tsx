import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { CashFlowTrendPoint } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"

/** Weekly / monthly cash-flow trend. Every number is computed by the backend; this only draws it. */
export function TrendPanel() {
  const [g, setG] = useState<"month" | "week">("month")
  const [pts, setPts] = useState<CashFlowTrendPoint[]>([])
  const [error, setError] = useState(false)
  const { defaultCurrency: currency } = useCurrencyConfig()
  useEffect(() => {
    api.get<CashFlowTrendPoint[]>(`/cashflow/trend?granularity=${g}&periods=${g === "week" ? 8 : 6}`)
      .then((p) => { setPts(p); setError(false) }).catch(() => setError(true))
  }, [g])
  if (error) return null
  const max = Math.max(1, ...pts.flatMap((p) => [p.totalIncome, p.totalExpenses]))
  const last = pts[pts.length - 1]
  const prev = pts[pts.length - 2]
  const change = last && prev ? last.netCashMovement - prev.netCashMovement : 0
  return (
    <div className="card stack">
      <div className="row" style={{ justifyContent: "space-between", gap: 8 }}>
        <h3 style={{ margin: 0 }}>Trend</h3>
        <span className="row" style={{ gap: 6 }}>
          {(["month", "week"] as const).map((x) => (
            <button key={x} className={`category-pill ${g === x ? "active" : ""}`} onClick={() => setG(x)}>{x === "month" ? "Monthly" : "Weekly"}</button>
          ))}
        </span>
      </div>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 8, height: 120 }} role="img" aria-label="Income and expenses by period">
        {pts.map((p) => (
          <div key={p.periodFrom} style={{ flex: 1, display: "flex", gap: 2, alignItems: "flex-end", height: "100%" }} title={`${p.periodFrom}: in ${p.totalIncome}, out ${p.totalExpenses}`}>
            <div style={{ flex: 1, height: `${(p.totalIncome / max) * 100}%`, background: "var(--success)", minHeight: 2 }} />
            <div style={{ flex: 1, height: `${(p.totalExpenses / max) * 100}%`, background: "var(--muted-2)", minHeight: 2 }} />
          </div>
        ))}
      </div>
      <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>Green = money in · Grey = money out.</p>
      {last && prev && (
        <p style={{ margin: 0, fontSize: "0.88rem" }}>
          Net cash movement this {g} is {formatMoney(last.netCashMovement, currency)}, {change >= 0 ? "up" : "down"} {formatMoney(Math.abs(change), currency)} from the previous {g}.
          Stock spending {last.stockSpend > prev.stockSpend ? "rose" : last.stockSpend < prev.stockSpend ? "fell" : "did not change"}.
        </p>
      )}
    </div>
  )
}
