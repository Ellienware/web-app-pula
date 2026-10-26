import { DemoPaymentNotice } from "../ui/DemoPaymentNotice"
import { SaveToBuyBanner } from "../SaveToBuyBanner"
import { clearActiveSaveToBuy, getActiveSaveToBuy } from "../../lib/saveToBuyContext"
import { useState } from "react"
import { useNavigate } from "react-router-dom"
import { X } from "lucide-react"
import { api } from "../../lib/api"
import { STOCK_FULFILMENT_LABELS } from "../../lib/types"
import type { StockCart, StockFulfilmentOption, StockOrder } from "../../lib/types"
import { formatMoney } from "../../lib/format"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import { Button } from "../ui/Button"
import { Modal } from "../Modal"

interface Props {
  cart: StockCart | null
  onUpdated: (cart: StockCart) => void
  onClose: () => void
}

const FULFILMENT_OPTIONS: StockFulfilmentOption[] = [
  "SELF_COLLECTION",
  "WHOLESALER_DELIVERY",
  "PULA_RIDE_DELIVERY",
]

/** Groups cart items by supplier for display and checkout (brief §14 "group into separate supplier orders"). */
function groupBySupplier(items: StockCart["items"]) {
  const map = new Map<string, { name: string; items: StockCart["items"] }>()
  for (const item of items) {
    const existing = map.get(item.supplierBusinessId)
    if (existing) existing.items.push(item)
    else map.set(item.supplierBusinessId, { name: item.supplierBusinessName, items: [item] })
  }
  return map
}

export default function StockCartPanel({ cart, onUpdated, onClose }: Props) {
  const { defaultCurrency: currency } = useCurrencyConfig()
  const navigate = useNavigate()
  const [fulfilment, setFulfilment] = useState<StockFulfilmentOption>("SELF_COLLECTION")
  const [deliveryAddress, setDeliveryAddress] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function removeItem(wholesaleProductId: string) {
    try {
      const updated = await api.del<StockCart>(`/stock/cart/items/${wholesaleProductId}`)
      onUpdated(updated)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not remove item")
    }
  }

  async function checkout() {
    if (!cart?.valid) return
    setBusy(true)
    setError(null)
    try {
      const orders = await api.post<StockOrder[]>("/stock/orders/checkout", {
        idempotencyKey: crypto.randomUUID(),
        fulfilmentMethod: fulfilment,
        deliveryAddress: deliveryAddress.trim() || null,
        saveToBuyRequestId: getActiveSaveToBuy("STOCK")?.id ?? null,
      })
      clearActiveSaveToBuy()
      onClose()
      navigate("/market/stock/orders", { state: { newOrderIds: orders.map((o) => o.id) } })
    } catch (e) {
      setError(e instanceof Error ? e.message : "Checkout failed — please try again")
    } finally {
      setBusy(false)
    }
  }

  if (!cart || cart.items.length === 0) {
    return (
      <Modal title="Stock cart" onClose={onClose}>
        <p className="muted" style={{ textAlign: "center", padding: "2rem" }}>
          Your stock cart is empty. Browse wholesale products and add them here.
        </p>
      </Modal>
    )
  }

  const supplierGroups = groupBySupplier(cart.items)

  return (
    <Modal title={`Stock cart (${cart.items.length} item${cart.items.length !== 1 ? "s" : ""})`} onClose={onClose}>
      <div className="stack">
        <DemoPaymentNotice kind="checkout" />
        <SaveToBuyBanner verticals={["STOCK"]} />
        {/* Validation warnings */}
        {!cart.valid && cart.validationMessages.length > 0 && (
          <div className="card" style={{ background: "var(--danger-soft)", borderColor: "var(--danger-soft)" }}>
            {cart.validationMessages.map((msg, i) => (
              <p key={i} style={{ color: "var(--danger-strong)", fontSize: "0.85rem", margin: "2px 0" }}>⚠ {msg}</p>
            ))}
          </div>
        )}

        {/* Items grouped by supplier */}
        {[...supplierGroups.entries()].map(([supplierId, { name, items }]) => (
          <div key={supplierId} className="card">
            <p className="muted" style={{ fontSize: "0.8rem", fontWeight: 700, marginBottom: 8 }}>
              📦 {name}
            </p>
            {items.map((item) => (
              <div key={item.id} className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", marginBottom: 12 }}>
                <div style={{ flex: 1 }}>
                  <p style={{ fontWeight: 600, margin: 0 }}>{item.productName}</p>
                  {item.sku && <p className="muted" style={{ fontSize: "0.75rem", margin: 0 }}>SKU: {item.sku}</p>}
                  <p className="muted" style={{ fontSize: "0.85rem", margin: "2px 0" }}>
                    {item.quantityPacks} pack{item.quantityPacks !== 1 ? "s" : ""} × {formatMoney(item.pricePerPack, currency)}
                  </p>
                  {!item.moqMet && (
                    <p style={{ color: "var(--danger)", fontSize: "0.78rem" }}>Min {item.moq} pack{item.moq !== 1 ? "s" : ""}</p>
                  )}
                  {!item.stockSufficient && (
                    <p style={{ color: "var(--danger)", fontSize: "0.78rem" }}>Insufficient stock</p>
                  )}
                </div>
                <div style={{ textAlign: "right" }}>
                  <p style={{ fontWeight: 700, margin: 0 }}>{formatMoney(item.lineTotal, currency)}</p>
                  <button
                    onClick={() => removeItem(item.wholesaleProductId)}
                    className="btn btn-ghost"
                    style={{ fontSize: "0.78rem", padding: "2px 6px", color: "var(--danger)" }}
                    aria-label={`Remove ${item.productName}`}
                  >
                    <X size={12} /> Remove
                  </button>
                </div>
              </div>
            ))}
          </div>
        ))}

        {/* Totals */}
        <div className="row" style={{ justifyContent: "space-between", fontWeight: 700 }}>
          <span>Subtotal</span>
          <span>{formatMoney(cart.subtotal, currency)}</span>
        </div>

        {/* Fulfilment */}
        <label className="field">
          <span>Delivery method</span>
          <select value={fulfilment} onChange={(e) => setFulfilment(e.target.value as StockFulfilmentOption)}>
            {FULFILMENT_OPTIONS.map((opt) => (
              <option key={opt} value={opt}>{STOCK_FULFILMENT_LABELS[opt]}</option>
            ))}
          </select>
        </label>
        {fulfilment !== "SELF_COLLECTION" && (
          <label className="field">
            <span>Delivery address</span>
            <input
              value={deliveryAddress}
              onChange={(e) => setDeliveryAddress(e.target.value)}
              placeholder="Where should we deliver?"
            />
          </label>
        )}

        {error && <p className="form-error">{error}</p>}

        <Button
          onClick={checkout}
          disabled={busy || !cart.valid || (fulfilment !== "SELF_COLLECTION" && !deliveryAddress.trim())}
        >
          {busy ? "Placing orders…" : "Place stock order"}
        </Button>

        {!cart.valid && (
          <p className="muted" style={{ fontSize: "0.8rem", textAlign: "center" }}>
            Fix the issues above before placing your order.
          </p>
        )}
      </div>
    </Modal>
  )
}
