import { useCallback, useEffect, useState, type FormEvent } from "react"
import { Link } from "react-router-dom"
import { stokvelMembershipApi } from "../lib/stokvelMembershipApi"
import {
  FREQUENCY_CHOICES, FUNDS_NOTICE, STOKVEL_TYPE_CHOICES, contributionSummary, discoveryAction, friendlyMembershipError,
  requestStatusLabel, requestStatusTone,
  type DiscoverableStokvel, type DiscoverFilter, type JoinRequest, type Page,
} from "../lib/stokvelMembership"
import { formatDateTime } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"
import { Badge } from "../components/ui/Badge"
import { Button } from "../components/ui/Button"
import { Card } from "../components/ui/Card"
import { ConfirmDialog } from "../components/ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonCard } from "../components/ui/Skeleton"
import { useToast } from "../context/ToastContext"

/**
 * Discover stokvels that their organisers chose to make public (route /stokvels/discover). Each card shows only the public
 * summary and what is needed to join, never balances, members or private notes. Asking to join sends a request for an organiser to
 * approve; it does not add you.
 */
export function DiscoverStokvelsPage() {
  const { showToast } = useToast()
  const [filter, setFilter] = useState<DiscoverFilter>({})
  const [applied, setApplied] = useState<DiscoverFilter>({})
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<DiscoverableStokvel> | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [mine, setMine] = useState<JoinRequest[] | null>(null)
  const [asking, setAsking] = useState<DiscoverableStokvel | null>(null)
  const [message, setMessage] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setState("loading")
    stokvelMembershipApi.discover(applied, page).then((d) => { setData(d); setState("ok") }).catch(() => setState("error"))
  }, [applied, page])
  useEffect(load, [load])

  const loadMine = useCallback(() => { stokvelMembershipApi.mine().then(setMine).catch(() => setMine([])) }, [])
  useEffect(loadMine, [loadMine])

  function search(e: FormEvent) { e.preventDefault(); setPage(0); setApplied({ ...filter }) }

  async function send() {
    if (!asking || busy) return
    setBusy(true); setError(null)
    try {
      await stokvelMembershipApi.requestToJoin(asking.circleId, message)
      showToast(`Request sent to ${asking.name}`, "success")
      setAsking(null); setMessage(""); load(); loadMine()
    } catch (e) {
      setError(friendlyMembershipError(e))
    } finally {
      setBusy(false)
    }
  }

  async function withdraw(r: JoinRequest) {
    if (busy) return
    setBusy(true)
    try { await stokvelMembershipApi.cancel(r.circleId, r.requestId); showToast("Request withdrawn", "success"); load(); loadMine() }
    catch (e) { showToast(friendlyMembershipError(e), "error") }
    finally { setBusy(false) }
  }

  return (
    <div className="page">
      <PageHeader eyebrow="Stokvels" title="Discover stokvels" subtitle="Groups whose organisers have chosen to let new members ask to join."
        actions={<Link className="btn btn-ghost btn-sm" to="/stokvels/join">I have an invite code</Link>} />
      <p className="muted" role="note">{FUNDS_NOTICE}</p>

      <form onSubmit={search} className="card stack" aria-label="Search stokvels">
        <label className="field"><span>Search</span>
          <input value={filter.q ?? ""} onChange={(e) => setFilter({ ...filter, q: e.target.value })} placeholder="Name, summary or area" /></label>
        <div className="field-row">
          <label className="field"><span>Type</span>
            <select value={filter.type ?? ""} onChange={(e) => setFilter({ ...filter, type: e.target.value as DiscoverFilter["type"] })}>
              <option value="">Any type</option>{STOKVEL_TYPE_CHOICES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}</select></label>
          <label className="field"><span>How often</span>
            <select value={filter.frequency ?? ""} onChange={(e) => setFilter({ ...filter, frequency: e.target.value as DiscoverFilter["frequency"] })}>
              <option value="">Any</option>{FREQUENCY_CHOICES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}</select></label>
        </div>
        <div className="field-row">
          <label className="field"><span>Area</span><input value={filter.area ?? ""} onChange={(e) => setFilter({ ...filter, area: e.target.value })} /></label>
          <label className="field"><span>Contribution from</span><input inputMode="decimal" value={filter.minContribution ?? ""} onChange={(e) => setFilter({ ...filter, minContribution: e.target.value })} /></label>
          <label className="field"><span>to</span><input inputMode="decimal" value={filter.maxContribution ?? ""} onChange={(e) => setFilter({ ...filter, maxContribution: e.target.value })} /></label>
        </div>
        <div><Button type="submit" size="sm">Search</Button>{" "}
          <Button type="button" size="sm" variant="ghost" onClick={() => { setFilter({}); setPage(0); setApplied({}) }}>Clear</Button></div>
      </form>

      {state === "loading" && <SkeletonCard />}
      {state === "error" && <ErrorState title="Could not load stokvels" onRetry={load} />}
      {state === "ok" && data && data.items.length === 0 && (
        <EmptyState title="No stokvels match" description="Only stokvels whose organisers chose to be discoverable appear here. Try fewer filters, or ask an organiser for an invite code." />
      )}
      {state === "ok" && data && data.items.map((s) => {
        const action = discoveryAction(s)
        return (
          <Card key={s.circleId}>
            <h3>{s.name}</h3>
            <p className="muted">{STOKVEL_TYPE_CHOICES.find((t) => t.value === s.stokvelType)?.label} · {contributionSummary(s.contributionAmount, s.frequency, s.currency)} · run by {s.organiser}{s.area ? ` · ${s.area}` : ""}</p>
            {s.publicSummary && <p>{s.publicSummary}</p>}
            {s.requirements && <p><strong>To join:</strong> {s.requirements}</p>}
            <p className="muted">{s.memberCount} member{s.memberCount === 1 ? "" : "s"}{s.spotsLeft !== null ? ` · ${s.spotsLeft} space${s.spotsLeft === 1 ? "" : "s"} left` : ""}</p>
            {action.note && <p role="status">{action.note}</p>}
            <Button disabled={!action.canRequest} onClick={() => { setError(null); setMessage(""); setAsking(s) }}>{action.label}</Button>
          </Card>
        )
      })}
      {state === "ok" && data && data.totalPages > 1 && (
        <div className="inline-actions" aria-label="Pages">
          <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
          <span aria-live="polite">Page {data.page + 1} of {data.totalPages}</span>
          <Button size="sm" variant="ghost" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
        </div>
      )}

      <section className="card">
        <h3>My requests</h3>
        {!mine ? <p className="muted">Loading…</p> : mine.length === 0 ? <p className="muted">You have not asked to join any stokvel.</p> : (
          <ul className="plain-list" aria-label="My membership requests">
            {mine.map((r) => (
              <li key={r.requestId} style={{ display: "flex", justifyContent: "space-between", padding: "0.4rem 0" }}>
                <span>{r.stokvelName} <Badge tone={requestStatusTone(r.status)}>{requestStatusLabel(r.status)}</Badge><br />
                  <small className="muted">{formatDateTime(r.createdAt)}{r.decisionNote ? ` — ${r.decisionNote}` : ""}</small></span>
                {r.canCancel && <Button size="sm" variant="ghost" disabled={busy} onClick={() => withdraw(r)}>Withdraw</Button>}
                {r.status === "APPROVED" && <Link to={`/stokvels/${r.circleId}`}>Open</Link>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {asking && (
        <ConfirmDialog
          title={`Ask to join ${asking.name}?`}
          summary="The organisers will see your name and decide. You are not a member until someone approves."
          rows={[{ label: "Contribution", value: contributionSummary(asking.contributionAmount, asking.frequency, asking.currency) },
            ...(asking.requirements ? [{ label: "Requirements", value: asking.requirements }] : [])]}
          confirmLabel="Send request" loading={busy} onConfirm={send} onCancel={() => setAsking(null)} />
      )}
      {asking && (
        <div className="field" style={{ marginTop: "0.5rem" }}>
          {error && <p className="form-error" role="alert">{error}</p>}
        </div>
      )}
    </div>
  )
}
