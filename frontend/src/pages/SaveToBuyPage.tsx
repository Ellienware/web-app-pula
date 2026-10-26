import { useCallback, useEffect, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { saveToBuyApi } from "../lib/saveToBuyApi"
import { setActiveSaveToBuy } from "../lib/saveToBuyContext"
import {
  canCheckOut, MARKETPLACE_ROUTE, STATUS_LABELS, VERTICAL_LABELS,
} from "../lib/saveToBuyTypes"
import type { EligibleGoal, SaveToBuyInsights, SaveToBuyVertical, SaveToBuyView } from "../lib/saveToBuyTypes"
import { formatMoney } from "../lib/format"
import { useAuth } from "../context/AuthContext"
import { PageHeader } from "../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"

const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")
const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random())
const SETUP: SaveToBuyVertical[] = ["RETAIL", "FOOD", "STOCK", "SERVICE", "TAXI", "GENERAL"]

/**
 * Save-to-Buy. A goal is designated for a purchase, a purchase plan is created, confirmed (personal) or approved by
 * the group (stokvel), and the named buyer then continues in the EXISTING marketplace. This page has no cart and no
 * payment: it only tracks which goal a purchase is for. Savings are never held or spent by PULA.
 */
export function SaveToBuyPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [goals, setGoals] = useState<EligibleGoal[] | null>(null)
  const [plans, setPlans] = useState<SaveToBuyView[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [insights, setInsights] = useState<Record<string, SaveToBuyInsights>>({})
  const [amounts, setAmounts] = useState<Record<string, string>>({})
  const [vertical, setVertical] = useState<SaveToBuyVertical>("RETAIL")

  const load = useCallback(async () => {
    setError(null)
    try {
      const g = await saveToBuyApi.goals(true)
      setGoals(g)
      const mine = await saveToBuyApi.mine()
      const circles = Array.from(new Set(g.filter((x) => x.scope === "STOKVEL").map((x) => x.circleId)))
      const group = (await Promise.all(circles.map((c) => saveToBuyApi.forCircle(c)))).flat()
      setPlans([...mine, ...group])
    } catch (e) {
      setError(errMsg(e))
    }
  }, [])
  useEffect(() => { void load() }, [load])

  async function act(fn: () => Promise<unknown>, done?: string) {
    setNotice(null)
    try { await fn(); if (done) setNotice(done); await load() } catch (e) { setNotice(errMsg(e)) }
  }

  function continueShopping(p: SaveToBuyView) {
    setActiveSaveToBuy({ id: p.id, vertical: p.vertical, purpose: p.purpose, requestedAmount: p.requestedAmount, currency: p.currency })
    navigate(MARKETPLACE_ROUTE[p.vertical])
  }

  if (error) return <ErrorState description={error} onRetry={load} />
  if (!goals) return <SkeletonCard />
  const me = user?.id ?? ""

  return (
    <div className="page stack">
      <PageHeader title="Save-to-Buy" />
      <p className="muted" role="note">
        Your savings stay yours: PULA does not hold them. A purchase plan only links a goal to a purchase you pay for at
        the normal checkout.
      </p>
      {notice && <div className="state-panel" role="status">{notice}</div>}

      <h2>Your goals</h2>
      {goals.length === 0 && (
        <EmptyState title="No savings goals yet" description="Create a savings goal in a circle or stokvel first." />
      )}
      {goals.map((g) => (
        <section key={g.goalId} className="card">
          <strong>{g.goalTitle}</strong> · {g.vertical ? VERTICAL_LABELS[g.vertical] : "not set up for a purchase"} · {g.scope === "STOKVEL" ? `stokvel ${g.circleName}` : "personal"}
          <p>{g.percent}% · {formatMoney(g.saved, g.currency)} of {formatMoney(g.target, g.currency)}
            {g.goalReached ? " · savings goal reached" : ` · ${formatMoney(g.remaining, g.currency)} to go`}</p>
          {!g.vertical && g.canDesignate && (
            <>
              <label>Saving toward
                <select value={vertical} onChange={(e) => setVertical(e.target.value as SaveToBuyVertical)}>
                  {(g.scope === "STOKVEL" ? SETUP.slice(0, 4) : SETUP).map((v) => <option key={v} value={v}>{VERTICAL_LABELS[v]}</option>)}
                </select>
              </label>
              <button onClick={() => act(() => saveToBuyApi.designate(g.goalId, vertical, g.goalTitle), "Goal set up for a purchase")}>
                Set up for a purchase
              </button>
            </>
          )}
          {g.canCreateRequest && (
            <>
              <label>Planned purchase amount
                <input inputMode="decimal" value={amounts[g.goalId] ?? ""} onChange={(e) => setAmounts({ ...amounts, [g.goalId]: e.target.value })} />
              </label>
              <button disabled={!(Number(amounts[g.goalId]) > 0)}
                onClick={() => act(() => saveToBuyApi.create({
                  goalId: g.goalId, purpose: g.purpose ?? g.goalTitle, requestedAmount: Number(amounts[g.goalId]),
                  // Stokvel requests name the buyer; here the creator buys. Change by creating the request with another member via the API.
                  namedBuyerUserId: g.scope === "STOKVEL" ? me : undefined, idempotencyKey: newKey(),
                }), "Purchase plan created")}>Plan a purchase</button>
            </>
          )}
        </section>
      ))}

      <h2>Purchase plans</h2>
      {plans.length === 0 && <p className="muted">No purchase plans yet.</p>}
      {plans.map((p) => (
        <article key={p.id} className="card">
          <strong>{p.purpose}</strong> · {VERTICAL_LABELS[p.vertical]} · <span className="badge">{STATUS_LABELS[p.status]}</span>
          <p>Goal target {formatMoney(p.goalTarget, p.currency)} · saved {formatMoney(p.saved, p.currency)} · planned {formatMoney(p.requestedAmount, p.currency)}</p>
          {p.shortfall > 0
            ? <p className="muted">Savings are {formatMoney(p.shortfall, p.currency)} short of this purchase. You can still check out; the difference is paid at checkout.</p>
            : p.differenceFromTarget > 0 && <p className="muted">Costs {formatMoney(p.differenceFromTarget, p.currency)} more than the goal target; paid at checkout.</p>}
          {p.approval && <p className="muted">Approvals {p.approval.approvals}/{p.approval.required} · buyer {p.namedBuyerName}</p>}
          {p.orderId && <p className="muted">Linked order: {p.orderId}{p.purchasedAmount != null ? ` · purchased ${formatMoney(p.purchasedAmount, p.currency)}` : ""}</p>}

          {p.status === "DRAFT" && p.ownerId === me && p.scope === "PERSONAL" && (
            <button disabled={!p.goalReached} onClick={() => act(() => saveToBuyApi.confirm(p.id), "Confirmed")}>
              {p.goalReached ? "Confirm this purchase" : "Reach your goal to confirm"}
            </button>
          )}
          {p.status === "DRAFT" && p.ownerId === me && p.scope === "STOKVEL" && (
            <button disabled={!p.goalReached} onClick={() => act(() => saveToBuyApi.submit(p.id), "Submitted for approval")}>
              {p.goalReached ? "Submit for group approval" : "Reach the group goal to submit"}
            </button>
          )}
          {p.status === "PENDING_APPROVAL" && (
            <p className="muted">Approve or reject in the stokvel's Requests tab. <Link to={`/stokvels/${p.circleId}`}>Open stokvel</Link></p>
          )}
          {canCheckOut(p, me) && <button onClick={() => continueShopping(p)}>Continue to {VERTICAL_LABELS[p.vertical]} checkout</button>}
          {p.status === "APPROVED" && (p.vertical === "TAXI" || p.vertical === "GENERAL") && p.namedBuyerId === me && (
            <>
              <Link to={MARKETPLACE_ROUTE[p.vertical]}>Open PULA Ride</Link>{" "}
              <button onClick={() => act(() => saveToBuyApi.complete(p.id, "Used in PULA Ride"), "Marked as done")}>Mark as done</button>
            </>
          )}
          {["DRAFT", "PENDING_APPROVAL", "APPROVED"].includes(p.status) && (
            <button onClick={() => act(() => saveToBuyApi.cancel(p.id), "Cancelled")}>Cancel plan</button>
          )}
          <button onClick={async () => { try { setInsights({ ...insights, [p.id]: await saveToBuyApi.insights(p.id) }) } catch (e) { setNotice(errMsg(e)) } }}>Explain</button>
          {insights[p.id] && (
            <div className="muted">
              <p>{insights[p.id].summary}</p><p>{insights[p.id].shortfallExplanation}</p>
              {insights[p.id].savingAdvice && <p>{insights[p.id].savingAdvice}</p>}
              <ul>{insights[p.id].suggestions.map((s) => <li key={s}>{s}</li>)}</ul>
              <p>{insights[p.id].notice}</p>
            </div>
          )}
        </article>
      ))}
    </div>
  )
}
