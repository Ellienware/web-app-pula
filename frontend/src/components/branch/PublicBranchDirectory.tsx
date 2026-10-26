import { useEffect, useState } from "react"
import { branchOpsApi, type DirectoryBranch } from "../../lib/branchApi"

/**
 * Customer-facing list of a seller's active branches. Shows only what the business entered for customers; nothing is verified by PULA.
 * Renders nothing for a seller with no branches (or a food business: restaurants use their own page).
 */
export function PublicBranchDirectory({ businessId }: { businessId: string }) {
  const [branches, setBranches] = useState<DirectoryBranch[]>([])
  useEffect(() => {
    let live = true
    branchOpsApi.directory(businessId).then((r) => { if (live) setBranches(r) }).catch(() => { if (live) setBranches([]) })
    return () => { live = false }
  }, [businessId])
  if (branches.length === 0) return null
  return (
    <div className="card stack">
      <h3>Branches</h3>
      {branches.map((b) => (
        <div key={b.id} className="stack" style={{ borderTop: "1px solid var(--line)", paddingTop: 8 }}>
          <strong>{b.name}{b.temporarilyClosed ? " (temporarily closed)" : ""}</strong>
          <div className="muted small">{[b.address, b.suburb, b.city, b.province, b.postalCode].filter(Boolean).join(", ") || "Address not provided"}</div>
          {b.phone && <div className="small"><a href={`tel:${b.phone}`}>📞 {b.phone}</a></div>}
          {b.operatingHours.length > 0 && <div className="small">{b.operatingHours.join(" · ")}</div>}
          {b.brandName && (
            <div className="muted small">
              {b.brandName}{b.operatingModel === "FRANCHISED" ? ` · independently operated${b.operatorName ? ` by ${b.operatorName}` : ""}` : ""}
            </div>
          )}
        </div>
      ))}
      <p className="muted small">Branch details are supplied by the seller and have not been verified by PULA.</p>
    </div>
  )
}
