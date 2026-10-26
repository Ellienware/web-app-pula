import { useCallback, useEffect, useState } from "react"
import { Link, useParams } from "react-router-dom"
import {
  ALL_BRANCH_PERMISSIONS, BRANCH_PERMISSION_LABELS, branchApi, BRANCH_STATUS_LABELS,
  type Branch, type BranchPermission, type BranchRole, type BranchStaff, type BranchStatus,
} from "../../lib/branchApi"
import { BranchStockPanel } from "../../components/branch/BranchStockPanel"
import { BranchOrdersPanel } from "../../components/branch/BranchOrdersPanel"
import { useToast } from "../../context/ToastContext"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { Badge, statusTone } from "../../components/ui/Badge"
import { ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"

/** Branch detail. Buttons follow the caller's role, but the backend makes the real decision on every request. */
export function BranchDetailPage() {
  const { businessId = "", branchId = "" } = useParams()
  const { showToast } = useToast()
  const [branch, setBranch] = useState<Branch | null>(null)
  const [staff, setStaff] = useState<BranchStaff[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [edit, setEdit] = useState<Record<string, string> | null>(null)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [identifier, setIdentifier] = useState("")
  const [role, setRole] = useState<BranchRole>("STAFF")
  const [siblings, setSiblings] = useState<Branch[]>([])
  const [newPerms, setNewPerms] = useState<BranchPermission[]>([])
  const [editingPerms, setEditingPerms] = useState<string | null>(null)
  const [permDraft, setPermDraft] = useState<BranchPermission[]>([])

  const load = useCallback(async () => {
    setError(null)
    try {
      const b = await branchApi.get(businessId, branchId)
      setBranch(b)
      setSiblings(await branchApi.list(businessId).catch(() => []))
      setStaff(b.yourRole === "STAFF" ? null : await branchApi.staff(businessId, branchId))
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load this branch")
    }
  }, [businessId, branchId])
  useEffect(() => { void load() }, [load])

  if (error) return <div className="page"><ErrorState title="Branch unavailable" description={error} /></div>
  if (!branch) return <div className="page stack"><SkeletonCard /></div>

  const isOwner = branch.yourRole === "OWNER"
  const canEdit = (isOwner || branch.yourRole === "MANAGER") && branch.status !== "CLOSED"
  const canAssignStaff = (isOwner || branch.yourRole === "MANAGER") && branch.status !== "CLOSED"

  async function run(fn: () => Promise<unknown>, ok: string) {
    setBusy(true); setFormError(null)
    try { await fn(); showToast(ok, "success"); await load() }
    catch (e) { setFormError(e instanceof Error ? e.message : "That did not work") }
    finally { setBusy(false) }
  }

  function startEdit() {
    setEdit({
      name: branch!.name, branchCode: branch!.branchCode ?? "", address: branch!.address ?? "", suburb: branch!.suburb ?? "",
      city: branch!.city ?? "", province: branch!.province ?? "", postalCode: branch!.postalCode ?? "", phone: branch!.phone ?? "",
      email: branch!.email ?? "", hours: branch!.operatingHours.join("\n"),
      brandName: branch!.brandName ?? "", operatingModel: branch!.operatingModel, operatorName: branch!.operatorName ?? "",
    })
  }

  async function saveEdit() {
    if (!edit) return
    await run(async () => {
      await branchApi.update(businessId, branchId, {
        name: edit.name, ...(isOwner ? { branchCode: edit.branchCode } : {}), address: edit.address, suburb: edit.suburb, city: edit.city,
        province: edit.province, postalCode: edit.postalCode, phone: edit.phone, email: edit.email,
        operatingHours: edit.hours.split("\n").map((h) => h.trim()).filter(Boolean),
        ...(isOwner ? { brandName: edit.brandName, operatingModel: edit.operatingModel as "OWNED" | "FRANCHISED", operatorName: edit.operatorName } : {}),
      })
      setEdit(null)
    }, "Branch updated")
  }

  function changeStatus(to: BranchStatus) {
    const msg = to === "CLOSED"
      ? "Close this branch permanently? It stops taking orders, its history is kept, and it cannot be reopened."
      : to === "SUSPENDED" ? "Suspend this branch? It stops taking new orders until you activate it again." : "Activate this branch?"
    if (!window.confirm(msg)) return
    void run(() => branchApi.setStatus(businessId, branchId, to), "Status updated")
  }

  const f = (k: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setEdit((s) => ({ ...(s as Record<string, string>), [k]: e.target.value }))

  return (
    <div className="page stack">
      <PageHeader eyebrow={branch.businessName} title={branch.name} subtitle={branch.branchCode ? `Branch code ${branch.branchCode}` : undefined}
        actions={<Link to="/sell/branches" className="btn btn-ghost btn-sm">All branches</Link>} />
      <div className="row">
        <Badge tone={statusTone(branch.status)}>{BRANCH_STATUS_LABELS[branch.status]}</Badge>
        <span className="muted small">Your role: {branch.yourRole === "OWNER" ? "Owner" : branch.yourRole === "MANAGER" ? "Manager" : "Staff"}</span>
        <span className="muted small">Not verified by PULA: details are as submitted.</span>
      </div>
      {formError && <p className="error-text" role="alert">{formError}</p>}

      {!edit && (
        <div className="card stack">
          <div><strong>Address:</strong> {[branch.address, branch.suburb, branch.city, branch.province, branch.postalCode].filter(Boolean).join(", ") || "Not added"}</div>
          <div><strong>Phone:</strong> {branch.phone ?? "Not added"} · <strong>Email:</strong> {branch.email ?? "Not added"}</div>
          <div><strong>Hours:</strong> {branch.operatingHours.length ? branch.operatingHours.join("; ") : "Not added"}</div>
          {(branch.brandName || branch.operatingModel === "FRANCHISED") && (
            <div>
              <strong>Brand:</strong> {branch.brandName ?? "Not added"} · <strong>Operated:</strong> {branch.operatingModel === "FRANCHISED" ? `by a franchisee${branch.operatorName ? ` (${branch.operatorName})` : ""}` : "by the business itself"}
              <div className="muted small">Brand and operator are descriptive, entered by the business and not verified by PULA. Outlets sharing a brand are not assumed to be one legal entity.</div>
            </div>
          )}
          {branch.latitude != null && branch.longitude != null && <div><strong>Pin:</strong> {branch.latitude}, {branch.longitude}</div>}
          {branch.fulfilmentOptions.length > 0 && <div><strong>Fulfilment:</strong> {branch.fulfilmentOptions.join(", ")} <span className="muted small">(managed in Kitchen &amp; menu)</span></div>}
          {canEdit && <div><Button variant="secondary" onClick={startEdit}>Edit details</Button></div>}
        </div>
      )}

      {edit && (
        <div className="card stack">
          <h3>Edit branch</h3>
          <label className="field"><span>Name</span><input value={edit.name} onChange={f("name")} maxLength={255} /></label>
          {isOwner && <label className="field"><span>Branch code</span><input value={edit.branchCode} onChange={f("branchCode")} maxLength={40} /></label>}
          <label className="field"><span>Street address</span><input value={edit.address} onChange={f("address")} maxLength={500} /></label>
          <div className="row">
            <label className="field"><span>Suburb</span><input value={edit.suburb} onChange={f("suburb")} /></label>
            <label className="field"><span>City</span><input value={edit.city} onChange={f("city")} /></label>
          </div>
          <div className="row">
            <label className="field"><span>Province</span><input value={edit.province} onChange={f("province")} /></label>
            <label className="field"><span>Postal code</span><input value={edit.postalCode} onChange={f("postalCode")} maxLength={10} /></label>
          </div>
          <div className="row">
            <label className="field"><span>Telephone</span><input value={edit.phone} onChange={f("phone")} /></label>
            <label className="field"><span>Contact email</span><input value={edit.email} onChange={f("email")} /></label>
          </div>
          {isOwner && (
            <>
              <label className="field"><span>Brand or trading name (optional, not verified)</span><input value={edit.brandName} onChange={f("brandName")} maxLength={255} /></label>
              <label className="field"><span>Who runs this outlet?</span>
                <select value={edit.operatingModel} onChange={(e) => setEdit((x) => ({ ...(x as Record<string, string>), operatingModel: e.target.value }))}>
                  <option value="OWNED">The business itself</option><option value="FRANCHISED">A franchisee</option>
                </select>
              </label>
              {edit.operatingModel === "FRANCHISED" && <label className="field"><span>Franchisee / operator name</span><input value={edit.operatorName} onChange={f("operatorName")} maxLength={255} /></label>}
            </>
          )}
          <label className="field"><span>Operating hours (one line per entry)</span><textarea value={edit.hours} onChange={f("hours")} rows={4} /></label>
          <div className="row">
            <Button loading={busy} disabled={busy} onClick={saveEdit}>Save</Button>
            <Button variant="ghost" onClick={() => { setEdit(null); setFormError(null) }}>Cancel</Button>
          </div>
        </div>
      )}

      {isOwner && branch.status !== "CLOSED" && (
        <div className="card stack">
          <h3>Branch status</h3>
          <p className="muted small">Suspended or closed branches cannot take new orders.</p>
          <div className="row">
            {branch.status === "ACTIVE" && <Button variant="secondary" disabled={busy} onClick={() => changeStatus("SUSPENDED")}>Suspend</Button>}
            {branch.status === "SUSPENDED" && <Button disabled={busy} onClick={() => changeStatus("ACTIVE")}>Activate</Button>}
            <Button variant="danger" disabled={busy} onClick={() => changeStatus("CLOSED")}>Close permanently</Button>
          </div>
        </div>
      )}

      {staff && (
        <div className="card stack">
          <h3>Manager and staff</h3>
          {staff.length === 0 && <p className="muted">Nobody is assigned to this branch yet.</p>}
          {staff.map((s) => (
            <div key={s.userId} className="row" style={{ justifyContent: "space-between" }}>
              <div>
                <strong>{s.fullName}</strong> <span className="muted small">{s.role === "MANAGER" ? "Manager" : "Staff"} · {s.email ?? s.phone}</span>
                <div className="muted small">{s.permissions.length ? s.permissions.map((p) => BRANCH_PERMISSION_LABELS[p]).join("; ") : "No extra permissions (view only)"}</div>
                {editingPerms === s.userId && (
                  <div className="stack">
                    {ALL_BRANCH_PERMISSIONS.filter((p) => isOwner || branch.yourPermissions.includes(p)).map((p) => (
                      <label key={p} className="row small"><input type="checkbox" checked={permDraft.includes(p)}
                        onChange={(e) => setPermDraft((d) => e.target.checked ? [...d, p] : d.filter((x) => x !== p))} /> {BRANCH_PERMISSION_LABELS[p]}</label>
                    ))}
                    <div className="row">
                      <Button size="sm" disabled={busy} onClick={() => run(async () => { await branchApi.setPermissions(businessId, branchId, s.userId, permDraft); setEditingPerms(null) }, "Permissions saved")}>Save permissions</Button>
                      <Button size="sm" variant="ghost" onClick={() => setEditingPerms(null)}>Cancel</Button>
                    </div>
                  </div>
                )}
              </div>
              {(isOwner || (canAssignStaff && s.role === "STAFF")) && editingPerms !== s.userId && (
                <Button variant="secondary" size="sm" disabled={busy} onClick={() => { setEditingPerms(s.userId); setPermDraft(s.permissions) }}>Permissions</Button>
              )}
              {(isOwner || (canAssignStaff && s.role === "STAFF")) && (
                <Button variant="ghost" size="sm" disabled={busy}
                  onClick={() => window.confirm(`Remove ${s.fullName} from this branch?`) && void run(() => branchApi.removeStaff(businessId, branchId, s.userId), "Assignment removed")}>
                  Remove
                </Button>
              )}
            </div>
          ))}
          {canAssignStaff && (
            <div className="stack">
              <label className="field"><span>Add by registered email or phone number</span><input value={identifier} onChange={(e) => setIdentifier(e.target.value)} /></label>
              <label className="field"><span>Role</span>
                <select value={role} onChange={(e) => setRole(e.target.value as BranchRole)}>
                  <option value="STAFF">Staff</option>
                  {isOwner && <option value="MANAGER">Manager</option>}
                </select>
              </label>
              <div className="stack">
                <span className="muted small">Permissions (a manager gets all of them if you tick none)</span>
                {ALL_BRANCH_PERMISSIONS.filter((p) => isOwner || branch.yourPermissions.includes(p)).map((p) => (
                  <label key={p} className="row small"><input type="checkbox" checked={newPerms.includes(p)}
                    onChange={(e) => setNewPerms((d) => e.target.checked ? [...d, p] : d.filter((x) => x !== p))} /> {BRANCH_PERMISSION_LABELS[p]}</label>
                ))}
              </div>
              <div><Button disabled={busy || !identifier.trim()} onClick={() => run(async () => {
                await branchApi.assign(businessId, branchId, identifier.trim(), role, newPerms.length ? newPerms : undefined)
                setIdentifier(""); setNewPerms([])
              }, "Assignment saved")}>Assign</Button></div>
            </div>
          )}
        </div>
      )}
      <BranchOrdersPanel branch={branch} />
      <BranchStockPanel branch={branch} siblings={siblings} />
    </div>
  )
}
