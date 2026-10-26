import { useCallback, useEffect, useMemo, useState } from "react"
import { Link } from "react-router-dom"
import { MapPin } from "lucide-react"
import { api } from "../../../lib/api"
import { formatMoney } from "../../../lib/format"
import type {
  FoodOffer, FoodSearchResponse, FoodSort, FulfilmentFilter, RestaurantList, RestaurantSummary,
} from "../../../lib/foodTypes"
import { MATCH_LEVEL_LABELS, ratingLabel } from "../../../lib/foodTypes"
import { useMarket } from "../../../context/MarketContext"
import { useToast } from "../../../context/ToastContext"
import { PageHeader } from "../../../components/ui/PageHeader"
import { Button } from "../../../components/ui/Button"
import { Badge } from "../../../components/ui/Badge"
import { EmptyState, ErrorState } from "../../../components/ui/StatePanel"
import { SkeletonGrid } from "../../../components/ui/Skeleton"
import { FoodOfferCard } from "../../../components/food/FoodOfferCard"
import { qs, useFoodLocation } from "../../../components/food/useFoodLocation"

// Quick searches only. They fill the search box; they are not restaurants or brands.
const QUICK_SEARCHES = ["Kota", "Chicken", "Beef and pap", "Burger", "Breakfast", "Traditional meals", "Chips"]

type Tab = "meals" | "restaurants"

export function FoodPage() {
  const { addToCart } = useMarket()
  const { showToast } = useToast()
  const { location, locating, locationError, requestLocation, clearLocation } = useFoodLocation()
  const [tab, setTab] = useState<Tab>("meals")
  const [q, setQ] = useState("")
  const [maxPrice, setMaxPrice] = useState("")
  const [minRating, setMinRating] = useState("")
  const [fulfilment, setFulfilment] = useState<"" | FulfilmentFilter>("")
  const [area, setArea] = useState("")
  const [maxDistanceKm, setMaxDistanceKm] = useState("")
  const [sort, setSort] = useState<FoodSort>("RELEVANCE")
  const [meals, setMeals] = useState<FoodSearchResponse | null>(null)
  const [restaurants, setRestaurants] = useState<RestaurantList | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [addingId, setAddingId] = useState<string | null>(null)

  const run = useCallback(async () => {
    setLoading(true)
    setError(null)
    const common = {
      area: area.trim(),
      minRating,
      fulfilment,
      lat: location?.lat,
      lng: location?.lng,
      maxDistanceKm: location ? maxDistanceKm : "",
      sort: sort === "RELEVANCE" && tab === "restaurants" ? "" : sort,
    }
    try {
      if (tab === "meals") {
        setMeals(await api.get<FoodSearchResponse>(`/market/food/search${qs({ ...common, q: q.trim(), maxPrice, size: 30 })}`))
      } else {
        setRestaurants(await api.get<RestaurantList>(`/market/restaurants${qs({ ...common, q: q.trim(), size: 30 })}`))
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Search failed")
    } finally {
      setLoading(false)
    }
  }, [tab, q, maxPrice, minRating, fulfilment, area, maxDistanceKm, sort, location])

  // Run when the tab, the location or the sort changes; typed filters wait for the Search button.
  useEffect(() => { run() }, [tab, location, sort]) // eslint-disable-line react-hooks/exhaustive-deps

  async function addDirect(offer: FoodOffer) {
    setAddingId(offer.productId)
    try {
      await addToCart(offer.productId, 1)
      showToast(`${offer.name} added to your cart`, "success")
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not add to cart", "error")
    } finally {
      setAddingId(null)
    }
  }

  const groups = useMemo(() => {
    if (!meals) return []
    const withLevels = meals.offers.some((o) => o.matchLevel)
    if (!withLevels) return [{ key: "all", title: "", offers: meals.offers }]
    const pick = (level: FoodOffer["matchLevel"]) => meals.offers.filter((o) => o.matchLevel === level)
    return [
      { key: "exact", title: MATCH_LEVEL_LABELS.EXACT_MATCH, offers: pick("EXACT_MATCH") },
      { key: "similar", title: MATCH_LEVEL_LABELS.SIMILAR_MEAL, offers: pick("SIMILAR_MEAL") },
      { key: "alt", title: MATCH_LEVEL_LABELS.ALTERNATIVE, offers: pick("ALTERNATIVE") },
      { key: "text", title: "Other matches", offers: meals.offers.filter((o) => !o.matchLevel) },
    ].filter((g) => g.offers.length > 0)
  }, [meals])

  return (
    <div className="page stack">
      <PageHeader
        eyebrow="PULA Market · Food"
        title="Find food you can trust"
        subtitle="Compare meals from restaurants, kasi kitchens and home cooks. We show the details; you choose."
        actions={<Link to="/cart" className="btn btn-secondary">Cart</Link>}
      />

      <form className="card stack" onSubmit={(e) => { e.preventDefault(); run() }}>
        <div className="food-filters">
          <label>Meal or restaurant
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="beef stew and pap, kota…" />
          </label>
          {tab === "meals" && (
            <label>Max price (R)
              <input type="number" min={0} value={maxPrice} onChange={(e) => setMaxPrice(e.target.value)} />
            </label>
          )}
          <label>Area
            <input value={area} onChange={(e) => setArea(e.target.value)} placeholder="e.g. Soweto" />
          </label>
          <label>Get it by
            <select value={fulfilment} onChange={(e) => setFulfilment(e.target.value as "" | FulfilmentFilter)}>
              <option value="">Collection or delivery</option>
              <option value="COLLECTION">Collection</option>
              <option value="DELIVERY">PULA delivery</option>
            </select>
          </label>
          <label>Min rating
            <select value={minRating} onChange={(e) => setMinRating(e.target.value)}>
              <option value="">Any</option>
              <option value="3">3+ stars</option>
              <option value="4">4+ stars</option>
              <option value="4.5">4.5+ stars</option>
            </select>
          </label>
          <label>Sort by
            <select value={sort} onChange={(e) => setSort(e.target.value as FoodSort)}>
              <option value="RELEVANCE">{tab === "meals" ? "Closest match" : "Name"}</option>
              {location && <option value="DISTANCE">Distance</option>}
              {tab === "meals" && <option value="PRICE">Price</option>}
              <option value="RATING">Rating</option>
              <option value="NAME">Name</option>
            </select>
          </label>
          {location && (
            <label>Within (km)
              <input type="number" min={1} value={maxDistanceKm} onChange={(e) => setMaxDistanceKm(e.target.value)} />
            </label>
          )}
        </div>
        <div className="food-chips">
          {QUICK_SEARCHES.map((s) => (
            <button type="button" key={s} className={`food-chip${q.toLowerCase() === s.toLowerCase() ? " is-active" : ""}`}
              onClick={() => { setQ(s); setTab("meals") }}>{s}</button>
          ))}
        </div>
        <div className="row wrap">
          <Button type="submit" loading={loading}>Search</Button>
          {location ? (
            <Button type="button" variant="ghost" onClick={clearLocation}><MapPin size={14} aria-hidden="true" /> Stop using my location</Button>
          ) : (
            <Button type="button" variant="secondary" onClick={requestLocation} loading={locating}>
              <MapPin size={14} aria-hidden="true" /> Use my location
            </Button>
          )}
        </div>
        <p className="muted">
          Your location is only used on this page to work out distances. PULA doesn't save it or show it to restaurants.
        </p>
        {locationError && <p className="error-text" role="alert">{locationError}</p>}
      </form>

      <div className="food-tabs" role="tablist">
        <button role="tab" className={`food-tab${tab === "meals" ? " is-active" : ""}`} onClick={() => setTab("meals")}>Meals</button>
        <button role="tab" className={`food-tab${tab === "restaurants" ? " is-active" : ""}`} onClick={() => setTab("restaurants")}>Restaurants</button>
      </div>

      {error && <ErrorState title="Search failed" description={error} onRetry={run} />}
      {loading && !meals && !restaurants && <SkeletonGrid />}

      {tab === "meals" && meals && (
        <div className="stack">
          {meals.interpretedAs.length > 0 && (
            <div className="food-note">We read that as: <strong>{meals.interpretedAs.join(", ")}</strong>. Meals are grouped by how closely they match.</div>
          )}
          {meals.excludedForUnknownDistance > 0 && (
            <div className="food-note">
              {meals.excludedForUnknownDistance} result{meals.excludedForUnknownDistance > 1 ? "s were" : " was"} left out because
              the restaurant hasn't shared its location, so we can't tell how far it is.
            </div>
          )}
          {meals.offers.length === 0 && !loading ? (
            <EmptyState title="No meals found" description="Try another meal, a higher price or a different area." />
          ) : (
            groups.map((g) => (
              <section key={g.key} className="stack">
                {g.title && <h2>{g.title} <span className="muted">({g.offers.length})</span></h2>}
                <div className="food-grid">
                  {g.offers.map((o) => (
                    <FoodOfferCard key={`${o.productId}-${o.branchId ?? "main"}`} offer={o}
                      onAdd={addDirect} adding={addingId === o.productId} />
                  ))}
                </div>
              </section>
            ))
          )}
          {meals.total > meals.offers.length && <p className="muted">Showing {meals.offers.length} of {meals.total}. Narrow your search to see more.</p>}
        </div>
      )}

      {tab === "restaurants" && restaurants && (
        <div className="stack">
          {restaurants.excludedForUnknownDistance > 0 && (
            <div className="food-note">{restaurants.excludedForUnknownDistance} restaurant(s) left out because their location isn't shared.</div>
          )}
          {restaurants.restaurants.length === 0 && !loading ? (
            <EmptyState title="No restaurants found" description="Try a different area or clear the filters." />
          ) : (
            <div className="food-grid">
              {restaurants.restaurants.map((r) => <RestaurantCard key={r.id} r={r} />)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function RestaurantCard({ r }: { r: RestaurantSummary }) {
  return (
    <Link to={`/market/restaurants/${r.id}`} className="card card-interactive food-card" style={{ textDecoration: "none", color: "inherit" }}>
      <div className="row between">
        <strong>{r.name}</strong>
        {!r.open && <Badge tone="failed">{r.closedReason ?? "Closed"}</Badge>}
      </div>
      {r.description && <p className="muted">{r.description}</p>}
      <div className="food-facts">
        {r.area && <div>{r.area}{r.branchCount > 1 ? ` · ${r.branchCount} branches` : ""}</div>}
        <div>{ratingLabel(0, 0, r.rating, r.ratingCount)}</div>
        <div>
          {[r.collectionAvailable ? "Collection" : null,
            r.deliveryAvailable ? `PULA delivery from ${r.deliveryFeeFrom != null ? formatMoney(r.deliveryFeeFrom, "ZAR") : "—"}` : null]
            .filter(Boolean).join(" · ") || "Ask the restaurant"}
        </div>
        {r.minimumOrder != null && <div>Minimum order {formatMoney(r.minimumOrder, "ZAR")}</div>}
        {r.nearestDistanceKm != null && <div>{r.nearestDistanceKm} km away</div>}
      </div>
    </Link>
  )
}
