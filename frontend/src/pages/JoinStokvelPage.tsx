import { useEffect, useState } from "react"
import { Link, useNavigate, useSearchParams } from "react-router-dom"
import { stokvelMembershipApi } from "../lib/stokvelMembershipApi"
import {
  FUNDS_NOTICE, acceptOutcomeMessage, contributionSummary, extractInviteCode, friendlyMembershipError,
  type AcceptInviteResult, type InvitePreview,
} from "../lib/stokvelMembership"
import { formatDateTime } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"
import { Button } from "../components/ui/Button"
import { Card } from "../components/ui/Card"
import { ConfirmDialog } from "../components/ui/ConfirmDialog"

/**
 * Join with an invite code or link (route /stokvels/join?code=...). Opening or previewing a code adds nobody: the person reviews
 * what the stokvel is and what happens next, then explicitly confirms. The server validates expiry, revocation, use limits and
 * capacity, and decides whether the code joins immediately or only creates a request for approval.
 */
export function JoinStokvelPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [text, setText] = useState(params.get("code") ?? "")
  const [code, setCode] = useState<string | null>(null)
  const [preview, setPreview] = useState<InvitePreview | null>(null)
  const [result, setResult] = useState<AcceptInviteResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)

  async function open(raw: string) {
    if (busy) return
    setError(null); setPreview(null); setResult(null); setCode(null)
    const c = extractInviteCode(raw)
    if (!c) { setError("That does not look like an invite code. It has 16 letters and numbers, like ABCD-1234-EFGH-5678."); return }
    setBusy(true)
    try { setPreview(await stokvelMembershipApi.preview(c)); setCode(c) } catch (e) { setError(friendlyMembershipError(e)) } finally { setBusy(false) }
  }

  useEffect(() => { if (params.get("code")) open(params.get("code") as string) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [])

  async function accept() {
    if (!code || busy) return
    setBusy(true); setError(null)
    try {
      const r = await stokvelMembershipApi.accept(code)
      setResult(r); setConfirming(false)
    } catch (e) {
      setConfirming(false); setError(friendlyMembershipError(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <PageHeader eyebrow="Stokvels" title="Join with an invite" subtitle="Paste the code or open the link you were sent."
        actions={<Link className="btn btn-ghost btn-sm" to="/stokvels/discover">Discover stokvels</Link>} />
      <form className="card stack" onSubmit={(e) => { e.preventDefault(); open(text) }} noValidate>
        <label className="field"><span>Invite code or link</span>
          <input value={text} onChange={(e) => setText(e.target.value)} autoComplete="off" spellCheck={false} placeholder="ABCD-1234-EFGH-5678" /></label>
        <p className="muted" style={{ margin: 0 }}>Opening a code only shows you the stokvel. You join when you confirm.</p>
        <div><Button type="submit" loading={busy && !confirming}>Open invite</Button></div>
      </form>
      {error && <p className="form-error" role="alert">{error}</p>}

      {preview && !result && (
        <Card aria-label="Invitation">
          <h3>{preview.stokvelName}</h3>
          <p className="muted">Run by {preview.organiser} · {contributionSummary(preview.contributionAmount, preview.frequency, preview.currency)}</p>
          {preview.publicSummary && <p>{preview.publicSummary}</p>}
          {preview.requirements && <p><strong>To join:</strong> {preview.requirements}</p>}
          <p className="muted">{preview.memberCount} member{preview.memberCount === 1 ? "" : "s"}{preview.maxMembers ? ` of ${preview.maxMembers}` : ""} · invite valid until {formatDateTime(preview.expiresAt)}</p>
          <p role="note" className="muted">{FUNDS_NOTICE}</p>
          {preview.alreadyMember
            ? <p role="status">You are already a member. <Link to="/">Go to your circles</Link></p>
            : <><p role="status">{preview.requiresApproval ? "An organiser will need to approve you after you accept." : "You become a member as soon as you accept."}</p>
              <Button onClick={() => setConfirming(true)}>{preview.requiresApproval ? "Ask to join" : "Join this stokvel"}</Button></>}
        </Card>
      )}

      {result && (
        <Card>
          <p role="status" className="success-text">{acceptOutcomeMessage(result)}</p>
          {result.stokvelId && <Button onClick={() => navigate(`/stokvels/${result.stokvelId}`)}>Open stokvel</Button>}
          {!result.stokvelId && <Link className="btn btn-secondary" to="/stokvels/discover">See my requests</Link>}
        </Card>
      )}

      {confirming && preview && (
        <ConfirmDialog
          title={preview.requiresApproval ? `Ask to join ${preview.stokvelName}?` : `Join ${preview.stokvelName}?`}
          summary={preview.requiresApproval ? "Your request goes to the organisers. You are a member only once they approve." : "You will become a member straight away."}
          rows={[{ label: "Contribution", value: contributionSummary(preview.contributionAmount, preview.frequency, preview.currency) }]}
          confirmLabel={preview.requiresApproval ? "Send request" : "Join"} loading={busy} onConfirm={accept} onCancel={() => setConfirming(false)} />
      )}
    </div>
  )
}
