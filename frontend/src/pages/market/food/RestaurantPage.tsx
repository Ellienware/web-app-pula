import { useEffect, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { api } from "../../../lib/api"
import { formatMoney } from "../../../lib/format"
import type { Menu, MenuItem, RestaurantDetail } from "../../../lib/foodTypes"
import { prettyToken, ratingLabel } from "../../../lib/foodTypes"
import { FULFILMENT_OPTION_LABELS } from "../../../lib/types"
import { useMarket } from "../../../context/MarketContext"
import { useToast } from "../../../context/ToastContext"
import { PageHeader } from "../../../components/ui/PageHeader"
import { Button } from "../../../components/ui/Button"
import { Badge } from "../../../components/ui/Badge"
import { ErrorState } from "../../../components/ui/StatePanel"
import { SkeletonCard } from "../../../components/ui/Skeleton"
import { ModifierSheet } from "../../../components/food/ModifierSheet"
import { FoodReviewsPanel } from "../../../components/food/FoodReviewsPanel"

export function RestaurantPage() {
  const { id } = useParams<{ id: string }>()
  const [params, setParams] = useSearchParams()
  const branchId = params.get("branch") ?? ""
  const { addToCart, cartCount } = useMarket()
  const { showToast } = useToast()
  const [restaurant, setRestaurant] = useState<RestaurantDetail | null>(null)
  const [menu, setMenu] = useState<Menu | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [choosing, setChoosing] = useState<MenuItem | null>(null)
  const [addingId, setAddingId] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    api.get<RestaurantDetail>(`/market/restaurants/${id}`).then(setRestaurant)
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load this restaurant"))
  }, [id])

  useEffect(() => {
    if (!id) return
    setMenu(null)
    api.get<Menu>(`/market/restaurants/${id}/menu${branchId ? `?branchId=${branchId}` : ""}`).then(setMenu)
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load the menu"))
  }, [id, branchId])

  async function add(item: MenuItem, modifierIds: string[], quantity: number) {
    await addToCart(item.id, quantity, modifierIds)
    showToast(`${item.name} added to your cart`, "success")
  }

  async function quickAdd(item: MenuItem) {
    setAddingId(item.id)
    try {
      await add(item, [], 1)
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not add to cart", "error")
    } finally {
      setAddingId(null)
    }
  }

  if (error && !restaurant) {
    return <div className="page"><ErrorState title="We couldn't load this restaurant" description={error} /></div>
  }
  if (!restaurant) return <div className="page"><SkeletonCard lines={5} /></div>

  const branch = restaurant.branches.find((b) => b.id === branchId)
  const options = branch && branch.fulfilmentOptions.length > 0 ? branch.fulfilmentOptions : restaurant.fulfilmentOptions

  return (
    <div className="page stack">
      <PageHeader
        eyebrow="PULA Food"
        title={restaurant.name}
        subtitle={restaurant.description ?? undefined}
        actions={<Link to="/cart" className="btn btn-secondary">Cart{cartCount > 0 ? ` (${cartCount})` : ""}</Link>}
      />

      <div className="card stack">
        <div className="row wrap">
          {!restaurant.open && <Badge tone="failed">{restaurant.closedReason ?? "Closed"}</Badge>}
          <span>{ratingLabel(0, 0, restaurant.rating, restaurant.ratingCount)}</span>
          {restaurant.location && <span className="muted">{restaurant.location}</span>}
        </div>
        <div className="food-facts">
          <div><strong>Get it by:</strong> {options.map((o) => FULFILMENT_OPTION_LABELS[o] ?? o).join(" · ") || "Ask the restaurant"}</div>
          {(branch?.deliveryFee ?? restaurant.deliveryFee) != null && (
            <div><strong>PULA delivery fee:</strong> {formatMoney((branch?.deliveryFee ?? restaurant.deliveryFee) as number, "ZAR")}</div>
          )}
          {(branch?.minimumOrder ?? restaurant.minimumOrder) != null && (
            <div><strong>Minimum order:</strong> {formatMoney((branch?.minimumOrder ?? restaurant.minimumOrder) as number, "ZAR")}</div>
          )}
          {branch?.prepMinutes != null && <div><strong>Preparation:</strong> about {branch.prepMinutes} min</div>}
          {(branch?.operatingHours.length ? branch.operatingHours : restaurant.operatingHours).length > 0 && (
            <div><strong>Hours:</strong> {(branch?.operatingHours.length ? branch.operatingHours : restaurant.operatingHours).join(" · ")}</div>
          )}
          {restaurant.contactPhone && <div><strong>Phone:</strong> {restaurant.contactPhone}</div>}
        </div>
        {restaurant.branches.length > 0 && (
          <label>Branch
            <select value={branchId} onChange={(e) => setParams(e.target.value ? { branch: e.target.value } : {})}>
              <option value="">Choose a branch…</option>
              {restaurant.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}{b.area ? ` · ${b.area}` : ""}{b.open ? "" : " (closed)"}
                </option>
              ))}
            </select>
          </label>
        )}
        {restaurant.branches.length > 0 && !branch && (
          <p className="muted">Menu prices can differ by branch. Choose a branch to see its prices and what's available there.</p>
        )}
      </div>

      {!menu ? <SkeletonCard lines={6} /> : (
        menu.categories.map((cat) => (
          <section key={cat.id ?? "other"} className="card stack">
            <h2>{cat.name}</h2>
            {cat.items.map((item) => (
              <div key={item.id} className="food-menu-item">
                {item.imageUrl && <img className="media-thumb" src={item.imageUrl} alt="" />}
                <div className="stack" style={{ gap: 4 }}>
                  <strong>{item.name}</strong>
                  {item.description && <span className="muted">{item.description}</span>}
                  <span className="muted">
                    {[item.portionDescription || (item.portionSize ? `${prettyToken(item.portionSize)} portion` : null),
                      item.sides.length ? `with ${item.sides.map(prettyToken).join(", ")}` : null,
                      item.spiceLevel ? `spice: ${prettyToken(item.spiceLevel)}` : null,
                      item.prepMinutes != null ? `~${item.prepMinutes} min` : null,
                      item.dietaryTags.length ? item.dietaryTags.map(prettyToken).join(", ") : null]
                      .filter(Boolean).join(" · ")}
                  </span>
                  {item.allergens.length > 0 && <span className="muted">Contains: {item.allergens.map(prettyToken).join(", ")}</span>}
                  <span className="muted">{ratingLabel(item.rating, item.ratingCount, 0, 0)}</span>
                </div>
                <div className="stack" style={{ alignItems: "flex-end", gap: 6 }}>
                  <strong>{formatMoney(item.price, item.currency)}</strong>
                  {!item.available ? (
                    <Badge tone="failed">{item.unavailableReason ?? "Unavailable"}</Badge>
                  ) : item.modifierGroups.length > 0 ? (
                    <Button size="sm" onClick={() => setChoosing(item)}>Choose &amp; add</Button>
                  ) : (
                    <Button size="sm" onClick={() => quickAdd(item)} loading={addingId === item.id}>Add</Button>
                  )}
                  <Link to={item.canonicalProductId ? `/market/food/compare/canonical/${item.canonicalProductId}` : `/market/food/compare/item/${item.id}`}
                    className="muted">Compare</Link>
                </div>
              </div>
            ))}
          </section>
        ))
      )}

      <section className="card stack">
        <h2>Reviews</h2>
        <FoodReviewsPanel targetType="BUSINESS" targetId={restaurant.id} />
      </section>

      {choosing && (
        <ModifierSheet item={choosing} onClose={() => setChoosing(null)}
          onAdd={(modifierIds, quantity) => add(choosing, modifierIds, quantity)} />
      )}
    </div>
  )
}
