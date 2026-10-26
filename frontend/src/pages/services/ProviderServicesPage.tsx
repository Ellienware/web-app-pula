import { useCallback, useEffect, useState } from "react"
import { servicesApi } from "../../lib/servicesApi"
import { MATERIALS_LABELS, PRICING_LABELS } from "../../lib/servicesTypes"
import type {
  MaterialsResponsibility, PricingModel, ProviderProfile, ServiceBooking, ServiceCategory, ServiceListing, ServiceRequest,
} from "../../lib/servicesTypes"
import { formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"
import { ImageField } from "../../components/ui/ImageField"

type Tab = "profile" | "listings" | "requests" | "bookings"
const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

/**
 * Provider side: profile, service listings, request inbox with quoting, and job status. The business needs the
 * "Provide services" capability (set on the business). Quote totals are calculated by the server from the inputs here.
 */
export function ProviderServicesPage() {
  const [tab, setTab] = useState<Tab>("profile")
  const [profile, setProfile] = useState<ProviderProfile | null>(null)
  const [categories, setCategories] = useState<ServiceCategory[]>([])
  const [listings, setListings] = useState<ServiceListing[]>([])
  const [inbox, setInbox] = useState<ServiceRequest[]>([])
  const [jobs, setJobs] = useState<ServiceBooking[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [pf, setPf] = useState({ headline: "", about: "", contactEmail: "", availabilityNote: "", areas: "", portfolio: "" })
  const [lf, setLf] = useState({ name: "", description: "", categoryId: "", pricingModel: "QUOTE_REQUIRED" as PricingModel,
    price: "", materials: "NONE" as MaterialsResponsibility, areas: "", images: [] as string[] })
  const [qf, setQf] = useState<Record<string, { labour: string; hours: string; delivery: string; other: string; otherDesc: string; days: string }>>({})

  const load = useCallback(async () => {
    try {
      setCategories(await servicesApi.categories())
      try { const p = await servicesApi.myProfile(); setProfile(p); setListings(await servicesApi.myListings()) } catch { setProfile(null) }
      try { setInbox(await servicesApi.inbox()); setJobs(await servicesApi.providerBookings()) } catch { /* not a provider yet */ }
    } catch (e) { setNotice(errMsg(e)) }
  }, [])
  useEffect(() => { void load() }, [load])

  async function act(fn: () => Promise<unknown>, done?: string) {
    setNotice(null)
    try { await fn(); if (done) setNotice(done); await load() } catch (e) { setNotice(errMsg(e)) }
  }
  const list = (s: string) => s.split(",").map((x) => x.trim()).filter(Boolean)
  const form = (id: string) => qf[id] ?? { labour: "", hours: "", delivery: "", other: "", otherDesc: "", days: "7" }

  return (
    <div className="page stack">
      <PageHeader title="Provide services" />
      <p className="muted">Your business needs the "Provide services" capability. PULA calculates every quote total from your inputs; customers cannot change it.</p>
      {notice && <div className="state-panel" role="status">{notice}</div>}
      <div className="tabs" role="tablist">
        {(["profile", "listings", "requests", "bookings"] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "tab active" : "tab"} onClick={() => setTab(t)}>
            {t === "profile" ? "Profile" : t === "listings" ? "My services" : t === "requests" ? "Requests" : "Jobs"}
          </button>
        ))}
      </div>

      {tab === "profile" && (
        <section className="card">
          {profile && <p>Status: <span className="badge">{profile.verificationStatus}</span>{profile.suspended ? " · suspended" : ""} · rating {profile.ratingCount ? profile.ratingAverage.toFixed(1) : "-"}</p>}
          <input placeholder="Headline" value={pf.headline} onChange={(e) => setPf({ ...pf, headline: e.target.value })} />
          <textarea placeholder="About your business" value={pf.about} onChange={(e) => setPf({ ...pf, about: e.target.value })} />
          <input placeholder="Contact email" value={pf.contactEmail} onChange={(e) => setPf({ ...pf, contactEmail: e.target.value })} />
          <input placeholder="Availability (e.g. Mon-Sat 8-5)" value={pf.availabilityNote} onChange={(e) => setPf({ ...pf, availabilityNote: e.target.value })} />
          <input placeholder="Service areas, comma separated" value={pf.areas} onChange={(e) => setPf({ ...pf, areas: e.target.value })} />
          <input placeholder="Portfolio image links (https), comma separated" value={pf.portfolio} onChange={(e) => setPf({ ...pf, portfolio: e.target.value })} />
          <button onClick={() => act(() => servicesApi.saveProfile({ headline: pf.headline, about: pf.about, contactEmail: pf.contactEmail,
            availabilityNote: pf.availabilityNote, acceptingRequests: true, serviceAreas: list(pf.areas), portfolioUrls: list(pf.portfolio) }), "Profile saved")}>Save profile</button>
          {profile && profile.verificationStatus === "UNVERIFIED" && (
            <button onClick={() => act(() => servicesApi.requestVerification(), "Verification requested")}>Ask PULA to verify my business</button>
          )}
        </section>
      )}

      {tab === "listings" && (
        <>
          <section className="card">
            <h3>New service</h3>
            <input placeholder="Name" value={lf.name} onChange={(e) => setLf({ ...lf, name: e.target.value })} />
            <textarea placeholder="Description" value={lf.description} onChange={(e) => setLf({ ...lf, description: e.target.value })} />
            <select value={lf.categoryId} onChange={(e) => setLf({ ...lf, categoryId: e.target.value })}>
              <option value="">Category</option>
              {categories.map((c) => <option key={c.id} value={c.id}>{c.parentId ? "  - " : ""}{c.name}</option>)}
            </select>
            <select value={lf.pricingModel} onChange={(e) => setLf({ ...lf, pricingModel: e.target.value as PricingModel })}>
              {(Object.keys(PRICING_LABELS) as PricingModel[]).map((m) => <option key={m} value={m}>{PRICING_LABELS[m]}</option>)}
            </select>
            {lf.pricingModel !== "QUOTE_REQUIRED" && <input inputMode="decimal" placeholder="Price" value={lf.price} onChange={(e) => setLf({ ...lf, price: e.target.value })} />}
            <select value={lf.materials} onChange={(e) => setLf({ ...lf, materials: e.target.value as MaterialsResponsibility })}>
              {(Object.keys(MATERIALS_LABELS) as MaterialsResponsibility[]).map((m) => <option key={m} value={m}>{MATERIALS_LABELS[m]}</option>)}
            </select>
            <input placeholder="Areas, comma separated" value={lf.areas} onChange={(e) => setLf({ ...lf, areas: e.target.value })} />
            <ImageField value={lf.images} onChange={(images) => setLf({ ...lf, images })} />
            <button disabled={!lf.name.trim() || !lf.categoryId} onClick={() => act(() => servicesApi.createListing({
              categoryId: lf.categoryId, name: lf.name, description: lf.description || undefined, pricingModel: lf.pricingModel,
              price: lf.pricingModel === "QUOTE_REQUIRED" ? undefined : Number(lf.price), materialsResponsibility: lf.materials,
              serviceAreas: list(lf.areas), imageUrls: lf.images, status: "ACTIVE" }).then(() => setLf({ ...lf, name: "", description: "", price: "", areas: "", images: [] })), "Service published")}>Publish service</button>
          </section>
          {listings.map((l) => (
            <article key={l.id} className="card">
              {l.imageUrls[0] && <img className="media-thumb" src={l.imageUrls[0]} alt="" />}
              <strong>{l.name}</strong> · {l.categoryName} · <span className="badge">{l.status}</span>{l.hiddenByAdmin ? " · hidden by PULA" : ""}
              <p className="muted">{PRICING_LABELS[l.pricingModel]}{l.price != null ? ` ${formatMoney(l.price, l.currency)}` : ""}</p>
              <button onClick={() => act(() => servicesApi.updateListing(l.id, {
                categoryId: l.categoryId, name: l.name, description: l.description ?? undefined, pricingModel: l.pricingModel,
                price: l.price ?? undefined, materialsResponsibility: l.materialsResponsibility, status: l.status === "ACTIVE" ? "PAUSED" : "ACTIVE" }))}>
                {l.status === "ACTIVE" ? "Pause" : "Activate"}
              </button>
              <button onClick={() => act(() => servicesApi.removeListing(l.id), "Removed")}>Remove</button>
            </article>
          ))}
        </>
      )}

      {tab === "requests" && (
        <>
          {inbox.length === 0 && <p className="muted">No requests yet.</p>}
          {inbox.map((r) => {
            const f = form(r.id)
            const set = (patch: Partial<typeof f>) => setQf({ ...qf, [r.id]: { ...f, ...patch } })
            const canQuote = r.status === "SUBMITTED" || (r.status === "QUOTED" && !r.quotes.some((q) => q.status === "SUBMITTED"))
            return (
              <article key={r.id} className="card">
                <strong>{r.listingName}</strong> · {r.customerName} · <span className="badge">{r.status}</span>
                <p>{r.description}</p>
                <p className="muted">{r.location}{r.preferredDate ? ` · ${r.preferredDate}` : ""} · {MATERIALS_LABELS[r.materialsResponsibility]}</p>
                {r.quotes.map((q) => <p key={q.id} className="muted">v{q.versionNo}: {formatMoney(q.total, q.currency)} ({q.status}){q.customerMessage ? ` · asks: ${q.customerMessage}` : ""}</p>)}
                {canQuote && (
                  <>
                    {r.pricingModel === "HOURLY" && <input inputMode="decimal" placeholder={`Hours (rate ${r.listingPrice ?? ""})`} value={f.hours} onChange={(e) => set({ hours: e.target.value })} />}
                    {(r.pricingModel === "QUOTE_REQUIRED" || r.pricingModel === "STARTING_FROM") && <input inputMode="decimal" placeholder="Labour amount" value={f.labour} onChange={(e) => set({ labour: e.target.value })} />}
                    <input inputMode="decimal" placeholder="Delivery (optional)" value={f.delivery} onChange={(e) => set({ delivery: e.target.value })} />
                    <input inputMode="decimal" placeholder="Other amount (optional)" value={f.other} onChange={(e) => set({ other: e.target.value })} />
                    <input placeholder="What the other amount is for" value={f.otherDesc} onChange={(e) => set({ otherDesc: e.target.value })} />
                    <input inputMode="numeric" placeholder="Valid for (days)" value={f.days} onChange={(e) => set({ days: e.target.value })} />
                    <p className="muted">Materials from PULA Stock lines are added automatically at stock prices. PULA calculates the total.</p>
                    <button onClick={() => act(() => servicesApi.quote(r.id, {
                      hours: f.hours ? Number(f.hours) : undefined, labourAmount: f.labour ? Number(f.labour) : undefined,
                      deliveryAmount: f.delivery ? Number(f.delivery) : undefined, otherAmount: f.other ? Number(f.other) : undefined,
                      otherDescription: f.otherDesc || undefined, validUntil: new Date(Date.now() + Math.min(Math.max(Number(f.days) || 7, 1), 30) * 86400000).toISOString() }), "Quote sent")}>Send quote</button>
                    <button onClick={() => act(() => servicesApi.decline(r.id), "Declined")}>Decline</button>
                  </>
                )}
              </article>
            )
          })}
        </>
      )}

      {tab === "bookings" && (
        <>
          {jobs.length === 0 && <p className="muted">No bookings yet.</p>}
          {jobs.map((b) => (
            <article key={b.id} className="card">
              <strong>{b.listingName}</strong> · {b.customerName} · <span className="badge">{b.status}</span> · {formatMoney(b.total, b.currency)} · payment {b.paymentStatus}
              {b.status === "BOOKED" && <button onClick={() => act(() => servicesApi.start(b.id), "Job started")}>Start job</button>}
              {b.status === "IN_PROGRESS" && <button onClick={() => act(() => servicesApi.complete(b.id), "Marked complete: the customer can now pay")}>Mark complete</button>}
              {["BOOKED", "IN_PROGRESS"].includes(b.status) && b.paymentStatus === "UNPAID" && (
                <button onClick={() => { const r = window.prompt("Reason for cancelling?"); if (r) void act(() => servicesApi.cancelBooking(b.id, r), "Cancelled") }}>Cancel</button>
              )}
            </article>
          ))}
        </>
      )}
    </div>
  )
}
