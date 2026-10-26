import { useCallback, useEffect, useState } from "react"
import { businessApi } from "../../lib/walletApi"
import type { BusinessMember, BusinessRole } from "../../lib/walletTypes"
import { describeWalletError, roleDescription } from "../../lib/walletLogic"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../ui/StatePanel"
import { SkeletonText } from "../ui/Skeleton"
import { useToast } from "../../context/ToastContext"
import { useGuard } from "./useGuard"

type Delegable = Exclude<BusinessRole, "OWNER">

/**
 * Owner-only: who may act on this business wallet, and with which role. The owner is derived from the business itself and
 * can never be added, demoted or removed here. The server enforces all of it; this screen just edits what the server allows.
 */
export function MembersPanel({ walletId }: { walletId: string }) {
  const { showToast } = useToast()
  const [members, setMembers] = useState<BusinessMember[] | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [identifier, setIdentifier] = useState("")
  const [role, setRole] = useState<Delegable>("STAFF")
  const [error, setError] = useState<string | null>(null)
  const [removing, setRemoving] = useState<BusinessMember | null>(null)
  const add = useGuard()
  const change = useGuard()
  const remove = useGuard()

  const load = useCallback(() => {
    setState("loading")
    businessApi.members(walletId).then((m) => { setMembers(m); setState("ok") }).catch(() => setState("error"))
  }, [walletId])
  useEffect(load, [load])

  async function submit() {
    if (!identifier.trim()) { setError("Enter the person's email, phone number or wallet id"); return }
    setError(null)
    await add.run(async () => {
      try { await businessApi.addMember(walletId, identifier.trim(), role); setIdentifier(""); showToast("Access granted", "success"); load() }
      catch (e) { setError(describeWalletError(e)) }
    })
  }

  async function setMemberRole(m: BusinessMember, r: Delegable) {
    await change.run(async () => {
      try { await businessApi.changeRole(walletId, m.memberId, r); showToast("Role updated", "success"); load() }
      catch (e) { showToast(describeWalletError(e), "error") }
    })
  }

  async function doRemove() {
    if (!removing) return
    await remove.run(async () => {
      try { await businessApi.removeMember(walletId, removing.memberId); showToast("Access removed", "success"); load() }
      catch (e) { showToast(describeWalletError(e), "error") }
      setRemoving(null)
    })
  }

  return (
    <div className="wallet-stack">
      <Card>
        <h3>Give someone access</h3>
        <form onSubmit={(e) => { e.preventDefault(); submit() }} noValidate>
          <div className="field">
            <label htmlFor="member-id">Email, phone number or wallet id</label>
            <input id="member-id" value={identifier} autoComplete="off" onChange={(e) => setIdentifier(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="member-role">Role</label>
            <select id="member-role" value={role} onChange={(e) => setRole(e.target.value as Delegable)}>
              <option value="STAFF">Staff</option><option value="FINANCE">Finance</option>
            </select>
            <span className="field-hint">{roleDescription(role)}</span>
          </div>
          {error && <p role="alert" className="field-error">{error}</p>}
          <Button type="submit" loading={add.loading}>Give access</Button>
        </form>
      </Card>
      <Card>
        <h3>People with access</h3>
        {state === "loading" && <SkeletonText />}
        {state === "error" && <ErrorState title="Could not load members" onRetry={load} />}
        {state === "ok" && members && members.length === 0 && <EmptyState title="No one yet" description="Only you can use this wallet." />}
        {state === "ok" && members && members.length > 0 && (
          <ul className="wallet-list" aria-label="People with access to this business wallet">
            {members.map((m) => (
              <li key={m.memberId} className="wallet-row">
                <div>
                  <strong>{m.displayName}</strong> <code>{m.memberId}</code>
                  <div className="field-hint">{roleDescription(m.role)}</div>
                </div>
                <div className="wallet-row-end">
                  {m.owner ? <span className="badge info">Owner</span> : (
                    <>
                      <select aria-label={`Role for ${m.displayName}`} value={m.role} disabled={change.loading}
                        onChange={(e) => setMemberRole(m, e.target.value as Delegable)}>
                        <option value="STAFF">Staff</option><option value="FINANCE">Finance</option>
                      </select>
                      <Button size="sm" variant="ghost" onClick={() => setRemoving(m)}>Remove</Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {removing && (
        <ConfirmDialog title="Remove access?" danger summary="They will immediately lose access to this business wallet."
          rows={[{ label: "Person", value: removing.displayName }, { label: "Role", value: removing.role }]}
          confirmLabel="Remove access" loading={remove.loading} onConfirm={doRemove} onCancel={() => setRemoving(null)} />
      )}
    </div>
  )
}
