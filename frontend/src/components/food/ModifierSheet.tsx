import { useMemo, useState } from "react"
import { formatMoney } from "../../lib/format"
import type { MenuItem } from "../../lib/foodTypes"
import { Modal } from "../Modal"
import { Button } from "../ui/Button"

/**
 * Lets the customer choose a dish's sides/extras. The rules shown here (required, "choose up to N")
 * are for convenience only — the backend re-validates every choice and prices the order itself, so
 * the "each" figure below is an estimate and the real total is shown at checkout.
 */
export function ModifierSheet({
  item,
  onClose,
  onAdd,
}: {
  item: MenuItem
  onClose: () => void
  onAdd: (modifierIds: string[], quantity: number) => Promise<void>
}) {
  const [chosen, setChosen] = useState<Record<string, string[]>>({})
  const [quantity, setQuantity] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function toggle(groupId: string, modifierId: string, max: number) {
    setChosen((prev) => {
      const current = prev[groupId] ?? []
      if (current.includes(modifierId)) return { ...prev, [groupId]: current.filter((m) => m !== modifierId) }
      if (max === 1) return { ...prev, [groupId]: [modifierId] }
      if (current.length >= max) return prev
      return { ...prev, [groupId]: [...current, modifierId] }
    })
  }

  const problems = useMemo(() => {
    const list: string[] = []
    for (const g of item.modifierGroups) {
      const n = (chosen[g.id] ?? []).length
      if (n < g.minSelect) list.push(`Choose at least ${g.minSelect} from ${g.name}`)
    }
    return list
  }, [item, chosen])

  const extra = item.modifierGroups.reduce((sum, g) => {
    const ids = chosen[g.id] ?? []
    return sum + g.modifiers.filter((m) => ids.includes(m.id)).reduce((s, m) => s + m.priceDelta, 0)
  }, 0)

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      await onAdd(Object.values(chosen).flat(), quantity)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not add to cart")
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={item.name} onClose={onClose}>
      <div className="stack">
        {item.description && <p className="muted">{item.description}</p>}
        {item.modifierGroups.map((g) => (
          <fieldset key={g.id} className="stack">
            <legend>
              <strong>{g.name}</strong>{" "}
              <span className="muted">
                {g.required ? "Required" : "Optional"}
                {g.maxSelect > 1 ? ` · up to ${g.maxSelect}` : ""}
              </span>
            </legend>
            {g.modifiers.map((m) => {
              const checked = (chosen[g.id] ?? []).includes(m.id)
              return (
                <label key={m.id} className="food-rate-row" style={{ opacity: m.available ? 1 : 0.5 }}>
                  <span>
                    <input
                      type={g.maxSelect === 1 ? "radio" : "checkbox"}
                      name={g.id}
                      checked={checked}
                      disabled={!m.available}
                      onChange={() => toggle(g.id, m.id, g.maxSelect)}
                    />{" "}
                    {m.name}
                    {!m.available && " (unavailable)"}
                  </span>
                  <span className="muted">{m.priceDelta > 0 ? `+${formatMoney(m.priceDelta, item.currency)}` : "Free"}</span>
                </label>
              )
            })}
          </fieldset>
        ))}
        <label className="row">
          Quantity
          <input type="number" min={1} max={20} value={quantity} className="market-qty-input"
            onChange={(e) => setQuantity(Math.max(1, Math.min(20, Number(e.target.value) || 1)))} />
        </label>
        <div className="food-note">
          About {formatMoney(item.price + extra, item.currency)} each. The final price is confirmed by PULA at checkout.
        </div>
        {problems.length > 0 && <p className="muted">{problems[0]}</p>}
        {error && <p className="error-text" role="alert">{error}</p>}
        <Button onClick={submit} loading={busy} disabled={problems.length > 0 || !item.available}>Add to cart</Button>
      </div>
    </Modal>
  )
}
