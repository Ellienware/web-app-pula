import { useCallback, useEffect, useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { api } from "../../lib/api"
import { bulkApi } from "../../lib/bulkApi"
import { ALLOCATION_STATUS_LABELS, GROUP_STATUS_LABELS } from "../../lib/bulkTypes"
import type { AllocationView, GroupSummary, ShortfallPolicy } from "../../lib/bulkTypes"
import type { Page, WholesaleProduct } from "../../lib/types"
import { formatDate, formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { Badge, statusTone } from "../../components/ui/Badge"
import { ProgressBar } from "../../components/ui/ProgressBar"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"

type Tab = "discover" | "mine" | "purchases" | "supplier" | "create"
const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

function GroupCard({ g }: { g: GroupSummary }) {
  const savings = g.basePricePerPack - g.currentPricePerPack
  return (
    <article className="card stack">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
        <strong>{g.name}</strong>
        <Badge tone={statusTone(g.status)}>{GROUP_STATUS_LABELS[g.status]}</Badge>
      </div>
      <p className="muted" style={{ margin: 0 }}>{g.productName} · supplier {g.supplierBusinessName} · ref {g.procurementReference}</p>
      <ProgressBar value={g.aggregatePacks} max={g.targetPacks} />
      <p style={{ margin: 0 }}>
        <strong>{g.aggregatePacks}</strong> of {g.targetPacks} packs from {g.participantCount} business(es) · supplier minimum {g.moqPacks}
      </p>
      <p style={{ margin: 0 }}>
        Price now: <strong>{formatMoney(g.currentPricePerPack, g.currency)}</strong> per pack
        {savings > 0 && <span className="muted"> (save {formatMoney(savings, g.currency)} vs {formatMoney(g.basePricePerPack, g.currency)})</span>}
      </p>
      <p className="muted" style={{ margin: 0 }}>Closes {formatDate(g.deadline)}</p>
      <Link to={`/market/stock/bulk/${g.id}`} className="btn btn-secondary">View</Link>
    </article>
  )
}

function AllocationRow({ a }: { a: AllocationView }) {
  return (
    <article className="card stack">
      <div style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
        <strong>{a.groupName}</strong>
        <Badge tone={statusTone(a.status)}>{ALLOCATION_STATUS_LABELS[a.status]}</Badge>
      </div>
      <p className="muted" style={{ margin: 0 }}>ref {a.procurementReference}</p>
      <p style={{ margin: 0 }}>
        {a.allocatedPacks ?? a.confirmedPacks ?? a.requestedPacks} pack(s)
        {a.total != null && <> · you pay <strong>{formatMoney(a.total, a.currency)}</strong></>}
        {" "}· payment: {a.paymentStatus}{a.paymentSimulated && " (SIMULATED — no real money)"}
        {a.fulfilmentStatus && <> · order: {a.fulfilmentStatus}</>}
      </p>
      {a.refundDueAmount > 0 && <p role="alert" style={{ margin: 0 }}>{formatMoney(a.refundDueAmount, a.currency)} is owed back to you. Refunds are handled manually and are not yet paid.</p>}
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <Link to={`/market/stock/bulk/${a.groupId}`} className="btn btn-secondary">Open group</Link>
        {a.stockOrderId && <Link to="/market/stock/orders" className="btn btn-ghost">Order, payment &amp; receipt</Link>}
      </div>
    </article>
  )
}

function CreateGroupForm({ onCreated }: { onCreated: (id: string) => void }) {
  const [products, setProducts] = useState<WholesaleProduct[]>([])
  const [productId, setProductId] = useState("")
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [target, setTarget] = useState("100")
  const [deadline, setDeadline] = useState("")
  const [policy, setPolicy] = useState<ShortfallPolicy>("CANCEL")
  const [collect, setCollect] = useState(true)
  const [supplierDelivery, setSupplierDelivery] = useState(false)
  const [ride, setRide] = useState(false)
  const [note, setNote] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.get<Page<WholesaleProduct>>("/stock/products?size=50").then((p) => setProducts(p.content)).catch((e) => setError(errMsg(e)))
  }, [])
  const product = products.find((p) => p.id === productId)

  async function submit() {
    if (!productId || !name.trim() || !deadline) { setError("Choose a product, give the group a name and set a deadline"); return }
    setBusy(true); setError(null)
    try {
      const created = await bulkApi.create({
        name: name.trim(), description: description.trim() || undefined, wholesaleProductId: productId,
        targetPacks: Number(target), deadline: new Date(deadline).toISOString(), shortfallPolicy: policy,
        allowSelfCollection: collect, allowSupplierDelivery: supplierDelivery, allowPulaRide: ride,
        collectionNote: note.trim() || undefined,
      })
      onCreated(created.group.id)
    } catch (e) { setError(errMsg(e)) } finally { setBusy(false) }
  }

  return (
    <section className="card stack">
      <h2 style={{ margin: 0 }}>Start a bulk purchase</h2>
      <p className="muted" style={{ margin: 0 }}>
        Businesses buy together to reach the supplier's minimum or a volume price. You stay separate businesses: each pays only for its own share.
      </p>
      {error && <div className="state-panel" role="alert">{error}</div>}
      <label>Product
        <select value={productId} onChange={(e) => setProductId(e.target.value)}>
          <option value="">Select a wholesale product…</option>
          {products.map((p) => <option key={p.id} value={p.id}>{p.name} — {p.supplierBusinessName} ({formatMoney(p.pricePerPack, p.currency)}/pack, min {p.moq})</option>)}
        </select>
      </label>
      {product && <p className="muted" style={{ margin: 0 }}>Supplier minimum order: {product.moq} pack(s) of {product.packSize}. Volume prices are whatever the supplier has configured; none are invented.</p>}
      <label>Group name<input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} /></label>
      <label>Description<textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} /></label>
      <label>Target (packs)<input type="number" min={1} value={target} onChange={(e) => setTarget(e.target.value)} /></label>
      <label>Order deadline<input type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} /></label>
      <label>If the target is not reached by the deadline
        <select value={policy} onChange={(e) => setPolicy(e.target.value as ShortfallPolicy)}>
          <option value="CANCEL">Cancel the group (nothing is charged)</option>
          <option value="EXTEND">Extend the deadline (up to 3 times), then cancel</option>
          <option value="ASK_PARTICIPANTS">Ask each business to approve the new price if the minimum is met</option>
        </select>
      </label>
      <fieldset>
        <legend>Collection / delivery options</legend>
        <label><input type="checkbox" checked={collect} onChange={(e) => setCollect(e.target.checked)} /> Self-collection</label>{" "}
        <label><input type="checkbox" checked={supplierDelivery} onChange={(e) => setSupplierDelivery(e.target.checked)} /> Supplier delivery (each business gives its own address)</label>{" "}
        <label><input type="checkbox" checked={ride} onChange={(e) => setRide(e.target.checked)} /> PULA Ride delivery</label>
      </fieldset>
      <label>Collection note<input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
      <Button onClick={submit} loading={busy}>Create draft</Button>
    </section>
  )
}

/** Bulk-purchase hub: discover, create, my groups, my purchases (history) and supplier orders. */
export default function BulkBuyPage() {
  const nav = useNavigate()
  const [tab, setTab] = useState<Tab>("discover")
  const [groups, setGroups] = useState<GroupSummary[]>([])
  const [allocs, setAllocs] = useState<AllocationView[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (t: Tab) => {
    if (t === "create") return
    setLoading(true); setError(null)
    try {
      if (t === "purchases") setAllocs((await bulkApi.myAllocations()).content)
      else if (t === "mine") setGroups((await bulkApi.mine()).content)
      else if (t === "supplier") setGroups((await bulkApi.forSupplier()).content)
      else setGroups((await bulkApi.open()).content)
    } catch (e) { setError(errMsg(e)); setGroups([]); setAllocs([]) } finally { setLoading(false) }
  }, [])
  useEffect(() => { void load(tab) }, [tab, load])

  const tabs: [Tab, string][] = [["discover", "Open groups"], ["mine", "My groups"], ["purchases", "My purchases"], ["supplier", "As supplier"], ["create", "Start a group"]]
  return (
    <div className="page stack">
      <PageHeader eyebrow="PULA Market · Stock" title="Bulk buying" subtitle="Combine demand with other businesses to meet supplier minimums and unlock volume prices." />
      <p style={{ margin: 0 }}><Link to="/market/stock">← Stock market</Link></p>
      <div role="tablist" style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {tabs.map(([t, label]) => (
          <button key={t} role="tab" aria-selected={tab === t} className={`btn ${tab === t ? "btn-primary" : "btn-ghost"}`} onClick={() => setTab(t)}>{label}</button>
        ))}
      </div>
      {tab === "create" && <CreateGroupForm onCreated={(id) => nav(`/market/stock/bulk/${id}`)} />}
      {error && <ErrorState title="Could not load" description={error} />}
      {loading && <p className="muted">Loading…</p>}
      {!loading && !error && tab === "purchases" && (allocs.length === 0
        ? <EmptyState title="No bulk purchases yet" description="When you join a group, your own share, payment and delivery status appear here." />
        : allocs.map((a) => <AllocationRow key={a.id} a={a} />))}
      {!loading && !error && (tab === "discover" || tab === "mine" || tab === "supplier") && (groups.length === 0
        ? <EmptyState title={tab === "discover" ? "No open groups right now" : "Nothing here yet"} description={tab === "discover" ? "Start one and invite other businesses." : undefined} />
        : <div className="grid">{groups.map((g) => <GroupCard key={g.id} g={g} />)}</div>)}
    </div>
  )
}
