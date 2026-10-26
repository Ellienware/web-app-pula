import { useCallback, useEffect, useState } from "react"
import { branchOpsApi, type Branch, type BranchStockItem, type StockMovement } from "../../lib/branchApi"
import { formatMoney } from "../../lib/format"
import { Button } from "../../components/ui/Button"
import { SkeletonCard } from "../../components/ui/Skeleton"

/**
 * Branch stock for non-food products. Central stock is the business pool; a branch holds an allocation from it.
 * Only the owner moves stock to/from the pool; a transfer between branches needs the stock permission at both. The backend enforces all of it.
 */
export function BranchStockPanel({ branch, siblings }: { branch: Branch; siblings: Branch[] }) {
  const { businessId, id } = branch
  const perms = new Set(branch.yourPermissions)
  const isOwner = branch.yourRole === "OWNER"
  const canPrice = perms.has("MANAGE_PRICES") && branch.status !== "CLOSED"
  const canMove = perms.has("MANAGE_STOCK")
  const [items, setItems] = useState<BranchStockItem[] | null>(null)
  const [moves, setMoves] = useState<StockMovement[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [qty, setQty] = useState<Record<string, string>>({})
  const [price, setPrice] = useState<Record<string, string>>({})
  const [dest, setDest] = useState<Record<string, string>>({})
  const others = siblings.filter((b) => b.id !== id && b.status === "ACTIVE")

  const load = useCallback(async () => {
    try { setItems(await branchOpsApi.stock(businessId, id)); setError(null) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load stock") }
  }, [businessId, id])
  useEffect(() => { void load() }, [load])

  async function act(fn: () => Promise<unknown>) {
    setBusy(true); setError(null)
    try { await fn(); await load(); if (moves) setMoves(await branchOpsApi.movements(businessId, id)) }
    catch (e) { setError(e instanceof Error ? e.message : "That did not work") }
    finally { setBusy(false) }
  }
  const n = (p: string) => Math.max(0, Math.floor(Number(qty[p] ?? "")))

  if (items === null && !error) return <SkeletonCard />
  return (
    <div className="card stack">
      <h3>Stock &amp; prices at this branch</h3>
      <p className="muted small">
        Customers can buy an item here only if it is switched on and in stock. Stock moved here comes out of your central stock; the numbers always add up.
        Demo wallet payments for these orders are simulated.
      </p>
      {error && <p className="error-text" role="alert">{error}</p>}
      {items?.length === 0 && <p className="muted">No products yet. Add products to your catalogue first.</p>}
      {items?.map((it) => (
        <div key={it.productId} className="stack" style={{ borderTop: "1px solid var(--line)", paddingTop: 8 }}>
          <div className="row between">
            <strong>{it.productName}</strong>
            <span>{it.available ? "On sale here" : "Not sold here"} · {it.quantity} here{it.centralStock != null ? ` · ${it.centralStock} central` : ""}</span>
          </div>
          <div className="muted small">Price: {formatMoney(it.effectivePrice, it.currency)}{it.priceOverride != null ? " (branch price)" : " (catalogue price)"}</div>
          <div className="row wrap">
            {canPrice && (
              <>
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => act(() => branchOpsApi.setItem(businessId, id, it.productId, { available: !it.available }))}>
                  {it.available ? "Stop selling here" : "Sell here"}
                </Button>
                <input aria-label={`Branch price for ${it.productName}`} style={{ width: 110 }} inputMode="decimal" placeholder="Branch price"
                  value={price[it.productId] ?? ""} onChange={(e) => setPrice((p) => ({ ...p, [it.productId]: e.target.value }))} />
                <Button size="sm" variant="secondary" disabled={busy || !Number(price[it.productId])}
                  onClick={() => act(() => branchOpsApi.setItem(businessId, id, it.productId, { priceOverride: Number(price[it.productId]) }))}>Set price</Button>
                {it.priceOverride != null && (
                  <Button size="sm" variant="ghost" disabled={busy} onClick={() => act(() => branchOpsApi.setItem(businessId, id, it.productId, { clearPriceOverride: true }))}>Use catalogue price</Button>
                )}
              </>
            )}
          </div>
          {(isOwner || canMove) && branch.status !== "CLOSED" && (
            <div className="row wrap">
              <input aria-label={`Quantity for ${it.productName}`} style={{ width: 80 }} inputMode="numeric" placeholder="Qty"
                value={qty[it.productId] ?? ""} onChange={(e) => setQty((p) => ({ ...p, [it.productId]: e.target.value }))} />
              {isOwner && branch.status === "ACTIVE" && (
                <Button size="sm" disabled={busy || n(it.productId) < 1} onClick={() => act(() => branchOpsApi.allocate(businessId, id, it.productId, n(it.productId)))}>Add from central stock</Button>
              )}
              {isOwner && (
                <Button size="sm" variant="secondary" disabled={busy || n(it.productId) < 1} onClick={() => act(() => branchOpsApi.returnToCentral(businessId, id, it.productId, n(it.productId)))}>Return to central</Button>
              )}
              {canMove && others.length > 0 && (
                <>
                  <select aria-label="Destination branch" value={dest[it.productId] ?? ""} onChange={(e) => setDest((p) => ({ ...p, [it.productId]: e.target.value }))}>
                    <option value="">Transfer to…</option>
                    {others.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                  <Button size="sm" variant="secondary" disabled={busy || n(it.productId) < 1 || !dest[it.productId]}
                    onClick={() => act(() => branchOpsApi.transfer(businessId, id, dest[it.productId], it.productId, n(it.productId)))}>Transfer</Button>
                </>
              )}
            </div>
          )}
        </div>
      ))}
      {canMove && (
        <div>
          <Button variant="ghost" size="sm" onClick={async () => setMoves(moves ? null : await branchOpsApi.movements(businessId, id).catch(() => []))}>
            {moves ? "Hide stock history" : "Show stock history"}
          </Button>
          {moves && (moves.length === 0 ? <p className="muted small">No stock movements yet.</p> : moves.map((m) => (
            <div key={m.id} className="muted small">{new Date(m.createdAt).toLocaleString()} · {m.type.replace(/_/g, " ").toLowerCase()} · {m.quantity}{m.note ? ` · ${m.note}` : ""}</div>
          )))}
        </div>
      )}
    </div>
  )
}
