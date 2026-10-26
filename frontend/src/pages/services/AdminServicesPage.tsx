import { useCallback, useEffect, useState } from "react"
import { servicesApi } from "../../lib/servicesApi"
import type { ProviderProfile, ServiceBooking, ServiceCategory } from "../../lib/servicesTypes"
import { formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"

const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

/**
 * Admin moderation for Services. The server requires an ADMIN on every call (others get an error here). Admins manage
 * categories, verification, suspension and listing visibility, and can see bookings; nothing here changes money.
 */
export function AdminServicesPage() {
  const [cats, setCats] = useState<ServiceCategory[]>([])
  const [pending, setPending] = useState<ProviderProfile[]>([])
  const [bookings, setBookings] = useState<ServiceBooking[]>([])
  const [name, setName] = useState("")
  const [parentId, setParentId] = useState("")
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [c, p, b] = await Promise.all([servicesApi.adminCategories(), servicesApi.adminPending(), servicesApi.adminBookings()])
      setCats(c); setPending(p); setBookings(b)
    } catch (e) { setNotice(errMsg(e)) }
  }, [])
  useEffect(() => { void load() }, [load])
  async function act(fn: () => Promise<unknown>) { setNotice(null); try { await fn(); await load() } catch (e) { setNotice(errMsg(e)) } }

  return (
    <div className="page stack">
      <PageHeader title="Services admin" />
      {notice && <div className="state-panel" role="alert">{notice}</div>}
      <section className="card">
        <h3>Categories</h3>
        <input placeholder="New category" value={name} onChange={(e) => setName(e.target.value)} />
        <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
          <option value="">Top level</option>
          {cats.filter((c) => !c.parentId).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <button disabled={!name.trim()} onClick={() => act(async () => { await servicesApi.adminCreateCategory(name, parentId || undefined); setName("") })}>Add</button>
        <ul>{cats.map((c) => (
          <li key={c.id}>{c.parentId ? "- " : ""}{c.name} {c.active ? "" : "(inactive)"}
            <button onClick={() => act(() => servicesApi.adminSetCategoryActive(c, !c.active))}>{c.active ? "Deactivate" : "Activate"}</button></li>
        ))}</ul>
      </section>
      <section className="card">
        <h3>Providers waiting for verification</h3>
        {pending.length === 0 && <p className="muted">None.</p>}
        {pending.map((p) => (
          <div key={p.businessId}>
            <strong>{p.businessName}</strong> {p.headline}
            <button onClick={() => act(() => servicesApi.adminVerify(p.businessId, "VERIFIED"))}>Verify</button>
            <button onClick={() => act(() => servicesApi.adminVerify(p.businessId, "REJECTED"))}>Reject</button>
            <button onClick={() => act(() => servicesApi.adminSuspend(p.businessId, true, "Suspended by admin"))}>Suspend</button>
          </div>
        ))}
      </section>
      <section className="card">
        <h3>Bookings (read only)</h3>
        <ul>{bookings.map((b) => <li key={b.id}>{b.listingName} · {b.businessName} → {b.customerName} · {b.status} · {formatMoney(b.total, b.currency)} · {b.paymentStatus}</li>)}</ul>
      </section>
    </div>
  )
}
