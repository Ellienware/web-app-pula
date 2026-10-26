import { useCallback, useEffect, useState } from "react"
import { api } from "../../../lib/api"
import type {
  BranchItem, BranchRecord, CanonicalSuggestion, FoodDetails, MenuCategory, ModifierGroupRecord,
} from "../../../lib/foodTypes"
import { MATCH_LEVEL_LABELS } from "../../../lib/foodTypes"
import type { Product } from "../../../lib/types"
import { useToast } from "../../../context/ToastContext"
import { Modal } from "../../Modal"
import { Button } from "../../ui/Button"

const csv = (v: string) => v.split(",").map((s) => s.trim()).filter(Boolean)

/**
 * Everything a kitchen can say about one dish, all optional: what's in it (so PULA can compare it
 * fairly with similar meals), its category, extras, per-branch price and availability. A small
 * kitchen can skip all of this and just list a name and a price.
 */
export function FoodItemEditor({ product, categories, onClose, onChanged }: {
  product: Product
  categories: MenuCategory[]
  onClose: () => void
  onChanged: () => void
}) {
  const { showToast } = useToast()
  const [categoryId, setCategoryId] = useState("")
  const [d, setD] = useState({ foodType: "", protein: "", starch: "", portionSize: "", portionDescription: "", spiceLevel: "",
    prep: "", portions: "", sides: "", dietary: "", allergens: "" })
  const [groups, setGroups] = useState<ModifierGroupRecord[]>([])
  const [attached, setAttached] = useState<Set<string>>(new Set())
  const [branches, setBranches] = useState<BranchRecord[]>([])
  const [branchItems, setBranchItems] = useState<Record<string, BranchItem>>({})
  const [prices, setPrices] = useState<Record<string, string>>({})
  const [suggestions, setSuggestions] = useState<CanonicalSuggestion[]>([])
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const [details, g, att, br, sug] = await Promise.all([
      api.get<FoodDetails>(`/market/food/items/${product.id}/details`).catch(() => null),
      api.get<ModifierGroupRecord[]>("/market/food/modifier-groups"),
      api.get<ModifierGroupRecord[]>(`/market/food/items/${product.id}/modifier-groups`),
      api.get<BranchRecord[]>("/market/food/branches"),
      api.get<CanonicalSuggestion[]>(`/market/food/items/${product.id}/canonical-suggestions`).catch(() => []),
    ])
    if (details) {
      setD({
        foodType: details.foodType ?? "", protein: details.protein ?? "", starch: details.starch ?? "",
        portionSize: details.portionSize ?? "", portionDescription: details.portionDescription ?? "",
        spiceLevel: details.spiceLevel ?? "", prep: details.prepTimeMinutes != null ? String(details.prepTimeMinutes) : "",
        portions: details.dailyQuantity != null ? String(details.dailyQuantity) : "",
        sides: details.sides.join(", "), dietary: details.dietaryTags.join(", "), allergens: details.allergens.join(", "),
      })
    }
    setGroups(g)
    setAttached(new Set(att.map((x) => x.id)))
    setBranches(br)
    setSuggestions(sug)
    const items: Record<string, BranchItem> = {}
    for (const b of br) {
      for (const i of await api.get<BranchItem[]>(`/market/food/branches/${b.id}/items`)) {
        if (i.productId === product.id) items[b.id] = i
      }
    }
    setBranchItems(items)
    setPrices(Object.fromEntries(Object.entries(items).map(([k, v]) => [k, v.priceOverride != null ? String(v.priceOverride) : ""])))
  }, [product.id])

  useEffect(() => { load().catch((e) => showToast(e instanceof Error ? e.message : "Could not load this dish", "error")) }, [load, showToast])

  async function run(fn: () => Promise<unknown>, ok: string) {
    setBusy(true)
    try { await fn(); showToast(ok, "success"); onChanged() }
    catch (err) { showToast(err instanceof Error ? err.message : "That didn't work", "error") }
    finally { setBusy(false) }
  }

  const saveDetails = () => run(async () => {
    await api.put(`/market/food/items/${product.id}/details`, {
      foodType: d.foodType || null, protein: d.protein || null, starch: d.starch || null,
      portionSize: d.portionSize || null, portionDescription: d.portionDescription || null, spiceLevel: d.spiceLevel || null,
      prepTimeMinutes: d.prep ? Number(d.prep) : null, dailyQuantity: d.portions ? Number(d.portions) : null,
      sides: csv(d.sides), dietaryTags: csv(d.dietary), allergens: csv(d.allergens),
    })
    await api.put(`/market/food/items/${product.id}/category`, { categoryId: categoryId || null })
  }, "Dish saved")

  return (
    <Modal title={product.name} onClose={onClose}>
      <div className="stack">
        <label>Menu section
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">No section</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <p className="muted">What's in this meal? Everything below is optional, but it helps customers compare fairly.</p>
        <div className="food-filters">
          <label>Style of dish<input value={d.foodType} onChange={(e) => setD({ ...d, foodType: e.target.value })} placeholder="stew, kota, burger…" /></label>
          <label>Main protein<input value={d.protein} onChange={(e) => setD({ ...d, protein: e.target.value })} placeholder="beef, chicken…" /></label>
          <label>Starch<input value={d.starch} onChange={(e) => setD({ ...d, starch: e.target.value })} placeholder="pap, chips, rice…" /></label>
          <label>Portion size
            <select value={d.portionSize} onChange={(e) => setD({ ...d, portionSize: e.target.value })}>
              <option value="">Not stated</option><option value="SMALL">Small</option><option value="REGULAR">Regular</option><option value="LARGE">Large</option>
            </select>
          </label>
          <label>Spice level
            <select value={d.spiceLevel} onChange={(e) => setD({ ...d, spiceLevel: e.target.value })}>
              <option value="">Not stated</option><option value="MILD">Mild</option><option value="MEDIUM">Medium</option><option value="HOT">Hot</option>
            </select>
          </label>
          <label>Prep time (min)<input type="number" min={0} value={d.prep} onChange={(e) => setD({ ...d, prep: e.target.value })} /></label>
          <label>Portions left (optional)<input type="number" min={0} value={d.portions} onChange={(e) => setD({ ...d, portions: e.target.value })} /></label>
        </div>
        <label>Describe the portion<input value={d.portionDescription} onChange={(e) => setD({ ...d, portionDescription: e.target.value })} placeholder="e.g. 2 pieces of chicken and a scoop of pap" /></label>
        <label>Sides included (comma separated)<input value={d.sides} onChange={(e) => setD({ ...d, sides: e.target.value })} placeholder="spinach, salad" /></label>
        <label>Dietary tags<input value={d.dietary} onChange={(e) => setD({ ...d, dietary: e.target.value })} placeholder="halal, vegetarian" /></label>
        <label>Allergens<input value={d.allergens} onChange={(e) => setD({ ...d, allergens: e.target.value })} placeholder="gluten, milk, eggs" /></label>
        <div><Button onClick={saveDetails} loading={busy}>Save dish</Button></div>

        <h3>Extras and sides</h3>
        {groups.length === 0 ? <p className="muted">Create groups in the Extras tab first.</p> : groups.map((g) => (
          <label key={g.id}>
            <input type="checkbox" checked={attached.has(g.id)} disabled={busy}
              onChange={(e) => run(async () => {
                if (e.target.checked) await api.put(`/market/food/items/${product.id}/modifier-groups/${g.id}`)
                else await api.del(`/market/food/items/${product.id}/modifier-groups/${g.id}`)
                await load()
              }, e.target.checked ? "Extras added to this dish" : "Extras removed")} /> {g.name} ({g.modifiers.length} choice{g.modifiers.length === 1 ? "" : "s"})
          </label>
        ))}

        {branches.length > 0 && (
          <>
            <h3>By branch</h3>
            {branches.map((b) => {
              const row = branchItems[b.id]
              const available = row ? row.available : true
              return (
                <div key={b.id} className="food-rate-row">
                  <span>{b.name}</span>
                  <span className="row wrap">
                    <input type="number" min={0} step="0.5" className="market-qty-input" placeholder="Own price" aria-label={`Price at ${b.name}`}
                      value={prices[b.id] ?? ""} onChange={(e) => setPrices({ ...prices, [b.id]: e.target.value })} />
                    <Button size="sm" variant="secondary" disabled={busy} onClick={() => run(async () => {
                      await api.put(`/market/food/branches/${b.id}/items/${product.id}`, { available, priceOverride: prices[b.id] ? Number(prices[b.id]) : null })
                      await load()
                    }, "Price saved")}>Save price</Button>
                    <Button size="sm" variant={available ? "ghost" : "primary"} disabled={busy} onClick={() => run(async () => {
                      await api.put(`/market/food/branches/${b.id}/items/${product.id}`, { available: !available, priceOverride: prices[b.id] ? Number(prices[b.id]) : null })
                      await load()
                    }, available ? "Hidden at this branch" : "Available at this branch")}>{available ? "Hide here" : "Show here"}</Button>
                  </span>
                </div>
              )
            })}
          </>
        )}

        {suggestions.length > 0 && (
          <>
            <h3>Same as a standard meal?</h3>
            <p className="muted">Linking lets PULA compare this dish with the same meal at other places. Only link it if it really is that meal.</p>
            {suggestions.map((s) => (
              <div key={s.canonicalProductId} className="food-rate-row">
                <span>{s.name}</span>
                <span className="row wrap">
                  <Button size="sm" variant="secondary" disabled={busy} onClick={() => run(
                    () => api.put(`/products/${product.id}/canonical-link`, { canonicalProductId: s.canonicalProductId, classification: "EXACT_MATCH" }),
                    "Linked as the same meal")}>It's {MATCH_LEVEL_LABELS.EXACT_MATCH.toLowerCase()}</Button>
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => run(
                    () => api.put(`/products/${product.id}/canonical-link`, { canonicalProductId: s.canonicalProductId, classification: "SIMILAR_ALTERNATIVE" }),
                    "Linked as a similar meal")}>Similar</Button>
                </span>
              </div>
            ))}
          </>
        )}
      </div>
    </Modal>
  )
}
