import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { Business } from "../../lib/types"
import { BRANCH_PERMISSION_LABELS, branchApi, type Branch, type BranchStaff } from "../../lib/branchApi"
import { PageHeader } from "../../components/ui/PageHeader"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"

interface BranchTeam { branch: Branch; staff: BranchStaff[] | null; denied: boolean }

/**
 * Everyone who works across your branches, in one table. Staff exist at branch level only; this is a read-only overview.
 * Adding people and changing permissions stay on each branch, where the server checks every change.
 */
export default function BusinessTeamPage() {
  const [business, setBusiness] = useState<Business | null>(null)
  const [teams, setTeams] = useState<BranchTeam[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    api.get<Business>("/businesses/me")
      .then(async (b) => {
        setBusiness(b)
        const branches = await branchApi.list(b.id)
        const rows = await Promise.all(
          branches.map(async (branch): Promise<BranchTeam> => {
            try {
              return { branch, staff: await branchApi.staff(b.id, branch.id), denied: false }
            } catch {
              // Staff lists are only for people allowed to manage that branch. A refusal is expected, not an error.
              return { branch, staff: null, denied: true }
            }
          }),
        )
        setTeams(rows)
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load your team"))
  }, [])
  useEffect(load, [load])

  return (
    <div className="page stack">
      <PageHeader title="Team" subtitle={business ? `People across the branches of ${business.name}.` : "People across your branches."} actions={<Link to="/sell/branches" className="btn btn-secondary btn-sm">Manage branches</Link>} />
      {error && <ErrorState description={error} onRetry={load} />}
      {!error && teams === null && <div className="stack"><SkeletonCard lines={3} /></div>}
      {teams && teams.length === 0 && (
        <EmptyState title="No branches yet" description="Add a branch first, then assign managers and staff to it." action={<Link to="/sell/branches" className="btn btn-primary btn-sm">Add a branch</Link>} />
      )}
      {teams?.map(({ branch, staff, denied }) => (
        <section key={branch.id} className="card stack" aria-labelledby={`t-${branch.id}`}>
          <div className="row between">
            <h2 id={`t-${branch.id}`}>{branch.name}</h2>
            <Link to={`/sell/branches/${branch.businessId}/${branch.id}`} className="btn btn-ghost btn-sm">Open branch</Link>
          </div>
          {denied ? (
            <p className="text-muted-aa">You don't have access to this branch's team.</p>
          ) : staff && staff.length === 0 ? (
            <p className="text-muted-aa">No one is assigned yet. The business owner can manage this branch.</p>
          ) : (
            <div style={{ overflowX: "auto" }}>
              <table className="table" style={{ width: "100%" }}>
                <caption className="sr-only">Team for {branch.name}</caption>
                <thead><tr><th scope="col">Name</th><th scope="col">Role</th><th scope="col">Can do</th></tr></thead>
                <tbody>
                  {staff?.map((m) => (
                    <tr key={m.userId}>
                      <td>{m.fullName}<div className="muted">{m.email ?? m.phone ?? ""}</div></td>
                      <td>{m.role === "MANAGER" ? "Manager" : "Staff"}</td>
                      <td>{m.permissions.length === 0 ? "View only" : m.permissions.map((p) => BRANCH_PERMISSION_LABELS[p]).join(", ")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      ))}
    </div>
  )
}
