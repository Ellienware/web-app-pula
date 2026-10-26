import { useEffect, useState } from "react"
import { bulkApi } from "../../lib/bulkApi"
import type { TierView } from "../../lib/bulkTypes"
import { formatMoney } from "../../lib/format"
import { Button } from "../ui/Button"

/**
 * Supplier-only. These are the volume prices PULA bulk groups will use. They are validated on the server (strictly increasing
 * thresholds, never above the standard price); groups already created keep the prices they started with.
 */
export function VolumeTiersPanel({ productId, basePrice, currency }: { productId: string; basePrice: number; currency: string }) {
  const [rows, setRows] = useState<{ minPacks: string; pricePerPack: string }[]>([])
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    bulkApi.tiers(productId)
      .then((t: TierView[]) => setRows(t.map((x) => ({ minPacks: String(x.minPacks), pricePerPack: String(x.pricePerPack) }))))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Could not load tiers"))
  }, [productId])

  async function save() {
    setBusy(true); setError(null); setMsg(null)
    try {
      const saved = await bulkApi.setTiers(productId, rows.filter((r) => r.minPacks && r.pricePerPack)
        .map((r) => ({ minPacks: Number(r.minPacks), pricePerPack: Number(r.pricePerPack) })))
      setRows(saved.map((x) => ({ minPacks: String(x.minPacks), pricePerPack: String(x.pricePerPack) })))
      setMsg("Saved. New bulk groups will use these prices.")
    } catch (e) { setError(e instanceof Error ? e.message : "Could not save") } finally { setBusy(false) }
  }

  return (
    <details style={{ marginTop: 8 }}>
      <summary>Volume prices (bulk groups)</summary>
      <p className="muted" style={{ fontSize: "0.75rem" }}>Standard price {formatMoney(basePrice, currency)} per pack. Add lower prices for larger combined orders.</p>
      {error && <div className="state-panel" role="alert">{error}</div>}
      {msg && <div className="state-panel" role="status">{msg}</div>}
      {rows.map((r, i) => (
        <div key={i} className="row" style={{ gap: 4, marginBottom: 4 }}>
          <input aria-label="From packs" type="number" min={1} placeholder="From packs" value={r.minPacks}
            onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, minPacks: e.target.value } : x)))} />
          <input aria-label="Price per pack" type="number" min={0.01} step="0.01" placeholder="Price/pack" value={r.pricePerPack}
            onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, pricePerPack: e.target.value } : x)))} />
          <Button size="sm" variant="ghost" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</Button>
        </div>
      ))}
      <div className="row" style={{ gap: 4 }}>
        <Button size="sm" variant="secondary" disabled={rows.length >= 10} onClick={() => setRows([...rows, { minPacks: "", pricePerPack: "" }])}>Add tier</Button>
        <Button size="sm" loading={busy} onClick={save}>Save volume prices</Button>
      </div>
    </details>
  )
}
