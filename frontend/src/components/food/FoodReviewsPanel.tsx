import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import { formatDate } from "../../lib/format"
import type { Page, Review } from "../../lib/types"
import type { ReviewSummary } from "../../lib/foodTypes"
import { StarRating } from "../market/StarRating"

/**
 * PULA reviews for a restaurant or a dish. Every review shown here comes from a completed PULA
 * order and is labelled so. External reviews (Google, Facebook, other apps) are never mixed in.
 */
export function FoodReviewsPanel({ targetType, targetId }: { targetType: "BUSINESS" | "PRODUCT"; targetId: string }) {
  const [summary, setSummary] = useState<ReviewSummary | null>(null)
  const [reviews, setReviews] = useState<Review[] | null>(null)

  useEffect(() => {
    let live = true
    api.get<ReviewSummary>(`/reviews/summary?targetType=${targetType}&targetId=${targetId}`)
      .then((s) => live && setSummary(s)).catch(() => live && setSummary(null))
    api.get<Page<Review>>(`/reviews?targetType=${targetType}&targetId=${targetId}&size=5`)
      .then((p) => live && setReviews(p.content)).catch(() => live && setReviews([]))
    return () => { live = false }
  }, [targetType, targetId])

  if (!summary || !reviews) return null
  if (summary.overallCount === 0) {
    return <p className="muted">No PULA reviews yet. Reviews appear after customers complete an order.</p>
  }
  const rated = summary.foodDimensions.filter((d) => d.average != null && d.count > 0)
  const delivery = summary.deliveryExperience.average != null && summary.deliveryExperience.count > 0
    ? summary.deliveryExperience : null

  return (
    <div className="stack">
      <div className="row between">
        <div>
          <strong>PULA reviews</strong> <StarRating value={summary.overallAverage} count={summary.overallCount} />
        </div>
        <span className="muted">{summary.verifiedOrderCount} verified PULA order{summary.verifiedOrderCount !== 1 ? "s" : ""}</span>
      </div>
      {rated.length > 0 && (
        <div className="food-facts">
          {rated.map((d) => (
            <div key={d.dimension}><strong>{d.dimension}:</strong> {d.average?.toFixed(1)} / 5 ({d.count})</div>
          ))}
        </div>
      )}
      {delivery && (
        <p className="muted">
          Delivery experience (about the delivery, not the food): {delivery.average?.toFixed(1)} / 5 ({delivery.count})
        </p>
      )}
      <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }}>
        {reviews.map((r) => (
          <li key={r.id} className="card">
            <div className="row between">
              <strong>{r.authorName}</strong>
              <span className="muted">{formatDate(r.createdAt)}</span>
            </div>
            <StarRating value={r.rating} />
            {r.text && <p>{r.text}</p>}
            <span className="muted">{r.verifiedOrder === false ? "Not verified" : "Verified PULA order"}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
