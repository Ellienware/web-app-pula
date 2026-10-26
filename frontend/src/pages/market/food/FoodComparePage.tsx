import { useCallback, useEffect, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { MapPin } from "lucide-react"
import { api } from "../../../lib/api"
import type { FoodComparison, FoodOffer, FoodSort, FulfilmentFilter } from "../../../lib/foodTypes"
import { useMarket } from "../../../context/MarketContext"
import { useToast } from "../../../context/ToastContext"
import { PageHeader } from "../../../components/ui/PageHeader"
import { Button } from "../../../components/ui/Button"
import { EmptyState, ErrorState } from "../../../components/ui/StatePanel"
import { SkeletonGrid } from "../../../components/ui/Skeleton"
import { FoodOfferCard } from "../../../components/food/FoodOfferCard"
import { qs, useFoodLocation } from "../../../components/food/useFoodLocation"

/**
 * Side-by-side comparison of one meal across restaurants, grouped by how closely each matches.
 * There is no automatic "winner": the sort is the customer's own choice and every card lists the
 * differences (portion, sides, delivery, preparation time, reviews) so they can decide.
 */
export function FoodComparePage({ mode }: { mode: "canonical" | "item" }) {
  const { id } = useParams<{ id: string }>()
  const { addToCart } = useMarket()
  const { showToast } = useToast()
  const { location, locating, locationError, requestLocation, clearLocation } = useFoodLocation()
  const [fulfilment, setFulfilment] = useState<"" | FulfilmentFilter>("")
  const [maxPrice, setMaxPrice] = useState("")
  const [sort, setSort] = useState<FoodSort>("PRICE")
  const [data, setData] = useState<FoodComparison | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [addingId, setAddingId] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    setError(null)
    try {
      const path = mode === "canonical" ? `/market/food/canonical/${id}/compare` : `/market/food/items/${id}/compare`
      setData(await api.get<FoodComparison>(`${path}${qs({
        fulfilment, maxPrice, sort, lat: location?.lat, lng: location?.lng,
      })}`))
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load the comparison")
    }
  }, [id, mode, fulfilment, maxPrice, sort, location])

  useEffect(() => { load() }, [load])

  async function add(offer: FoodOffer) {
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

  const sections: { title: string; blurb: string; offers: FoodOffer[] }[] = data ? [
    { title: "Exact matches", blurb: "Same dish, protein, starch, sides and portion.", offers: data.exactMatches },
    { title: "Similar meals", blurb: "Close, but something differs. The differences are listed on each card.", offers: data.similarMeals },
    { title: "Alternatives", blurb: "A different style of dish with the same protein, or a different starch. Not the same meal.", offers: data.alternatives },
  ] : []

  return (
    <div className="page stack">
      <PageHeader eyebrow="Compare" title={data?.referenceName ?? "Compare meals"}
        actions={<Link to="/market/food" className="btn btn-ghost">Back to food</Link>} />

      <div className="card stack">
        <div className="food-filters">
          <label>Get it by
            <select value={fulfilment} onChange={(e) => setFulfilment(e.target.value as "" | FulfilmentFilter)}>
              <option value="">Collection or delivery</option>
              <option value="COLLECTION">Collection</option>
              <option value="DELIVERY">PULA delivery</option>
            </select>
          </label>
          <label>Max price (R)
            <input type="number" min={0} value={maxPrice} onChange={(e) => setMaxPrice(e.target.value)} />
          </label>
          <label>Sort by
            <select value={sort} onChange={(e) => setSort(e.target.value as FoodSort)}>
              <option value="PRICE">Price</option>
              {location && <option value="DISTANCE">Distance</option>}
              <option value="RATING">Rating</option>
              <option value="NAME">Name</option>
            </select>
          </label>
        </div>
        <div className="row wrap">
          {location ? (
            <Button variant="ghost" onClick={clearLocation}><MapPin size={14} aria-hidden="true" /> Stop using my location</Button>
          ) : (
            <Button variant="secondary" onClick={requestLocation} loading={locating}><MapPin size={14} aria-hidden="true" /> Use my location</Button>
          )}
          <span className="muted">Used only for distances. Not saved.</span>
        </div>
        {locationError && <p className="error-text" role="alert">{locationError}</p>}
      </div>

      {data && <div className="food-note">{data.note}</div>}
      {data && data.excludedForUnknownDistance > 0 && (
        <div className="food-note">{data.excludedForUnknownDistance} result(s) left out because their location isn't shared.</div>
      )}
      {error && <ErrorState title="Comparison failed" description={error} onRetry={load} />}
      {!data && !error && <SkeletonGrid />}

      {data && sections.every((s) => s.offers.length === 0) && (
        <EmptyState title="Nothing to compare right now" description="No available restaurants match this meal yet. Try removing a filter." />
      )}

      {sections.filter((s) => s.offers.length > 0).map((s) => (
        <section key={s.title} className="stack">
          <h2>{s.title} <span className="muted">({s.offers.length})</span></h2>
          <p className="muted">{s.blurb}</p>
          <div className="food-compare-cols">
            {s.offers.map((o) => (
              <FoodOfferCard key={`${o.productId}-${o.branchId ?? "main"}`} offer={o} showCompare={false}
                onAdd={add} adding={addingId === o.productId} />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}
