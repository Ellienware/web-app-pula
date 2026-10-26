import { useCallback, useEffect, useState } from "react"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { formatMoney } from "../../lib/format"
import { futureApi, type FutureCalculation, type FuturePlan } from "../../lib/futureApi"
import { describeFutureError, itemKindLabel } from "../../lib/futureLogic"
import { planLineDescription, planNotes, planOutcome, planStatusWord } from "../../lib/planView"

/**
 * Read-only view of each plan and its latest saved result. Editing, items, status and running a calculation stay in
 * Smart Allocation. A plan is a planning estimate and never moves money.
 */
export function PlanTab({ currency, goTo }: { currency: string; goTo: (tab: string) => void }) {
  const [plans, setPlans] = useState<FuturePlan[] | null>(null)
  const [latest, setLatest] = useState<Record<string, FutureCalculation | null>>({})
  const [error, setError] = useState<string | null>(null)
  const fmt = (n: number) => formatMoney(n, currency)

  const load = useCallback(() => {
    setError(null)
    futureApi
      .plans()
      .then(async (page) => {
        setPlans(page.content)
        const entries = await Promise.all(
          page.content.map(async (p) => [p.id, await futureApi.history(p.id).then((h) => h.content[0] ?? null).catch(() => null)] as const),
        )
        setLatest(Object.fromEntries(entries))
      })
      .catch((e) => setError(describeFutureError(e)))
  }, [])
  useEffect(load, [load])

  if (error) return <ErrorState description={error} onRetry={load} />
  if (plans === null) return <div className="spinner" role="status" aria-label="Loading" />
  if (plans.length === 0) {
    return (
      <EmptyState
        title="No plan yet"
        description="A plan describes how income could be divided between reserves, commitments and goals. Create one in Smart Allocation."
        action={<button className="btn btn-primary btn-sm" onClick={() => goTo("allocation")}>Open Smart Allocation</button>}
      />
    )
  }

  return (
    <div className="stack">
      {plans.map((plan) => {
        const calc = latest[plan.id] ?? null
        const outcome = planOutcome(calc, fmt)
        const notes = planNotes(plan)
        return (
          <section key={plan.id} className="card stack" aria-labelledby={`plan-${plan.id}`}>
            <div className="row between">
              <h2 id={`plan-${plan.id}`}>{plan.name}</h2>
              <span className="status-pill">{planStatusWord(plan.status)}</span>
            </div>
            <p className="text-muted-aa">Planning estimate only. Nothing here moves money.</p>

            <dl className="row wrap" style={{ gap: 24 }}>
              <div><dt className="muted">Reserves</dt><dd>{fmt(plan.effectiveReserves)}</dd></div>
              <div><dt className="muted">Commitments</dt><dd>{fmt(plan.effectiveCommitments)}</dd></div>
              <div><dt className="muted">Minimum buffer</dt><dd>{fmt(plan.minOperatingBuffer)}</dd></div>
            </dl>

            <h3>Where it would go</h3>
            {plan.lines.length === 0 ? <p className="text-muted-aa">No lines yet.</p> : (
              <ul>{plan.lines.map((l) => <li key={l.id}><strong>{l.label}</strong>: {planLineDescription(l, fmt)}</li>)}</ul>
            )}

            {plan.items.length > 0 && (
              <>
                <h3>Set aside first</h3>
                <ul>{plan.items.map((i) => <li key={i.id}>{itemKindLabel(i.kind)}: {i.label}, {fmt(i.amount)} ({i.treatedAs === "RESERVE" ? "reserve" : "commitment"})</li>)}</ul>
              </>
            )}

            <h3>Latest result</h3>
            <p><strong>{outcome.headline}</strong></p>
            <p className="text-muted-aa">{outcome.detail}</p>
            {calc && calc.valid && (
              <dl className="row wrap" style={{ gap: 24 }}>
                <div><dt className="muted">Income used</dt><dd>{fmt(calc.incomeBasis)}</dd></div>
                <div><dt className="muted">Available</dt><dd>{fmt(calc.allocatable)}</dd></div>
                <div><dt className="muted">Planned</dt><dd>{fmt(calc.totalAllocated)}</dd></div>
                <div><dt className="muted">Not yet planned</dt><dd>{fmt(calc.remaining)}</dd></div>
              </dl>
            )}
            {notes.length > 0 && <ul>{notes.map((n) => <li key={n}>{n}</li>)}</ul>}
            <div><button className="btn btn-secondary btn-sm" onClick={() => goTo("allocation")}>Edit or recalculate in Smart Allocation</button></div>
          </section>
        )
      })}
    </div>
  )
}
