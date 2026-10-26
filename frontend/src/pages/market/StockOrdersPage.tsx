import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { Page, StockOrder, StockPaymentSession } from "../../lib/types"
import { STOCK_ORDER_STATUS_LABELS, STOCK_FULFILMENT_LABELS } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { friendlyError, parseRiskError, riskApi } from "../../lib/riskOutcome"
import { stockWalletCheckoutApi } from "../../lib/stockWalletCheckout"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { PageHeader } from "../../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"

type Tab = "buying" | "supplying"

export default function StockOrdersPage() {
  const [tab, setTab] = useState<Tab>("buying")
  const [orders, setOrders] = useState<StockOrder[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [sessions, setSessions] = useState<Record<string, StockPaymentSession>>({})
  const { defaultCurrency: currency } = useCurrencyConfig()

  function load() {
    setLoading(true)
    const path = tab === "buying" ? "/stock/orders/mine" : "/stock/orders/supplier"
    api.get<Page<StockOrder>>(path)
      .then((res) => { setOrders(res.content); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(load, [tab])

  function replace(updated: StockOrder) {
    setOrders((prev) => prev.map((o) => (o.id === updated.id ? updated : o)))
  }

  async function run(orderId: string, fn: () => Promise<void>) {
    setBusyId(orderId)
    try { await fn(); setError(null) }
    catch (e) { setError(friendlyError(e)) }
    finally { setBusyId(null) }
  }

  async function advance(orderId: string, to: string) {
    await run(orderId, async () => {
      replace(await api.post<StockOrder>(`/stock/orders/supplier/${orderId}/advance?to=${to}`))
    })
  }

  // Payment is confirmed by the BACKEND with the provider; this page never marks anything paid.
  async function pay(orderId: string, acknowledged = false): Promise<void> {
    await run(orderId, async () => {
      let s: StockPaymentSession
      try {
        s = await api.post<StockPaymentSession>(`/stock/orders/mine/${orderId}/pay?acknowledged=${acknowledged}`)
      } catch (e) {
        const msg = e instanceof Error ? e.message : ""
        // Server-side transaction-risk gate. STEP_UP = the signed-in owner re-confirms this exact amount; the server records it.
        // REVIEW / BLOCKED / RETRY are shown as the server's safe message (via run()), never as an accusation, and no payment starts.
        const risk = parseRiskError(e)
        const order = orders.find((o) => o.id === orderId)
        if (risk && risk.kind === "STEP_UP" && risk.assessmentId && order) {
          if (!window.confirm(risk.message + "\n\nPay " + formatMoney(order.total, order.currency) + " for this order?")) return
          await riskApi.confirmStepUp(risk.assessmentId, order.total, order.currency)
          s = await api.post<StockPaymentSession>(`/stock/orders/mine/${orderId}/pay?acknowledged=${acknowledged}`)
        } else if (risk) { throw e }
        // Advisory supplier/order warning: the user's own acknowledgement. It has NO effect on the server's risk decision.
        else if (msg.startsWith("RISK_CONFIRMATION_REQUIRED|")) {
          if (window.confirm("Check before you pay\n\n" + msg.replace("RISK_CONFIRMATION_REQUIRED|", ""))) {
            s = await api.post<StockPaymentSession>(`/stock/orders/mine/${orderId}/pay?acknowledged=true`)
          } else { return }
        } else { throw e }
      }
      setSessions((p) => ({ ...p, [orderId]: s }))
      if (s.checkoutUrl) window.open(s.checkoutUrl, "_blank", "noopener")
      load()
    })
  }

  // SIMULATED business-wallet payment. The amount comes from the server; the backend decides whether it was paid.
  async function payFromWallet(orderId: string, acknowledged = false): Promise<void> {
    await run(orderId, async () => {
      let s: StockPaymentSession
      try {
        s = await stockWalletCheckoutApi.start(orderId, acknowledged)
      } catch (e) {
        const msg = e instanceof Error ? e.message : ""
        if (msg.startsWith("RISK_CONFIRMATION_REQUIRED|") && !acknowledged) {
          if (!window.confirm("Check before you pay\n\n" + msg.replace("RISK_CONFIRMATION_REQUIRED|", ""))) return
          s = await stockWalletCheckoutApi.start(orderId, true)
        } else { throw e }
      }
      setSessions((p) => ({ ...p, [orderId]: s }))
      if (!window.confirm("Pay " + formatMoney(s.amount, s.currency) + " from your business's DEMO wallet? Simulated funds only; no real money moves and the supplier is not paid in the real world.")) { load(); return }
      const r = await stockWalletCheckoutApi.pay(orderId, s.amount)
      if (!r.paid) throw new Error("The payment is not confirmed yet. Check the order status shortly.")
      load()
    })
  }

  async function refreshPayment(orderId: string) {
    await run(orderId, async () => {
      const s = await api.post<StockPaymentSession>(`/stock/orders/mine/${orderId}/payment/refresh`)
      setSessions((p) => ({ ...p, [orderId]: s }))
      load()
    })
  }

  async function sandboxComplete(orderId: string) {
    const s = sessions[orderId]
    if (!s) return
    await run(orderId, async () => {
      await api.post<StockPaymentSession>(`/payments/sandbox/${encodeURIComponent(s.reference)}/complete`)
      load()
    })
  }

  // Only works if the earlier delivery was cancelled; the backend decides.
  async function redispatch(orderId: string) {
    await run(orderId, async () => { replace(await api.post<StockOrder>(`/stock/orders/supplier/${orderId}/redispatch`)); load() })
  }

  async function cancel(orderId: string) {
    await run(orderId, async () => { replace(await api.del<StockOrder>(`/stock/orders/mine/${orderId}`)) })
  }

  return (
    <div className="page stack">
      <PageHeader title="Stock orders" subtitle="Track your B2B procurement orders." />

      <div className="tab-bar" role="tablist">
        {(["buying", "supplying"] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t}
            className={`tab ${tab === t ? "active" : ""}`}
            onClick={() => setTab(t)}>
            {t === "buying" ? "I'm buying" : "I'm supplying"}
          </button>
        ))}
      </div>

      {tab === "supplying" && (
        <p className="muted" style={{ margin: 0 }}>
          Delivering with your own drivers? <Link to="/market/stock/pool">Manage your driver pool</Link>
        </p>
      )}

      {error && <ErrorState description={error} onRetry={load} />}
      {loading && <p className="muted">Loading orders…</p>}
      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="No stock orders yet"
          description={tab === "buying"
            ? "Browse PULA Market — Stock to place your first procurement order."
            : "When buyers place stock orders with your business they'll appear here."}
        />
      )}

      {orders.map((order) => {
        const session = sessions[order.id]
        const busy = busyId === order.id
        const isDelivery = order.fulfilmentMethod !== "SELF_COLLECTION"
        return (
          <div key={order.id} className="card stack">
            <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 8 }}>
              <div>
                <p style={{ margin: 0, fontWeight: 700 }}>
                  {tab === "buying" ? `From: ${order.supplierBusinessName}` : `To: ${order.buyerBusinessName}`}
                </p>
                <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>
                  {STOCK_FULFILMENT_LABELS[order.fulfilmentMethod]}
                </p>
              </div>
              <div style={{ textAlign: "right" }}>
                <span className="badge">{STOCK_ORDER_STATUS_LABELS[order.status]}</span>
                <p style={{ margin: "4px 0 0", fontWeight: 700 }}>{formatMoney(order.total, currency)}</p>
              </div>
            </div>

            <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
              {order.items.map((item, i) => (
                <li key={i} className="row" style={{ justifyContent: "space-between", fontSize: "0.85rem", padding: "4px 0", borderBottom: "1px solid var(--line)" }}>
                  <span>{item.productName} × {item.quantityPacks} pack{item.quantityPacks !== 1 ? "s" : ""}</span>
                  <span>{formatMoney(item.lineTotal, currency)}</span>
                </li>
              ))}
            </ul>

            {tab === "buying" && order.status === "PENDING_PAYMENT" && (
              <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                <button className="btn btn-primary" disabled={busy} onClick={() => pay(order.id)}>
                  {busy ? "Starting payment…" : `Pay ${formatMoney(order.total, currency)}`}
                </button>
                <button className="btn btn-secondary" disabled={busy} onClick={() => payFromWallet(order.id)}>
                  Pay from demo wallet (simulated)
                </button>
                <button className="btn btn-ghost" disabled={busy} onClick={() => cancel(order.id)}>Cancel order</button>
              </div>
            )}
            {tab === "buying" && order.status === "PAYMENT_PROCESSING" && (
              <div className="stack" style={{ gap: 6 }}>
                {session?.sandbox && (
                  <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>
                    <strong>Demo payment</strong> — no real money moves. No supplier is actually paid.
                  </p>
                )}
                <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
                  Waiting for the payment provider to confirm. Your order is only marked paid once PULA has verified it.
                </p>
                <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                  {session?.sandbox && (
                    <button className="btn btn-primary" disabled={busy} onClick={() => sandboxComplete(order.id)}>
                      Complete sandbox payment
                    </button>
                  )}
                  {session?.checkoutUrl && (
                    <a className="btn btn-primary" href={session.checkoutUrl} target="_blank" rel="noopener noreferrer">Open payment page</a>
                  )}
                  <button className="btn btn-ghost" disabled={busy} onClick={() => refreshPayment(order.id)}>
                    Check payment status
                  </button>
                </div>
              </div>
            )}

            {tab === "buying" && ["OUT_FOR_DELIVERY", "READY_FOR_COLLECTION"].includes(order.status) && isDelivery && (
              <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
                {order.status === "READY_FOR_COLLECTION" ? "Waiting for a driver to accept the delivery." : "Your stock is on its way."}
              </p>
            )}

            {tab === "supplying" && order.status === "PAID" && (
              <button className="btn btn-primary" disabled={busy} onClick={() => advance(order.id, "CONFIRMED")}>Confirm order</button>
            )}
            {tab === "supplying" && order.status === "CONFIRMED" && (
              <button className="btn btn-primary" disabled={busy} onClick={() => advance(order.id, "PREPARING")}>Mark as preparing</button>
            )}
            {tab === "supplying" && order.status === "PREPARING" && (
              <button className="btn btn-primary" disabled={busy} onClick={() => advance(order.id, "READY_FOR_COLLECTION")}>
                {isDelivery ? "Mark as ready — send to delivery" : "Mark as ready for collection"}
              </button>
            )}
            {tab === "supplying" && order.status === "READY_FOR_COLLECTION" && !isDelivery && (
              <button className="btn btn-primary" disabled={busy} onClick={() => advance(order.id, "DELIVERED")}>Mark as collected</button>
            )}
            {tab === "supplying" && order.status === "READY_FOR_COLLECTION" && isDelivery && (
              <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
                {order.fulfilmentMethod === "WHOLESALER_DELIVERY"
                  ? "Sent to your driver pool — a pool driver will accept it."
                  : "Sent to PULA Ride — an available driver will accept it."}
                {" "}<button className="btn btn-ghost" disabled={busy} onClick={() => redispatch(order.id)}>Send to drivers again</button>
              </p>
            )}
          </div>
        )
      })}
    </div>
  )
}
