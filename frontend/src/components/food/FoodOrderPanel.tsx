import { useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import { DELIVERY_STATUS_LABELS, FULFILMENT_OPTION_LABELS, type DeliveryRequestInfo, type Order } from "../../lib/types"
import { useToast } from "../../context/ToastContext"
import { Button } from "../ui/Button"
import { Badge } from "../ui/Badge"
import { FoodReviewForm } from "./FoodReviewForm"

/** What the status means for a food order, in plain words, from the customer's side. */
function customerHeadline(order: Order, deliveryStatus: string | null): { text: string; tone: "pending" | "success" | "failed" | "info" } {
  const d = order.restaurantDecision
  if (d === "REJECTED") return { text: "The restaurant couldn't make this order. You're being refunded.", tone: "failed" }
  if (d === "WITHDRAWN") return { text: "You cancelled this order. You're being refunded.", tone: "failed" }
  if (order.status === "REFUNDED") return { text: "Refunded.", tone: "failed" }
  if (order.status === "REFUND_PENDING") return { text: "Your refund is being processed.", tone: "failed" }
  switch (order.status) {
    case "PAID": return { text: "Waiting for the restaurant to accept your order.", tone: "pending" }
    case "PROCESSING": return { text: "The restaurant is preparing your food.", tone: "info" }
    case "READY":
      if (order.fulfilmentMethod === "CUSTOMER_PICKUP") return { text: "Ready for collection.", tone: "success" }
      if (deliveryStatus === "PICKED_UP") return { text: "Out for delivery.", tone: "info" }
      if (deliveryStatus === "ACCEPTED") return { text: "A driver has accepted and is on the way to collect your food.", tone: "info" }
      return { text: "Your food is ready. We're finding a PULA Ride driver.", tone: "pending" }
    case "COMPLETED": return { text: order.fulfilmentMethod === "CUSTOMER_PICKUP" ? "Collected. Enjoy!" : "Delivered. Enjoy!", tone: "success" }
    default: return { text: "", tone: "info" }
  }
}

/**
 * Food-specific part of the order page: plain-language progress, the customer's cancel option and
 * review form, and the kitchen's actions. The server decides everything; this only shows the choices
 * that are currently possible and reports the server's answer if one is refused.
 */
export function FoodOrderPanel({ order, isSeller, delivery, onChanged }: {
  order: Order
  isSeller: boolean
  delivery: DeliveryRequestInfo | null
  onChanged: () => void
}) {
  const { showToast } = useToast()
  const [busy, setBusy] = useState(false)

  async function call(fn: () => Promise<unknown>, ok: string) {
    setBusy(true)
    try { await fn(); showToast(ok, "success"); onChanged() }
    catch (err) { showToast(err instanceof Error ? err.message : "That didn't work", "error") }
    finally { setBusy(false) }
  }

  const delivered = order.fulfilmentMethod && order.fulfilmentMethod !== "CUSTOMER_PICKUP"
  const head = customerHeadline(order, delivery?.status ?? null)
  const canTryCancel = !isSeller && order.restaurantDecision !== "WITHDRAWN" && (
    (order.status === "PAID" && order.restaurantDecision === "PENDING") ||
    (order.status === "READY" && order.fulfilmentMethod === "MOMORIDE_DELIVERY" && (!delivery || delivery.status === "REQUESTED")))

  return (
    <div className="stack">
      <div className="row wrap">
        <strong>{order.fulfilmentMethod ? FULFILMENT_OPTION_LABELS[order.fulfilmentMethod] : ""}</strong>
        {delivered && order.deliveryAddress && <span className="muted">to {order.deliveryAddress}</span>}
      </div>
      {order.customerNote && <div className="food-note">Note: {order.customerNote}</div>}

      {!isSeller && head.text && (
        <div className="callout" role="status" aria-live="polite"><Badge tone={head.tone}>{head.tone === "failed" ? "Update" : "Status"}</Badge> {head.text}</div>
      )}
      {order.rejectionReason && order.restaurantDecision !== "PENDING" && order.restaurantDecision !== "ACCEPTED" && (
        <p className="muted">Reason: {order.rejectionReason}</p>
      )}

      {delivery && delivered && (
        <div className="card">
          <strong>{DELIVERY_STATUS_LABELS[delivery.status]}</strong>
          {delivery.driverName && <p className="muted">Driver: {delivery.driverName}</p>}
          <p className="muted">{delivery.pickupLocation} → {delivery.dropoffLocation}</p>
        </div>
      )}
      {!delivery && delivered && order.status === "READY" && order.fulfilmentMethod === "MOMORIDE_DELIVERY" && (
        <p className="muted">Driver matching starts as soon as the order is ready. Nothing more to request.</p>
      )}

      {canTryCancel && (
        <div className="stack">
          <Button variant="danger" loading={busy} onClick={() => {
            if (window.confirm("Cancel this order? You'll be refunded.")) call(() => api.post(`/orders/${order.id}/cancel`), "Order cancelled. Your refund is on its way.")
          }}>Cancel order</Button>
          {order.status === "READY" && <p className="muted">If no driver has taken your delivery after a while you can cancel for a full refund.</p>}
        </div>
      )}

      {isSeller && (
        <p className="muted">Manage this order from your <Link to="/sell/food">kitchen queue</Link>.</p>
      )}

      {!isSeller && order.status === "COMPLETED" && (
        <FoodReviewForm order={order} delivered={!!delivered} onDone={onChanged} />
      )}
    </div>
  )
}
