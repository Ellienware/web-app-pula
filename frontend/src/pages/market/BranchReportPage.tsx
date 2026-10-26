import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { Business } from "../../lib/types"
import { branchOpsApi, type BranchReport, type MyBranchAssignment, branchApi } from "../../lib/branchApi"
import { formatMoney } from "../../lib/format"
import { PageHeader } from "../../components/ui/PageHeader"
import { ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"

/**
 * Parent-business sales by branch. Owners see every branch and the consolidated total; a manager or staff member with the report permission
 * sees only their own branches. Order totals net of completed refunds: not settled cash, and demo-wallet payments are simulated.
 */
export function BranchReportPage() {
  const [businessId, setBusinessId] = useState<string | null>(null)
  const [report, setReport] = useState<BranchReport | null>(null)
  const [days, setDays] = useState(30)
  const [error, setError] = useState<string | null>(null)

  const resolveBusiness = useCallback(async () => {
    const mine = await api.get<Business>("/businesses/me").catch(() => null)
    if (mine) return mine.id
    const assigned: MyBranchAssignment[] = await branchApi.myAssignments()
    return assigned[0]?.businessId ?? null
  }, [])

  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const id = businessId ?? (await resolveBusiness())
        if (!id) { setError("You are not part of a business yet."); return }
        if (!businessId) setBusinessId(id)
        const to = new Date()
        const from = new Date(to.getTime() - days * 86_400_000)
        const r = await branchOpsApi.report(id, from.toISOString(), to.toISOString())
        if (live) { setReport(r); setError(null) }
      } catch (e) { if (live) setError(e instanceof Error ? e.message : "Could not load the report") }
    })()
    return () => { live = false }
  }, [businessId, days, resolveBusiness])

  if (error) return <div className="page"><ErrorState title="Report unavailable" description={error} /></div>
  if (!report) return <div className="page stack"><SkeletonCard /></div>
  const cur = "ZAR"
  return (
    <div className="page stack">
      <PageHeader title="Branch report" subtitle="Sales by branch" actions={<Link to="/sell/branches" className="btn btn-ghost btn-sm">All branches</Link>} />
      <label className="field"><span>Period</span>
        <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option><option value={30}>Last 30 days</option><option value={90}>Last 90 days</option>
        </select>
      </label>
      <p className="muted small">{report.notice}</p>
      <div className="card stack">
        {report.report.branches.map((b) => (
          <div key={b.branchId ?? "none"} className="row between">
            <div><strong>{b.branchName}</strong><div className="muted small">{b.orders} orders · {b.units} units</div></div>
            <div style={{ textAlign: "right" }}>
              <div>{formatMoney(b.net, cur)}</div>
              {b.refunded > 0 && <div className="muted small">after {formatMoney(b.refunded, cur)} refunded</div>}
            </div>
          </div>
        ))}
        <div className="row between" style={{ borderTop: "1px solid var(--line)", paddingTop: 8 }}>
          <strong>Total</strong><strong>{formatMoney(report.report.net, cur)}</strong>
        </div>
      </div>
    </div>
  )
}
