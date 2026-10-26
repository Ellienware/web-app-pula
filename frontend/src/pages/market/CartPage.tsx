import { DemoPaymentNotice } from "../../components/ui/DemoPaymentNotice"
import { SaveToBuyBanner } from "../../components/SaveToBuyBanner"
import { clearActiveSaveToBuy, getActiveSaveToBuy } from "../../lib/saveToBuyContext"
import { useEffect, useMemo, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { Trash2 } from "lucide-react"
import { api } from "../../lib/api"
import { walletCheckoutApi } from "../../lib/walletCheckout"
import type { Order } from "../../lib/types"
import type { FoodCheckoutRequest, RestaurantDetail } from "../../lib/foodTypes"
import { FoodCheckoutPanel } from "../../components/food/FoodCheckoutPanel"
import { RetailBranchPicker } from "../../components/branch/RetailBranchPicker"
import { formatMoney } from "../../lib/format"
import { useMarket } from "../../context/MarketContext"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"

export function CartPage() {
  const { cart, updateCartItem, removeCartItem, refreshCart } = useMarket()
  const navigate = useNavigate()
  const [checkingOut, setCheckingOut] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [walletEnabled, setWalletEnabled] = useState(false)
  // Non-food sellers with branches: which branch fills each seller's order ("" = the seller's main stock, as before).
  const [branchChoice, setBranchChoice] = useState<Record<string, string>>({})
  const [branchTotal, setBranchTotal] = useState<Record<string, number | null>>({})
  useEffect(() => { walletCheckoutApi.enabled().then(setWalletEnabled) }, [])
  // PULA Food: businesses that run a menu need branch / collection-or-delivery choices at checkout.
  // A restaurant's public page exists only for food businesses, so a failed lookup means "ordinary seller".
  const [restaurants, setRestaurants] = useState<Record<string, RestaurantDetail | null>>({})
  const [looked, setLooked] = useState<Record<string, boolean>>({})

  const groups = useMemo(() => {
    if (!cart) return []
    const byBusiness = new Map<string, { businessId: string; businessName: string; items: typeof cart.items }>()
    for (const item of cart.items) {
      const key = item.product.businessId
      if (!byBusiness.has(key)) {
        byBusiness.set(key, { businessId: key, businessName: item.product.businessName, items: [] })
      }
      byBusiness.get(key)!.items.push(item)
    }
    return [...byBusiness.values()]
  }, [cart])

  const businessIds = useMemo(() => (cart ? [...new Set(cart.items.map((i) => i.product.businessId))] : []), [cart])
  useEffect(() => {
    for (const bid of businessIds) {
      if (bid in looked) continue
      setLooked((prev) => ({ ...prev, [bid]: false }))
      api.get<RestaurantDetail>(`/market/restaurants/${bid}`)
        .then((r) => setRestaurants((prev) => ({ ...prev, [bid]: r })))
        .catch(() => undefined)
        .finally(() => setLooked((prev) => ({ ...prev, [bid]: true })))
    }
  }, [businessIds, looked])

  async function checkout(businessId: string, food?: FoodCheckoutRequest, method: "default" | "wallet" = "default") {
    setCheckingOut(businessId)
    setError(null)
    try {
      const idempotencyKey = crypto.randomUUID()
      const active = getActiveSaveToBuy("RETAIL", "FOOD")
      const base = food ?? { businessId, ...(branchChoice[businessId] ? { branchId: branchChoice[businessId], expectedTotal: branchTotal[businessId] } : {}) }
      // The server validates the request (buyer, status, vertical) and the order only references it.
      const body = active ? { ...base, saveToBuyRequestId: active.id } : base
      // The server decides which checkout is active: Paystack hosted checkout or the original one.
      // "wallet" creates an order to be paid from the SIMULATED demo wallet on the next screen; nothing is charged here.
      let order: { id: string }
      if (method === "wallet") {
        order = { id: (await walletCheckoutApi.create(body, idempotencyKey)).orderId }
      } else {
        const cfg = await api.get<{ consumerCheckout: string }>("/config/market-payments").catch(() => ({ consumerCheckout: "legacy" }))
        order = cfg.consumerCheckout === "paystack"
          ? { id: (await api.post<{ orderId: string }>("/orders/checkout/paystack", body, { "Idempotency-Key": idempotencyKey })).orderId }
          : await api.post<Order>("/orders/checkout", body, { "Idempotency-Key": idempotencyKey })
      }
      await refreshCart()
      clearActiveSaveToBuy()
      navigate(`/orders/${order.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Checkout failed")
    } finally {
      setCheckingOut(null)
    }
  }

  if (!cart || cart.items.length === 0) {
    return (
      <div className="page">
        <PageHeader title="Your cart" />
        <EmptyState
          title="Your cart is empty"
          description="Browse PULA Market to find something to add."
          action={
            <Link to="/market">
              <Button>Explore Market</Button>
            </Link>
          }
        />
      </div>
    )
  }

  return (
    <div className="page stack">
      <PageHeader title="Your cart" />
      <DemoPaymentNotice kind="checkout" />
      <SaveToBuyBanner verticals={["RETAIL", "FOOD"]} />
      {error && <ErrorState title="Checkout failed" description={error} />}
      {groups.map((group) => {
        const subtotal = group.items.reduce((sum, i) => sum + i.lineTotal, 0)
        const currency = group.items[0].product.currency
        return (
          <div key={group.businessId} className="card stack">
            <h2>{group.businessName}</h2>
            <ul className="cart-line-list">
              {group.items.map((item) => (
                <li key={item.id} className="cart-line">
                  <div>
                    <strong>{item.product.name}</strong>
                    {item.modifiers && item.modifiers.length > 0 && (
                      <div className="muted">
                        {item.modifiers.map((m) => (m.priceDelta > 0 ? `${m.name} +${formatMoney(m.priceDelta, item.product.currency)}` : m.name)).join(", ")}
                      </div>
                    )}
                    <div className="muted">{formatMoney(item.product.price, item.product.currency)} each</div>
                  </div>
                  <div className="row">
                    <input
                      type="number"
                      min={1}
                      value={item.quantity}
                      onChange={(e) => updateCartItem(item.id, Math.max(1, Number(e.target.value)))}
                      className="market-qty-input"
                      aria-label={`Quantity for ${item.product.name}`}
                    />
                    <span className="money">{formatMoney(item.lineTotal, item.product.currency)}</span>
                    <button className="icon-btn" aria-label={`Remove ${item.product.name}`} onClick={() => removeCartItem(item.id)}>
                      <Trash2 size={16} aria-hidden="true" />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
            {looked[group.businessId] === false ? (
              <div className="row between">
                <strong>Subtotal: {formatMoney(subtotal, currency)}</strong>
                <Button disabled>Checking seller…</Button>
              </div>
            ) : restaurants[group.businessId] ? (
              <div className="stack">
                <strong>Food total before delivery: {formatMoney(subtotal, currency)}</strong>
                <FoodCheckoutPanel
                  restaurant={restaurants[group.businessId] as RestaurantDetail}
                  placing={checkingOut === group.businessId}
                  onConfirm={(req) => checkout(group.businessId, req)}
                  onConfirmWallet={walletEnabled ? (req) => checkout(group.businessId, req, "wallet") : undefined}
                />
              </div>
            ) : (
              <div className="stack">
                <RetailBranchPicker businessId={group.businessId}
                  items={group.items.map((i) => ({ productId: i.product.id, quantity: i.quantity }))}
                  value={branchChoice[group.businessId] ?? ""} onChange={(v, t) => { setBranchChoice((c) => ({ ...c, [group.businessId]: v })); setBranchTotal((c) => ({ ...c, [group.businessId]: t })) }} />
                <div className="row between">
                  <strong>{branchChoice[group.businessId] && branchTotal[group.businessId] != null ? "Branch total" : "Subtotal"}: {formatMoney(branchChoice[group.businessId] && branchTotal[group.businessId] != null ? (branchTotal[group.businessId] as number) : subtotal, currency)}</strong>
                  <div className="row wrap">
                    <Button onClick={() => checkout(group.businessId)} loading={checkingOut === group.businessId}>
                      Checkout with this seller
                    </Button>
                    {walletEnabled && (
                      <Button variant="secondary" onClick={() => checkout(group.businessId, undefined, "wallet")} loading={checkingOut === group.businessId}>
                        Pay with demo wallet
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
