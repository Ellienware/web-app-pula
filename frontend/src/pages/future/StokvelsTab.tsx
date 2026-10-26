import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { formatMoney } from "../../lib/format"
import { futureApi, type StokvelView } from "../../lib/futureApi"
import { describeFutureError, progressPercent } from "../../lib/futureLogic"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { Bar } from "./shared"

const CAN = (v: boolean) => (v ? "Yes" : "No")

/** Read-only. What you can do depends on your role in each stokvel; nothing here bypasses its approval rules. */
export function StokvelsTab({ currency }: { currency: string }) {
  const [rows, setRows] = useState<StokvelView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(() => {
    futureApi.stokvels().then((r) => { setRows(r); setError(null) }).catch((e) => setError(describeFutureError(e)))
  }, [])
  useEffect(load, [load])

  if (error) return <ErrorState description={error} onRetry={load} />
  if (!rows) return <div className="spinner" role="status" aria-label="Loading" />
  if (rows.length === 0) return <EmptyState title="No stokvels yet" description="Stokvels you join will appear here with your role and your own obligation." />
  return (
    <div className="stack">
      {rows.map((s) => (
        <div className="card stack" key={s.circleId}>
          <div className="future-between"><strong>{s.name}</strong><span className="badge badge-muted">{s.myRole.toLowerCase()}</span></div>
          {s.contributionAmount != null && (
            <div>Your agreed contribution: {formatMoney(s.contributionAmount, currency)} {s.contributionFrequency ? s.contributionFrequency.toLowerCase() : ""}
              {s.currentPeriodEnds ? ` · current period ends ${s.currentPeriodEnds}` : ""}</div>
          )}
          {s.groupRecordedProgress != null && s.groupTarget != null && (
            <>
              <Bar percent={progressPercent(s.groupRecordedProgress, s.groupTarget)} />
              <div>Group-tracked progress {formatMoney(s.groupRecordedProgress, currency)} of {formatMoney(s.groupTarget, currency)} — this is the stokvel's total, not your balance.</div>
              <div>Your own recorded contributions: {formatMoney(s.myRecordedContributions ?? 0, currency)}</div>
            </>
          )}
          <div className="field-hint">With your role you can: create requests — {CAN(s.permissions.canCreateRequest)}; vote — {CAN(s.permissions.canVote)};
            record payouts — {CAN(s.permissions.canRecordPayout)}; change settings — {CAN(s.permissions.canUpdateSettings)}.</div>
          {s.notes.map((n) => <div className="field-hint" key={n}>{n}</div>)}
          <Link className="btn btn-ghost btn-sm" to={`/circles/${s.circleId}`}>Open stokvel</Link>
        </div>
      ))}
    </div>
  )
}
