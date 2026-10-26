import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../../lib/api"
import { formatDateTime, formatMoney } from "../../../lib/format"
import type { Order, Page } from "../../../lib/types"
import { FULFILMENT_OPTION_LABELS } from "../../../lib/types"
import { useToast } from "../../../context/ToastContext"
import { Button } from "../../ui/Button"
import { Badge } from "../../ui/Badge"
import { EmptyState, ErrorState } from "../../ui/StatePanel"
import { SkeletonCard } from "../../ui/Skeleton"

/**
 * The kitchen queue. The restaurant must accept or reject each paid order (it is refunded if it
 * doesn't answer in time), then moves it through preparing and ready. For PULA Ride delivery there
 * is nothing else to do: when an order is marked ready, driver matching starts automatically, and the
 * delivery is completed by the driver, not by the restaurant.
 */
export function KitchenOrders() {
  const { showToast } = useToast()
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [now, setNow] = useState(Date.now())

  const load = useCallback(() => {
    api.get<Page<Order>>("/orders/selling?size=50").then((p) => setOrders(p.content.filter((o) => o.kind === "FOOD")))
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load orders"))
  }, [])

  useEffect(() => {
    load()
    const poll = setInterval(load, 15000)   // new orders need answering quickly
    const tick = setInterval(() => setNow(Date.now()), 1000)
    return () => { clearInterval(poll); clearInterval(tick) }
  }, [load])

  async function act(order: Order, path: string, body?: unknown, done?: string) {
    setBusyId(order.id)
    try {
      await api.post<Order>(`/orders/${order.id}/${path}`, body)
      if (done) showToast(done, "success")
      load()
    } catch (err) {
      showToast(err instanceof Error ? err.message : "That didn't work", "error")
    } finally {
      setBusyId(null)
    }
  }

  async function setStatus(order: Order, status: "READY" | "COMPLETED") {
    setBusyId(order.id)
    try {
      await api.patch<Order>(`/orders/${order.id}/status`, { status })
      load()
    } catch (err) {
      showToast(err instanceof Error ? err.message : "That didn't work", "error")
    } finally {
      setBusyId(null)
    }
  }

  if (error && !orders) return <ErrorState title="Couldn't load your kitchen queue" description={error} onRetry={load} />
  if (!orders) return <SkeletonCard lines={3} />

  const needsAnswer = orders.filter((o) => o.status === "PAID" && o.restaurantDecision === "PENDING")
  const active = orders.filter((o) => o.status === "PROCESSING" || o.status === "READY")
  const recent = orders.filter((o) => !needsAnswer.includes(o) && !active.includes(o)).slice(0, 10)

  const card = (o: Order) => {
    const delivery = o.fulfilmentMethod && o.fulfilmentMethod !== "CUSTOMER_PICKUP"
    const secondsLeft = o.acceptDeadlineAt ? Math.max(0, Math.round((new Date(o.acceptDeadlineAt).getTime() - now) / 1000)) : null
    return (
      <div key={o.id} className="card stack">
        <div className="row between">
          <div>
            <strong>{o.buyerName}</strong>
            <div className="muted">{formatDateTime(o.createdAt)} · {o.fulfilmentMethod ? FULFILMENT_OPTION_LABELS[o.fulfilmentMethod] : ""}</div>
          </div>
          <div className="stack" style={{ alignItems: "flex-end", gap: 4 }}>
            <strong>{formatMoney(o.subtotal, o.currency)}</strong>
            {delivery && o.deliveryFee != null && <span className="muted">+ {formatMoney(o.deliveryFee, o.currency)} delivery (not your income when PULA Ride delivers)</span>}
          </div>
        </div>
        <ul style={{ margin: 0, paddingLeft: "1.2rem" }}>
          {o.items.map((i) => (
            <li key={i.productId + (i.modifiers?.map((m) => m.name).join("") ?? "")}>
              {i.quantity} × {i.productName}
              {i.modifiers && i.modifiers.length > 0 && <span className="muted"> — {i.modifiers.map((m) => m.name).join(", ")}</span>}
            </li>
          ))}
        </ul>
        {o.customerNote && <div className="food-note">Customer note: {o.customerNote}</div>}
        {delivery && o.deliveryAddress && <div className="muted">Deliver to: {o.deliveryAddress}</div>}

        {o.status === "PAID" && o.restaurantDecision === "PENDING" && (
          <div className="row wrap">
            <Button onClick={() => act(o, "accept", undefined, "Order accepted. Start preparing.")} loading={busyId === o.id}>Accept</Button>
            <Button variant="danger" onClick={() => {
              const reason = window.prompt("Reason for rejecting (the customer will see it and is refunded):", "Sorry, we can't make this right now")
              if (reason !== null) act(o, "reject", { reason }, "Order rejected. The customer is being refunded.")
            }} disabled={busyId === o.id}>Reject</Button>
            {secondsLeft !== null && (
              <Badge tone={secondsLeft < 120 ? "failed" : "pending"}>
                {secondsLeft > 0 ? `Answer within ${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, "0")}` : "Time is up"}
              </Badge>
            )}
          </div>
        )}
        {o.status === "PROCESSING" && (
          <Button onClick={() => setStatus(o, "READY")} loading={busyId === o.id}>
            {o.fulfilmentMethod === "MOMORIDE_DELIVERY" ? "Food is ready — find a driver" : "Mark ready"}
          </Button>
        )}
        {o.status === "READY" && o.fulfilmentMethod === "MOMORIDE_DELIVERY" && (
          <p className="muted">Waiting for a PULA Ride driver. The driver confirms collection and delivery.</p>
        )}
        {o.status === "READY" && o.fulfilmentMethod !== "MOMORIDE_DELIVERY" && (
          <Button onClick={() => setStatus(o, "COMPLETED")} loading={busyId === o.id}>
            {o.fulfilmentMethod === "CUSTOMER_PICKUP" ? "Customer collected" : "Delivered"}
          </Button>
        )}
        <Link to={`/orders/${o.id}`} className="muted">Order details</Link>
      </div>
    )
  }

  return (
    <div className="stack">
      <h2>Needs your answer {needsAnswer.length > 0 && <Badge tone="pending">{needsAnswer.length}</Badge>}</h2>
      {needsAnswer.length === 0 ? <p className="muted">No new orders waiting.</p> : needsAnswer.map(card)}
      <h2>In the kitchen</h2>
      {active.length === 0 ? <p className="muted">Nothing being prepared.</p> : active.map(card)}
      {recent.length > 0 && (
        <>
          <h2>Recent</h2>
          {recent.map((o) => (
            <Link key={o.id} to={`/orders/${o.id}`} className="card order-row">
              <span>{o.buyerName} · {formatDateTime(o.createdAt)}</span>
              <span>{formatMoney(o.subtotal, o.currency)} · {o.restaurantDecision === "REJECTED" || o.restaurantDecision === "WITHDRAWN" ? "Not fulfilled" : o.status}</span>
            </Link>
          ))}
        </>
      )}
      {orders.length === 0 && <EmptyState title="No food orders yet" description="Orders appear here as soon as customers pay." />}
    </div>
  )
}
