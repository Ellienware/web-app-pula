import { Link } from "react-router-dom"
import { formatMoney } from "../../lib/format"
import { MATCH_LEVEL_LABELS, prettyToken, ratingLabel, type FoodOffer } from "../../lib/foodTypes"
import { Badge } from "../ui/Badge"
import { Button } from "../ui/Button"

/**
 * One orderable meal at one place. Shows the facts a customer needs to compare (price, portion,
 * sides, rating, delivery/collection, preparation time, distance, availability) and, when the
 * meal was matched to a search, WHY it matches and what differs. There is deliberately no
 * "best" or "cheapest" badge: PULA gives comparable details and the customer chooses.
 */
export function FoodOfferCard({
  offer,
  onAdd,
  adding,
  showCompare = true,
}: {
  offer: FoodOffer
  /** Called only for offers that can be added directly (no extras to choose). */
  onAdd?: (offer: FoodOffer) => void
  adding?: boolean
  showCompare?: boolean
}) {
  const where = [offer.branchName, offer.area].filter(Boolean).join(" · ")
  const menuLink = `/market/restaurants/${offer.businessId}${offer.branchId ? `?branch=${offer.branchId}` : ""}`
  const compareLink = offer.canonicalProductId
    ? `/market/food/compare/canonical/${offer.canonicalProductId}`
    : `/market/food/compare/item/${offer.productId}`

  return (
    <div className={`card food-card${offer.available ? "" : " is-unavailable"}`}>
      {offer.imageUrl && <img className="media-thumb" src={offer.imageUrl} alt="" />}
      <div className="row between">
        <div>
          <strong>{offer.name}</strong>
          <div className="muted">
            <Link to={menuLink}>{offer.businessName}</Link>
            {where && <> · {where}</>}
          </div>
        </div>
        <div className="food-card-price">{formatMoney(offer.price, offer.currency)}</div>
      </div>

      {offer.matchLevel && (
        <div className={`food-match${offer.matchLevel === "EXACT_MATCH" ? " is-exact" : ""}${offer.matchUncertain ? " is-uncertain" : ""}`}>
          <Badge tone={offer.matchLevel === "EXACT_MATCH" ? "success" : "neutral"}>{MATCH_LEVEL_LABELS[offer.matchLevel]}</Badge>{" "}
          {offer.matchReasons.length > 0 && <span>{offer.matchReasons.join(". ")}</span>}
          {offer.matchUncertain && <div className="muted">Some details aren't stated by the restaurant, so check before ordering.</div>}
        </div>
      )}

      <div className="food-facts">
        <div>
          <strong>Portion:</strong>{" "}
          {offer.portionDescription || (offer.portionSize ? `${prettyToken(offer.portionSize)} portion` : "Not stated")}
        </div>
        <div>
          <strong>Includes:</strong>{" "}
          {[offer.protein, offer.starch, ...offer.sides].filter(Boolean).map((t) => prettyToken(t)).join(", ") || "Not stated"}
          {offer.hasExtras && " · extras available"}
        </div>
        <div>
          <strong>Reviews:</strong>{" "}
          {ratingLabel(offer.itemRating, offer.itemRatingCount, offer.restaurantRating, offer.restaurantRatingCount)}
          {(offer.itemRatingCount > 0 || offer.restaurantRatingCount > 0) && " · verified PULA orders"}
        </div>
        <div>
          <strong>Get it:</strong>{" "}
          {[
            offer.collectionAvailable ? "Collection" : null,
            offer.deliveryAvailable ? `PULA delivery ${offer.deliveryFee != null ? formatMoney(offer.deliveryFee, offer.currency) : ""}`.trim() : null,
          ].filter(Boolean).join(" · ") || "Ask the restaurant"}
        </div>
        <div>
          {offer.prepMinutes != null && <>About {offer.prepMinutes} min to prepare</>}
          {offer.prepMinutes != null && offer.distanceKm != null && " · "}
          {offer.distanceKm != null && <>{offer.distanceKm} km away</>}
        </div>
        {offer.allergens.length > 0 && <div><strong>Allergens:</strong> {offer.allergens.map(prettyToken).join(", ")}</div>}
      </div>

      {!offer.available && <Badge tone="failed">{offer.unavailableReason ?? "Unavailable"}</Badge>}

      <div className="row wrap">
        {offer.available && !offer.hasExtras && onAdd && (
          <Button size="sm" onClick={() => onAdd(offer)} loading={adding}>Add to cart</Button>
        )}
        <Link to={menuLink} className="btn btn-secondary btn-sm">
          {offer.hasExtras ? "Choose extras & order" : "View menu"}
        </Link>
        {showCompare && <Link to={compareLink} className="btn btn-ghost btn-sm">Compare</Link>}
      </div>
    </div>
  )
}
