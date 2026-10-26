import { useCallback, useEffect, useState } from "react"
import { branchOpsApi, type Branch, type BranchOrder } from "../../lib/branchApi"
import { formatMoney } from "../../lib/format"
import { Button } from "../../components/ui/Button"

const NEXT: Record<string, { label: string; to: "PROCESSING" | "READY" | "COMPLETED" } | undefined> = {
  PAID: { label: "Start processing", to: "PROCESSING" },
  PROCESSING: { label: "Mark ready", to: "READY" },
  READY: { label: "Mark completed", to: "COMPLETED" },
}

/** Orders placed for this branch. Visible only with VIEW_ORDERS; status buttons only with FULFIL_ORDERS. The backend decides. */
export function BranchOrdersPanel({ branch }: { branch: Branch }) {
  const perms = new Set(branch.yourPermissions)
  const [orders, setOrders] = useState<BranchOrder[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const load = useCallback(async () => {
    try { setOrders(await branchOpsApi.orders(branch.businessId, branch.id)); setError(null) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load orders") }
  }, [branch.businessId, branch.id])
  useEffect(() => { if (perms.has("VIEW_ORDERS")) void load() }, [load]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!perms.has("VIEW_ORDERS")) return null
  async function step(o: BranchOrder, to: "PROCESSING" | "READY" | "COMPLETED") {
    setBusy(o.id); setError(null)
    try { await branchOpsApi.fulfil(branch.businessId, branch.id, o.id, to); await load() }
    catch (e) { setError(e instanceof Error ? e.message : "That did not work") }
    finally { setBusy(null) }
  }
  return (
    <div className="card stack">
      <h3>Branch orders</h3>
      {error && <p className="error-text" role="alert">{error}</p>}
      {orders?.length === 0 && <p className="muted">No orders for this branch yet.</p>}
      {orders?.map((o) => {
        const next = NEXT[o.status]
        return (
          <div key={o.id} className="stack" style={{ borderTop: "1px solid var(--line)", paddingTop: 8 }}>
            <div className="row between"><strong>{formatMoney(o.total, o.currency)}</strong><span>{o.status.replace(/_/g, " ")}</span></div>
            <div className="muted small">{new Date(o.createdAt).toLocaleString()}{o.buyerName ? ` · ${o.buyerName}` : ""}</div>
            <div className="small">{o.items.join(", ")}</div>
            {next && perms.has("FULFIL_ORDERS") && (
              <div><Button size="sm" disabled={busy === o.id} loading={busy === o.id} onClick={() => step(o, next.to)}>{next.label}</Button></div>
            )}
          </div>
        )
      })}
    </div>
  )
}
