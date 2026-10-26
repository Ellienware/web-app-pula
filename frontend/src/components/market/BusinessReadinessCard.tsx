import { useEffect, useState } from "react"
import { api, ApiError } from "../../lib/api"
import type { BusinessProfileCompleteness } from "../../lib/types"

const CHECKLIST: Array<{ key: keyof BusinessProfileCompleteness; label: string }> = [
  { key: "businessName", label: "Business name" },
  { key: "ownerInformation", label: "Owner contact details" },
  { key: "location", label: "Location" },
  { key: "businessType", label: "Business type" },
  { key: "hasProducts", label: "Products or services listed" },
  { key: "registrationDetails", label: "Registration details" },
  { key: "supportingDocuments", label: "Supporting documents" },
]

/**
 * PULA Business Profile completeness — brief §8. A plain ✓/○ checklist built
 * entirely from data already on PULA. This is NOT a credit score or any kind
 * of ranking — it never blocks anything, it just shows what's missing.
 */
export default function BusinessReadinessCard() {
  const [data, setData] = useState<BusinessProfileCompleteness | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .get<BusinessProfileCompleteness>("/businesses/me/readiness")
      .then((res) => { if (!cancelled) setData(res) })
      .catch((err) => { if (!cancelled) setError(err instanceof ApiError ? err.message : "Could not load your profile checklist") })
    return () => { cancelled = true }
  }, [])

  if (error) return <p className="form-error">{error}</p>
  if (!data) return <p className="muted">Loading your PULA Business Profile…</p>

  return (
    <div className="card stack" style={{ maxWidth: 480 }}>
      <h2>PULA Business Profile</h2>
      <p className="muted" style={{ fontSize: "0.85rem" }}>
        {data.completedCount} of {data.totalCount} complete. This is just a checklist to help you spot what's
        missing — it never limits what you can do on PULA.
      </p>
      <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "grid", gap: "0.4rem" }}>
        {CHECKLIST.map(({ key, label }) => (
          <li key={key} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <span aria-hidden="true" style={{ color: data[key] ? "var(--success)" : "var(--muted-2)" }}>
              {data[key] ? "✓" : "○"}
            </span>
            <span>{label}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
