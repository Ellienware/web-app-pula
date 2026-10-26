import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { servicesApi } from "../../lib/servicesApi"
import { MATERIALS_LABELS, PRICING_LABELS } from "../../lib/servicesTypes"
import type {
  PricingModel, SearchFilters, ServiceBooking, ServiceCategory, ServiceInsights, ServiceListing, ServiceRequest,
} from "../../lib/servicesTypes"
import { clearActiveSaveToBuy, getActiveSaveToBuy } from "../../lib/saveToBuyContext"
import { formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"
import { SaveToBuyBanner } from "../../components/SaveToBuyBanner"

type Tab = "discover" | "requests" | "bookings"
const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")
const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random())

/**
 * Customer side of PULA Market - Services: discover, compare, request, review quotes, pay and review.
 * Prices and totals shown here come from the server; the client never sends an amount (payment charges the booking total).
 */
export function ServicesPage() {
  const [tab, setTab] = useState<Tab>("discover")
  const [categories, setCategories] = useState<ServiceCategory[]>([])
  const [filters, setFilters] = useState<SearchFilters>({})
  const [results, setResults] = useState<ServiceListing[]>([])
  const [compare, setCompare] = useState<string[]>([])
  const [compared, setCompared] = useState<ServiceListing[] | null>(null)
  const [compareNote, setCompareNote] = useState<ServiceInsights | null>(null)
  const [requests, setRequests] = useState<ServiceRequest[]>([])
  const [bookings, setBookings] = useState<ServiceBooking[]>([])
  const [requesting, setRequesting] = useState<ServiceListing | null>(null)
  const [form, setForm] = useState({ description: "", location: "", preferredDate: "", preferredTime: "", requirements: "" })
  const [explain, setExplain] = useState<Record<string, ServiceInsights>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const search = useCallback(async (f: SearchFilters) => {
    try { setResults((await servicesApi.search(f)).content) } catch (e) { setNotice(errMsg(e)) }
  }, [])
  const loadMine = useCallback(async () => {
    try {
      const [r, b] = await Promise.all([servicesApi.myRequests(), servicesApi.myBookings()])
      setRequests(r); setBookings(b)
    } catch (e) { setNotice(errMsg(e)) }
  }, [])
  useEffect(() => { void servicesApi.categories().then(setCategories).catch(() => undefined); void search({}); void loadMine() }, [search, loadMine])

  async function act(fn: () => Promise<unknown>, done?: string) {
    setNotice(null)
    try { await fn(); if (done) setNotice(done); await loadMine() } catch (e) { setNotice(errMsg(e)) }
  }

  function toggleCompare(id: string) {
    setCompare((c) => (c.includes(id) ? c.filter((x) => x !== id) : c.length < 4 ? [...c, id] : c))
  }

  async function pay(b: ServiceBooking) {
    setNotice(null)
    try {
      const active = getActiveSaveToBuy("SERVICE")
      const s = await servicesApi.pay(b.id, active?.id)
      if (active) clearActiveSaveToBuy()
      if (s.checkoutUrl) window.open(s.checkoutUrl, "_blank", "noopener,noreferrer")
      await loadMine()
    } catch (e) { setNotice(errMsg(e)) }
  }

  // SIMULATED demo-wallet payment. The amount comes from the server and the backend decides whether it was paid.
  async function payFromWallet(b: ServiceBooking) {
    setNotice(null)
    try {
      const active = getActiveSaveToBuy("SERVICE")
      const s = await servicesApi.walletStart(b.id, active?.id)
      if (active) clearActiveSaveToBuy()
      if (!window.confirm("Pay " + formatMoney(s.amount, s.currency) + " from your DEMO wallet? Simulated funds only; no real money moves and the provider is not paid in the real world.")) { await loadMine(); return }
      const r = await servicesApi.walletPay(b.id, s.amount)
      if (!r.paid) setNotice("The payment is not confirmed yet. Check the booking status shortly.")
      await loadMine()
    } catch (e) { setNotice(errMsg(e)) }
  }

  return (
    <div className="page stack">
      <PageHeader title="Services" />
      <Link to="/services/provider">Offer your services</Link>
      {notice && <div className="state-panel" role="status">{notice}</div>}
      <div className="tabs" role="tablist">
        {(["discover", "requests", "bookings"] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "tab active" : "tab"} onClick={() => setTab(t)}>
            {t === "discover" ? "Find a provider" : t === "requests" ? "My requests" : "My bookings"}
          </button>
        ))}
      </div>

      {tab === "discover" && (
        <>
          <section className="card">
            <input placeholder="Search services" value={filters.q ?? ""} onChange={(e) => setFilters({ ...filters, q: e.target.value })} />
            <select value={filters.categoryId ?? ""} onChange={(e) => setFilters({ ...filters, categoryId: e.target.value || undefined })}>
              <option value="">All categories</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.parentId ? "  - " : ""}{c.name}</option>)}
            </select>
            <select value={filters.pricingModel ?? ""} onChange={(e) => setFilters({ ...filters, pricingModel: (e.target.value || undefined) as PricingModel | undefined })}>
              <option value="">Any pricing</option>
              {(Object.keys(PRICING_LABELS) as PricingModel[]).map((m) => <option key={m} value={m}>{PRICING_LABELS[m]}</option>)}
            </select>
            <input placeholder="Area (e.g. Soweto)" value={filters.area ?? ""} onChange={(e) => setFilters({ ...filters, area: e.target.value || undefined })} />
            <input inputMode="decimal" placeholder="Max price" onChange={(e) => setFilters({ ...filters, maxPrice: Number(e.target.value) || undefined })} />
            <label><input type="checkbox" checked={!!filters.verifiedOnly} onChange={(e) => setFilters({ ...filters, verifiedOnly: e.target.checked })} /> Verified only</label>
            <button onClick={() => search(filters)}>Search</button>
          </section>
          {results.length === 0 && <p className="muted">No services match.</p>}
          {results.map((l) => (
            <article key={l.id} className="card">
              {l.imageUrls[0] && <img className="media-thumb" src={l.imageUrls[0]} alt="" />}
              <strong>{l.name}</strong> · {l.businessName} {l.providerVerification === "VERIFIED" && <span className="badge">Verified</span>}
              <p className="muted">{l.categoryName} · {PRICING_LABELS[l.pricingModel]}{l.price != null ? ` ${formatMoney(l.price, l.currency)}` : ""} · {MATERIALS_LABELS[l.materialsResponsibility]}</p>
              <p className="muted">{l.ratingCount > 0 ? `${l.ratingAverage.toFixed(1)} (${l.ratingCount})` : "No reviews yet"} · {l.serviceAreas.join(", ") || "Areas on request"}</p>
              <label><input type="checkbox" checked={compare.includes(l.id)} onChange={() => toggleCompare(l.id)} /> Compare</label>
              <button onClick={() => setRequesting(l)}>Request this service</button>
            </article>
          ))}
          {compare.length >= 2 && (
            <button onClick={async () => {
              try { setCompared(await servicesApi.compare(compare)); setCompareNote(await servicesApi.compareInsights(compare)) } catch (e) { setNotice(errMsg(e)) }
            }}>Compare {compare.length} services</button>
          )}
          {compared && (
            <section className="card">
              <h3>Comparison</h3>
              <table>
                <thead><tr><th></th>{compared.map((l) => <th key={l.id}>{l.businessName}</th>)}</tr></thead>
                <tbody>
                  <tr><td>Pricing</td>{compared.map((l) => <td key={l.id}>{PRICING_LABELS[l.pricingModel]}{l.price != null ? ` ${formatMoney(l.price, l.currency)}` : ""}</td>)}</tr>
                  <tr><td>Rating</td>{compared.map((l) => <td key={l.id}>{l.ratingCount ? l.ratingAverage.toFixed(1) : "-"}</td>)}</tr>
                  <tr><td>Verified</td>{compared.map((l) => <td key={l.id}>{l.providerVerification === "VERIFIED" ? "Yes" : "No"}</td>)}</tr>
                  <tr><td>Areas</td>{compared.map((l) => <td key={l.id}>{l.serviceAreas.join(", ") || "-"}</td>)}</tr>
                  <tr><td>Materials</td>{compared.map((l) => <td key={l.id}>{MATERIALS_LABELS[l.materialsResponsibility]}</td>)}</tr>
                </tbody>
              </table>
              {compareNote && <><ul>{compareNote.points.map((p) => <li key={p}>{p}</li>)}</ul><p className="muted">{compareNote.notice}</p></>}
            </section>
          )}
          {requesting && (
            <section className="card">
              <h3>Request: {requesting.name}</h3>
              <textarea placeholder="What do you need done?" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              <input placeholder="Address / location" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />
              <input type="date" value={form.preferredDate} onChange={(e) => setForm({ ...form, preferredDate: e.target.value })} />
              <input placeholder="Preferred time (e.g. morning)" value={form.preferredTime} onChange={(e) => setForm({ ...form, preferredTime: e.target.value })} />
              <textarea placeholder="Requirements (optional)" value={form.requirements} onChange={(e) => setForm({ ...form, requirements: e.target.value })} />
              <p className="muted">The provider sends you a quote; nothing is booked or charged until you accept it.</p>
              <button disabled={!form.description.trim() || !form.location.trim()} onClick={() => act(async () => {
                await servicesApi.createRequest({ listingId: requesting.id, description: form.description, location: form.location,
                  preferredDate: form.preferredDate || undefined, preferredTime: form.preferredTime || undefined,
                  requirements: form.requirements || undefined, submit: true, idempotencyKey: newKey() })
                setRequesting(null); setTab("requests")
              }, "Request sent")}>Send request</button>
              <button onClick={() => setRequesting(null)}>Cancel</button>
            </section>
          )}
        </>
      )}

      {tab === "requests" && (
        <>
          {requests.length === 0 && <p className="muted">No requests yet.</p>}
          {requests.map((r) => (
            <article key={r.id} className="card">
              <strong>{r.listingName}</strong> · {r.businessName} · <span className="badge">{r.status}</span>
              <p>{r.description}</p>
              {r.quotes.map((q) => (
                <div key={q.id} className="muted">
                  Quote v{q.versionNo} · <strong>{formatMoney(q.total, q.currency)}</strong> ({q.status}) · labour {formatMoney(q.labourAmount, q.currency)}
                  {q.materialsAmount > 0 ? ` · materials ${formatMoney(q.materialsAmount, q.currency)}` : ""}
                  {q.deliveryAmount > 0 ? ` · delivery ${formatMoney(q.deliveryAmount, q.currency)}` : ""} · valid until {new Date(q.validUntil).toLocaleString()}
                  {q.status === "SUBMITTED" && (
                    <>
                      <button onClick={() => act(() => servicesApi.acceptQuote(r.id, q.id), "Quote accepted: your booking is made")}>Accept</button>
                      <button onClick={() => act(() => servicesApi.rejectQuote(r.id, q.id), "Quote declined")}>Decline</button>
                      <button onClick={() => { const m = window.prompt("What would you like to ask?"); if (m) void act(() => servicesApi.clarifyQuote(r.id, q.id, m), "Question sent") }}>Ask a question</button>
                    </>
                  )}
                  <button onClick={async () => { try { setExplain({ ...explain, [q.id]: await servicesApi.explainQuote(r.id, q.id) }) } catch (e) { setNotice(errMsg(e)) } }}>Explain</button>
                  {explain[q.id] && <div><ul>{explain[q.id].points.map((p) => <li key={p}>{p}</li>)}</ul><p>{explain[q.id].notice}</p></div>}
                </div>
              ))}
              {["DRAFT", "SUBMITTED", "QUOTED"].includes(r.status) && <button onClick={() => act(() => servicesApi.cancelRequest(r.id), "Request cancelled")}>Cancel request</button>}
              {r.status === "DRAFT" && <button onClick={() => act(() => servicesApi.submitRequest(r.id), "Request sent")}>Send</button>}
            </article>
          ))}
        </>
      )}

      {tab === "bookings" && (
        <>
          <SaveToBuyBanner verticals={["SERVICE"]} />
          {bookings.length === 0 && <p className="muted">No bookings yet.</p>}
          {bookings.map((b) => (
            <article key={b.id} className="card">
              <strong>{b.listingName}</strong> · {b.businessName} · <span className="badge">{b.status}</span> · {formatMoney(b.total, b.currency)} · payment {b.paymentStatus}
              {b.scheduledDate && <p className="muted">Scheduled {b.scheduledDate} {b.scheduledTime ?? ""}</p>}
              {b.status === "COMPLETED" && b.paymentStatus !== "PAID" && <button onClick={() => pay(b)}>{b.paymentStatus === "PROCESSING" ? "Resume payment" : "Pay " + formatMoney(b.total, b.currency)}</button>}
              {b.status === "COMPLETED" && b.paymentStatus !== "PAID" && <button className="btn-secondary" onClick={() => payFromWallet(b)}>Pay from demo wallet (simulated)</button>}
              {["BOOKED", "IN_PROGRESS"].includes(b.status) && b.paymentStatus === "UNPAID" && (
                <button onClick={() => { const r = window.prompt("Reason for cancelling?"); if (r) void act(() => servicesApi.cancelBooking(b.id, r), "Booking cancelled") }}>Cancel booking</button>
              )}
              {b.canReview && (
                <button onClick={() => { const v = Number(window.prompt("Rating 1-5?")); if (v >= 1 && v <= 5) void act(() => servicesApi.review(b.id, v, "", true), "Thanks for your review") }}>Leave a review</button>
              )}
              {b.reviewed && <span className="muted"> Reviewed</span>}
            </article>
          ))}
        </>
      )}
    </div>
  )
}
