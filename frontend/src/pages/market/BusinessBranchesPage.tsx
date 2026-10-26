import { useCallback, useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { Plus, Store } from "lucide-react"
import { api, ApiError } from "../../lib/api"
import type { Business } from "../../lib/types"
import { branchApi, BRANCH_STATUS_LABELS, type Branch, type MyBranchAssignment } from "../../lib/branchApi"
import { useToast } from "../../context/ToastContext"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { Badge, statusTone } from "../../components/ui/Badge"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"

const EMPTY = { name: "", branchCode: "", address: "", suburb: "", city: "", province: "", postalCode: "", phone: "", email: "" }

/**
 * One parent business, many branches. The owner manages every branch; a manager or staff member only sees the branches they
 * are assigned to. A single-location trader never has to add a branch. Branch details are stored as submitted: PULA does not
 * verify a business, an address or an identity.
 */
export function BusinessBranchesPage() {
  const { showToast } = useToast()
  const [business, setBusiness] = useState<Business | null | undefined>(undefined) // undefined = loading, null = none
  const [branches, setBranches] = useState<Branch[] | null>(null)
  const [assignments, setAssignments] = useState<MyBranchAssignment[]>([])
  const [error, setError] = useState<string | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState(EMPTY)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [mine, assigned] = await Promise.all([
        api.get<Business>("/businesses/me").catch((e) => { if (e instanceof ApiError && e.status === 404) return null; throw e }),
        branchApi.myAssignments(),
      ])
      setBusiness(mine)
      setAssignments(assigned)
      setBranches(mine ? await branchApi.list(mine.id) : [])
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load branches")
    }
  }, [])
  useEffect(() => { void load() }, [load])

  async function create() {
    if (!business) return
    setFormError(null)
    if (!form.name.trim()) { setFormError("Branch name is required"); return }
    setSaving(true)
    try {
      await branchApi.create(business.id, {
        name: form.name.trim(), branchCode: form.branchCode, address: form.address, suburb: form.suburb, city: form.city,
        province: form.province, postalCode: form.postalCode, phone: form.phone, email: form.email,
      })
      showToast("Branch added", "success")
      setForm(EMPTY)
      setShowForm(false)
      await load()
    } catch (e) {
      setFormError(e instanceof Error ? e.message : "Could not add the branch")
    } finally {
      setSaving(false)
    }
  }

  const set = (k: keyof typeof EMPTY) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [k]: e.target.value }))

  if (error) return <div className="page"><ErrorState title="Could not load branches" description={error} /></div>
  if (business === undefined || branches === null) return <div className="page stack"><SkeletonCard /></div>

  return (
    <div className="page stack">
      <PageHeader
        eyebrow={business ? business.name : "Branches"}
        title="Branches"
        subtitle="Manage your outlets under one business profile. You do not need to register the business again to add a location."
        actions={business ? (
          <>
            <Link to="/sell/branches/report" className="btn btn-ghost btn-sm">Report</Link>{" "}
            <Button onClick={() => setShowForm((v) => !v)}><Plus size={16} /> Add branch</Button>
          </>
        ) : assignments.length > 0 ? <Link to="/sell/branches/report" className="btn btn-ghost btn-sm">Report</Link> : undefined}
      />

      {business && showForm && (
        <div className="card stack">
          <h3>New branch</h3>
          <p className="muted small">Only the name is required. Details you enter are stored as submitted; PULA does not verify addresses or businesses.</p>
          <label className="field"><span>Branch name *</span><input value={form.name} onChange={set("name")} maxLength={255} /></label>
          <label className="field"><span>Branch code (optional, unique in your business)</span><input value={form.branchCode} onChange={set("branchCode")} maxLength={40} placeholder="e.g. PTA-CBD" /></label>
          <label className="field"><span>Street address</span><input value={form.address} onChange={set("address")} maxLength={500} /></label>
          <div className="row">
            <label className="field"><span>Suburb</span><input value={form.suburb} onChange={set("suburb")} /></label>
            <label className="field"><span>City</span><input value={form.city} onChange={set("city")} /></label>
          </div>
          <div className="row">
            <label className="field"><span>Province</span><input value={form.province} onChange={set("province")} /></label>
            <label className="field"><span>Postal code</span><input value={form.postalCode} onChange={set("postalCode")} maxLength={10} /></label>
          </div>
          <div className="row">
            <label className="field"><span>Telephone</span><input value={form.phone} onChange={set("phone")} inputMode="tel" /></label>
            <label className="field"><span>Contact email</span><input value={form.email} onChange={set("email")} inputMode="email" /></label>
          </div>
          {formError && <p className="error-text" role="alert">{formError}</p>}
          <div className="row">
            <Button loading={saving} disabled={saving} onClick={create}>Save branch</Button>
            <Button variant="ghost" onClick={() => { setShowForm(false); setFormError(null) }}>Cancel</Button>
          </div>
        </div>
      )}

      {business && branches.length === 0 && (
        <EmptyState icon={<Store size={28} />} title="No branches yet"
          description="Your business works as a single location. Add a branch when you open another outlet."
          action={<Button onClick={() => setShowForm(true)}>Add your first branch</Button>} />
      )}

      {business && branches.map((b) => (
        <Link key={b.id} to={`/sell/branches/${b.businessId}/${b.id}`} className="card row" style={{ justifyContent: "space-between" }}>
          <div>
            <strong>{b.name}</strong>{b.branchCode && <span className="muted"> · {b.branchCode}</span>}
            <div className="muted small">{[b.address, b.suburb, b.city].filter(Boolean).join(", ") || "No address added"}</div>
          </div>
          <Badge tone={statusTone(b.status)}>{BRANCH_STATUS_LABELS[b.status]}</Badge>
        </Link>
      ))}

      {assignments.length > 0 && (
        <div className="stack">
          <h3>Branches you work at</h3>
          {assignments.map((a) => (
            <Link key={a.branchId} to={`/sell/branches/${a.businessId}/${a.branchId}`} className="card row" style={{ justifyContent: "space-between" }}>
              <div><strong>{a.branchName}</strong><div className="muted small">{a.businessName} · {a.role === "MANAGER" ? "Manager" : "Staff"}</div></div>
              <Badge tone={statusTone(a.status)}>{BRANCH_STATUS_LABELS[a.status]}</Badge>
            </Link>
          ))}
        </div>
      )}

      {!business && assignments.length === 0 && (
        <EmptyState icon={<Store size={28} />} title="No business or branch assignments"
          description="Create a business profile from the seller dashboard, or ask a business owner to assign you to a branch." />
      )}
    </div>
  )
}
