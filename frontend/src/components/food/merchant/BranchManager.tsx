import { useCallback, useEffect, useState } from "react"
import { api } from "../../../lib/api"
import type { BranchRecord } from "../../../lib/foodTypes"
import { FULFILMENT_OPTION_LABELS, type FulfilmentOption } from "../../../lib/types"
import { useToast } from "../../../context/ToastContext"
import { Button } from "../../ui/Button"
import { Badge } from "../../ui/Badge"
import { EmptyState, ErrorState } from "../../ui/StatePanel"

const OPTIONS: FulfilmentOption[] = ["CUSTOMER_PICKUP", "MOMORIDE_DELIVERY", "SELLER_DELIVERY"]

/**
 * Optional. A single-location kitchen needs no branches at all. Add them for a chain or a second
 * location. Coordinates are optional and only ever set by you (never guessed): they let customers
 * search by distance.
 */
export function BranchManager() {
  const { showToast } = useToast()
  const [branches, setBranches] = useState<BranchRecord[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ name: "", address: "", area: "", lat: "", lng: "", fee: "", min: "", prep: "", delivery: false, collection: true })

  const load = useCallback(() => {
    api.get<BranchRecord[]>("/market/food/branches").then(setBranches)
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load branches"))
  }, [])
  useEffect(load, [load])

  function useMyPosition() {
    navigator.geolocation?.getCurrentPosition(
      (p) => setForm((f) => ({ ...f, lat: p.coords.latitude.toFixed(6), lng: p.coords.longitude.toFixed(6) })),
      () => showToast("Couldn't read your position. You can type the coordinates instead.", "error"),
    )
  }

  async function create() {
    const fulfilmentOptions: FulfilmentOption[] = [
      ...(form.collection ? (["CUSTOMER_PICKUP"] as FulfilmentOption[]) : []),
      ...(form.delivery ? (["MOMORIDE_DELIVERY"] as FulfilmentOption[]) : []),
    ]
    try {
      await api.post("/market/food/branches", {
        name: form.name.trim(), address: form.address.trim() || null, area: form.area.trim() || null,
        latitude: form.lat ? Number(form.lat) : null, longitude: form.lng ? Number(form.lng) : null,
        deliveryFee: form.fee ? Number(form.fee) : null, minimumOrderAmount: form.min ? Number(form.min) : null,
        prepTimeMinutes: form.prep ? Number(form.prep) : null, fulfilmentOptions,
      })
      setForm({ name: "", address: "", area: "", lat: "", lng: "", fee: "", min: "", prep: "", delivery: false, collection: true })
      showToast("Branch added", "success")
      load()
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not add the branch", "error")
    }
  }

  async function patch(b: BranchRecord, body: Record<string, unknown>) {
    try { await api.patch(`/market/food/branches/${b.id}`, body); load() }
    catch (err) { showToast(err instanceof Error ? err.message : "Could not update", "error") }
  }

  if (error) return <ErrorState title="Couldn't load branches" description={error} onRetry={load} />
  if (!branches) return null
  return (
    <div className="stack">
      <div className="card stack">
        <h2>Add a branch</h2>
        <div className="food-filters">
          <label>Branch name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>Address<input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></label>
          <label>Suburb / township<input value={form.area} onChange={(e) => setForm({ ...form, area: e.target.value })} placeholder="e.g. Soweto" /></label>
          <label>Latitude<input value={form.lat} onChange={(e) => setForm({ ...form, lat: e.target.value })} /></label>
          <label>Longitude<input value={form.lng} onChange={(e) => setForm({ ...form, lng: e.target.value })} /></label>
          <label>Delivery fee (R)<input type="number" min={0} value={form.fee} onChange={(e) => setForm({ ...form, fee: e.target.value })} /></label>
          <label>Minimum order (R)<input type="number" min={0} value={form.min} onChange={(e) => setForm({ ...form, min: e.target.value })} /></label>
          <label>Prep time (min)<input type="number" min={0} value={form.prep} onChange={(e) => setForm({ ...form, prep: e.target.value })} /></label>
        </div>
        <div className="row wrap">
          <label><input type="checkbox" checked={form.collection} onChange={(e) => setForm({ ...form, collection: e.target.checked })} /> Collection</label>
          <label><input type="checkbox" checked={form.delivery} onChange={(e) => setForm({ ...form, delivery: e.target.checked })} /> PULA Ride delivery</label>
          <Button variant="secondary" size="sm" onClick={useMyPosition}>Use my current position</Button>
        </div>
        <div><Button onClick={create} disabled={!form.name.trim()}>Add branch</Button></div>
      </div>

      {branches.length === 0 && <EmptyState title="No branches" description="That's fine for a single location. Your business details are used." />}
      {branches.map((b) => (
        <div key={b.id} className="card stack">
          <div className="row between">
            <strong>{b.name}</strong>
            <span>{!b.active && <Badge tone="failed">Inactive</Badge>} {b.temporarilyClosed && <Badge tone="pending">Paused</Badge>}</span>
          </div>
          <div className="muted">
            {[b.address, b.area].filter(Boolean).join(" · ")}
            {b.latitude != null && b.longitude != null ? " · location set" : " · no map location (customers can't search by distance)"}
          </div>
          <div className="muted">{b.fulfilmentOptions.map((o) => FULFILMENT_OPTION_LABELS[o] ?? o).join(" · ") || "Uses the business defaults"}</div>
          <div className="row wrap">
            <Button size="sm" variant="secondary" onClick={() => patch(b, { temporarilyClosed: !b.temporarilyClosed })}>
              {b.temporarilyClosed ? "Resume orders" : "Pause this branch"}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => patch(b, { active: !b.active })}>{b.active ? "Deactivate" : "Activate"}</Button>
            {OPTIONS.map((o) => (
              <label key={o}>
                <input type="checkbox" checked={b.fulfilmentOptions.includes(o)}
                  onChange={(e) => patch(b, { fulfilmentOptions: e.target.checked ? [...b.fulfilmentOptions, o] : b.fulfilmentOptions.filter((x) => x !== o) })} />{" "}
                {o === "MOMORIDE_DELIVERY" ? "PULA delivery" : o === "CUSTOMER_PICKUP" ? "Collection" : "Own riders"}
              </label>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
