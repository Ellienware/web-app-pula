import { DemoPaymentNotice } from "../ui/DemoPaymentNotice"
import { useEffect, useMemo, useState } from "react"
import { api } from "../../lib/api"
import { formatMoney } from "../../lib/format"
import type { CheckoutQuote, FoodCheckoutRequest, RestaurantDetail } from "../../lib/foodTypes"
import { FULFILMENT_OPTION_LABELS, type FulfilmentOption } from "../../lib/types"
import { Button } from "../ui/Button"

const CUSTOMER_CHOICES: FulfilmentOption[] = ["CUSTOMER_PICKUP", "MOMORIDE_DELIVERY", "SELLER_DELIVERY", "SUPPORTED_PARTNER_DELIVERY"]
const labelFor = (o: FulfilmentOption) => (o === "MOMORIDE_DELIVERY" ? "PULA Ride delivery" : o === "CUSTOMER_PICKUP" ? "Collection" : FULFILMENT_OPTION_LABELS[o] ?? o)

/**
 * Checkout for a food order: choose branch and collection/delivery, review the FULL total (items,
 * extras, delivery fee) priced by the backend, then confirm. Delivery chosen here is the customer's
 * request: once the restaurant has accepted, prepared and marked the order ready, PULA starts
 * driver matching by itself, so there is nothing more to request later. The client never computes
 * a total: the amount confirmed is the one the server quoted, and if it changes the server refuses.
 */
export function FoodCheckoutPanel({
  restaurant,
  placing,
  onConfirm,
  onConfirmWallet,
}: {
  restaurant: RestaurantDetail
  placing: boolean
  onConfirm: (req: FoodCheckoutRequest) => void
  /** Offered only when the server enables wallet checkout. Creates the order; the amount is confirmed on the next screen. */
  onConfirmWallet?: (req: FoodCheckoutRequest) => void
}) {
  const openBranches = restaurant.branches.filter((b) => b.open)
  const [branchId, setBranchId] = useState(openBranches.length === 1 ? openBranches[0].id : "")
  const [fulfilment, setFulfilment] = useState<FulfilmentOption | "">("")
  const [address, setAddress] = useState("")
  const [note, setNote] = useState("")
  const [quote, setQuote] = useState<CheckoutQuote | null>(null)
  const [quoting, setQuoting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const branch = restaurant.branches.find((b) => b.id === branchId)
  const offered = useMemo(() => {
    const opts = branch && branch.fulfilmentOptions.length > 0 ? branch.fulfilmentOptions : restaurant.fulfilmentOptions
    return CUSTOMER_CHOICES.filter((o) => opts.includes(o))
  }, [branch, restaurant])
  const needsAddress = fulfilment !== "" && fulfilment !== "CUSTOMER_PICKUP"
  const needsBranch = restaurant.branches.length > 0

  // Any change to the choices invalidates a quote the customer was looking at.
  useEffect(() => { setQuote(null); setError(null) }, [branchId, fulfilment, address, note])
  useEffect(() => { if (fulfilment && !offered.includes(fulfilment)) setFulfilment("") }, [offered, fulfilment])

  const request: FoodCheckoutRequest | null = fulfilment
    ? {
        businessId: restaurant.id,
        branchId: needsBranch ? branchId || null : null,
        fulfilmentMethod: fulfilment,
        deliveryAddress: needsAddress ? address.trim() : null,
        customerNote: note.trim() || null,
      }
    : null
  const ready = !!request && (!needsBranch || !!branchId) && (!needsAddress || address.trim().length > 4)

  async function review() {
    if (!request) return
    setQuoting(true)
    setError(null)
    try {
      setQuote(await api.post<CheckoutQuote>("/orders/checkout/quote", request))
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not price your order")
    } finally {
      setQuoting(false)
    }
  }

  return (
    <div className="stack">
      <DemoPaymentNotice kind="checkout" />
      {!restaurant.open && <p className="error-text" role="alert">{restaurant.name} is not taking orders right now ({restaurant.closedReason}).</p>}

      {needsBranch && (
        <label>Branch
          <select value={branchId} onChange={(e) => setBranchId(e.target.value)}>
            <option value="">Choose a branch…</option>
            {restaurant.branches.map((b) => (
              <option key={b.id} value={b.id} disabled={!b.open}>{b.name}{b.area ? ` · ${b.area}` : ""}{b.open ? "" : " (closed)"}</option>
            ))}
          </select>
        </label>
      )}

      <fieldset className="stack">
        <legend><strong>How would you like it?</strong></legend>
        {offered.length === 0 && <p className="muted">{needsBranch && !branchId ? "Choose a branch first." : "This restaurant hasn't set up collection or delivery yet."}</p>}
        {offered.map((o) => (
          <label key={o}>
            <input type="radio" name={`fulfil-${restaurant.id}`} checked={fulfilment === o} onChange={() => setFulfilment(o)} /> {labelFor(o)}
            {o === "MOMORIDE_DELIVERY" && <span className="muted"> · a PULA Ride driver is matched automatically once your food is ready</span>}
          </label>
        ))}
      </fieldset>

      {needsAddress && (
        <label>Delivery address
          <input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="Street, suburb, any directions" />
        </label>
      )}
      <label>Note for the restaurant (optional)
        <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="e.g. no onions" />
      </label>

      {error && <p className="error-text" role="alert">{error}</p>}

      {!quote ? (
        <Button onClick={review} loading={quoting} disabled={!ready || !restaurant.open}>Review full total</Button>
      ) : (
        <div className="card stack">
          <h3>Your order{quote.branchName ? ` · ${quote.branchName}` : ""}</h3>
          {quote.lines.map((l) => (
            <div key={`${l.productId}-${l.modifiers.map((m) => m.name).join("+")}`} className="food-total-row">
              <span>
                {l.quantity} × {l.name}
                {l.modifiers.length > 0 && <span className="muted"> ({l.modifiers.map((m) => m.name).join(", ")})</span>}
              </span>
              <span>{formatMoney(l.lineTotal, quote.currency)}</span>
            </div>
          ))}
          <div className="food-total-row"><span>Food total</span><span>{formatMoney(quote.subtotal, quote.currency)}</span></div>
          <div className="food-total-row">
            <span>{quote.fulfilmentMethod === "CUSTOMER_PICKUP" ? "Collection" : "Delivery fee"}</span>
            <span>{quote.fulfilmentMethod === "CUSTOMER_PICKUP" ? "Free" : formatMoney(quote.deliveryFee, quote.currency)}</span>
          </div>
          <div className="food-total-row is-grand"><span>Total to pay</span><span>{formatMoney(quote.total, quote.currency)}</span></div>
          <p className="muted">
            {quote.fulfilmentMethod === "CUSTOMER_PICKUP"
              ? "You'll collect from the restaurant when it's ready."
              : `Delivering to ${quote.deliveryAddress}. The restaurant must accept your order first; if it doesn't respond in time you're refunded.`}
          </p>
          <div className="row wrap">
            <Button onClick={() => request && onConfirm({ ...request, expectedTotal: quote.total })} loading={placing}>
              Confirm order and pay {formatMoney(quote.total, quote.currency)}
            </Button>
            {onConfirmWallet && (
              <Button variant="secondary" onClick={() => request && onConfirmWallet({ ...request, expectedTotal: quote.total })} loading={placing}>
                Order and pay with demo wallet
              </Button>
            )}
            <Button variant="ghost" onClick={() => setQuote(null)} disabled={placing}>Change details</Button>
          </div>
        </div>
      )}
    </div>
  )
}
