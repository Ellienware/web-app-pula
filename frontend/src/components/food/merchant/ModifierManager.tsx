import { useCallback, useEffect, useState } from "react"
import { api } from "../../../lib/api"
import { formatMoney } from "../../../lib/format"
import type { ModifierGroupRecord } from "../../../lib/foodTypes"
import { useToast } from "../../../context/ToastContext"
import { Button } from "../../ui/Button"
import { EmptyState, ErrorState } from "../../ui/StatePanel"

/** Sides and extras (e.g. "Extras: Egg +R8, Atchar +R5"). Attach a group to a dish from the dish's editor. */
export function ModifierManager() {
  const { showToast } = useToast()
  const [groups, setGroups] = useState<ModifierGroupRecord[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [name, setName] = useState("")
  const [min, setMin] = useState("0")
  const [max, setMax] = useState("1")
  const [newMod, setNewMod] = useState<Record<string, { name: string; price: string }>>({})

  const load = useCallback(() => {
    api.get<ModifierGroupRecord[]>("/market/food/modifier-groups").then(setGroups)
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load extras"))
  }, [])
  useEffect(load, [load])

  async function run(fn: () => Promise<unknown>, ok?: string) {
    try { await fn(); if (ok) showToast(ok, "success"); load() }
    catch (err) { showToast(err instanceof Error ? err.message : "That didn't work", "error") }
  }

  if (error) return <ErrorState title="Couldn't load extras" description={error} onRetry={load} />
  if (!groups) return null
  return (
    <div className="stack">
      <form className="card stack" onSubmit={(e) => {
        e.preventDefault()
        run(() => api.post("/market/food/modifier-groups", { name: name.trim(), minSelect: Number(min), maxSelect: Number(max) }), "Group created")
          .then(() => setName(""))
      }}>
        <h2>New group of sides or extras</h2>
        <div className="food-filters">
          <label>Name<input value={name} onChange={(e) => setName(e.target.value)} placeholder="Extras, Sides, Sauce…" required /></label>
          <label>Customer must pick at least<input type="number" min={0} value={min} onChange={(e) => setMin(e.target.value)} /></label>
          <label>and at most<input type="number" min={1} value={max} onChange={(e) => setMax(e.target.value)} /></label>
        </div>
        <div><Button type="submit" disabled={!name.trim()}>Create group</Button></div>
      </form>

      {groups.length === 0 && <EmptyState title="No extras yet" description="Create a group, add choices to it, then attach it to a dish." />}
      {groups.map((g) => {
        const draft = newMod[g.id] ?? { name: "", price: "" }
        return (
          <div key={g.id} className="card stack">
            <div className="row between">
              <strong>{g.name}</strong>
              <span className="muted">{g.required ? "Required" : "Optional"} · pick {g.minSelect}–{g.maxSelect}</span>
            </div>
            {g.modifiers.map((m) => (
              <div key={m.id} className="food-rate-row">
                <span style={{ opacity: m.available ? 1 : 0.5 }}>{m.name} {m.priceDelta > 0 ? `+${formatMoney(m.priceDelta, "ZAR")}` : "(free)"}</span>
                <Button size="sm" variant="ghost" onClick={() => run(() => api.patch(`/market/food/modifiers/${m.id}`, { available: !m.available }))}>
                  {m.available ? "Mark unavailable" : "Mark available"}
                </Button>
              </div>
            ))}
            <div className="row wrap">
              <input placeholder="Choice, e.g. Egg" value={draft.name} onChange={(e) => setNewMod({ ...newMod, [g.id]: { ...draft, name: e.target.value } })} />
              <input type="number" min={0} step="0.5" placeholder="Extra price (R)" value={draft.price} className="market-qty-input"
                onChange={(e) => setNewMod({ ...newMod, [g.id]: { ...draft, price: e.target.value } })} />
              <Button size="sm" disabled={!draft.name.trim()} onClick={() => run(async () => {
                await api.post(`/market/food/modifier-groups/${g.id}/modifiers`, { name: draft.name.trim(), priceDelta: draft.price ? Number(draft.price) : 0 })
                setNewMod({ ...newMod, [g.id]: { name: "", price: "" } })
              })}>Add choice</Button>
            </div>
          </div>
        )
      })}
    </div>
  )
}
