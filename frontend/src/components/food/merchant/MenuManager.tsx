import { useCallback, useEffect, useState } from "react"
import { api } from "../../../lib/api"
import { formatMoney } from "../../../lib/format"
import type { MenuCategory } from "../../../lib/foodTypes"
import type { Business, Page, Product, ProductStatus } from "../../../lib/types"
import { useToast } from "../../../context/ToastContext"
import { ProductFormModal } from "../../market/ProductFormModal"
import { Button } from "../../ui/Button"
import { Badge } from "../../ui/Badge"
import { EmptyState, ErrorState } from "../../ui/StatePanel"
import { SkeletonCard } from "../../ui/Skeleton"
import { FoodItemEditor } from "./FoodItemEditor"

/**
 * The menu: sections and dishes. A new dish starts as a draft, so nothing reaches customers until
 * you press "Put on menu". "Sold out" hides a dish instantly without deleting it.
 */
export function MenuManager({ business }: { business: Business }) {
  const { showToast } = useToast()
  const [categories, setCategories] = useState<MenuCategory[]>([])
  const [items, setItems] = useState<Product[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [newCategory, setNewCategory] = useState("")
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<Product | null>(null)
  const [details, setDetails] = useState<Product | null>(null)

  const load = useCallback(() => {
    api.get<MenuCategory[]>("/market/food/categories").then(setCategories).catch(() => undefined)
    api.get<Page<Product>>(`/businesses/${business.id}/products?size=100`).then((p) => setItems(p.content.filter((x) => x.status !== "ARCHIVED")))
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load your menu"))
  }, [business.id])
  useEffect(load, [load])

  async function setStatus(p: Product, status: ProductStatus) {
    try { await api.put(`/products/${p.id}`, { status }); load() }
    catch (err) { showToast(err instanceof Error ? err.message : "Could not update", "error") }
  }

  async function addCategory() {
    try {
      await api.post("/market/food/categories", { name: newCategory.trim(), sortOrder: categories.length })
      setNewCategory("")
      load()
    } catch (err) { showToast(err instanceof Error ? err.message : "Could not add the section", "error") }
  }

  async function removeCategory(c: MenuCategory) {
    try { await api.del(`/market/food/categories/${c.id}`); load() }
    catch (err) { showToast(err instanceof Error ? err.message : "Could not remove it", "error") }
  }

  if (error && !items) return <ErrorState title="Couldn't load your menu" description={error} onRetry={load} />
  if (!items) return <SkeletonCard lines={4} />

  return (
    <div className="stack">
      <div className="card stack">
        <h2>Menu sections</h2>
        <div className="food-chips">
          {categories.map((c) => (
            <span key={c.id} className="food-chip">{c.name} <button className="icon-btn" aria-label={`Remove ${c.name}`} onClick={() => removeCategory(c)}>×</button></span>
          ))}
          {categories.length === 0 && <span className="muted">e.g. Breakfast, Kota, Chicken, Beef, Sides, Drinks</span>}
        </div>
        <div className="row wrap">
          <input value={newCategory} onChange={(e) => setNewCategory(e.target.value)} placeholder="New section" />
          <Button size="sm" onClick={addCategory} disabled={!newCategory.trim()}>Add section</Button>
        </div>
      </div>

      <div className="row between">
        <h2>Dishes</h2>
        <Button onClick={() => setAdding(true)}>+ Add dish</Button>
      </div>
      {items.length === 0 ? (
        <EmptyState title="Your menu is empty" description="Add your first dish with a name and a price. You can add details later."
          action={<Button onClick={() => setAdding(true)}>+ Add dish</Button>} />
      ) : items.map((p) => (
        <div key={p.id} className="card row between">
          <div>
            <strong>{p.name}</strong>
            <div className="muted">{formatMoney(p.price, p.currency)}</div>
          </div>
          <div className="row wrap">
            <Badge tone={p.status === "ACTIVE" ? "success" : p.status === "OUT_OF_STOCK" ? "failed" : "pending"}>
              {p.status === "ACTIVE" ? "On the menu" : p.status === "OUT_OF_STOCK" ? "Sold out" : "Draft"}
            </Badge>
            {p.status === "ACTIVE" ? (
              <Button size="sm" variant="secondary" onClick={() => setStatus(p, "OUT_OF_STOCK")}>Mark sold out</Button>
            ) : (
              <Button size="sm" onClick={() => setStatus(p, "ACTIVE")}>{p.status === "DRAFT" ? "Put on menu" : "Back on the menu"}</Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setDetails(p)}>Details &amp; extras</Button>
            <Button size="sm" variant="ghost" onClick={() => setEditing(p)}>Name &amp; price</Button>
          </div>
        </div>
      ))}

      {adding && <ProductFormModal onClose={() => setAdding(false)} onSaved={() => { setAdding(false); load() }} />}
      {editing && <ProductFormModal product={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load() }} />}
      {details && <FoodItemEditor product={details} categories={categories} onClose={() => setDetails(null)} onChanged={load} />}
    </div>
  )
}
