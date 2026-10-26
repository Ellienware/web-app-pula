import type { ReactNode } from "react"
import { Link } from "react-router-dom"
import { useAuth } from "../../context/AuthContext"
import { EmptyState } from "../../components/ui/StatePanel"

/**
 * Hides admin screens from people who are not administrators. This is a convenience only: the server rejects every admin
 * request from a non-admin regardless of what the browser shows.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  if (user?.role === "ADMIN") return <>{children}</>
  return (
    <div className="page">
      <EmptyState title="Administrators only" description="This area is for PULA administrators." action={<Link to="/" className="btn btn-primary btn-sm">Go to home</Link>} />
    </div>
  )
}
