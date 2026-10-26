import { useState } from "react"
import { api } from "../../lib/api"
import type { CreateReviewRequest, Order } from "../../lib/types"
import { useToast } from "../../context/ToastContext"
import { StarRating } from "../market/StarRating"
import { Button } from "../ui/Button"

type Dim = "taste" | "portionSize" | "valueForMoney" | "foodQuality" | "packaging" | "deliveryExperience"
const FOOD_DIMS: { key: Dim; label: string }[] = [
  { key: "taste", label: "Taste" }, { key: "portionSize", label: "Portion size" }, { key: "valueForMoney", label: "Value for money" },
  { key: "foodQuality", label: "Food quality" }, { key: "packaging", label: "Packaging" },
]

/**
 * Review the restaurant and (optionally) each dish after a completed order. Only the customer who
 * completed the order can review it, once per order and item; the server enforces both and marks
 * the review "Verified PULA order". Detailed ratings are optional.
 */
export function FoodReviewForm({ order, delivered, onDone }: { order: Order; delivered: boolean; onDone: () => void }) {
  const { showToast } = useToast()
  const [rating, setRating] = useState(5)
  const [text, setText] = useState("")
  const [dims, setDims] = useState<Partial<Record<Dim, number>>>({})
  const [itemRatings, setItemRatings] = useState<Record<string, number>>({})
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  async function submit() {
    setBusy(true)
    try {
      const base = { orderId: order.id, ...dims }
      const restaurant: CreateReviewRequest = { ...base, targetType: "BUSINESS", targetId: order.businessId, rating, text: text.trim() || null }
      await api.post("/reviews", restaurant)
      for (const [productId, r] of Object.entries(itemRatings)) {
        const item: CreateReviewRequest = { targetType: "PRODUCT", targetId: productId, orderId: order.id, rating: r }
        await api.post("/reviews", item)
      }
      setDone(true)
      showToast("Thanks for your review", "success")
      onDone()
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Could not save your review"
      if (/already reviewed/i.test(msg)) setDone(true)
      showToast(msg, /already reviewed/i.test(msg) ? "default" : "error")
    } finally {
      setBusy(false)
    }
  }

  if (done) return <p className="muted">You've reviewed this order. Thank you!</p>

  const dimList = delivered ? [...FOOD_DIMS, { key: "deliveryExperience" as Dim, label: "Delivery experience" }] : FOOD_DIMS
  return (
    <div className="card stack">
      <h3>How was it?</h3>
      <p className="muted">Your review is shown as a verified PULA order.</p>
      <div className="food-rate-row"><strong>{order.businessName}</strong><StarRating value={rating} onChange={setRating} /></div>
      {order.items.length > 0 && <p className="muted">Rate individual dishes (optional)</p>}
      {order.items.map((i) => (
        <div key={i.productId} className="food-rate-row">
          <span>{i.productName}</span>
          <StarRating value={itemRatings[i.productId] ?? 0} onChange={(v) => setItemRatings({ ...itemRatings, [i.productId]: v })} />
        </div>
      ))}
      <details>
        <summary>Add detailed ratings (optional)</summary>
        <div className="stack" style={{ marginTop: 8 }}>
          {dimList.map((d) => (
            <div key={d.key} className="food-rate-row">
              <span>{d.label}{d.key === "deliveryExperience" ? " (about the delivery, not the food)" : ""}</span>
              <StarRating value={dims[d.key] ?? 0} onChange={(v) => setDims({ ...dims, [d.key]: v })} />
            </div>
          ))}
        </div>
      </details>
      <label>Comment (optional)
        <textarea value={text} rows={3} onChange={(e) => setText(e.target.value)} />
      </label>
      <div><Button onClick={submit} loading={busy}>Submit review</Button></div>
    </div>
  )
}
