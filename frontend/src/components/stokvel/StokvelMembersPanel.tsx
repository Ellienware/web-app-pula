import { useCallback, useEffect, useState } from "react"
import { stokvelMembershipApi, type StokvelMember } from "../../lib/stokvelMembershipApi"
import { ErrorState } from "../ui/StatePanel"
import { SkeletonText } from "../ui/Skeleton"

/** The stokvel's existing membership list (circle members). People who joined through an invite or an approved request appear here. */
export function StokvelMembersPanel({ circleId }: { circleId: string }) {
  const [members, setMembers] = useState<StokvelMember[] | null>(null)
  const [failed, setFailed] = useState(false)
  const load = useCallback(() => {
    setFailed(false)
    stokvelMembershipApi.members(circleId).then(setMembers).catch(() => setFailed(true))
  }, [circleId])
  useEffect(load, [load])

  if (failed) return <ErrorState title="Could not load members" onRetry={load} />
  if (!members) return <SkeletonText />
  return (
    <section className="card">
      <h3>Members ({members.length})</h3>
      <ul className="plain-list" aria-label="Stokvel members">
        {members.map((m) => (
          <li key={m.membershipId} style={{ display: "flex", justifyContent: "space-between", padding: "0.4rem 0" }}>
            <span>{m.fullName}</span>
            <span className="muted">{m.role.charAt(0) + m.role.slice(1).toLowerCase()}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
