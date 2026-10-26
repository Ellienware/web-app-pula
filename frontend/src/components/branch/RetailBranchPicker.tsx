import { useEffect, useState } from "react"
import { branchOpsApi, type BranchAvailability } from "../../lib/branchApi"
import { formatMoney } from "../../lib/format"

/**
 * Lets a customer buying from a NON-FOOD seller that has branches choose which branch fills the order. Renders nothing when the seller has
 * no branches (or is a food business, which has its own checkout). "No branch" keeps the original behaviour (the seller's central stock).
 * The server re-validates the branch, the price and the stock at checkout; this list is only a guide.
 */
export function RetailBranchPicker({ businessId, items, value, onChange }: {
  businessId: string
  items: { productId: string; quantity: number }[]
  value: string
  /** Also reports the total the customer is being shown for that branch, so checkout can send it back for the server to verify. */
  onChange: (branchId: string, total: number | null) => void
}) {
  const [options, setOptions] = useState<BranchAvailability[]>([])
  const key = JSON.stringify(items)
  useEffect(() => {
    let live = true
    branchOpsApi.check(businessId, items).then((r) => { if (live) setOptions(r) }).catch(() => { if (live) setOptions([]) })
    return () => { live = false }
  }, [businessId, key]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (value && !options.some((o) => o.branchId === value && o.canFulfil)) onChange("", null)
  }, [options]) // eslint-disable-line react-hooks/exhaustive-deps

  if (options.length === 0) return null
  return (
    <fieldset className="stack">
      <legend><strong>Which branch should fill this order?</strong></legend>
      <label className="row"><input type="radio" name={`branch-${businessId}`} checked={value === ""} onChange={() => onChange("", null)} /> <span>No specific branch (seller's main stock)</span></label>
      {options.map((o) => (
        <label key={o.branchId} className="row" style={{ opacity: o.canFulfil ? 1 : 0.6 }}>
          <input type="radio" name={`branch-${businessId}`} disabled={!o.canFulfil} checked={value === o.branchId} onChange={() => onChange(o.branchId, o.total)} />
          <span>
            {o.branchName}{o.suburb || o.city ? ` — ${[o.suburb, o.city].filter(Boolean).join(", ")}` : ""}
            {o.canFulfil && o.total != null && o.currency ? ` · ${formatMoney(o.total, o.currency)}` : ""}
            {!o.canFulfil && o.problems.length > 0 ? ` · ${o.problems[0]}` : ""}
          </span>
        </label>
      ))}
    </fieldset>
  )
}
