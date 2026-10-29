import { useEffect, useState } from "react"
import { Link, useParams, useSearchParams } from "react-router-dom"
import { Store } from "lucide-react"
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

    setError(null)

    api
      .get<RestaurantDetail>(`/market/restaurants/${id}`)
      .then(setRestaurant)
      .catch((err) =>
        setError(
          err instanceof Error
            ? err.message
            : "Could not load this restaurant",
        ),
      )
  }, [id])

  useEffect(() => {
    if (!id) return

    setMenu(null)

    api
      .get<Menu>(
        `/market/restaurants/${id}/menu${
          branchId ? `?branchId=${encodeURIComponent(branchId)}` : ""
        }`,
      )
      .then(setMenu)
      .catch((err) =>
        setError(
          err instanceof Error ? err.message : "Could not load the menu",
        ),
      )
  }, [id, branchId])

  async function add(
    item: MenuItem,
    modifierIds: string[],
    quantity: number,
  ) {
    await addToCart(item.id, quantity, modifierIds)
    showToast(`${item.name} added to your cart`, "success")
  }

  async function quickAdd(item: MenuItem) {
    setAddingId(item.id)

    try {
      await add(item, [], 1)
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : "Could not add to cart",
        "error",
      )
    } finally {
      setAddingId(null)
    }
  }

  if (error && !restaurant) {
    return (
      <div className="page">
        <ErrorState
          title="We couldn't load this restaurant"
          description={error}
        />
      </div>
    )
  }

  if (!restaurant) {
    return (
      <div className="page">
        <SkeletonCard lines={5} />
      </div>
    )
  }

  const branch = restaurant.branches.find((item) => item.id === branchId)

  const options =
    branch && branch.fulfilmentOptions.length > 0
      ? branch.fulfilmentOptions
      : restaurant.fulfilmentOptions

  const deliveryFee = branch?.deliveryFee ?? restaurant.deliveryFee
  const minimumOrder = branch?.minimumOrder ?? restaurant.minimumOrder

  const operatingHours =
    branch?.operatingHours.length
      ? branch.operatingHours
      : restaurant.operatingHours

  return (
    <div className="page stack">
      <PageHeader
        eyebrow="PULA Food"
        title={restaurant.name}
        subtitle={restaurant.description ?? undefined}
        actions={
          <Link to="/cart" className="btn btn-secondary">
            Cart{cartCount > 0 ? ` (${cartCount})` : ""}
          </Link>
        }
      />

      <div className="card stack">
        <div
          className="row wrap"
          style={{ alignItems: "center", gap: "1rem" }}
        >
          {restaurant.logoUrl ? (
            <img
              src={restaurant.logoUrl}
              alt={`${restaurant.name} logo`}
              style={{
                width: 112,
                height: 112,
                objectFit: "contain",
                flexShrink: 0,
                borderRadius: 12,
                border: "1px solid var(--border-color, #ddd)",
                padding: 8,
                background: "var(--card-bg, #fff)",
              }}
            />
          ) : (
            <div
              aria-hidden="true"
              style={{
                width: 112,
                height: 112,
                display: "grid",
                placeItems: "center",
                flexShrink: 0,
                borderRadius: 12,
                border: "1px solid var(--border-color, #ddd)",
              }}
            >
              <Store size={36} />
            </div>
          )}

          <div className="stack" style={{ gap: 8 }}>
            {!restaurant.open && (
              <div>
                <Badge tone="failed">
                  {restaurant.closedReason ?? "Closed"}
                </Badge>
              </div>
            )}

            <strong>
              {ratingLabel(
                0,
                0,
                restaurant.rating,
                restaurant.ratingCount,
              )}
            </strong>

            {restaurant.location && (
              <span className="muted">{restaurant.location}</span>
            )}
          </div>
        </div>

        <div className="food-facts">
          <div>
            <strong>Get it by:</strong>{" "}
            {options
              .map((option) => FULFILMENT_OPTION_LABELS[option] ?? option)
              .join(" · ") || "Ask the restaurant"}
          </div>

          {deliveryFee != null && (
            <div>
              <strong>PULA delivery fee:</strong>{" "}
              {formatMoney(deliveryFee, "ZAR")}
            </div>
          )}

          {minimumOrder != null && (
            <div>
              <strong>Minimum order:</strong>{" "}
              {formatMoney(minimumOrder, "ZAR")}
            </div>
          )}

          {branch?.prepMinutes != null && (
            <div>
              <strong>Preparation:</strong> about {branch.prepMinutes} min
            </div>
          )}

          {operatingHours.length > 0 && (
            <div>
              <strong>Hours:</strong> {operatingHours.join(" · ")}
            </div>
          )}

          {restaurant.contactPhone && (
            <div>
              <strong>Phone:</strong> {restaurant.contactPhone}
            </div>
          )}
        </div>

        {restaurant.branches.length > 0 && (
          <label className="field">
            Branch
            <select
              value={branchId}
              onChange={(event) =>
                setParams(
                  event.target.value
                    ? { branch: event.target.value }
                    : {},
                )
              }
            >
              <option value="">Choose a branch…</option>

              {restaurant.branches.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                  {item.area ? ` · ${item.area}` : ""}
                  {item.open ? "" : " (closed)"}
                </option>
              ))}
            </select>
          </label>
        )}

        {restaurant.branches.length > 0 && !branch && (
          <p className="muted">
            Menu prices can differ by branch. Choose a branch to see its prices
            and what's available there.
          </p>
        )}
      </div>

      {!menu ? (
        <SkeletonCard lines={6} />
      ) : (
        menu.categories.map((category) => (
          <section key={category.id ?? "other"} className="card stack">
            <h2>{category.name}</h2>

            {category.items.map((item) => (
              <div key={item.id} className="food-menu-item">
                {item.imageUrl && (
                  <img
                    className="media-thumb"
                    src={item.imageUrl}
                    alt=""
                    loading="lazy"
                  />
                )}

                <div className="stack" style={{ gap: 4 }}>
                  <strong>{item.name}</strong>

                  {item.description && (
                    <span className="muted">{item.description}</span>
                  )}

                  <span className="muted">
                    {[
                      item.portionDescription ||
                        (item.portionSize
                          ? `${prettyToken(item.portionSize)} portion`
                          : null),
                      item.sides.length
                        ? `with ${item.sides.map(prettyToken).join(", ")}`
                        : null,
                      item.spiceLevel
                        ? `spice: ${prettyToken(item.spiceLevel)}`
                        : null,
                      item.prepMinutes != null
                        ? `~${item.prepMinutes} min`
                        : null,
                      item.dietaryTags.length
                        ? item.dietaryTags.map(prettyToken).join(", ")
                        : null,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>

                  {item.allergens.length > 0 && (
                    <span className="muted">
                      Contains: {item.allergens.map(prettyToken).join(", ")}
                    </span>
                  )}

                  <span className="muted">
                    {ratingLabel(item.rating, item.ratingCount, 0, 0)}
                  </span>
                </div>

                <div
                  className="stack"
                  style={{ alignItems: "flex-end", gap: 6 }}
                >
                  <strong>{formatMoney(item.price, item.currency)}</strong>

                  {!item.available ? (
                    <Badge tone="failed">
                      {item.unavailableReason ?? "Unavailable"}
                    </Badge>
                  ) : item.modifierGroups.length > 0 ? (
                    <Button
                      size="sm"
                      onClick={() => setChoosing(item)}
                    >
                      Choose &amp; add
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      onClick={() => quickAdd(item)}
                      loading={addingId === item.id}
                    >
                      Add
                    </Button>
                  )}

                  <Link
                    to={
                      item.canonicalProductId
                        ? `/market/food/compare/canonical/${item.canonicalProductId}`
                        : `/market/food/compare/item/${item.id}`
                    }
                    className="muted"
                  >
                    Compare
                  </Link>
                </div>
              </div>
            ))}
          </section>
        ))
      )}

      <section className="card stack">
        <h2>Reviews</h2>
        <FoodReviewsPanel
          targetType="BUSINESS"
          targetId={restaurant.id}
        />
      </section>

      {choosing && (
        <ModifierSheet
          item={choosing}
          onClose={() => setChoosing(null)}
          onAdd={(modifierIds, quantity) =>
            add(choosing, modifierIds, quantity)
          }
        />
      )}
    </div>
  )
}
