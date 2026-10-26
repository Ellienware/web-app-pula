import { useState } from "react"
import { api } from "../../lib/api"
import type { StockCart, StockPlan } from "../../lib/types"
import { formatMoney } from "../../lib/format"

/**
 * "I have R5,000 to restock" — backend builds a PROPOSAL from real listings. Nothing is bought:
 * the owner reviews it, adds it to the stock cart, then checks out and pays as normal.
 */
export function StockPlanPanel({ onCartChanged }: { onCartChanged: (c: StockCart) => void }) {
  const [budget, setBudget] = useState("")
  const [plan, setPlan] = useState<StockPlan | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)

  async function make() {
    setBusy(true); setMsg(null)
    try { setPlan(await api.post<StockPlan>("/stock/planner", { budget: Number(budget) })) }
    catch (e) { setMsg(e instanceof Error ? e.message : "Could not build a plan") }
    finally { setBusy(false) }
  }

  async function addAll() {
    if (!plan) return
    setBusy(true); setMsg(null)
    try {
      let cart: StockCart | null = null
      for (const l of plan.lines) {
        cart = await api.post<StockCart>("/stock/cart/items", { wholesaleProductId: l.wholesaleProductId, quantityPacks: l.quantityPacks })
      }
      if (cart) onCartChanged(cart)
      setMsg("Added to your stock cart. Review it, choose collection or delivery, then pay.")
    } catch (e) { setMsg(e instanceof Error ? e.message : "Could not add to cart") }
    finally { setBusy(false) }
  }

  return (
    <div className="card stack">
      <h3 style={{ margin: 0 }}>Plan my restock</h3>
      <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
        <input type="number" min="1" inputMode="decimal" value={budget} onChange={(e) => setBudget(e.target.value)} placeholder="Budget, e.g. 5000" aria-label="Restock budget" />
        <button className="btn btn-primary" disabled={busy || !(Number(budget) > 0)} onClick={make}>{busy ? "Working…" : "Suggest a stock cart"}</button>
      </div>
      {plan && (
        <>
          {plan.lines.map((l) => (
            <div key={l.wholesaleProductId} className="row" style={{ justifyContent: "space-between", fontSize: "0.88rem" }}>
              <span>{l.productName} × {l.quantityPacks} <span className="muted">({l.supplierName})</span></span>
              <span>{formatMoney(l.lineTotal, plan.currency)}</span>
            </div>
          ))}
          {plan.suppliers.map((s) => (
            <div key={s.supplierBusinessId} className="muted" style={{ fontSize: "0.82rem" }}>
              {s.supplierName}: delivery {formatMoney(s.deliveryFee, plan.currency)}
            </div>
          ))}
          <p style={{ margin: 0, fontWeight: 700 }}>
            Estimated total {formatMoney(plan.estimatedTotal, plan.currency)} · remaining {formatMoney(plan.remainingBudget, plan.currency)}
            {plan.potentialSavings > 0 && <> · potential saving {formatMoney(plan.potentialSavings, plan.currency)}</>}
          </p>
          {plan.notes.map((n) => <p key={n} className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>{n}</p>)}
          {plan.lines.length > 0 && <button className="btn btn-primary" disabled={busy} onClick={addAll}>Add this plan to my stock cart</button>}
        </>
      )}
      {msg && <p style={{ margin: 0, fontSize: "0.85rem" }}>{msg}</p>}
    </div>
  )
}
