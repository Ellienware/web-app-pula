import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import { WalletPayPanel } from "./WalletPayPanel"

interface Session {
  orderId: string; reference: string; checkoutUrl: string | null; sandbox: boolean
  paymentStatus: string; orderStatus: string
  mode?: string; amount?: number; currency?: string
}

/**
 * Shown on a consumer order whose payment is being collected through Paystack. The order is only marked
 * paid after PULA verifies the payment with the provider — this panel never marks anything paid.
 * Renders nothing for orders paid the original way (the session lookup returns 404).
 */
export function ConsumerPaymentPanel({ orderId, onChanged }: { orderId: string; onChanged: () => void }) {
  const [session, setSession] = useState<Session | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { api.get<Session>(`/orders/${orderId}/payment-session`).then(setSession).catch(() => setSession(null)) }, [orderId])
  if (!session) return null
  // Wallet orders are paid by a verified ledger debit, not through hosted checkout.
  if (session.mode === "WALLET_SIMULATED") {
    return <WalletPayPanel orderId={orderId} amount={session.amount ?? 0} currency={session.currency ?? "ZAR"} onChanged={onChanged} />
  }

  async function run(fn: () => Promise<void>) {
    setBusy(true); setError(null)
    try { await fn(); onChanged() } catch (e) { setError(e instanceof Error ? e.message : "Something went wrong") } finally { setBusy(false) }
  }

  return (
    <div className="callout stack" role="status">
      {session.sandbox && <strong>Demo payment — no real money moves. No shop or supplier is actually paid.</strong>}
      <span>Waiting for the demo payment to be confirmed.</span>
      <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
        {session.checkoutUrl && <a className="btn btn-primary" href={session.checkoutUrl} target="_blank" rel="noopener noreferrer">Open payment page</a>}
        {session.sandbox && (
          <button className="btn btn-primary" disabled={busy}
            onClick={() => run(async () => { await api.post(`/payments/sandbox/${encodeURIComponent(session.reference)}/complete`) })}>
            Complete sandbox payment
          </button>
        )}
        <button className="btn btn-ghost" disabled={busy}
          onClick={() => run(async () => { setSession(await api.post<Session>(`/orders/${orderId}/payment/refresh`)) })}>
          Check payment status
        </button>
      </div>
      {error && <span style={{ color: "var(--danger)" }}>{error}</span>}
    </div>
  )
}
