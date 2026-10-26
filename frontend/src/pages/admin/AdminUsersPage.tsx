import { useCallback, useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { Page } from "../../lib/types"
import { formatDate } from "../../lib/format"
import { useAuth } from "../../context/AuthContext"
import { useToast } from "../../context/ToastContext"
import { PageHeader } from "../../components/ui/PageHeader"
import { Button } from "../../components/ui/Button"
import { ConfirmDialog } from "../../components/ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../../components/ui/StatePanel"
import { SkeletonCard } from "../../components/ui/Skeleton"

interface AdminUser { id: string; email: string; fullName: string; phoneNumber: string | null; role: "USER" | "ADMIN"; active: boolean; createdAt: string }

export default function AdminUsersPage() {
  const { user: me } = useAuth()
  const { showToast } = useToast()
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<AdminUser> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<AdminUser | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    setError(null)
    api.get<Page<AdminUser>>(`/admin/users?page=${page}&size=20`).then(setData).catch((e) => setError(e instanceof Error ? e.message : "Could not load users"))
  }, [page])
  useEffect(load, [load])

  async function confirm() {
    if (!target) return
    setBusy(true)
    try {
      await api.post(`/admin/users/${encodeURIComponent(target.id)}/${target.active ? "suspend" : "reactivate"}`)
      showToast(target.active ? "Account suspended" : "Account reactivated")
      setTarget(null)
      load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : "That didn't work", "error")
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page stack">
      <PageHeader title="Users" subtitle="Suspending an account signs that person out of every request immediately." />
      {error && <ErrorState description={error} onRetry={load} />}
      {!error && data === null && <div className="stack"><SkeletonCard lines={2} /><SkeletonCard lines={2} /></div>}
      {data && data.content.length === 0 && <EmptyState title="No users" />}
      {data && data.content.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table className="table" style={{ width: "100%" }}>
            <caption className="muted" style={{ textAlign: "left" }}>Accounts, page {data.number + 1}</caption>
            <thead><tr><th scope="col">Name</th><th scope="col">Email</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col">Joined</th><th scope="col"><span className="sr-only">Action</span></th></tr></thead>
            <tbody>
              {data.content.map((u) => (
                <tr key={u.id}>
                  <td>{u.fullName}</td>
                  <td>{u.email}</td>
                  <td>{u.role === "ADMIN" ? "Administrator" : "User"}</td>
                  <td><span className={`status-pill ${u.active ? "status-success" : "status-danger"}`}><span aria-hidden="true">{u.active ? "✓" : "✕"}</span>{u.active ? "Active" : "Suspended"}</span></td>
                  <td>{formatDate(u.createdAt)}</td>
                  <td>
                    {u.id === me?.id ? <span className="muted">You</span> : (
                      <Button size="sm" variant={u.active ? "danger" : "secondary"} onClick={() => setTarget(u)}>{u.active ? "Suspend" : "Reactivate"}</Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && (
        <div className="row">
          <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
          <Button variant="ghost" size="sm" disabled={(data.number + 1) * data.size >= data.totalElements} onClick={() => setPage(page + 1)}>Next</Button>
        </div>
      )}
      {target && (
        <ConfirmDialog
          title={target.active ? "Suspend this account?" : "Reactivate this account?"}
          summary={target.active ? "They will be unable to use PULA until reactivated." : "They will be able to use PULA again."}
          rows={[{ label: "Name", value: target.fullName }, { label: "Email", value: target.email }]}
          confirmLabel={target.active ? "Suspend" : "Reactivate"}
          danger={target.active}
          loading={busy}
          onConfirm={confirm}
          onCancel={() => setTarget(null)}
        />
      )}
    </div>
  )
}
