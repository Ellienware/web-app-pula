import { api } from "./api"
import type {
  AcceptInviteResult, CreatedInviteLink, DiscoverableStokvel, DiscoverFilter, InviteLink, InvitePreview, JoinRequest, JoinRequestStatus,
  MembershipSettings, Page,
} from "./stokvelMembership"

const S = "/stokvels"

/** One row of the existing circle membership list. Contact details are deliberately not part of this view. */
export interface StokvelMember { membershipId: string; userId: string; fullName: string; role: string; joinedAt?: string }

/**
 * Client for stokvel discovery, invite links and membership requests. It never decides who may do what: every call is checked
 * again by the server, which also keeps private stokvels private. Joining happens only after a server-side approval or a valid
 * invite; nothing here adds anyone.
 */
export const stokvelMembershipApi = {
  discover: (f: DiscoverFilter = {}, page = 0) => {
    const q = new URLSearchParams({ page: String(page), size: "20" })
    if (f.q?.trim()) q.set("q", f.q.trim())
    if (f.type) q.set("type", f.type)
    if (f.frequency) q.set("frequency", f.frequency)
    if (f.minContribution?.trim()) q.set("minContribution", f.minContribution.trim())
    if (f.maxContribution?.trim()) q.set("maxContribution", f.maxContribution.trim())
    if (f.area?.trim()) q.set("area", f.area.trim())
    return api.get<Page<DiscoverableStokvel>>(`${S}/discover?${q}`)
  },
  requestToJoin: (circleId: string, message: string) =>
    api.post<JoinRequest>(`${S}/${encodeURIComponent(circleId)}/join-requests`, { message: message.trim() || undefined }),
  mine: () => api.get<JoinRequest[]>(`${S}/join-requests/mine`),
  cancel: (circleId: string, requestId: string) =>
    api.post<JoinRequest>(`${S}/${encodeURIComponent(circleId)}/join-requests/${encodeURIComponent(requestId)}/cancel`),

  // ---- owner / admin ----
  settings: (circleId: string) => api.get<MembershipSettings>(`${S}/${encodeURIComponent(circleId)}/membership-settings`),
  saveSettings: (circleId: string, body: unknown) => api.put<MembershipSettings>(`${S}/${encodeURIComponent(circleId)}/membership-settings`, body),
  requests: (circleId: string, status?: JoinRequestStatus) =>
    api.get<JoinRequest[]>(`${S}/${encodeURIComponent(circleId)}/join-requests${status ? `?status=${status}` : ""}`),
  approve: (circleId: string, requestId: string, note?: string) =>
    api.post<JoinRequest>(`${S}/${encodeURIComponent(circleId)}/join-requests/${encodeURIComponent(requestId)}/approve`, { note: note?.trim() || undefined }),
  reject: (circleId: string, requestId: string, note?: string) =>
    api.post<JoinRequest>(`${S}/${encodeURIComponent(circleId)}/join-requests/${encodeURIComponent(requestId)}/reject`, { note: note?.trim() || undefined }),
  links: (circleId: string) => api.get<InviteLink[]>(`${S}/${encodeURIComponent(circleId)}/invite-links`),
  createLink: (circleId: string, expiresInDays: number, maxUses: number) =>
    api.post<CreatedInviteLink>(`${S}/${encodeURIComponent(circleId)}/invite-links`, { expiresInDays, maxUses }),
  revokeLink: (circleId: string, linkId: string) =>
    api.post<InviteLink>(`${S}/${encodeURIComponent(circleId)}/invite-links/${encodeURIComponent(linkId)}/revoke`),

  // ---- invitations ----
  preview: (code: string) => api.post<InvitePreview>(`${S}/invite-links/preview`, { code }),
  accept: (code: string) => api.post<AcceptInviteResult>(`${S}/invite-links/accept`, { code }),

  // ---- the existing membership list ----
  members: (circleId: string) => api.get<StokvelMember[]>(`/circles/${encodeURIComponent(circleId)}/members`),
}
