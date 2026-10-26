import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { PoolMember } from "../../lib/types"
import { PageHeader } from "../../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"

/** Supplier driver pool. Members are existing PULA drivers; PULA Ride dispatches to the pool and a driver accepts. */
export default function DriverPoolPage() {
  const [members, setMembers] = useState<PoolMember[]>([])
  const [phone, setPhone] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function load() {
    api.get<PoolMember[]>("/stock/pool/members").then((m) => { setMembers(m); setError(null) })
      .catch((e) => setError(e.message))
  }
  useEffect(load, [])

  async function add() {
    if (!phone.trim()) return
    setBusy(true)
    try { await api.post<PoolMember>("/stock/pool/members", { phoneNumber: phone.trim() }); setPhone(""); load() }
    catch (e) { setError(e instanceof Error ? e.message : "Could not add driver") }
    finally { setBusy(false) }
  }
  async function remove(id: string) {
    try { await api.del<PoolMember>(`/stock/pool/members/${id}`); load() }
    catch (e) { setError(e instanceof Error ? e.message : "Could not remove driver") }
  }

  return (
    <div className="page stack">
      <PageHeader title="Driver pool" subtitle="Drivers who deliver your wholesale orders. You don't pick a driver per order — PULA Ride dispatches to your pool and a driver accepts." />
      <p className="muted" style={{ margin: 0 }}><Link to="/market/stock/orders">← Stock orders</Link></p>
      {error && <ErrorState description={error} onRetry={load} />}
      <div className="card stack">
        <label>Add a PULA driver by phone number
          <input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" placeholder="e.g. 0821234567" />
        </label>
        <button className="btn btn-primary" disabled={busy || !phone.trim()} onClick={add}>{busy ? "Adding…" : "Add to pool"}</button>
        <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>The person must already have a PULA driver profile and must accept your invitation before they receive pool deliveries. Your business needs the "manage a driver pool" capability.</p>
      </div>
      {members.length === 0 && !error && <EmptyState title="No drivers in your pool yet" description="Add drivers above to offer 'Supplier delivery' on your products." />}
      {members.map((m) => (
        <div key={m.id} className="card row" style={{ justifyContent: "space-between", gap: 8 }}>
          <div>
            <strong>{m.driverName}</strong>
            <div className="muted" style={{ fontSize: "0.82rem" }}>{m.phoneNumber} · {m.status === "ACTIVE" ? "Active" : m.status === "PENDING" ? "Invited — waiting for the driver to accept" : "Removed"}</div>
          </div>
          {m.status !== "REMOVED" && <button className="btn btn-ghost" onClick={() => remove(m.id)}>Remove</button>}
        </div>
      ))}
    </div>
  )
}
