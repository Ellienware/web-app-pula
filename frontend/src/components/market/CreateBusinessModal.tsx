import { useState, type FormEvent } from "react"
import { api } from "../../lib/api"
import type { Business, BusinessType, FulfilmentOption, BusinessCapability, BusinessRegistrationStatus } from "../../lib/types"
import { FULFILMENT_OPTION_LABELS, BUSINESS_CAPABILITY_LABELS, BUSINESS_REGISTRATION_STATUS_LABELS } from "../../lib/types"
import { Modal } from "../Modal"
import {
  TYPE_CHOICES, toPayload, validateAll, validateStep,
  type BusinessDraft, type BusinessTypeId, type CapabilityId, type FulfilmentId, type RegistrationId,
} from "../../lib/businessSetup"

const CAPABILITY_OPTIONS: BusinessCapability[] = [
  "CAN_SELL_TO_CONSUMERS",
  "CAN_BUY_STOCK",
  "CAN_SUPPLY_BUSINESSES",
  "CAN_MANAGE_DELIVERY_FLEET",
  "CAN_PROVIDE_SERVICES",
]
const FULFILMENT_OPTIONS: FulfilmentOption[] = [
  "CUSTOMER_PICKUP",
  "SELLER_DELIVERY",
  "MOMORIDE_DELIVERY",
  "SUPPORTED_PARTNER_DELIVERY",
]

/** Sensible starting capabilities per business type — the owner can change them in step 3. */
const DEFAULT_CAPABILITIES: Record<BusinessType, BusinessCapability[]> = {
  LOCAL_BUSINESS: ["CAN_SELL_TO_CONSUMERS", "CAN_BUY_STOCK"],
  INFORMAL_TRADER: ["CAN_SELL_TO_CONSUMERS", "CAN_BUY_STOCK"],
  FORMAL_RETAILER: ["CAN_SELL_TO_CONSUMERS"],
  SERVICE_PROVIDER: ["CAN_PROVIDE_SERVICES", "CAN_BUY_STOCK"],
  WHOLESALER_SUPPLIER: ["CAN_SUPPLY_BUSINESSES", "CAN_MANAGE_DELIVERY_FLEET"],
  OTHER_SMALL_BUSINESS: ["CAN_SELL_TO_CONSUMERS"],
  RESTAURANT: ["CAN_SELL_TO_CONSUMERS"],
  CAFE: ["CAN_SELL_TO_CONSUMERS"],
  FAST_FOOD: ["CAN_SELL_TO_CONSUMERS"],
  FOOD_VENDOR: ["CAN_SELL_TO_CONSUMERS"],
  HOME_KITCHEN: ["CAN_SELL_TO_CONSUMERS"],
  SPAZA: ["CAN_SELL_TO_CONSUMERS", "CAN_BUY_STOCK"],
  BAKERY: ["CAN_SELL_TO_CONSUMERS"],
  CATERING: ["CAN_SELL_TO_CONSUMERS"],
}
const REGISTRATION_OPTIONS: BusinessRegistrationStatus[] = ["NOT_REGISTERED", "REGISTRATION_IN_PROGRESS", "FORMALLY_REGISTERED"]
const STEP_TITLES = ["Business type", "Business details", "What you'll do & registration"]

/**
 * PULA Business Setup — a 3-step wizard: type → details → capabilities, fulfilment and registration readiness.
 * Registration is self-declared readiness information only: PULA does not register companies with CIPC.
 */
export function CreateBusinessModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [step, setStep] = useState(0)
  const [name, setName] = useState("")
  const [category, setCategory] = useState("")
  const [businessType, setBusinessType] = useState<BusinessType | null>(null)
  const [description, setDescription] = useState("")
  const [location, setLocation] = useState("")
  const [contactPhone, setContactPhone] = useState("")
  const [fulfilmentOptions, setFulfilmentOptions] = useState<FulfilmentOption[]>(["CUSTOMER_PICKUP"])
  const [capabilities, setCapabilities] = useState<BusinessCapability[]>([])
  const [registrationStatus, setRegistrationStatus] = useState<BusinessRegistrationStatus>("NOT_REGISTERED")
  const [registrationNumber, setRegistrationNumber] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function chooseType(t: BusinessType) {
    setBusinessType(t)
    setCapabilities(DEFAULT_CAPABILITIES[t])
  }
  function toggleFulfilment(option: FulfilmentOption) {
    setFulfilmentOptions((c) => (c.includes(option) ? c.filter((o) => o !== option) : [...c, option]))
  }
  function toggleCapability(capability: BusinessCapability) {
    setCapabilities((c) => (c.includes(capability) ? c.filter((x) => x !== capability) : [...c, capability]))
  }

  /** The same rules and payload as the mobile setup screen (lib/businessSetup.ts). */
  function draft(): BusinessDraft {
    return {
      businessType: (businessType ?? "") as BusinessTypeId | "", name, category, description, location, contactPhone,
      capabilities: capabilities as CapabilityId[], fulfilmentOptions: fulfilmentOptions as FulfilmentId[],
      registrationStatus: registrationStatus as RegistrationId, registrationNumber,
    }
  }

  function next() {
    setError(null)
    const problems = validateStep(step as 0 | 1 | 2, draft())
    const first = Object.values(problems)[0]
    if (first) { setError(first); return }
    setStep((s) => Math.min(2, s + 1))
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (step < 2) { next(); return }
    if (busy) return // never submit twice
    const bad = validateAll(draft())
    if (bad) { setStep(bad.step); setError(Object.values(bad.errors)[0] ?? "Check your details"); return }
    setBusy(true); setError(null)
    try {
      await api.post<Business>("/businesses", toPayload(draft()))
      onCreated()
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create business")
    } finally { setBusy(false) }
  }

  return (
    <Modal title="Set up your PULA Business" onClose={onClose}>
      <form className="modal-form" onSubmit={handleSubmit}>
        <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>Step {step + 1} of 3 — {STEP_TITLES[step]}</p>

        {step === 0 && (
          <fieldset className="field">
            <legend>What kind of business are you?</legend>
            {TYPE_CHOICES.map((t) => (
              <label key={t.value} style={{ display: "flex", alignItems: "flex-start", gap: "0.5rem" }}>
                <input type="radio" name="btype" checked={businessType === t.value} onChange={() => chooseType(t.value as BusinessType)} />
                <span>{t.label}<br /><small className="muted">{t.hint}</small></span>
              </label>
            ))}
          </fieldset>
        )}

        {step === 1 && (
          <>
            <label className="field"><span>Business name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="Thandi's Spaza" /></label>
            <label className="field"><span>Category</span>
              <input value={category} onChange={(e) => setCategory(e.target.value)} required placeholder="Groceries" /></label>
            <label className="field"><span>What do you sell or offer?</span>
              <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} /></label>
            <div className="field-row">
              <label className="field"><span>Trading location</span>
                <input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="e.g. Mamelodi East, Pretoria" /></label>
              <label className="field"><span>Contact phone</span>
                <input value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} inputMode="tel" placeholder="e.g. 082 123 4567" /></label>
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <fieldset className="field">
              <legend>What will you do on PULA?</legend>
              {CAPABILITY_OPTIONS.map((c) => (
                <label key={c} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <input type="checkbox" checked={capabilities.includes(c)} onChange={() => toggleCapability(c)} />
                  {BUSINESS_CAPABILITY_LABELS[c]}
                </label>
              ))}
            </fieldset>
            <fieldset className="field">
              <legend>How can customers get their order?</legend>
              {FULFILMENT_OPTIONS.map((o) => (
                <label key={o} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <input type="checkbox" checked={fulfilmentOptions.includes(o)} onChange={() => toggleFulfilment(o)} />
                  {FULFILMENT_OPTION_LABELS[o]}
                </label>
              ))}
            </fieldset>
            <label className="field"><span>Registration status</span>
              <select value={registrationStatus} onChange={(e) => setRegistrationStatus(e.target.value as BusinessRegistrationStatus)}>
                {REGISTRATION_OPTIONS.map((r) => <option key={r} value={r}>{BUSINESS_REGISTRATION_STATUS_LABELS[r]}</option>)}
              </select>
            </label>
            {registrationStatus !== "NOT_REGISTERED" && (
              <label className="field"><span>Registration number (optional)</span>
                <input value={registrationNumber} onChange={(e) => setRegistrationNumber(e.target.value)} /></label>
            )}
            <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>
              This is information you tell PULA to help you get ready. PULA does not register companies with CIPC or check registration numbers.
            </p>
          </>
        )}

        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="modal-actions">
          {step === 0
            ? <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
            : <button type="button" className="btn btn-ghost" onClick={() => setStep((s) => s - 1)}>Back</button>}
          {step < 2
            ? <button type="button" className="btn btn-primary" onClick={next} disabled={step === 0 && !businessType}>Next</button>
            : <button className="btn btn-primary" disabled={busy}>{busy ? "Creating…" : "Create my PULA Business"}</button>}
        </div>
      </form>
    </Modal>
  )
}
