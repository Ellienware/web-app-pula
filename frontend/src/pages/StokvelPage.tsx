import { useCallback, useEffect, useState } from "react"
import { Link, useParams } from "react-router-dom"
import { ChevronLeft } from "lucide-react"
import { stokvelApi } from "../lib/stokvelApi"
import {
  canCreateRequest, canOverrideRotation, canRecordPayout, canVote, STOKVEL_TYPE_LABELS,
} from "../lib/stokvelTypes"
import type {
  ContributionStatusView, GoalProgress, RotationView, StokvelOverview, StokvelRequest,
} from "../lib/stokvelTypes"
import { formatMoney } from "../lib/format"
import { ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"
import { ProgressBar } from "../components/ui/ProgressBar"
import { StokvelMembersPanel } from "../components/stokvel/StokvelMembersPanel"
import { StokvelJoinPanel } from "../components/stokvel/StokvelJoinPanel"
import { canManageMembership } from "../lib/stokvelMembership"

type Tab = "overview" | "contributions" | "rotation" | "requests" | "members" | "join"

const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random())
const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

/**
 * Stokvel screen. Buttons are shown from the viewer's role as a convenience; the backend re-checks every action
 * and every amount. Money shown here is tracked from confirmed contributions - PULA does not hold it.
 */
export function StokvelPage() {
  const { id = "" } = useParams()
  const [tab, setTab] = useState<Tab>("overview")
  const [membersVersion, setMembersVersion] = useState(0)
  const [overview, setOverview] = useState<StokvelOverview | null>(null)
  const [status, setStatus] = useState<ContributionStatusView | null>(null)
  const [progress, setProgress] = useState<GoalProgress | null>(null)
  const [rotation, setRotation] = useState<RotationView | null>(null)
  const [requests, setRequests] = useState<StokvelRequest[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [amount, setAmount] = useState("")

  const load = useCallback(async () => {
    setError(null)
    try {
      const o = await stokvelApi.overview(id)
      setOverview(o)
      const [s, r] = await Promise.all([stokvelApi.contributionStatus(id), stokvelApi.requests(id)])
      setStatus(s)
      setRequests(r)
      setProgress(o.settings.goalId ? await stokvelApi.progress(id) : null)
      setRotation(o.settings.stokvelType === "ROTATIONAL" ? await stokvelApi.rotation(id) : null)
    } catch (e) {
      setError(errMsg(e))
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  async function act(fn: () => Promise<unknown>, done?: string) {
    setNotice(null)
    try {
      await fn()
      if (done) setNotice(done)
      await load()
    } catch (e) {
      setNotice(errMsg(e))
    }
  }

  if (error) return <ErrorState description={error} onRetry={load} />
  if (!overview) return <SkeletonCard />

  const role = overview.viewerRole
  const cur = overview.currency
  const tabs: { key: Tab; label: string }[] = [
    { key: "overview", label: "Overview" },
    { key: "contributions", label: "Contributions" },
    ...(rotation ? [{ key: "rotation" as Tab, label: "Rotation" }] : []),
    { key: "requests", label: "Requests" },
    { key: "members", label: "Members" },
    ...(canManageMembership(role) ? [{ key: "join" as Tab, label: "Join & invites" }] : []),
  ]

  return (
    <div className="page">
      <Link to="/" className="back-link"><ChevronLeft size={16} aria-hidden="true" /> Back</Link>
      <h1>{overview.name}</h1>
      <p className="muted">
        {STOKVEL_TYPE_LABELS[overview.settings.stokvelType]} stokvel · {overview.memberCount} members · you are {role.toLowerCase()}
      </p>
      <p className="muted" role="note">{overview.fundsNotice}</p>
      {notice && <div className="state-panel" role="status">{notice}</div>}

      <div className="tabs" role="tablist">
        {tabs.map((t) => (
          <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? "tab active" : "tab"}
            onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>

      {tab === "members" && <StokvelMembersPanel key={membersVersion} circleId={id} />}
      {tab === "join" && canManageMembership(role) && <StokvelJoinPanel circleId={id} onMembersChanged={() => setMembersVersion((v) => v + 1)} />}

      {tab === "overview" && (
        <section className="card">
          <p>Tracked contributions: <strong className="money">{formatMoney(overview.trackedContributions, cur)}</strong></p>
          <p>Already committed (payouts and approved requests): {formatMoney(overview.trackedCommitted, cur)}</p>
          <p>Still available to request: {formatMoney(overview.trackedAvailable, cur)}</p>
          {progress && (
            <>
              <h3>Shared target</h3>
              <ProgressBar value={progress.current} max={progress.target} />
              <p>{progress.percent}% · {formatMoney(progress.current, cur)} of {formatMoney(progress.target, cur)} · {formatMoney(progress.remaining, cur)} to go
                {progress.targetDate ? ` · by ${progress.targetDate}` : ""}</p>
              <ul>{progress.members.map((m) => <li key={m.userId}>{m.fullName}: {formatMoney(m.contributed, cur)}</li>)}</ul>
            </>
          )}
        </section>
      )}

      {tab === "contributions" && status && (
        <section className="card">
          <p>
            Period {status.periodStart} to {status.periodEnd}
            {status.expectedPerMember != null && <> · expected per member {formatMoney(status.expectedPerMember, cur)}</>}
          </p>
          <ul>
            {status.currentPeriodMembers.map((m) => (
              <li key={m.userId}>{m.fullName}: {formatMoney(m.paid, cur)} <span className="badge">{m.status}</span></li>
            ))}
          </ul>
          {status.missed.length > 0 && (
            <>
              <h3>Missed</h3>
              <ul>{status.missed.map((m) => <li key={m.userId + m.periodIndex}>{m.fullName} · period {m.periodIndex + 1}: paid {formatMoney(m.paid, cur)} of {formatMoney(m.expected, cur)}</li>)}</ul>
            </>
          )}
          <label>Amount
            <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <button disabled={!(Number(amount) > 0)}
            onClick={() => act(() => stokvelApi.contribute(id, Number(amount), newKey()), "Contribution submitted").then(() => setAmount(""))}>
            Contribute
          </button>
        </section>
      )}

      {tab === "rotation" && rotation && (
        <section className="card">
          <p>
            Cycle {rotation.currentCycleNumber}: <strong>{rotation.currentBeneficiaryName ?? "-"}</strong>
            {rotation.upcomingBeneficiaryName && <> · next {rotation.upcomingBeneficiaryName}</>}
            {rotation.locked ? " · order locked" : " · order follows join order until the first payout"}
          </p>
          <p>Collected this cycle: {formatMoney(rotation.currentCycleCollected, cur)} of {formatMoney(rotation.currentCycleExpected, cur)}</p>
          <ol>{rotation.order.map((s) => <li key={s.userId}>{s.fullName}{s.paidOut ? ` (paid in cycle ${s.paidInCycle})` : ""}</li>)}</ol>
          {!rotation.locked && canOverrideRotation(role) && rotation.order.length > 1 && (
            <button onClick={() => act(() => {
              const ids = rotation.order.map((s) => s.userId)
              return stokvelApi.setRotationOrder(id, [...ids.slice(1), ids[0]]) // demo control: move first to last
            }, "Order updated")}>Move first member to the end</button>
          )}
          {canRecordPayout(role) && rotation.currentBeneficiaryId && (
            <button onClick={() => act(() => stokvelApi.recordPayout(id, rotation.currentCycleNumber), "Payout recorded")}>
              Record payout for cycle {rotation.currentCycleNumber}
            </button>
          )}
          <h3>History</h3>
          <ul>{rotation.history.map((c) => <li key={c.cycleNumber}>Cycle {c.cycleNumber}: {c.beneficiaryName} · {formatMoney(c.collectedAmount, cur)} · recorded by {c.recordedByName}</li>)}</ul>
        </section>
      )}

      {tab === "requests" && (
        <section className="card">
          {requests.length === 0 && <p className="muted">No requests yet.</p>}
          {requests.map((r) => (
            <article key={r.id} className="card">
              <strong>{formatMoney(r.amount, r.currency)}</strong> · {r.type.replace(/_/g, " ").toLowerCase()} · <span className="badge">{r.status}</span>
              <p>{r.purpose}</p>
              <p className="muted">
                by {r.requesterName}{r.buyerName ? ` · buyer ${r.buyerName}` : ""} · {r.approvals}/{r.requiredApprovals} approvals
              </p>
              {r.status === "PENDING" && canVote(role, overview.settings.membersCanVote) && (
                <>
                  <button onClick={() => act(() => stokvelApi.approve(id, r.id), "Approved")}>Approve</button>
                  <button onClick={() => act(() => stokvelApi.reject(id, r.id), "Rejected")}>Reject</button>
                </>
              )}
              {(r.status === "PENDING" || r.status === "APPROVED") && canCreateRequest(role) && (
                <button onClick={() => act(() => stokvelApi.cancel(id, r.id), "Cancelled")}>Cancel</button>
              )}
              {r.status === "APPROVED" && r.buyerId && (
                <p className="muted">Approved. {r.buyerName} buys through normal PULA checkout, then links the order here.</p>
              )}
              {r.relatedOrderId && <p className="muted">Order reference: {r.relatedOrderId}</p>}
            </article>
          ))}
        </section>
      )}
    </div>
  )
}
