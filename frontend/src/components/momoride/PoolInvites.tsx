import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { PoolInvite } from "../../lib/types"

/** A driver must accept before joining a wholesaler's pool and receiving its delivery jobs. */
export function PoolInvites() {
  const [invites, setInvites] = useState<PoolInvite[]>([])
  const [error, setError] = useState<string | null>(null)
  function load() { api.get<PoolInvite[]>("/drivers/me/pool-invites").then(setInvites).catch(() => setInvites([])) }
  useEffect(load, [])
  async function respond(id: string, accept: boolean) {
    try { await api.post<void>(`/drivers/me/pool-invites/${id}/${accept ? "accept" : "decline"}`); load() }
    catch (e) { setError(e instanceof Error ? e.message : "Could not respond") }
  }
  if (invites.length === 0) return null
  return (
    <div className="card stack" style={{ marginTop: "var(--space-3)" }}>
      <h3 style={{ margin: 0 }}>Driver pool invitations</h3>
      {error && <p style={{ color: "var(--danger)", margin: 0 }}>{error}</p>}
      {invites.map((i) => (
        <div key={i.id} className="row" style={{ justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
          <span>{i.businessName} invited you to deliver for them.</span>
          <span className="row" style={{ gap: 8 }}>
            <button className="btn btn-primary" onClick={() => respond(i.id, true)}>Accept</button>
            <button className="btn btn-ghost" onClick={() => respond(i.id, false)}>Decline</button>
          </span>
        </div>
      ))}
    </div>
  )
}
