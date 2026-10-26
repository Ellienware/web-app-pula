import { useEffect, useState } from "react"
import { futureApi, type CoachAnswer, type FuturePlan } from "../../lib/futureApi"
import { COACH_STARTERS, coachSourceLabel, describeFutureError } from "../../lib/futureLogic"
import { Button } from "../../components/ui/Button"

interface Turn { q: string; a: CoachAnswer }

/** The coach explains figures PULA already calculated. It cannot move money, recommend products or predict returns. */
export function CoachTab() {
  const [plans, setPlans] = useState<FuturePlan[]>([])
  const [planId, setPlanId] = useState("")
  const [question, setQuestion] = useState("")
  const [turns, setTurns] = useState<Turn[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { futureApi.plans().then((p) => setPlans(p.content)).catch(() => setPlans([])) }, [])

  async function ask(q: string) {
    const text = q.trim()
    if (!text) return setError("Type a question first")
    setBusy(true); setError(null)
    try {
      let calculationId: string | undefined
      if (planId) {
        const h = await futureApi.history(planId)
        calculationId = h.content[0]?.id ?? undefined
      }
      const a = await futureApi.coach(text, calculationId)
      setTurns((t) => [{ q: text, a }, ...t]); setQuestion("")
    } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }

  return (
    <div className="stack">
      <div className="card stack">
        <h3>Ask the PULA Future coach</h3>
        <p className="field-hint">The coach explains your planning figures and financial ideas in plain language. It cannot move money, buy anything, recommend a product or predict returns. Do not type PINs, codes or account numbers.</p>
        {plans.length > 0 && (
          <label className="field"><span>Explain my latest calculation for (optional)</span>
            <select value={planId} onChange={(e) => setPlanId(e.target.value)}>
              <option value="">No plan</option>
              {plans.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <span className="field-hint">Only your planning figures (amounts and warnings) are used. Names and personal details are never sent.</span>
          </label>
        )}
        <label className="field"><span>Your question</span>
          <textarea value={question} maxLength={500} rows={3} onChange={(e) => setQuestion(e.target.value)} />
        </label>
        <div className="future-row">
          <Button loading={busy} onClick={() => ask(question)}>Ask</Button>
          {COACH_STARTERS.map((s) => <Button key={s} size="sm" variant="ghost" disabled={busy} onClick={() => ask(s)}>{s}</Button>)}
        </div>
        {error && <div className="form-error" role="alert">{error}</div>}
      </div>
      {turns.map((t, i) => (
        <div className="card stack" key={i} role={i === 0 ? "status" : undefined}>
          <strong>{t.q}</strong>
          <p>{t.a.answer}</p>
          <span className="badge badge-muted">{coachSourceLabel(t.a.source)}</span>
        </div>
      ))}
    </div>
  )
}
