import { useEffect, useState, type FormEvent } from "react"
import { stokvelApi } from "../../lib/stokvelApi"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import {
  APPROVAL_CHOICES, FREQUENCY_CHOICES, FUNDS_NOTICE, STOKVEL_TYPE_CHOICES, emptyStokvelDraft, friendlyMembershipError, toCreatePayload,
  validateStokvelDraft, type StokvelDraft, type StokvelErrors,
} from "../../lib/stokvelMembership"
import { Modal } from "../Modal"

/**
 * Create a stokvel through the STOKVEL api (POST /api/stokvels), not the generic circle endpoint, which refuses stokvels. The
 * creator becomes the owner. New stokvels are PRIVATE: they can be made discoverable later from the stokvel's Join tab.
 */
export function CreateStokvelModal({ onClose, onCreated }: { onClose: () => void; onCreated: (circleId: string) => void }) {
  const { defaultCurrency, supportedCurrencies } = useCurrencyConfig()
  const [d, setD] = useState<StokvelDraft>(emptyStokvelDraft())
  const [currency, setCurrency] = useState(defaultCurrency)
  const [errors, setErrors] = useState<StokvelErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setCurrency((prev) => (supportedCurrencies.includes(prev) ? prev : defaultCurrency))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultCurrency, supportedCurrencies.join(",")])

  const set = <K extends keyof StokvelDraft>(k: K, v: StokvelDraft[K]) => { setD((x) => ({ ...x, [k]: v })); setErrors({}); setError(null) }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return // never submit twice
    const found = validateStokvelDraft(d)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    setBusy(true); setError(null)
    try {
      const created = await stokvelApi.create(toCreatePayload(d, currency) as Parameters<typeof stokvelApi.create>[0])
      onCreated(created.circleId)
    } catch (err) {
      setError(friendlyMembershipError(err))
    } finally {
      setBusy(false)
    }
  }

  const err = (k: keyof StokvelErrors) => errors[k] && <span className="field-error" role="alert">{errors[k]}</span>

  return (
    <Modal title="Create a stokvel" onClose={onClose}>
      <form onSubmit={submit} className="stack" noValidate>
        <p className="muted" role="note" style={{ margin: 0, fontSize: "0.85rem" }}>{FUNDS_NOTICE}</p>
        <label className="field"><span>Stokvel name</span>
          <input value={d.name} maxLength={120} onChange={(e) => set("name", e.target.value)} aria-invalid={!!errors.name} autoFocus />{err("name")}</label>
        <label className="field"><span>Private notes for members (optional)</span>
          <textarea rows={2} maxLength={1000} value={d.description} onChange={(e) => set("description", e.target.value)} />{err("description")}</label>
        <fieldset className="field">
          <legend>What kind of stokvel is it?</legend>
          {STOKVEL_TYPE_CHOICES.map((t) => (
            <label key={t.value} style={{ display: "flex", alignItems: "flex-start", gap: "0.5rem" }}>
              <input type="radio" name="stype" checked={d.stokvelType === t.value} onChange={() => set("stokvelType", t.value)} />
              <span>{t.label}<br /><small className="muted">{t.hint}</small></span>
            </label>
          ))}
        </fieldset>
        <div className="field-row">
          <label className="field"><span>Each member contributes ({currency}){d.stokvelType === "ROTATIONAL" ? "" : " (optional)"}</span>
            <input inputMode="decimal" value={d.contributionAmount} onChange={(e) => set("contributionAmount", e.target.value)} aria-invalid={!!errors.contributionAmount} />{err("contributionAmount")}</label>
          <label className="field"><span>How often</span>
            <select value={d.frequency} onChange={(e) => set("frequency", e.target.value as StokvelDraft["frequency"])}>
              {FREQUENCY_CHOICES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
            </select></label>
        </div>
        {supportedCurrencies.length > 1 && (
          <label className="field"><span>Currency</span>
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>{supportedCurrencies.map((c) => <option key={c} value={c}>{c}</option>)}</select></label>
        )}
        <div className="field-row">
          <label className="field"><span>Start date (optional, YYYY-MM-DD)</span>
            <input value={d.startDate} placeholder="2026-11-01" onChange={(e) => set("startDate", e.target.value)} />{err("startDate")}</label>
          <label className="field"><span>Shared target ({currency}, optional)</span>
            <input inputMode="decimal" value={d.targetAmount} onChange={(e) => set("targetAmount", e.target.value)} />{err("targetAmount")}</label>
        </div>
        {d.targetAmount.trim() && (
          <label className="field"><span>Target date (optional, YYYY-MM-DD)</span>
            <input value={d.targetDate} onChange={(e) => set("targetDate", e.target.value)} />{err("targetDate")}</label>
        )}
        <fieldset className="field">
          <legend>Who approves spending requests?</legend>
          {APPROVAL_CHOICES.map((a) => (
            <label key={a.value} style={{ display: "flex", alignItems: "flex-start", gap: "0.5rem" }}>
              <input type="radio" name="arule" checked={d.approvalRule === a.value} onChange={() => set("approvalRule", a.value)} />
              <span>{a.label}<br /><small className="muted">{a.hint}</small></span>
            </label>
          ))}
          {d.approvalRule === "THRESHOLD" && (
            <label className="field"><span>Approvals needed</span>
              <input inputMode="numeric" value={d.approvalThreshold} onChange={(e) => set("approvalThreshold", e.target.value)} />{err("approvalThreshold")}</label>
          )}
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input type="checkbox" checked={d.membersCanVote} onChange={(e) => set("membersCanVote", e.target.checked)} /> Ordinary members can vote
          </label>
        </fieldset>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy}>{busy ? "Creating…" : "Create stokvel"}</button>
        </div>
      </form>
    </Modal>
  )
}
