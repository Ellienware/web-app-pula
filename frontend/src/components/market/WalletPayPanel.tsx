import { useEffect, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { ApiError } from "../../lib/api"
import { walletApi } from "../../lib/walletApi"
import { walletCheckoutApi } from "../../lib/walletCheckout"
import type { WalletOverview } from "../../lib/walletTypes"
import { formatMoney } from "../../lib/format"
import { Button } from "../ui/Button"

/**
 * Pays one order from the SIMULATED demo wallet. The amount shown is the server's stored total; pressing the button is
 * the buyer's explicit confirmation of exactly that amount. This panel never decides an order is paid: it shows what the
 * backend returns, and only reloads the order when the backend says the payment succeeded.
 */
export function WalletPayPanel({ orderId, amount, currency, onChanged }: { orderId: string; amount: number; currency: string; onChanged: () => void }) {
  const [wallet, setWallet] = useState<WalletOverview | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ code?: string; message: string } | null>(null)
  const [pending, setPending] = useState(false)
  const inFlight = useRef(false) // blocks a second submit before React re-renders the disabled button

  useEffect(() => { walletApi.overview().then(setWallet).catch(() => setWallet(null)) }, [orderId])

  async function pay() {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(true); setError(null); setPending(false)
    try {
      const result = await walletCheckoutApi.pay(orderId, amount)
      if (result.paid) { onChanged() } else { setPending(true) }
    } catch (e) {
      if (e instanceof ApiError) setError({ code: e.code, message: e.message })
      else setError({ message: e instanceof Error ? e.message : "Something went wrong" })
      walletApi.overview().then(setWallet).catch(() => undefined)
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const short = error?.code === "INSUFFICIENT_FUNDS"
  return (
    <div className="callout stack" role="status">
      <strong>Demo wallet payment — simulated. No real money moves, and no shop, restaurant or supplier is actually paid.</strong>
      <div className="food-total-row"><span>Order total</span><span>{formatMoney(amount, currency)}</span></div>
      {wallet && <div className="food-total-row"><span>Your demo wallet balance</span><span>{formatMoney(Number(wallet.balance), wallet.currency)}</span></div>}
      <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
        <Button onClick={pay} loading={busy} disabled={busy}>Pay {formatMoney(amount, currency)} from demo wallet</Button>
        <Link className="btn btn-ghost" to="/money/wallet">Open demo wallet</Link>
      </div>
      {short && <span style={{ color: "var(--danger)" }} role="alert">Not enough demo funds. Add demo funds in your wallet, then come back and pay. Your order is held for a short time.</span>}
      {error && !short && <span style={{ color: "var(--danger)" }} role="alert">{error.message}</span>}
      {pending && <span role="alert">Your payment is still being finalised. Nothing is lost; check again in a moment.</span>}
    </div>
  )
}
