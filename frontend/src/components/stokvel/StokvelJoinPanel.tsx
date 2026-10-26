import { useCallback, useEffect, useState } from "react"
import { stokvelMembershipApi } from "../../lib/stokvelMembershipApi"
import {
  FUNDS_NOTICE, friendlyMembershipError, inviteStatusLabel, inviteStatusTone, requestStatusLabel, requestStatusTone, settingsToDraft,
  toSettingsPayload, validateSettings, webInviteLink,
  type CreatedInviteLink, type InviteLink, type JoinRequest, type MembershipSettings, type SettingsDraft, type SettingsErrors,
} from "../../lib/stokvelMembership"
import { formatDateTime } from "../../lib/format"
import { Badge } from "../ui/Badge"
import { Button } from "../ui/Button"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../ui/StatePanel"
import { SkeletonText } from "../ui/Skeleton"
import { useToast } from "../../context/ToastContext"

type Decision = { request: JoinRequest; action: "approve" | "reject" }

/**
 * OWNER/ADMIN only (the server enforces it on every call): who can find this stokvel, how people join, invite links, and the
 * queue of membership requests. People are added only by approving a request or by a valid invite; this panel adds nobody itself.
 */
export function StokvelJoinPanel({ circleId, onMembersChanged }: { circleId: string; onMembersChanged: () => void }) {
  const { showToast } = useToast()
  const [settings, setSettings] = useState<MembershipSettings | null>(null)
  const [draft, setDraft] = useState<SettingsDraft | null>(null)
  const [errors, setErrors] = useState<SettingsErrors>({})
  const [links, setLinks] = useState<InviteLink[] | null>(null)
  const [requests, setRequests] = useState<JoinRequest[] | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [created, setCreated] = useState<CreatedInviteLink | null>(null)
  const [days, setDays] = useState(7)
  const [uses, setUses] = useState(1)
  const [busy, setBusy] = useState<string | null>(null)
  const [decision, setDecision] = useState<Decision | null>(null)
  const [note, setNote] = useState("")
  const [revoking, setRevoking] = useState<InviteLink | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = useCallback(() => {
    setState("loading")
    Promise.all([stokvelMembershipApi.settings(circleId), stokvelMembershipApi.links(circleId), stokvelMembershipApi.requests(circleId)])
      .then(([s, l, r]) => { setSettings(s); setDraft(settingsToDraft(s)); setLinks(l); setRequests(r); setState("ok") })
      .catch((e) => { setMessage(friendlyMembershipError(e)); setState("error") })
  }, [circleId])
  useEffect(load, [load])

  async function run(key: string, fn: () => Promise<void>) {
    if (busy) return // one action at a time: no double submits
    setBusy(key); setMessage(null)
    try { await fn() } catch (e) { setMessage(friendlyMembershipError(e)) } finally { setBusy(null) }
  }

  const saveSettings = () => run("settings", async () => {
    if (!draft || !settings) return
    const found = validateSettings(draft, settings.memberCount)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    const saved = await stokvelMembershipApi.saveSettings(circleId, toSettingsPayload(draft))
    setSettings(saved); setDraft(settingsToDraft(saved)); showToast("Settings saved", "success")
  })

  const createLink = () => run("create-link", async () => {
    const made = await stokvelMembershipApi.createLink(circleId, days, uses)
    setCreated(made)
    setLinks(await stokvelMembershipApi.links(circleId))
  })

  const doRevoke = () => run("revoke", async () => {
    if (!revoking) return
    await stokvelMembershipApi.revokeLink(circleId, revoking.linkId)
    setRevoking(null)
    setLinks(await stokvelMembershipApi.links(circleId))
    showToast("Invite link revoked", "success")
  })

  const decide = () => run("decide", async () => {
    if (!decision) return
    const { request, action } = decision
    if (action === "approve") await stokvelMembershipApi.approve(circleId, request.requestId, note)
    else await stokvelMembershipApi.reject(circleId, request.requestId, note)
    setDecision(null); setNote("")
    const [r, s] = await Promise.all([stokvelMembershipApi.requests(circleId), stokvelMembershipApi.settings(circleId)])
    setRequests(r); setSettings(s)
    if (action === "approve") onMembersChanged()
    showToast(action === "approve" ? "Member added" : "Request declined", "success")
  })

  async function copy(text: string, what: string) {
    try { await navigator.clipboard.writeText(text); showToast(`${what} copied`) } catch { showToast("Copy failed. Select the text and copy it manually.", "error") }
  }

  if (state === "loading") return <SkeletonText lines={4} />
  if (state === "error" || !settings || !draft || !links || !requests) {
    return <ErrorState title="Could not load membership settings" description={message ?? undefined} onRetry={load} />
  }
  const pending = requests.filter((r) => r.status === "PENDING")
  const history = requests.filter((r) => r.status !== "PENDING")
  const err = (k: keyof SettingsErrors) => errors[k] && <span className="field-error" role="alert">{errors[k]}</span>
  const set = <K extends keyof SettingsDraft>(k: K, v: SettingsDraft[K]) => { setDraft((x) => (x ? { ...x, [k]: v } : x)); setErrors({}) }

  return (
    <div className="stack">
      <p className="muted" role="note">{FUNDS_NOTICE}</p>
      {message && <p className="form-error" role="alert">{message}</p>}

      <section className="card">
        <h3>Membership requests {pending.length > 0 && <Badge tone="pending">{pending.length} waiting</Badge>}</h3>
        {pending.length === 0 ? <EmptyState title="No requests waiting" description="People who ask to join appear here for you to approve or decline." /> : (
          <ul className="plain-list" aria-label="Pending membership requests">
            {pending.map((r) => (
              <li key={r.requestId} style={{ padding: "0.5rem 0", borderBottom: "1px solid rgba(0,0,0,0.08)" }}>
                <strong>{r.applicantName}</strong> <small className="muted">{r.source === "INVITE_LINK" ? "used an invite link" : "found you in Discover"} · {formatDateTime(r.createdAt)}</small>
                {r.message && <p style={{ margin: "0.25rem 0" }}>“{r.message}”</p>}
                <div className="inline-actions">
                  <Button size="sm" onClick={() => { setNote(""); setDecision({ request: r, action: "approve" }) }}>Approve</Button>
                  <Button size="sm" variant="ghost" onClick={() => { setNote(""); setDecision({ request: r, action: "reject" }) }}>Decline</Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {history.length > 0 && (
          <details>
            <summary>Earlier requests ({history.length})</summary>
            <ul className="plain-list">
              {history.map((r) => (
                <li key={r.requestId} style={{ padding: "0.3rem 0" }}>
                  {r.applicantName} <Badge tone={requestStatusTone(r.status)}>{requestStatusLabel(r.status)}</Badge>{" "}
                  <small className="muted">{r.decidedAt ? formatDateTime(r.decidedAt) : ""}{r.decidedByName ? ` by ${r.decidedByName}` : ""}{r.decisionNote ? ` — ${r.decisionNote}` : ""}</small>
                </li>
              ))}
            </ul>
          </details>
        )}
      </section>

      <section className="card">
        <h3>Invite links</h3>
        <p className="muted">A link or code lets someone join {settings.inviteRequiresApproval ? "by asking for approval" : "straight away"}. Codes are shown once, expire, and can be revoked.</p>
        <div className="field-row">
          <label className="field"><span>Valid for</span>
            <select value={days} onChange={(e) => setDays(Number(e.target.value))}>{[1, 3, 7, 14, 30].map((n) => <option key={n} value={n}>{n} day{n > 1 ? "s" : ""}</option>)}</select></label>
          <label className="field"><span>Number of people</span>
            <select value={uses} onChange={(e) => setUses(Number(e.target.value))}>{[1, 5, 10, 25, 50, 100].map((n) => <option key={n} value={n}>{n}</option>)}</select></label>
        </div>
        <Button onClick={createLink} loading={busy === "create-link"}>Create invite link</Button>
        {created && (
          <div className="state-panel" role="status" aria-live="polite" style={{ marginTop: "0.75rem" }}>
            <p><strong>Copy this now. It is shown only once.</strong></p>
            <p>Code: <code>{created.code}</code> <Button size="sm" variant="ghost" onClick={() => copy(created.code, "Code")}>Copy code</Button></p>
            <p>Link: <code style={{ wordBreak: "break-all" }}>{webInviteLink(window.location.origin, created.code)}</code>{" "}
              <Button size="sm" variant="ghost" onClick={() => copy(webInviteLink(window.location.origin, created.code), "Link")}>Copy link</Button></p>
            <Button size="sm" variant="ghost" onClick={() => setCreated(null)}>I have copied it</Button>
          </div>
        )}
        {links.length === 0 ? <p className="muted">No invite links yet.</p> : (
          <ul className="plain-list" aria-label="Invite links">
            {links.map((l) => (
              <li key={l.linkId} style={{ display: "flex", justifyContent: "space-between", gap: "0.5rem", padding: "0.4rem 0" }}>
                <span>…{l.codeHint} <Badge tone={inviteStatusTone(l.status)}>{inviteStatusLabel(l.status)}</Badge><br />
                  <small className="muted">{l.uses} of {l.maxUses} used · expires {formatDateTime(l.expiresAt)} · by {l.createdBy}{l.requiresApproval ? " · needs approval" : ""}</small></span>
                {l.status === "ACTIVE" && <Button size="sm" variant="ghost" onClick={() => setRevoking(l)}>Revoke</Button>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h3>Who can find this stokvel</h3>
        <p className="muted">Stokvels are private by default. A discoverable stokvel shows only the public card below in Discover: never your balances, members or notes.</p>
        <form onSubmit={(e) => { e.preventDefault(); saveSettings() }} noValidate className="stack">
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input type="checkbox" checked={draft.discoverable} onChange={(e) => set("discoverable", e.target.checked)} /> Let people find this stokvel in Discover
          </label>
          <label className="field"><span>Public summary (shown on the card)</span>
            <textarea rows={2} maxLength={300} value={draft.publicSummary} onChange={(e) => set("publicSummary", e.target.value)} />{err("publicSummary")}</label>
          <div className="field-row">
            <label className="field"><span>Area (optional)</span>
              <input value={draft.area} maxLength={80} onChange={(e) => set("area", e.target.value)} placeholder="e.g. Soweto" />{err("area")}</label>
            <label className="field"><span>Member limit (optional)</span>
              <input inputMode="numeric" value={draft.maxMembers} onChange={(e) => set("maxMembers", e.target.value)} />{err("maxMembers")}</label>
          </div>
          <label className="field"><span>What people should know before asking to join</span>
            <textarea rows={2} maxLength={500} value={draft.membershipRequirements} onChange={(e) => set("membershipRequirements", e.target.value)} />{err("membershipRequirements")}</label>
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input type="checkbox" checked={draft.inviteRequiresApproval} onChange={(e) => set("inviteRequiresApproval", e.target.checked)} /> People with an invite link still need my approval
          </label>
          <div><Button type="submit" loading={busy === "settings"}>Save</Button></div>
        </form>
      </section>

      {decision && (
        <ConfirmDialog
          title={decision.action === "approve" ? "Approve this request?" : "Decline this request?"}
          summary={decision.action === "approve" ? "They become a member straight away." : "They will be told it was not approved."}
          rows={[{ label: "Person", value: decision.request.applicantName }]}
          confirmLabel={decision.action === "approve" ? "Approve" : "Decline"}
          loading={busy === "decide"} onConfirm={decide} onCancel={() => setDecision(null)} />
      )}
      {revoking && (
        <ConfirmDialog title="Revoke this invite link?" danger summary="Nobody will be able to use it again."
          rows={[{ label: "Link ending", value: revoking.codeHint }, { label: "Used", value: `${revoking.uses} of ${revoking.maxUses}` }]}
          confirmLabel="Revoke" loading={busy === "revoke"} onConfirm={doRevoke} onCancel={() => setRevoking(null)} />
      )}
    </div>
  )
}
