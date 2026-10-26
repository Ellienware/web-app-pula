import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { Business } from "../../lib/types"
import { branchApi, type Branch } from "../../lib/branchApi"
import { PageHeader } from "../../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"
import BusinessReadinessCard from "../../components/market/BusinessReadinessCard"
import BusinessDocumentsPanel from "../../components/market/BusinessDocumentsPanel"

/** Business settings: profile summary, readiness, documents, branches and shortcuts, all in one place. */
export default function BusinessSettingsPage() {
  const [business, setBusiness] = useState<Business | null | undefined>(undefined)
  const [branches, setBranches] = useState<Branch[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    api.get<Business>("/businesses/me")
      .then(async (b) => {
        setBusiness(b)
        setBranches(await branchApi.list(b.id).catch(() => []))
      })
      .catch((e) => {
        if (e?.status === 404) setBusiness(null)
        else setError(e instanceof Error ? e.message : "Could not load your business")
      })
  }, [])
  useEffect(load, [load])

  if (error) return <div className="page"><ErrorState description={error} onRetry={load} /></div>
  if (business === undefined) return <div className="page"><SkeletonCard lines={4} /></div>
  if (business === null) {
    return (
      <div className="page">
        <EmptyState title="No business yet" description="Create your business first, then come back to manage its settings." action={<Link to="/sell" className="btn btn-primary btn-sm">Set up a business</Link>} />
      </div>
    )
  }

  return (
    <div className="page stack">
      <PageHeader title="Business settings" subtitle={business.name} actions={<Link to="/sell" className="btn btn-secondary btn-sm">Edit profile</Link>} />

      <section className="card stack" aria-labelledby="bp-h">
        <h2 id="bp-h">Profile</h2>
        <dl className="stack">
          <div><dt className="muted">Name</dt><dd>{business.name}</dd></div>
          <div><dt className="muted">Category</dt><dd>{business.category}</dd></div>
          <div><dt className="muted">Location</dt><dd>{business.location || "Not set"}</dd></div>
          <div><dt className="muted">Contact</dt><dd>{[business.contactPhone, business.contactEmail].filter(Boolean).join(" · ") || "Not set"}</dd></div>
          <div><dt className="muted">Registration</dt><dd>{business.registrationStatus.replace(/_/g, " ").toLowerCase()}{business.registrationNumber ? ` (${business.registrationNumber})` : ""}</dd></div>
          <div><dt className="muted">Listing</dt><dd>{business.active ? "Active" : "Inactive"}</dd></div>
        </dl>
      </section>

      <BusinessReadinessCard />
      <BusinessDocumentsPanel />

      <section className="card stack" aria-labelledby="br-h">
        <div className="row between"><h2 id="br-h">Branches</h2><Link to="/sell/branches" className="btn btn-ghost btn-sm">Manage</Link></div>
        {branches.length === 0 ? <p className="text-muted-aa">No branches yet.</p> : (
          <ul>{branches.map((b) => <li key={b.id}>{b.name} · {b.status.toLowerCase()}</li>)}</ul>
        )}
      </section>

      <section className="card stack" aria-labelledby="sc-h">
        <h2 id="sc-h">More</h2>
        <div className="row wrap">
          <Link to="/sell/team" className="btn btn-ghost btn-sm">Team</Link>
          <Link to="/sell/settlements" className="btn btn-ghost btn-sm">Settlements</Link>
          <Link to="/cashflow" className="btn btn-ghost btn-sm">Cash flow</Link>
          <Link to="/sell/branches/report" className="btn btn-ghost btn-sm">Reports</Link>
        </div>
      </section>
    </div>
  )
}
