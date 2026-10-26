import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../lib/api"
import type { Order, Page, StockOrder } from "../lib/types"
import type { AllocationView } from "../lib/bulkTypes"
import { formatDateTime, formatMoney } from "../lib/format"
import { buildRefundList, summariseRefunds, type RefundItem } from "../lib/refundLogic"
import { refundStatusLabel } from "../lib/statusLabels"
import { PageHeader } from "../components/ui/PageHeader"
import { DemoPaymentNotice } from "../components/ui/DemoPaymentNotice"
import { StatusPill } from "../components/ui/StatusPill"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"

function linkFor(i: RefundItem): string {
  if (i.source === "ORDER") return `/orders/${i.refId}`
  if (i.source === "STOCK_ORDER") return "/market/stock/orders"
  return `/market/stock/bulk/${i.refId}`
}

/**
 * Refund status in one place. There is no refund-list endpoint, so this reads the same order, stock-order and
 * collective-purchase lists the rest of the app uses and shows only entries that are in a refund state.
 * Every refund here is a SIMULATED record: nothing is sent to a real account.
 */
export default function RefundsPage() {
  const [items, setItems] = useState<RefundItem[] | null>(null)
  const [partial, setPartial] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    Promise.allSettled([
      api.get<Page<Order>>("/orders/mine?size=50"),
      api.get<Page<StockOrder>>("/stock/orders/mine?size=50"),
      api.get<{ content: AllocationView[] }>("/bulk/allocations/mine?page=0&size=50"),
    ]).then(([o, s, b]) => {
      // Orders are available to every account; stock/collective lists only exist for business accounts, so a refusal there is normal.
      if (o.status === "rejected") {
        setError(o.reason instanceof Error ? o.reason.message : "Could not load your refunds")
        return
      }
      setPartial(false)
      setItems(
        buildRefundList({
          orders: o.value.content,
          stockOrders: s.status === "fulfilled" ? s.value.content : [],
          allocations: b.status === "fulfilled" ? b.value.content : [],
        }),
      )
    })
  }, [])
  useEffect(load, [load])

  const summary = items ? summariseRefunds(items) : null

  return (
    <div className="page stack">
      <PageHeader title="Refunds" subtitle="Where each refund stands, kept separate from the order itself." />
      <DemoPaymentNotice kind="refund" />
      {error && <ErrorState description={error} onRetry={load} />}
      {!error && items === null && <div className="stack"><SkeletonCard lines={2} /><SkeletonCard lines={2} /></div>}
      {items && summary && (
        <>
          <div className="row wrap" aria-label="Refund summary">
            <span className="status-pill status-warning">Requested: {summary.requested}</span>
            <span className="status-pill status-simulated">Recorded (demo): {summary.recorded}</span>
            <span className="status-pill status-warning">Due (demo): {summary.due}</span>
          </div>
          {partial && <p className="refund-note">Some lists could not be loaded, so this may not be complete.</p>}
          {items.length === 0 ? (
            <EmptyState title="No refunds" description="When an order or collective purchase is refunded, it will show up here." />
          ) : (
            <ul className="order-list">
              {items.map((i) => (
                <li key={i.key}>
                  <Link to={linkFor(i)} className="order-row card">
                    <div>
                      <strong>{i.title}</strong>
                      <div className="muted">{i.sourceLabel}{i.date ? ` · ${formatDateTime(i.date)}` : ""}</div>
                    </div>
                    <div className="row">
                      {i.amount != null && <span className="money">{formatMoney(i.amount, i.currency)}</span>}
                      <StatusPill status={refundStatusLabel(i.statusKey)} />
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
