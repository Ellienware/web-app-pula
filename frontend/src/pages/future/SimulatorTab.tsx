import { useEffect, useState } from "react"
import { formatMoney } from "../../lib/format"
import { futureApi, type SavedScenario, type SimulateInput, type Simulation } from "../../lib/futureApi"
import { describeFutureError, parseNonNegativeAmount, parseSignedPercent } from "../../lib/futureLogic"
import { Button } from "../../components/ui/Button"

export function SimulatorTab({ currency }: { currency: string }) {
  const [start, setStart] = useState("0")
  const [monthly, setMonthly] = useState("500")
  const [years, setYears] = useState("10")
  const [ret, setRet] = useState("8")
  const [infl, setInfl] = useState("5")
  const [fee, setFee] = useState("0")
  const [growth, setGrowth] = useState("0")
  const [sim, setSim] = useState<Simulation | null>(null)
  const [lastInput, setLastInput] = useState<SimulateInput | null>(null)
  const [label, setLabel] = useState("")
  const [saved, setSaved] = useState<SavedScenario[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSaved = () => futureApi.scenarios().then((p) => setSaved(p.content)).catch(() => setSaved([]))
  useEffect(() => { loadSaved() }, [])

  async function run() {
    setError(null)
    const s = parseNonNegativeAmount(start, "Starting amount"), m = parseNonNegativeAmount(monthly, "Monthly contribution")
    const r = parseSignedPercent(ret, -50, 50, "Return"), i = parseSignedPercent(infl, 0, 50, "Inflation")
    const f = parseSignedPercent(fee, 0, 10, "Fee"), g = parseSignedPercent(growth, 0, 30, "Contribution growth")
    for (const x of [s, m, r, i, f, g]) if (!x.ok) return setError(x.error)
    const y = Number(years)
    if (!Number.isInteger(y) || y < 1 || y > 60) return setError("Years must be a whole number between 1 and 60")
    if (!s.ok || !m.ok || !r.ok || !i.ok || !f.ok || !g.ok) return
    const input: SimulateInput = {
      startingAmount: s.value, monthlyContribution: m.value, years: y, annualReturnPct: r.value,
      annualInflationPct: i.value, annualFeePct: f.value, contributionGrowthPct: g.value,
    }
    setBusy(true)
    try { setSim(await futureApi.simulate(input)); setLastInput(input) }
    catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function save() {
    if (!lastInput || !label.trim()) return setError("Name the scenario to save it")
    try { await futureApi.saveScenario(label.trim(), lastInput); setLabel(""); loadSaved() }
    catch (e) { setError(describeFutureError(e)) }
  }

  const field = (l: string, v: string, set: (x: string) => void, hint?: string) => (
    <label className="field"><span>{l}</span><input inputMode="decimal" value={v} onChange={(e) => set(e.target.value)} />{hint && <span className="field-hint">{hint}</span>}</label>
  )
  return (
    <div className="stack">
      <div className="card stack">
        <h3>Savings and investment simulator</h3>
        <p className="field-hint">Investment projection — not an actual investment. Returns fluctuate and may be negative. Nothing here is guaranteed or recorded as a transaction.</p>
        <div className="grid">
          {field("Starting amount", start, setStart)}
          {field("Monthly contribution", monthly, setMonthly)}
          {field("Years (1–60)", years, setYears)}
          {field("Illustrative yearly return %", ret, setRet, "An assumption, not a forecast")}
          {field("Illustrative yearly inflation %", infl, setInfl)}
          {field("Yearly fee %", fee, setFee)}
          {field("Yearly increase in monthly amount %", growth, setGrowth)}
        </div>
        <Button loading={busy} onClick={run}>Show illustration</Button>
        {error && <div className="form-error" role="alert">{error}</div>}
      </div>

      {sim && (
        <div className="card stack" role="status">
          <div className="grid">
            {sim.scenarios.map((s) => (
              <div className="card stack" key={s.name}>
                <strong>{s.name}</strong>
                <div className="field-hint">{s.annualReturnPct}% return, {s.netAnnualRatePct}% after fees</div>
                <div>You contribute {formatMoney(s.totalContributions, currency)}</div>
                <div>Illustrative growth {formatMoney(s.growth, currency)}</div>
                <strong>Could be worth {formatMoney(s.futureValue, currency)}</strong>
                <div>About {formatMoney(s.futureValueAfterInflation, currency)} in today's buying power</div>
              </div>
            ))}
          </div>
          <ul className="field-hint future-list">{sim.assumptions.map((a) => <li key={a}>{a}</li>)}</ul>
          <div className="field-hint">{sim.notice}</div>
          <div className="future-row">
            <label className="field"><span>Save this illustration as</span><input value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} /></label>
            <Button size="sm" variant="secondary" onClick={save}>Save</Button>
          </div>
        </div>
      )}

      {saved.length > 0 && (
        <div className="card stack">
          <h3>Saved illustrations</h3>
          <ul className="future-list">
            {saved.map((s) => (
              <li key={s.id}>{s.label}: {formatMoney(s.futureValue, currency)} (hypothetical, from {formatMoney(s.totalContributions, currency)} contributed)</li>
            ))}
          </ul>
          <div className="field-hint">Saved illustrations are not accounts and are not part of your wallet or savings.</div>
        </div>
      )}
    </div>
  )
}
