
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react"
import { api } from "../../lib/api"
import { uploadBusinessLogo } from "../../lib/imageApi"
import type {
  Business,
  BusinessType,
  FulfilmentOption,
  BusinessCapability,
  BusinessRegistrationStatus,
} from "../../lib/types"
import {
  FULFILMENT_OPTION_LABELS,
  BUSINESS_CAPABILITY_LABELS,
  BUSINESS_REGISTRATION_STATUS_LABELS,
} from "../../lib/types"
import { Modal } from "../Modal"
import {
  TYPE_CHOICES,
  toPayload,
  validateAll,
  validateStep,
  type BusinessDraft,
  type BusinessTypeId,
  type CapabilityId,
  type FulfilmentId,
  type RegistrationId,
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

const DEFAULT_CAPABILITIES: Record<BusinessType, BusinessCapability[]> = {
  LOCAL_BUSINESS: ["CAN_SELL_TO_CONSUMERS", "CAN_BUY_STOCK"],
  INFORMAL_TRADER: ["CAN_SELL_TO_CONSUMERS", "CAN_BUY_STOCK"],
  FORMAL_RETAILER: ["CAN_SELL_TO_CONSUMERS"],
  SERVICE_PROVIDER: ["CAN_PROVIDE_SERVICES", "CAN_BUY_STOCK"],
  WHOLESALER_SUPPLIER: ["CAN_SELL_TO_CONSUMERS", "CAN_SUPPLY_BUSINESSES"],
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

const REGISTRATION_OPTIONS: BusinessRegistrationStatus[] = [
  "NOT_REGISTERED",
  "REGISTRATION_IN_PROGRESS",
  "FORMALLY_REGISTERED",
]

const STEP_TITLES = [
  "Business type",
  "Business details",
  "What you'll do & registration",
]

const ERROR_PRIORITY: (keyof BusinessDraft)[] = [
  "businessType",
  "name",
  "category",
  "description",
  "location",
  "contactPhone",
  "capabilities",
  "fulfilmentOptions",
  "registrationStatus",
  "registrationNumber",
]

const MAX_LOGO_BYTES = 2 * 1024 * 1024
const ACCEPTED_LOGO_TYPES = ["image/jpeg", "image/png", "image/webp"]

function firstError(
  problems: Partial<Record<keyof BusinessDraft, string>>,
): string | undefined {
  for (const key of ERROR_PRIORITY) {
    const message = problems[key]
    if (message) return message
  }

  return Object.values(problems).find(
    (message): message is string =>
      typeof message === "string" && message.length > 0,
  )
}

/**
 * PULA Business Setup — a three-step wizard.
 * The logo is uploaded before the business profile is created.
 * Registration details are self-declared; PULA does not register companies with CIPC.
 */
export function CreateBusinessModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (business: Business) => void
}) {
  const [step, setStep] = useState(0)
  const [name, setName] = useState("")
  const [category, setCategory] = useState("")
  const [businessType, setBusinessType] = useState<BusinessType | null>(null)
  const [description, setDescription] = useState("")
  const [location, setLocation] = useState("")
  const [contactPhone, setContactPhone] = useState("")
  const [fulfilmentOptions, setFulfilmentOptions] =
    useState<FulfilmentOption[]>(["CUSTOMER_PICKUP"])
  const [capabilities, setCapabilities] = useState<BusinessCapability[]>([])
  const [capabilitiesTouched, setCapabilitiesTouched] = useState(false)
  const [registrationStatus, setRegistrationStatus] =
    useState<BusinessRegistrationStatus>("NOT_REGISTERED")
  const [registrationNumber, setRegistrationNumber] = useState("")
  const [logoFile, setLogoFile] = useState<File | null>(null)
  const [logoPreviewUrl, setLogoPreviewUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const stepRef = useRef(0)
  const submittingRef = useRef(false)
  const lastAdvanceRef = useRef(0)
  const logoInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    stepRef.current = step
  }, [step])

  useEffect(() => {
    headingRef.current?.focus()
  }, [step])

  useEffect(() => {
    if (!logoFile) {
      setLogoPreviewUrl(null)
      return
    }

    const previewUrl = URL.createObjectURL(logoFile)
    setLogoPreviewUrl(previewUrl)

    return () => URL.revokeObjectURL(previewUrl)
  }, [logoFile])

  function chooseType(type: BusinessType) {
    setBusinessType(type)

    if (!capabilitiesTouched) {
      setCapabilities(DEFAULT_CAPABILITIES[type])
    }
  }

  function toggleFulfilment(option: FulfilmentOption) {
    setFulfilmentOptions((current) =>
      current.includes(option)
        ? current.filter((item) => item !== option)
        : [...current, option],
    )
  }

  function toggleCapability(capability: BusinessCapability) {
    setCapabilitiesTouched(true)

    setCapabilities((current) =>
      current.includes(capability)
        ? current.filter((item) => item !== capability)
        : [...current, capability],
    )
  }

  function changeRegistrationStatus(next: BusinessRegistrationStatus) {
    setRegistrationStatus(next)

    if (next === "NOT_REGISTERED") {
      setRegistrationNumber("")
    }
  }

  function handleLogoChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null
    setError(null)

    if (!file) {
      setLogoFile(null)
      return
    }

    if (!ACCEPTED_LOGO_TYPES.includes(file.type)) {
      setLogoFile(null)
      event.target.value = ""
      setError("Choose a JPEG, PNG or WebP business logo")
      return
    }

    if (file.size === 0 || file.size > MAX_LOGO_BYTES) {
      setLogoFile(null)
      event.target.value = ""
      setError("Choose a logo larger than 0 bytes and no more than 2 MB")
      return
    }

    setLogoFile(file)
  }

  function removeLogo() {
    setLogoFile(null)

    if (logoInputRef.current) {
      logoInputRef.current.value = ""
    }

    setError(null)
  }

  function draft(): BusinessDraft {
    return {
      businessType: (businessType ?? "") as BusinessTypeId | "",
      name,
      category,
      description,
      location,
      contactPhone,
      capabilities: capabilities as CapabilityId[],
      fulfilmentOptions: fulfilmentOptions as FulfilmentId[],
      registrationStatus: registrationStatus as RegistrationId,
      registrationNumber,
    }
  }

  function advance() {
    setError(null)

    const current = stepRef.current
    if (current >= 2) return

    const problems = validateStep(current as 0 | 1 | 2, draft())
    const first = firstError(problems)

    if (first) {
      setError(first)
      return
    }

    const nextStep = current + 1
    stepRef.current = nextStep
    lastAdvanceRef.current = Date.now()
    setStep(nextStep)
  }

  function back() {
    if (busy) return

    setError(null)
    const previousStep = Math.max(0, stepRef.current - 1)
    stepRef.current = previousStep
    setStep(previousStep)
  }

  function handleClose() {
    if (busy) return
    onClose()
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (submittingRef.current) return

    const current = stepRef.current

    if (current >= 2 && Date.now() - lastAdvanceRef.current < 300) {
      return
    }

    if (current < 2) {
      advance()
      return
    }

    const validation = validateAll(draft())

    if (validation) {
      stepRef.current = validation.step
      setStep(validation.step)
      setError(firstError(validation.errors) ?? "Check your details")
      return
    }

    if (!logoFile) {
      setError("Upload your business logo before creating your profile")
      return
    }

    if (capabilities.length === 0) {
      setError("Select at least one thing you'll do on PULA")
      return
    }

    if (fulfilmentOptions.length === 0) {
      setError("Choose at least one way customers can get their order")
      return
    }

    submittingRef.current = true
    setBusy(true)
    setError(null)

    try {
      setError("Uploading business logo…")

      const logoUrl = await uploadBusinessLogo(logoFile)

      setError(null)

      const createdBusiness = await api.post<Business>(
        "/businesses",
        {
          ...toPayload(draft()),
          logoUrl,
        },
      )

      onCreated(createdBusiness)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to create business",
      )
    } finally {
      submittingRef.current = false
      setBusy(false)
    }
  }

  function handleFormKeyDown(event: KeyboardEvent<HTMLFormElement>) {
    if (event.key !== "Enter") return

    const target = event.target as HTMLElement

    if (target.tagName === "TEXTAREA") return
    if (target.tagName === "BUTTON") return
    if (target.tagName === "INPUT" && (target as HTMLInputElement).type === "file") return

    if (stepRef.current < 2) {
      event.preventDefault()
      advance()
    }
  }

  return (
    <Modal title="Set up your PULA Business" onClose={handleClose}>
      <form
        className="modal-form"
        onSubmit={handleSubmit}
        onKeyDown={handleFormKeyDown}
        noValidate
      >
        <h3
          ref={headingRef}
          tabIndex={-1}
          className="muted"
          style={{
            margin: 0,
            fontSize: "0.85rem",
            fontWeight: 600,
            outline: "none",
          }}
        >
          Step {step + 1} of 3 — {STEP_TITLES[step]}
        </h3>

        {step === 0 && (
          <fieldset className="field">
            <legend>What kind of business are you?</legend>

            {TYPE_CHOICES.map((type) => (
              <label
                key={type.value}
                style={{
                  display: "flex",
                  alignItems: "flex-start",
                  gap: "0.5rem",
                }}
              >
                <input
                  type="radio"
                  name="btype"
                  checked={businessType === type.value}
                  onChange={() => chooseType(type.value as BusinessType)}
                />

                <span>
                  {type.label}
                  <br />
                  <small className="muted">{type.hint}</small>
                </span>
              </label>
            ))}
          </fieldset>
        )}

        {step === 1 && (
          <>
            <label className="field">
              <span>Business name</span>
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                required
                placeholder="Thandi's Spaza"
              />
            </label>

            <label className="field">
              <span>Category</span>
              <input
                value={category}
                onChange={(event) => setCategory(event.target.value)}
                required
                placeholder="Groceries"
              />
            </label>

            <label className="field">
              <span>What do you sell or offer?</span>
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                rows={2}
              />
            </label>

            <div className="field-row">
              <label className="field">
                <span>Trading location</span>
                <input
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
                  placeholder="e.g. Mamelodi East, Pretoria"
                />
              </label>

              <label className="field">
                <span>Contact phone</span>
                <input
                  value={contactPhone}
                  onChange={(event) => setContactPhone(event.target.value)}
                  inputMode="tel"
                  placeholder="e.g. 082 123 4567"
                />
              </label>
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <fieldset className="field">
              <legend>Business logo (required)</legend>

              {logoPreviewUrl ? (
                <img
                  src={logoPreviewUrl}
                  alt="Selected business logo preview"
                  style={{
                    width: 112,
                    height: 112,
                    objectFit: "contain",
                    borderRadius: 12,
                    border: "1px solid var(--border-color, #ddd)",
                    padding: 8,
                  }}
                />
              ) : (
                <p className="muted" style={{ margin: 0 }}>
                  Add your logo so customers can recognise your business.
                </p>
              )}

              <label className="field">
                <span>Choose a logo</span>
                <input
                  ref={logoInputRef}
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  required
                  disabled={busy}
                  onChange={handleLogoChange}
                />
              </label>

              <small className="muted">
                JPEG, PNG or WebP. Maximum size: 2 MB.
              </small>

              {logoFile && (
                <button
                  type="button"
                  className="btn btn-ghost"
                  disabled={busy}
                  onClick={removeLogo}
                >
                  Remove logo
                </button>
              )}
            </fieldset>

            <fieldset className="field">
              <legend>What will you do on PULA?</legend>

              {CAPABILITY_OPTIONS.map((capability) => (
                <label
                  key={capability}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "0.5rem",
                  }}
                >
                  <input
                    type="checkbox"
                    checked={capabilities.includes(capability)}
                    disabled={busy}
                    onChange={() => toggleCapability(capability)}
                  />
                  {BUSINESS_CAPABILITY_LABELS[capability] ?? capability}
                </label>
              ))}
            </fieldset>

            <fieldset className="field">
              <legend>How can customers get their order?</legend>

              {FULFILMENT_OPTIONS.map((option) => (
                <label
                  key={option}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "0.5rem",
                  }}
                >
                  <input
                    type="checkbox"
                    checked={fulfilmentOptions.includes(option)}
                    disabled={busy}
                    onChange={() => toggleFulfilment(option)}
                  />
                  {FULFILMENT_OPTION_LABELS[option] ?? option}
                </label>
              ))}
            </fieldset>

            <label className="field">
              <span>Registration status</span>
              <select
                value={registrationStatus}
                disabled={busy}
                onChange={(event) =>
                  changeRegistrationStatus(
                    event.target.value as BusinessRegistrationStatus,
                  )
                }
              >
                {REGISTRATION_OPTIONS.map((status) => (
                  <option key={status} value={status}>
                    {BUSINESS_REGISTRATION_STATUS_LABELS[status] ?? status}
                  </option>
                ))}
              </select>
            </label>

            {registrationStatus !== "NOT_REGISTERED" && (
              <label className="field">
                <span>Registration number (optional)</span>
                <input
                  value={registrationNumber}
                  disabled={busy}
                  onChange={(event) => setRegistrationNumber(event.target.value)}
                />
              </label>
            )}

            <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>
              This is information you tell PULA to help you get ready. PULA
              does not register companies with CIPC or check registration
              numbers.
            </p>
          </>
        )}

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <div className="modal-actions">
          {step === 0 ? (
            <button
              key="cancel"
              type="button"
              className="btn btn-ghost"
              onClick={handleClose}
              disabled={busy}
            >
              Cancel
            </button>
          ) : (
            <button
              key="back"
              type="button"
              className="btn btn-ghost"
              onClick={back}
              disabled={busy}
            >
              Back
            </button>
          )}

          {step < 2 ? (
            <button
              key="next"
              type="button"
              className="btn btn-primary"
              onClick={advance}
              disabled={busy || (step === 0 && !businessType)}
            >
              Next
            </button>
          ) : (
            <button
              key="create"
              type="submit"
              className="btn btn-primary"
              disabled={busy}
            >
              {busy ? "Uploading logo…" : "Create my PULA Business"}
            </button>
          )}
        </div>
      </form>
    </Modal>
  )
}
