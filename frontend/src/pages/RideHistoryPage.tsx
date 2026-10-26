import { useCallback, useEffect, useState } from "react"
import { api } from "../lib/api"
import type { Page } from "../lib/types"
import { formatDateTime, formatMoney } from "../lib/format"
import { paymentStatusLabel } from "../lib/statusLabels"
import { PageHeader } from "../components/ui/PageHeader"
import { DemoPaymentNotice } from "../components/ui/DemoPaymentNotice"
import { StatusPill } from "../components/ui/StatusPill"
import { Button } from "../components/ui/Button"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"

interface Receipt {
  id: string
  receiptNumber: string
  payerName: string
  payeeLabel: string
  description: string
  amount: number
  currency: string
  status: string
  providerReference: string | null
  createdAt: string
}

/** Simulated payment receipts, including minibus taxi fare payments. */
export default function RideHistoryPage() {
  const [receipts, setReceipts] = useState<Receipt[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    api.get<Page<Receipt>>("/receipts?size=50")
      .then((r) => setReceipts(r.content))
      .catch((reason: unknown) => {
        setError(reason instanceof Error ? reason.message : "Could not load receipts")
      })
  }, [])
  useEffect(load, [load])

  const selected = receipts?.find((r) => r.id === open) ?? null

  return (
    <div className="page stack">
      <PageHeader title="Payment receipts and history" subtitle="View recorded payments, including minibus taxi fares." />
      <DemoPaymentNotice kind="taxi" />
      {error && <ErrorState description={error} onRetry={load} />}
      {!error && receipts === null && <div className="stack"><SkeletonCard lines={2} /><SkeletonCard lines={2} /></div>}

      {receipts && (
        <section className="stack" aria-labelledby="rc-h">
          <h2 id="rc-h">Receipts</h2>
          {receipts.length === 0 ? (
            <EmptyState title="No receipts yet" description="When a payment is recorded, its receipt will appear here." />
          ) : (
            <ul className="order-list">
              {receipts.map((r) => (
                <li key={r.id}>
                  <button type="button" className="order-row card" style={{ width: "100%", textAlign: "left" }} aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? null : r.id)}>
                    <div>
                      <strong>{r.description}</strong>
                      <div className="muted">{r.receiptNumber} · {formatDateTime(r.createdAt)}</div>
                    </div>
                    <div className="row">
                      <span className="money">{formatMoney(r.amount, r.currency)}</span>
                      <StatusPill status={paymentStatusLabel(r.status)} />
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {selected && (
        <section className="card stack" aria-labelledby="rd-h">
          <h2 id="rd-h">Receipt {selected.receiptNumber}</h2>
          <dl className="stack">
            <div><dt className="muted">Date</dt><dd>{formatDateTime(selected.createdAt)}</dd></div>
            <div><dt className="muted">From</dt><dd>{selected.payerName}</dd></div>
            <div><dt className="muted">To</dt><dd>{selected.payeeLabel}</dd></div>
            <div><dt className="muted">For</dt><dd>{selected.description}</dd></div>
            <div><dt className="muted">Amount</dt><dd>{formatMoney(selected.amount, selected.currency)}</dd></div>
            <div><dt className="muted">Status</dt><dd><StatusPill status={paymentStatusLabel(selected.status)} /></dd></div>
            {selected.providerReference && <div><dt className="muted">Demo reference</dt><dd>{selected.providerReference}</dd></div>}
          </dl>
          <p className="refund-note">This receipt records a simulated payment. No real money moved and nobody was actually paid.</p>
          <div><Button variant="secondary" size="sm" onClick={() => window.print()}>Print</Button></div>
        </section>
      )}

    </div>
  )
}
