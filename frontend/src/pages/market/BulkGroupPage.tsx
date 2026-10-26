import { useCallback, useEffect, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { bulkApi } from "../../lib/bulkApi"
import { ALLOCATION_STATUS_LABELS, GROUP_STATUS_LABELS } from "../../lib/bulkTypes"
import type {
  AllocationView, BulkEvent, FulfilmentMethod, GroupDetail, Quote, SupplierGroupView,
} from "../../lib/bulkTypes"
import { formatDate, formatDateTime, formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { Badge, statusTone } from "../../components/ui/Badge"
import { ProgressBar } from "../../components/ui/ProgressBar"
import { ErrorState } from "../../components/ui/StatePanel"

const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

function QuoteBox({ q }: { q: Quote }) {
  return (
    <div className="card" aria-live="polite">
      <p style={{ margin: 0 }}>Group would total <strong>{q.expectedAggregatePacks}</strong> packs{q.meetsMoq ? "" : " (below the supplier minimum)"}.</p>
      <p style={{ margin: 0 }}>
        Price per pack: <strong>{formatMoney(q.pricePerPack, q.currency)}</strong>
        {q.discountPerPack > 0 && <> — volume discount {formatMoney(q.discountPerPack, q.currency)} off {formatMoney(q.basePricePerPack, q.currency)}</>}
      </p>
      <p style={{ margin: 0 }}>Your goods {formatMoney(q.subtotal, q.currency)} + delivery {formatMoney(q.deliveryFee, q.currency)} = <strong>{formatMoney(q.total, q.currency)}</strong></p>
      {q.nextTier && <p className="muted" style={{ margin: 0 }}>Another {q.packsToNextTier} pack(s) across the group unlocks {formatMoney(q.nextTier.pricePerPack, q.currency)} per pack.</p>}
      <p className="muted" style={{ margin: 0 }}>Estimate only. The price you agree to is shown again when you confirm, and you are never charged more than you agree to.</p>
    </div>
  )
}

function JoinPanel({ d, onDone }: { d: GroupDetail; onDone: () => void }) {
  const g = d.group
  const [packs, setPacks] = useState("")
  const [method, setMethod] = useState<FulfilmentMethod>(g.allowSelfCollection ? "SELF_COLLECTION" : g.allowSupplierDelivery ? "WHOLESALER_DELIVERY" : "PULA_RIDE_DELIVERY")
  const [address, setAddress] = useState("")
  const [quote, setQuote] = useState<Quote | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const n = Number(packs)
    if (!n || n < 1) { setQuote(null); return }
    const t = setTimeout(() => { bulkApi.quote(g.id, n, method).then(setQuote).catch(() => setQuote(null)) }, 300)
    return () => clearTimeout(t)
  }, [packs, method, g.id])

  async function join() {
    setBusy(true); setError(null)
    try { await bulkApi.join(g.id, { requestedPacks: Number(packs), fulfilmentMethod: method, deliveryAddress: method === "SELF_COLLECTION" ? undefined : address }); onDone() }
    catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }
  return (
    <section className="card stack">
      <h2 style={{ margin: 0 }}>Join this group</h2>
      <p className="muted" style={{ margin: 0 }}>Joining only registers your interest. Nothing is ordered or charged until you confirm your quantity and price and then pay for your own share.</p>
      {error && <div className="state-panel" role="alert">{error}</div>}
      <label>How many packs do you want?<input type="number" min={1} max={g.targetPacks} value={packs} onChange={(e) => setPacks(e.target.value)} /></label>
      <label>Collection / delivery
        <select value={method} onChange={(e) => setMethod(e.target.value as FulfilmentMethod)}>
          {g.allowSelfCollection && <option value="SELF_COLLECTION">I will collect</option>}
          {g.allowSupplierDelivery && <option value="WHOLESALER_DELIVERY">Supplier delivers to me</option>}
          {g.allowPulaRide && <option value="PULA_RIDE_DELIVERY">PULA Ride delivers to me</option>}
        </select>
      </label>
      {method !== "SELF_COLLECTION" && <label>Your delivery address<input value={address} onChange={(e) => setAddress(e.target.value)} maxLength={500} /></label>}
      {g.collectionNote && <p className="muted" style={{ margin: 0 }}>Collection: {g.collectionNote}</p>}
      {quote && <QuoteBox q={quote} />}
      <Button onClick={join} loading={busy} disabled={!Number(packs)}>Join with {Number(packs) || "…"} pack(s)</Button>
    </section>
  )
}

function MyAllocationPanel({ d, a, onChange }: { d: GroupDetail; a: AllocationView; onChange: () => void }) {
  const g = d.group
  const [qty, setQty] = useState(String(a.requestedPacks))
  const [agree, setAgree] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [issueType, setIssueType] = useState<"DAMAGED" | "MISSING" | "PARTIAL_DELIVERY">("DAMAGED")
  const [issuePacks, setIssuePacks] = useState("1")
  const [issueNote, setIssueNote] = useState("")

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true); setError(null); setNotice(null)
    try { await fn(); onChange() } catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }
  const quote = a.quote
  const needsConfirm = g.status === "CONFIRMATION" && (a.status === "JOINED" || a.status === "PENDING_RECONFIRMATION")
  const canChange = (g.status === "OPEN" || g.status === "TARGET_REACHED" || g.status === "CONFIRMATION") && ["JOINED", "CONFIRMED", "PENDING_RECONFIRMATION"].includes(a.status)
  const canIssue = a.status === "PAID" && ["READY_FOR_COLLECTION", "OUT_FOR_DELIVERY", "DELIVERED"].includes(a.fulfilmentStatus ?? "")

  async function confirm() {
    if (!quote) return
    setBusy(true); setError(null); setNotice(null)
    try {
      const out = await bulkApi.confirm(g.id, a.requestedPacks, quote.pricePerPack)
      if (!out.confirmed) {
        setAgree(false)
        setNotice(out.reason === "PRICE_CHANGED"
          ? `The price changed to ${formatMoney(out.currentQuote.pricePerPack, out.currentQuote.currency)} per pack. Review it and confirm again.`
          : "Your quantity changed. Review it and confirm again.")
      }
      onChange()
    } catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }

  return (
    <section className="card stack">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
        <h2 style={{ margin: 0 }}>Your share — {a.businessName}</h2>
        <Badge tone={statusTone(a.status)}>{ALLOCATION_STATUS_LABELS[a.status]}</Badge>
      </div>
      {error && <div className="state-panel" role="alert">{error}</div>}
      {notice && <div className="state-panel" role="status">{notice}</div>}
      <p style={{ margin: 0 }}>
        Requested <strong>{a.requestedPacks}</strong>
        {a.confirmedPacks != null && <> · confirmed <strong>{a.confirmedPacks}</strong></>}
        {a.allocatedPacks != null && a.allocatedPacks !== a.confirmedPacks && <> · supplier allocated <strong>{a.allocatedPacks}</strong></>}
        {" "}· group total {g.aggregatePacks}/{g.targetPacks}
      </p>
      {a.status === "PENDING_RECONFIRMATION" && <p role="alert" style={{ margin: 0 }}>Something changed (your quantity or the group's price). You will not be charged more than you agreed unless you confirm again.</p>}

      {quote && canChange && <QuoteBox q={quote} />}

      {a.total != null && (
        <div className="card">
          <p style={{ margin: 0 }}>Final price per pack: <strong>{formatMoney(a.pricePerPack ?? 0, a.currency)}</strong>{(a.discountPerPack ?? 0) > 0 && <> (discount {formatMoney(a.discountPerPack ?? 0, a.currency)})</>}</p>
          <p style={{ margin: 0 }}>Goods {formatMoney(a.subtotal ?? 0, a.currency)} + delivery {formatMoney(a.deliveryFee ?? 0, a.currency)} = <strong>You pay {formatMoney(a.total, a.currency)}</strong></p>
          <p style={{ margin: 0 }}>Payment: <strong>{a.paymentStatus}</strong>{a.paymentSimulated && <> — <strong>SIMULATED payment, no real money moves</strong></>}{a.fulfilmentStatus && <> · Order: {a.fulfilmentStatus}</>}</p>
          <p className="muted" style={{ margin: 0 }}>You pay only for your own share. Another business paying does not pay yours, and yours does not pay theirs.</p>
          {a.status === "ORDERED" && <Link to="/market/stock/orders" className="btn btn-primary">Pay your share</Link>}
          {a.stockOrderId && a.status !== "ORDERED" && <Link to="/market/stock/orders" className="btn btn-ghost">Order &amp; receipt</Link>}
        </div>
      )}
      {a.shortagePacks > 0 && <p role="alert" style={{ margin: 0 }}>The supplier could not supply everything: you are short {a.shortagePacks} pack(s), shared out pro-rata with the other businesses.</p>}
      {a.refundDueAmount > 0 && <p role="alert" style={{ margin: 0 }}>{formatMoney(a.refundDueAmount, a.currency)} is owed back to you. Refunds for stock orders are handled manually and have <strong>not</strong> been paid yet.</p>}

      {needsConfirm && quote && (
        <div className="card stack">
          <label><input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} /> I agree to buy <strong>{a.requestedPacks}</strong> pack(s) for no more than <strong>{formatMoney(quote.pricePerPack, quote.currency)}</strong> each ({formatMoney(quote.total, quote.currency)} in total), paid by my business only.</label>
          <Button onClick={confirm} loading={busy} disabled={!agree}>Confirm my share</Button>
        </div>
      )}

      {canChange && (
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "end" }}>
          <label>Change quantity<input type="number" min={1} max={g.targetPacks} value={qty} onChange={(e) => setQty(e.target.value)} /></label>
          <Button variant="secondary" disabled={busy || Number(qty) === a.requestedPacks || !Number(qty)} onClick={() => run(() => bulkApi.updateAllocation(g.id, { requestedPacks: Number(qty) }))}>Update (you will need to confirm again)</Button>
          <Button variant="danger" disabled={busy} onClick={() => { if (window.confirm("Withdraw from this group? You have not paid anything yet.")) void run(() => bulkApi.withdraw(g.id)) }}>Withdraw</Button>
        </div>
      )}
      {a.status === "ORDERED" && <Button variant="danger" disabled={busy} onClick={() => { if (window.confirm("Cancel your unpaid share? The other businesses keep their price; the organiser decides whether to proceed.")) void run(() => bulkApi.withdraw(g.id)) }}>Cancel my unpaid share</Button>}

      {canIssue && (
        <details>
          <summary>Report damaged or missing items</summary>
          <div className="stack">
            <label>Problem
              <select value={issueType} onChange={(e) => setIssueType(e.target.value as typeof issueType)}>
                <option value="DAMAGED">Damaged</option><option value="MISSING">Missing</option><option value="PARTIAL_DELIVERY">Partial delivery</option>
              </select>
            </label>
            <label>Packs affected<input type="number" min={1} value={issuePacks} onChange={(e) => setIssuePacks(e.target.value)} /></label>
            <label>Note<input value={issueNote} onChange={(e) => setIssueNote(e.target.value)} maxLength={500} /></label>
            <Button variant="secondary" disabled={busy} onClick={() => run(() => bulkApi.reportIssue(g.id, issueType, Number(issuePacks), issueNote || undefined))}>Record claim</Button>
            <p className="muted" style={{ margin: 0 }}>This records a refund/credit claim for review. It does not refund you automatically.</p>
          </div>
        </details>
      )}
    </section>
  )
}

function OrganiserPanel({ d, onChange }: { d: GroupDetail; onChange: () => void }) {
  const g = d.group
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [below, setBelow] = useState(false)
  const [drop, setDrop] = useState(false)
  const [partial, setPartial] = useState(false)

  const run = async (fn: () => Promise<string | void>) => {
    setBusy(true); setError(null); setNotice(null)
    try { const m = await fn(); if (m) setNotice(m); onChange() } catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }
  return (
    <section className="card stack">
      <h2 style={{ margin: 0 }}>Organiser</h2>
      {error && <div className="state-panel" role="alert">{error}</div>}
      {notice && <div className="state-panel" role="status">{notice}</div>}
      {d.participants && (
        <table>
          <thead><tr><th>Business</th><th>Status</th><th>Requested</th><th>Confirmed</th></tr></thead>
          <tbody>{d.participants.map((p) => (
            <tr key={p.businessId}><td>{p.businessName}</td><td>{ALLOCATION_STATUS_LABELS[p.status]}</td><td>{p.requestedPacks}</td><td>{p.confirmedPacks ?? "—"}</td></tr>
          ))}</tbody>
        </table>
      )}
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {g.status === "DRAFT" && <Button disabled={busy} onClick={() => run(async () => { await bulkApi.openGroup(g.id) })}>Open for participation</Button>}
        {(g.status === "OPEN" || g.status === "TARGET_REACHED") && (
          <Button disabled={busy || g.aggregatePacks < g.moqPacks} onClick={() => run(async () => { await bulkApi.startConfirmation(g.id) })}>Close joining &amp; ask businesses to confirm</Button>
        )}
        {(g.status === "OPEN" || g.status === "TARGET_REACHED") && g.aggregatePacks < g.moqPacks && <span className="muted">Supplier minimum ({g.moqPacks}) not reached yet.</span>}
      </div>
      {g.status === "CONFIRMATION" && (
        <div className="card stack">
          <label><input type="checkbox" checked={drop} onChange={(e) => setDrop(e.target.checked)} /> Proceed without businesses that have not confirmed</label>
          <label><input type="checkbox" checked={below} onChange={(e) => setBelow(e.target.checked)} /> Proceed below the target (price follows the confirmed quantity)</label>
          <Button disabled={busy} onClick={() => run(async () => (await bulkApi.finalise(g.id, below, drop)).message)}>Finalise &amp; create each business's order</Button>
          <p className="muted" style={{ margin: 0 }}>Each business then pays its own share. Nobody can be charged more than they confirmed.</p>
        </div>
      )}
      {g.status === "PAYMENT_PENDING" && (
        <div className="card stack">
          <p style={{ margin: 0 }}>Waiting for each business to pay its own share. The combined order is placed with the supplier only when payments are confirmed.</p>
          <label><input type="checkbox" checked={partial} onChange={(e) => setPartial(e.target.checked)} /> If some have not paid, proceed with the paid shares only (unpaid shares are cancelled)</label>
          <Button variant="secondary" disabled={busy} onClick={() => run(async () => (await bulkApi.placeOrder(g.id, partial)).message)}>Place order with supplier</Button>
        </div>
      )}
      {["DRAFT", "OPEN", "TARGET_REACHED", "CONFIRMATION", "PAYMENT_PENDING"].includes(g.status) && (
        <Button variant="danger" disabled={busy} onClick={() => { const r = window.prompt("Reason for cancelling this group?"); if (r !== null) void run(async () => { await bulkApi.cancel(g.id, r) }) }}>Cancel group</Button>
      )}
    </section>
  )
}

function SupplierPanel({ d, onChange }: { d: GroupDetail; onChange: () => void }) {
  const [view, setView] = useState<SupplierGroupView | null>(null)
  const [packs, setPacks] = useState("")
  const [ref, setRef] = useState("")
  const [note, setNote] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { bulkApi.supplierView(d.group.id).then((v) => { setView(v); setPacks(String(v.orderedPacks)) }).catch((e) => setError(errMsg(e))) }, [d.group.id, d.group.status])
  const n = Number(packs)
  const short = view ? view.orderedPacks - n : 0
  async function confirm() {
    setBusy(true); setError(null)
    try { await bulkApi.supplierConfirm(d.group.id, n, ref || undefined, note || undefined); onChange() } catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }
  return (
    <section className="card stack">
      <h2 style={{ margin: 0 }}>Supplier — {d.group.procurementReference}</h2>
      {error && <div className="state-panel" role="alert">{error}</div>}
      {view && (
        <>
          <p style={{ margin: 0 }}>Combined demand: <strong>{view.orderedPacks}</strong> pack(s) · paid: <strong>{view.paidPacks}</strong></p>
          <table>
            <thead><tr><th>Business</th><th>Packs</th><th>Deliver / collect</th><th>Payment</th><th>Order</th><th>Shortage</th><th>Issues</th></tr></thead>
            <tbody>{view.lines.map((l) => (
              <tr key={l.stockOrderId ?? l.businessName}>
                <td>{l.businessName}</td><td>{l.packs}</td>
                <td>{l.fulfilmentMethod === "SELF_COLLECTION" ? "Collection" : l.deliveryAddress}</td>
                <td>{l.paymentStatus}</td><td>{l.orderStatus ?? "—"}</td><td>{l.shortagePacks}</td><td>{l.issuePacks}</td>
              </tr>
            ))}</tbody>
          </table>
          <p className="muted" style={{ margin: 0 }}>Progress each business's own order (preparing, delivery, delivered) from your normal stock orders.</p>
          {d.group.status === "ORDER_PLACED" && (
            <div className="card stack">
              <label>Packs you can supply (0 cancels the order)<input type="number" min={0} max={view.orderedPacks} value={packs} onChange={(e) => setPacks(e.target.value)} /></label>
              {short > 0 && n > 0 && <p role="alert" style={{ margin: 0 }}>Shortage of {short} pack(s). It will be shared pro-rata across the paid businesses (whole packs, largest remainder, ties to the earliest confirmation). Each is owed back its unsupplied portion; refunds are handled manually.</p>}
              {n === 0 && <p role="alert" style={{ margin: 0 }}>Every paid share will be marked as owed a refund and the group cancelled.</p>}
              <label>Your reference<input value={ref} onChange={(e) => setRef(e.target.value)} maxLength={100} /></label>
              <label>Note<input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
              <Button onClick={confirm} loading={busy} disabled={Number.isNaN(n) || n < 0 || n > view.orderedPacks}>Confirm order</Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

export default function BulkGroupPage() {
  const { id = "" } = useParams()
  const [d, setD] = useState<GroupDetail | null>(null)
  const [events, setEvents] = useState<BulkEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      // refresh syncs payment/fulfilment from the real orders; browsers who are not involved just read.
      let detail: GroupDetail
      try { detail = await bulkApi.refresh(id) } catch { detail = await bulkApi.get(id) }
      setD(detail); setError(null)
      if (detail.viewerRole !== "BROWSER") setEvents((await bulkApi.events(id)).content)
    } catch (e) { setError(errMsg(e)) }
  }, [id])
  useEffect(() => { void load() }, [load])

  if (error) return <div className="page"><ErrorState title="Could not load this group" description={error} /></div>
  if (!d) return <div className="page"><p className="muted">Loading…</p></div>
  const g = d.group
  const joinable = (g.status === "OPEN" || g.status === "TARGET_REACHED") && !d.myAllocation
  const rejoinable = (g.status === "OPEN" || g.status === "TARGET_REACHED") && d.myAllocation && ["WITHDRAWN", "LAPSED"].includes(d.myAllocation.status)
  return (
    <div className="page stack">
      <PageHeader eyebrow={`Ref ${g.procurementReference}`} title={g.name} subtitle={`${g.productName} · ${g.supplierBusinessName} · organised by ${g.creatorBusinessName}`} />
      <p style={{ margin: 0 }}><Link to="/market/stock/bulk">← Bulk buying</Link></p>
      <section className="card stack">
        <div style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
          <Badge tone={statusTone(g.status)}>{GROUP_STATUS_LABELS[g.status]}</Badge>
          <span className="muted">Deadline {formatDate(g.deadline)}</span>
        </div>
        {g.description && <p style={{ margin: 0 }}>{g.description}</p>}
        <ProgressBar value={g.aggregatePacks} max={g.targetPacks} />
        <p style={{ margin: 0 }}>Group total <strong>{g.aggregatePacks}</strong> / target {g.targetPacks} · supplier minimum {g.moqPacks} · {g.participantCount} business(es)</p>
        <p style={{ margin: 0 }}>Current price: <strong>{formatMoney(g.currentPricePerPack, g.currency)}</strong> per pack (standard {formatMoney(g.basePricePerPack, g.currency)})</p>
        {g.tiers.length > 0 ? (
          <table>
            <thead><tr><th>From (packs across the group)</th><th>Price per pack</th></tr></thead>
            <tbody>
              <tr><td>1</td><td>{formatMoney(g.basePricePerPack, g.currency)}</td></tr>
              {g.tiers.map((t) => <tr key={t.minPacks}><td>{t.minPacks}</td><td>{formatMoney(t.pricePerPack, g.currency)}{g.aggregatePacks >= t.minPacks && " ✓"}</td></tr>)}
            </tbody>
          </table>
        ) : <p className="muted" style={{ margin: 0 }}>This supplier has not configured volume discounts, so the price stays at the standard price.</p>}
        {d.finalPricePerPack != null && <p style={{ margin: 0 }}>Agreed price: <strong>{formatMoney(d.finalPricePerPack, g.currency)}</strong> per pack for {d.finalAggregatePacks} pack(s).</p>}
        {d.supplierConfirmedPacks != null && <p style={{ margin: 0 }}>Supplier confirmed {d.supplierConfirmedPacks} pack(s){d.supplierReference && <> · ref {d.supplierReference}</>}.</p>}
        {d.cancelReason && <p role="alert" style={{ margin: 0 }}>Cancelled: {d.cancelReason}</p>}
        <p className="muted" style={{ margin: 0 }}>Shortfall policy: {g.shortfallPolicy === "CANCEL" ? "cancel if the target is missed" : g.shortfallPolicy === "EXTEND" ? "extend the deadline, then cancel" : "ask each business to approve the new price"}. Combining orders does not make the businesses one entity or share any money.</p>
      </section>

      {(joinable || rejoinable) && <JoinPanel d={d} onDone={load} />}
      {d.myAllocation && <MyAllocationPanel d={d} a={d.myAllocation} onChange={load} />}
      {d.viewerRole === "CREATOR" && <OrganiserPanel d={d} onChange={load} />}
      {d.viewerRole === "SUPPLIER" && <SupplierPanel d={d} onChange={load} />}

      {events.length > 0 && (
        <section className="card stack">
          <h2 style={{ margin: 0 }}>History</h2>
          <ul>{events.map((e) => <li key={e.id}><span className="muted">{formatDateTime(e.createdAt)}</span> — {e.message ?? e.type}</li>)}</ul>
        </section>
      )}
    </div>
  )
}
