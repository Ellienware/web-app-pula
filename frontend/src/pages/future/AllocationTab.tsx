import { useCallback, useEffect, useState } from "react"
import { formatMoney } from "../../lib/format"
import { futureApi, type BusinessSummary, type FutureCalculation, type FutureGoal, type FuturePlan, type IncomePreview, type LineType } from "../../lib/futureApi"
import {
  describeFutureError, incomeTrustLabel, INCOME_SOURCES, ITEM_KINDS, itemKindLabel, newCalcKey, parseNonNegativeAmount,
  parsePercent, percentHint, percentTotalBp, planStatusLabel, validateDateRange, type IncomeSourceKey,
} from "../../lib/futureLogic"
import { Button } from "../../components/ui/Button"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"

interface LineDraft { goalId: string; label: string; type: LineType; value: string }
const blankLine = (): LineDraft => ({ goalId: "", label: "", type: "PERCENT", value: "" })

export function AllocationTab({ goals, currency }: { goals: FutureGoal[]; currency: string }) {
  const [plans, setPlans] = useState<FuturePlan[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [businesses, setBusinesses] = useState<BusinessSummary[]>([])
  const load = useCallback(() => {
    futureApi.plans().then((p) => { setPlans(p.content); setLoadError(null) }).catch((e) => setLoadError(describeFutureError(e)))
  }, [])
  useEffect(load, [load])
  useEffect(() => { futureApi.businesses().then(setBusinesses).catch(() => setBusinesses([])) }, [])

  return (
    <div className="stack">
      <NewPlan goals={goals} businesses={businesses} onCreated={load} />
      {loadError && <ErrorState description={loadError} onRetry={load} />}
      {plans && plans.length === 0 && <EmptyState title="No plans yet" description="Create a plan above. It only proposes amounts; it never moves money." />}
      {plans?.map((p) => <PlanCard key={p.id} plan={p} currency={currency} onChanged={load} />)}
    </div>
  )
}

function NewPlan({ goals, businesses, onCreated }: { goals: FutureGoal[]; businesses: BusinessSummary[]; onCreated: () => void }) {
  const [name, setName] = useState("")
  const [businessId, setBusinessId] = useState("")
  const [reserves, setReserves] = useState("")
  const [commitments, setCommitments] = useState("")
  const [buffer, setBuffer] = useState("")
  const [lines, setLines] = useState<LineDraft[]>([blankLine()])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const pcts = lines.filter((l) => l.type === "PERCENT" && parsePercent(l.value).ok).map((l) => l.value)
  const hint = percentHint(percentTotalBp(pcts))
  const setLine = (i: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)))

  async function submit() {
    setError(null)
    if (!name.trim()) return setError("Give the plan a name")
    const optional = (raw: string, label: string) => (raw.trim() === "" ? { ok: true as const, value: undefined } : parseNonNegativeAmount(raw, label))
    const r = optional(reserves, "Reserves"), c = optional(commitments, "Commitments"), b = optional(buffer, "Operating buffer")
    for (const x of [r, c, b]) if (!x.ok) return setError(x.error)
    const outLines = []
    for (const [i, l] of lines.entries()) {
      const label = l.label.trim() || goals.find((g) => g.goalId === l.goalId)?.title || ""
      if (!label) return setError(`Line ${i + 1}: choose a goal or enter a label`)
      const v = l.type === "PERCENT" ? parsePercent(l.value, "Percentage") : parseNonNegativeAmount(l.value, "Amount")
      if (!v.ok) return setError(`Line ${i + 1}: ${v.error}`)
      outLines.push({
        goalId: l.goalId || undefined, label, type: l.type,
        ...(l.type === "PERCENT" ? { percent: v.value } : { fixedAmount: v.value }),
      })
    }
    setBusy(true)
    try {
      await futureApi.createPlan({
        name: name.trim(), businessId: businessId || undefined,
        reserves: r.ok ? r.value : undefined, commitments: c.ok ? c.value : undefined, minOperatingBuffer: b.ok ? b.value : undefined,
        lines: outLines,
      })
      setName(""); setReserves(""); setCommitments(""); setBuffer(""); setLines([blankLine()]); onCreated()
    } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }

  return (
    <div className="card stack">
      <h3>New allocation plan</h3>
      <p className="field-hint">A plan proposes how a hypothetical income could be divided. It does not debit a wallet, create a contribution or pay anyone.</p>
      <label className="field"><span>Plan name</span><input value={name} maxLength={120} onChange={(e) => setName(e.target.value)} /></label>
      {businesses.length > 0 && (
        <label className="field"><span>Plan for</span>
          <select value={businessId} onChange={(e) => setBusinessId(e.target.value)}>
            <option value="">Me personally</option>
            {businesses.map((b) => <option key={b.id} value={b.id}>My business: {b.name}</option>)}
          </select>
          <span className="field-hint">Business plans can use your settled sales and declared business commitments. Business and personal plans stay separate.</span>
        </label>
      )}
      <div className="grid">
        <label className="field"><span>Reserves you plan to keep (optional)</span><input inputMode="decimal" value={reserves} onChange={(e) => setReserves(e.target.value)} /></label>
        <label className="field"><span>Declared commitments (optional)</span><input inputMode="decimal" value={commitments} onChange={(e) => setCommitments(e.target.value)} /></label>
        <label className="field"><span>Minimum operating buffer (optional)</span><input inputMode="decimal" value={buffer} onChange={(e) => setBuffer(e.target.value)} /></label>
      </div>
      <div className="field-hint">Reserves and commitments are planning inputs. They do not prove money is set aside, and PULA cannot control how real money is spent.</div>

      {lines.map((l, i) => (
        <div className="grid" key={i}>
          <label className="field"><span>Goal</span>
            <select value={l.goalId} onChange={(e) => setLine(i, { goalId: e.target.value })}>
              <option value="">Not linked to a goal</option>
              {goals.filter((g) => g.status !== "CLOSED").map((g) => <option key={g.goalId} value={g.goalId}>{g.title}</option>)}
            </select>
          </label>
          <label className="field"><span>Label</span><input value={l.label} maxLength={120} onChange={(e) => setLine(i, { label: e.target.value })} /></label>
          <label className="field"><span>Type</span>
            <select value={l.type} onChange={(e) => setLine(i, { type: e.target.value as LineType, value: "" })}>
              <option value="PERCENT">Percentage of income</option><option value="FIXED">Fixed amount</option>
            </select>
          </label>
          <label className="field"><span>{l.type === "PERCENT" ? "Percent" : "Amount"}</span>
            <input inputMode="decimal" value={l.value} onChange={(e) => setLine(i, { value: e.target.value })} />
          </label>
          {lines.length > 1 && <Button size="sm" variant="ghost" onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>Remove</Button>}
        </div>
      ))}
      {hint && <div className="form-error" role="alert">{hint}</div>}
      <div className="future-row">
        <Button variant="ghost" size="sm" disabled={lines.length >= 20} onClick={() => setLines((ls) => [...ls, blankLine()])}>Add line</Button>
        <Button loading={busy} onClick={submit}>Save plan</Button>
      </div>
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}

function PlanCard({ plan, currency, onChanged }: { plan: FuturePlan; currency: string; onChanged: () => void }) {
  const [income, setIncome] = useState("")
  const [source, setSource] = useState<IncomeSourceKey>("USER_ENTERED")
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const [preview, setPreview] = useState<IncomePreview | null>(null)
  const [result, setResult] = useState<FutureCalculation | null>(null)
  const [history, setHistory] = useState<FutureCalculation[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // One key per user intent: a double-click or retry replays the same stored result instead of creating a second.
  const [key, setKey] = useState(newCalcKey())

  const sources = INCOME_SOURCES.filter((s) => !s.needsBusiness || plan.businessId)
  const periodChanged = () => { setPreview(null); setKey(newCalcKey()) }

  async function showPreview() {
    setError(null)
    const bad = validateDateRange(from, to)
    if (bad) return setError(bad)
    try { setPreview(await futureApi.settlementIncome(plan.businessId as string, from, to)) } catch (e) { setError(describeFutureError(e)) }
  }

  async function calculate() {
    setError(null)
    let body: Parameters<typeof futureApi.calculate>[1]
    if (source === "USER_ENTERED") {
      const a = parseNonNegativeAmount(income, "Income")
      if (!a.ok) return setError(a.error)
      body = { idempotencyKey: key, incomeSource: source, incomeAmount: a.value }
    } else {
      const bad = validateDateRange(from, to)
      if (bad) return setError(bad)
      body = { idempotencyKey: key, incomeSource: source, from, to }
    }
    setBusy(true)
    try {
      setResult(await futureApi.calculate(plan.id, body))
      setKey(newCalcKey())
    } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function status(s: "ACTIVE" | "PAUSED" | "ARCHIVED") {
    setBusy(true); setError(null)
    try { await futureApi.setPlanStatus(plan.id, s); onChanged() } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function showHistory() {
    try { setHistory((await futureApi.history(plan.id)).content) } catch (e) { setError(describeFutureError(e)) }
  }

  const canCalc = plan.status === "DRAFT" || plan.status === "ACTIVE"
  return (
    <div className="card stack">
      <div className="future-between"><strong>{plan.name}</strong><span className="badge badge-muted">{planStatusLabel(plan.status)}</span></div>
      <ul className="future-list">
        {plan.lines.map((l) => (
          <li key={l.id}>{l.label}: {l.type === "PERCENT" ? `${l.percent}% of income` : formatMoney(l.fixedAmount ?? 0, currency)}</li>
        ))}
      </ul>
      <div className="future-row">
        {plan.status === "DRAFT" && <Button size="sm" variant="secondary" disabled={busy} onClick={() => status("ACTIVE")}>Mark as active rule</Button>}
        {plan.status === "ACTIVE" && <Button size="sm" variant="secondary" disabled={busy} onClick={() => status("PAUSED")}>Pause</Button>}
        {plan.status === "PAUSED" && <Button size="sm" variant="secondary" disabled={busy} onClick={() => status("ACTIVE")}>Resume</Button>}
        {plan.status !== "ARCHIVED" && <Button size="sm" variant="ghost" disabled={busy} onClick={() => status("ARCHIVED")}>Archive</Button>}
      </div>
      <div className="field-hint">An active rule is only a saved planning preference. Nothing runs on a schedule and no money moves.</div>

      {canCalc && (
        <div className="stack">
          {sources.length > 1 && (
            <label className="field"><span>Income basis</span>
              <select value={source} onChange={(e) => { setSource(e.target.value as IncomeSourceKey); periodChanged() }}>
                {sources.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select>
              <span className="field-hint">{incomeTrustLabel(source)}</span>
            </label>
          )}
          {source === "USER_ENTERED" ? (
            <label className="field"><span>Hypothetical income to plan with</span>
              <input inputMode="decimal" value={income} onChange={(e) => { setIncome(e.target.value); setKey(newCalcKey()) }} />
            </label>
          ) : (
            <div className="future-row">
              <label className="field"><span>From</span><input type="date" value={from} onChange={(e) => { setFrom(e.target.value); periodChanged() }} /></label>
              <label className="field"><span>To</span><input type="date" value={to} onChange={(e) => { setTo(e.target.value); periodChanged() }} /></label>
              {source === "MERCHANT_SIMULATED_SETTLEMENTS" && <Button size="sm" variant="ghost" onClick={showPreview}>Preview settled income</Button>}
            </div>
          )}
          {preview && <SettlementPreview p={preview} currency={currency} />}
          <div><Button size="sm" loading={busy} onClick={calculate}>Calculate</Button></div>
        </div>
      )}
      {plan.businessId && <ItemsEditor plan={plan} currency={currency} onChanged={onChanged} />}
      {result && <Result r={result} currency={currency} />}
      <Button size="sm" variant="ghost" onClick={showHistory}>Show calculation history</Button>
      {history && (history.length === 0 ? <div className="field-hint">No saved calculations yet.</div> : (
        <ul className="future-list">{history.map((h) => (
          <li key={h.id}>{h.incomeAsOf}: income basis {formatMoney(h.incomeBasis, currency)}, proposed {formatMoney(h.totalAllocated, currency)} (simulated)</li>
        ))}</ul>
      ))}
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}

function Result({ r, currency }: { r: FutureCalculation; currency: string }) {
  return (
    <div className="card stack" role="status">
      <div className="badge badge-muted">{r.persisted ? "Simulated allocation (saved)" : "Not saved — invalid scenario"}</div>
      <div>Income basis {formatMoney(r.incomeBasis, currency)} · available after reserves and commitments {formatMoney(r.allocatable, currency)}</div>
      <ul className="future-list">{r.lines.map((l, i) => <li key={i}>{l.label}: {formatMoney(l.amount, currency)}</li>)}</ul>
      <strong>Remaining unallocated {formatMoney(r.remaining, currency)}</strong>
      {r.warnings.map((w) => <div className="form-error" key={w}>{w}</div>)}
      <div className="badge badge-muted">{incomeTrustLabel(r.incomeSource)}</div>
      <div className="field-hint">{r.incomeSourceNote}</div>
      {r.incomeNotes.map((n) => <div className="field-hint" key={n}>{n}</div>)}
      {(r.reserves > 0 || r.commitments > 0) && (
        <div className="field-hint">Declared reserves {formatMoney(r.reserves, currency)} and commitments {formatMoney(r.commitments, currency)} were set aside in this planning view only.</div>
      )}
      <div className="field-hint">{r.notice}</div>
    </div>
  )
}

function SettlementPreview({ p, currency }: { p: IncomePreview; currency: string }) {
  return (
    <div className="card stack" role="status">
      <strong>Settled in PULA (simulated): {formatMoney(p.basis, currency)}</strong>
      <div>From {p.paidCount} paid settlement(s). Sales {formatMoney(p.gross, currency)}, refunds {formatMoney(p.refunded, currency)}, commission, fees and adjustments {formatMoney(p.feesAndCommission, currency)}.</div>
      {p.pendingCount > 0 && <div>Not included: {p.pendingCount} pending settlement(s) worth {formatMoney(p.pendingExcluded, currency)}.</div>}
      {p.notes.map((n) => <div className="field-hint" key={n}>{n}</div>)}
    </div>
  )
}

function ItemsEditor({ plan, currency, onChanged }: { plan: FuturePlan; currency: string; onChanged: () => void }) {
  const [kind, setKind] = useState(ITEM_KINDS[0].value)
  const [label, setLabel] = useState("")
  const [amount, setAmount] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const locked = plan.status === "ARCHIVED"

  async function add() {
    setError(null)
    const a = parseNonNegativeAmount(amount, "Amount")
    if (!a.ok) return setError(a.error)
    if (!label.trim()) return setError("Give the item a label")
    setBusy(true)
    try { await futureApi.addItem(plan.id, { kind, label: label.trim(), amount: a.value }); setLabel(""); setAmount(""); onChanged() }
    catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function remove(id: string) {
    setBusy(true)
    try { await futureApi.removeItem(plan.id, id); onChanged() } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }

  return (
    <div className="card stack">
      <strong>Business reserves and commitments</strong>
      <div className="field-hint">Planning inputs only. They do not reserve or hold money, and PULA cannot control how your real money is spent. Payroll and tax figures are your own declarations.</div>
      <ul className="future-list">
        {plan.items.map((i) => (
          <li key={i.id} className="future-between">
            <span>{itemKindLabel(i.kind)}: {i.label} — {formatMoney(i.amount, currency)} ({i.treatedAs === "RESERVE" ? "reserve" : "commitment"})</span>
            {!locked && <Button size="sm" variant="ghost" disabled={busy} onClick={() => remove(i.id)}>Remove</Button>}
          </li>
        ))}
      </ul>
      <div>Total reserves {formatMoney(plan.effectiveReserves, currency)} · Total commitments {formatMoney(plan.effectiveCommitments, currency)}</div>
      {!locked && (
        <div className="grid">
          <label className="field"><span>Type</span>
            <select value={kind} onChange={(e) => setKind(e.target.value)}>{ITEM_KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}</select>
          </label>
          <label className="field"><span>Label</span><input value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} /></label>
          <label className="field"><span>Amount</span><input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
          <Button size="sm" variant="secondary" loading={busy} onClick={add}>Add</Button>
        </div>
      )}
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}
