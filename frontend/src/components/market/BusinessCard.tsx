import { Link } from "react-router-dom"
import type { PublicBusiness } from "../../lib/types"
import { BUSINESS_TYPE_LABELS } from "../../lib/types"
import { StarRating } from "./StarRating"

const DELIVERY_BADGE: Partial<Record<string, string>> = {
  SELLER_DELIVERY: "Delivers",
  MOMORIDE_DELIVERY: "PULA Ride delivery",
  CUSTOMER_PICKUP: "Pickup",
}

export function BusinessCard({ business }: { business: PublicBusiness }) {
  const deliveryOptions = business.fulfilmentOptions
    .filter((o) => DELIVERY_BADGE[o])
    .slice(0, 2) // cap at 2 so the card stays compact

  return (
    <Link to={`/market/businesses/${business.id}`} className="business-card">
      <div className="business-card-logo" aria-hidden="true">
        {business.logoUrl ? <img src={business.logoUrl} alt="" /> : <span>{business.name[0]?.toUpperCase()}</span>}
      </div>
      <div>
        <h3 className="business-card-name">{business.name}</h3>
        <div className="row wrap" style={{ gap: 4, marginBottom: 4 }}>
          <span className="badge">{business.category}</span>
          <span className="badge badge-muted">{BUSINESS_TYPE_LABELS[business.businessType]}</span>
          {deliveryOptions.map((opt) => (
            <span key={opt} className="badge badge-success">{DELIVERY_BADGE[opt]}</span>
          ))}
        </div>
        {business.location && <span className="muted business-card-location">📍 {business.location}</span>}
        {business.minimumOrderAmount != null && business.minimumOrderAmount > 0 && (
          <span className="muted business-card-location"> · Min R{business.minimumOrderAmount}</span>
        )}
        {business.ratingCount > 0 && (
          <div className="business-card-rating">
            <StarRating value={business.ratingAverage} count={business.ratingCount} />
          </div>
        )}
      </div>
    </Link>
  )
}
