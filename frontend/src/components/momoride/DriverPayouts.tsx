import { DemoPaymentNotice } from "../ui/DemoPaymentNotice"
import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { DriverPayout, Page } from "../../lib/types"
import { formatMoney, humanize } from "../../lib/format"

/** A driver's own earnings payouts. Status comes from the backend/provider, never from the app. */
export function DriverPayouts() {
  const [payouts, setPayouts] = useState<DriverPayout[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api.get<Page<DriverPayout>>("/payouts/mine").then((p) => setPayouts(p.content)).catch((e) => setError(e.message))
  }, [])
  if (error || payouts.length === 0) return null
  return (
    <div className="card stack" style={{ marginTop: "var(--space-3)" }}>
      <h3 style={{ margin: 0 }}>Delivery earnings</h3>
      <DemoPaymentNotice kind="payout" />
      {payouts.map((p) => (
        <div key={p.id} className="row" style={{ justifyContent: "space-between", gap: 8 }}>
          <span>{formatMoney(p.amount, p.currency)} {p.sandbox && <span className="badge">Sandbox — no real money</span>}</span>
          <span className="badge">{humanize(p.status)}</span>
        </div>
      ))}
    </div>
  )
}
