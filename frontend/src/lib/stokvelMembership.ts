/**
 * Stokvel creation, discovery, invite and membership-request logic shared (by identical copy) between the web app and the mobile
 * app. Pure (no React, no network) so it is unit-tested. The server is the authority for every permission, capacity limit and state
 * transition; nothing here decides who may do what. PULA tracks stokvel contributions: it does not hold or pool the group's money.
 */

export type StokvelTypeId = 'ROTATIONAL' | 'GOAL_BASED' | 'GROCERY' | 'STOCK' | 'BUSINESS_EQUIPMENT' | 'EMERGENCY' | 'COMMUNITY_PROJECT' | 'GENERAL';
export type FrequencyId = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY';
export type ApprovalRuleId = 'SINGLE' | 'THRESHOLD' | 'MAJORITY';

export const FUNDS_NOTICE = "PULA tracks contributions for the group. It does not hold, pool or pay out the group's money.";

export const STOKVEL_TYPE_CHOICES: { value: StokvelTypeId; label: string; hint: string }[] = [
  { value: 'ROTATIONAL', label: 'Rotational', hint: 'Members take turns receiving the pot' },
  { value: 'GOAL_BASED', label: 'Goal-based', hint: 'Save together towards a shared target' },
  { value: 'GROCERY', label: 'Grocery', hint: 'Save for groceries, often for the festive season' },
  { value: 'STOCK', label: 'Stock', hint: 'Pool buying power to restock businesses' },
  { value: 'BUSINESS_EQUIPMENT', label: 'Business equipment', hint: 'Save for tools and equipment' },
  { value: 'EMERGENCY', label: 'Emergency', hint: 'A shared safety net' },
  { value: 'COMMUNITY_PROJECT', label: 'Community project', hint: 'Fund something for the neighbourhood' },
  { value: 'GENERAL', label: 'General', hint: 'A general savings group' },
];

export const FREQUENCY_CHOICES: { value: FrequencyId; label: string }[] = [
  { value: 'WEEKLY', label: 'Weekly' }, { value: 'BIWEEKLY', label: 'Every two weeks' }, { value: 'MONTHLY', label: 'Monthly' },
  { value: 'QUARTERLY', label: 'Quarterly' }, { value: 'YEARLY', label: 'Yearly' },
];

export const APPROVAL_CHOICES: { value: ApprovalRuleId; label: string; hint: string }[] = [
  { value: 'SINGLE', label: 'One approver', hint: 'One officer approves requests' },
  { value: 'THRESHOLD', label: 'A set number of approvers', hint: 'Choose how many approvals are needed' },
  { value: 'MAJORITY', label: 'Majority of members', hint: 'Most members must approve' },
];

// ------------------------------------------------------------------------------------------------ server contracts

export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }

export interface DiscoverableStokvel {
  circleId: string; name: string; organiser: string; stokvelType: StokvelTypeId; frequency: FrequencyId
  contributionAmount: number | null; currency: string; publicSummary: string | null; area: string | null
  requirements: string | null; memberCount: number; maxMembers: number | null; spotsLeft: number | null; full: boolean
  joinState: 'NONE' | 'PENDING'; pendingRequestId: string | null; notice: string
}

export type JoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface JoinRequest {
  requestId: string; circleId: string; stokvelName: string; applicantName: string; status: JoinRequestStatus
  source: 'DISCOVERY' | 'INVITE_LINK'; message: string | null; createdAt: string; decidedAt: string | null
  decidedByName: string | null; decisionNote: string | null; canCancel: boolean
}

export type InviteStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED' | 'EXHAUSTED';
export interface InviteLink {
  linkId: string; codeHint: string; status: InviteStatus; uses: number; maxUses: number; expiresAt: string
  createdAt: string; requiresApproval: boolean; createdBy: string; revokedAt: string | null
}
export interface CreatedInviteLink { link: InviteLink; code: string; notice: string }

export interface InvitePreview {
  stokvelName: string; organiser: string; stokvelType: StokvelTypeId; frequency: FrequencyId; contributionAmount: number | null
  currency: string; publicSummary: string | null; requirements: string | null; memberCount: number; maxMembers: number | null
  requiresApproval: boolean; expiresAt: string; alreadyMember: boolean; notice: string
}

export interface AcceptInviteResult {
  outcome: 'JOINED' | 'REQUESTED' | 'ALREADY_MEMBER' | 'ALREADY_REQUESTED'
  stokvelId: string | null; stokvelName: string; request: JoinRequest | null
}

export interface MembershipSettings {
  discoverable: boolean; area: string | null; publicSummary: string | null; membershipRequirements: string | null
  maxMembers: number | null; inviteRequiresApproval: boolean; memberCount: number; pendingRequests: number
}

export interface DiscoverFilter { q?: string; type?: StokvelTypeId | ''; frequency?: FrequencyId | ''; minContribution?: string; maxContribution?: string; area?: string }

// ------------------------------------------------------------------------------------------------ creating a stokvel

export interface StokvelDraft {
  name: string; description: string; stokvelType: StokvelTypeId; contributionAmount: string; frequency: FrequencyId
  startDate: string; targetAmount: string; targetDate: string; approvalRule: ApprovalRuleId; approvalThreshold: string; membersCanVote: boolean
}

export function emptyStokvelDraft(): StokvelDraft {
  return { name: '', description: '', stokvelType: 'GENERAL', contributionAmount: '', frequency: 'MONTHLY', startDate: '', targetAmount: '',
    targetDate: '', approvalRule: 'SINGLE', approvalThreshold: '', membersCanVote: false };
}

export type StokvelErrors = Partial<Record<'name' | 'description' | 'contributionAmount' | 'startDate' | 'targetAmount' | 'targetDate' | 'approvalThreshold', string>>;

/** A positive amount with at most two decimals, as a number; null if it is not one. */
export function parseMoney(raw: string): number | null {
  const s = (raw ?? '').trim().replace(/\s+/g, '').replace(',', '.');
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(s)) return null;
  const n = Number(s);
  return n > 0 ? n : null;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const validDate = (s: string) => ISO_DATE.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

export function validateStokvelDraft(d: StokvelDraft): StokvelErrors {
  const e: StokvelErrors = {};
  const name = d.name.trim();
  if (name.length < 2) e.name = 'Enter a name for the stokvel (at least 2 characters)';
  else if (name.length > 120) e.name = 'The name is too long (120 characters at most)';
  if (d.description.trim().length > 1000) e.description = 'The description is too long (1000 characters at most)';
  if (d.contributionAmount.trim()) {
    if (parseMoney(d.contributionAmount) === null) e.contributionAmount = 'Enter an amount above zero with at most two decimals, for example 250.00';
  } else if (d.stokvelType === 'ROTATIONAL') {
    e.contributionAmount = 'A rotational stokvel needs the amount each member contributes';
  }
  if (d.startDate.trim() && !validDate(d.startDate.trim())) e.startDate = 'Use the format YYYY-MM-DD';
  if (d.targetAmount.trim() && parseMoney(d.targetAmount) === null) e.targetAmount = 'Enter a target above zero with at most two decimals';
  if (d.targetDate.trim()) {
    if (!validDate(d.targetDate.trim())) e.targetDate = 'Use the format YYYY-MM-DD';
    else if (d.startDate.trim() && validDate(d.startDate.trim()) && d.targetDate.trim() < d.startDate.trim()) e.targetDate = 'The target date cannot be before the start date';
  }
  if (d.approvalRule === 'THRESHOLD' && !/^[1-9]\d{0,2}$/.test(d.approvalThreshold.trim())) e.approvalThreshold = 'Enter how many approvals are needed (1 or more)';
  return e;
}

/** The exact body for POST /api/stokvels (never the generic circle endpoint, which refuses stokvels). */
export function toCreatePayload(d: StokvelDraft, currency: string) {
  const amount = d.contributionAmount.trim() ? parseMoney(d.contributionAmount) : null;
  const target = d.targetAmount.trim() ? parseMoney(d.targetAmount) : null;
  return {
    name: d.name.trim(),
    ...(d.description.trim() ? { description: d.description.trim() } : {}),
    stokvelType: d.stokvelType,
    currency,
    ...(amount !== null ? { contributionAmount: amount } : {}),
    frequency: d.frequency,
    ...(d.startDate.trim() ? { startDate: d.startDate.trim() } : {}),
    ...(target !== null ? { targetAmount: target } : {}),
    ...(d.targetDate.trim() ? { targetDate: d.targetDate.trim() } : {}),
    approvalRule: d.approvalRule,
    ...(d.approvalRule === 'THRESHOLD' ? { approvalThreshold: Number(d.approvalThreshold.trim()) } : {}),
    membersCanVote: d.membersCanVote,
  };
}

// ------------------------------------------------------------------------------------------------ discovery settings

export interface SettingsDraft {
  discoverable: boolean; area: string; publicSummary: string; membershipRequirements: string; maxMembers: string; inviteRequiresApproval: boolean
}

export function settingsToDraft(s: MembershipSettings): SettingsDraft {
  return { discoverable: s.discoverable, area: s.area ?? '', publicSummary: s.publicSummary ?? '', membershipRequirements: s.membershipRequirements ?? '',
    maxMembers: s.maxMembers === null ? '' : String(s.maxMembers), inviteRequiresApproval: s.inviteRequiresApproval };
}

export type SettingsErrors = Partial<Record<'area' | 'publicSummary' | 'membershipRequirements' | 'maxMembers', string>>;

/** Mirrors the server rules so a person sees the problem before saving; the server re-checks all of them. */
export function validateSettings(d: SettingsDraft, currentMembers: number): SettingsErrors {
  const e: SettingsErrors = {};
  if (d.area.trim().length > 80) e.area = 'Area is too long (80 characters at most)';
  const summary = d.publicSummary.trim();
  if (summary.length > 300) e.publicSummary = 'The public summary is too long (300 characters at most)';
  else if (d.discoverable && summary.length < 10) e.publicSummary = 'Add a short public summary (at least 10 characters) before making the stokvel discoverable';
  if (d.membershipRequirements.trim().length > 500) e.membershipRequirements = 'Requirements are too long (500 characters at most)';
  if (d.maxMembers.trim()) {
    if (!/^\d{1,3}$/.test(d.maxMembers.trim())) e.maxMembers = 'Enter a whole number between 2 and 500';
    else {
      const n = Number(d.maxMembers.trim());
      if (n < 2 || n > 500) e.maxMembers = 'Enter a whole number between 2 and 500';
      else if (n < currentMembers) e.maxMembers = `The limit cannot be lower than the current number of members (${currentMembers})`;
    }
  }
  return e;
}

export function toSettingsPayload(d: SettingsDraft) {
  return {
    discoverable: d.discoverable,
    area: d.area.trim() || null,
    publicSummary: d.publicSummary.trim() || null,
    membershipRequirements: d.membershipRequirements.trim() || null,
    maxMembers: d.maxMembers.trim() ? Number(d.maxMembers.trim()) : null,
    inviteRequiresApproval: d.inviteRequiresApproval,
  };
}

// ------------------------------------------------------------------------------------------------ invite codes and links

const DASHED_CODE = /(?<![0-9A-Za-z]|[0-9A-Za-z]-)([0-9A-Za-z]{4})-([0-9A-Za-z]{4})-([0-9A-Za-z]{4})-([0-9A-Za-z]{4})(?![0-9A-Za-z]|-[0-9A-Za-z])/;

function format(raw16: string): string | null {
  const up = raw16.toUpperCase();
  return /^[0-9A-Z]{16}$/.test(up) ? `${up.slice(0, 4)}-${up.slice(4, 8)}-${up.slice(8, 12)}-${up.slice(12, 16)}` : null;
}

/**
 * Finds an invite code in what was typed, pasted or opened from a link, in this order: a ?code= value in a link; the whole text
 * being just the code (any case, with or without dashes or spaces); a dashed XXXX-XXXX-XXXX-XXXX code inside a longer message.
 * A code written with spaces inside ordinary prose is NOT guessed at, because words would match. Returns XXXX-XXXX-XXXX-XXXX or
 * null. Shape only: the server decides whether the code is valid, and nothing is joined by pasting a code.
 */
export function extractInviteCode(raw: string | null | undefined): string | null {
  if (!raw || raw.length > 300) return null;
  const text = raw.trim();
  const q = text.match(/[?&#]code=([^&#\s]+)/i);
  if (q) {
    let value = q[1];
    try { value = decodeURIComponent(value); } catch { /* use as is */ }
    const fromParam = format(value.replace(/[\s-]/g, ''));
    if (fromParam) return fromParam;
  }
  const whole = format(text.replace(/[\s-]/g, ''));
  if (whole) return whole;
  const dashed = text.match(DASHED_CODE);
  return dashed ? format(dashed.slice(1, 5).join('')) : null;
}

export function webInviteLink(origin: string, code: string): string {
  return `${origin.replace(/\/+$/, '')}/stokvels/join?code=${encodeURIComponent(code)}`;
}

export function appInviteLink(scheme: string, code: string): string {
  return `${scheme}://stokvel/join?code=${encodeURIComponent(code)}`;
}

// ------------------------------------------------------------------------------------------------ states and wording

export type Tone = 'neutral' | 'success' | 'pending' | 'failed' | 'info';

export function requestStatusLabel(s: JoinRequestStatus): string {
  switch (s) {
    case 'PENDING': return 'Waiting for approval';
    case 'APPROVED': return 'Approved';
    case 'REJECTED': return 'Declined';
    case 'CANCELLED': return 'Withdrawn';
  }
}

export function requestStatusTone(s: JoinRequestStatus): Tone {
  switch (s) {
    case 'PENDING': return 'pending';
    case 'APPROVED': return 'success';
    case 'REJECTED':
    case 'CANCELLED': return 'failed';
  }
}

export function inviteStatusLabel(s: InviteStatus): string {
  switch (s) {
    case 'ACTIVE': return 'Active';
    case 'EXPIRED': return 'Expired';
    case 'REVOKED': return 'Revoked';
    case 'EXHAUSTED': return 'All uses taken';
  }
}

export function inviteStatusTone(s: InviteStatus): Tone { return s === 'ACTIVE' ? 'success' : 'failed'; }

/** What a Discover card lets the person do. Only a hint: the server enforces everything again on submit. */
export function discoveryAction(c: Pick<DiscoverableStokvel, 'full' | 'joinState'>): { canRequest: boolean; label: string; note: string | null } {
  if (c.joinState === 'PENDING') return { canRequest: false, label: 'Request pending', note: 'The organisers have been asked to approve you.' };
  if (c.full) return { canRequest: false, label: 'Full', note: 'This stokvel has no spaces left.' };
  return { canRequest: true, label: 'Ask to join', note: null };
}

export function acceptOutcomeMessage(r: Pick<AcceptInviteResult, 'outcome' | 'stokvelName'>): string {
  switch (r.outcome) {
    case 'JOINED': return `You joined ${r.stokvelName}.`;
    case 'REQUESTED': return `Your request to join ${r.stokvelName} was sent. You become a member when an organiser approves it.`;
    case 'ALREADY_MEMBER': return `You are already a member of ${r.stokvelName}.`;
    case 'ALREADY_REQUESTED': return `You already have a pending request for ${r.stokvelName}.`;
  }
}

export function contributionSummary(amount: number | null, frequency: FrequencyId, currency: string): string {
  const every = FREQUENCY_CHOICES.find((f) => f.value === frequency)?.label.toLowerCase() ?? frequency.toLowerCase();
  if (amount === null) return `Contribution set by the group, ${every}`;
  const money = new Intl.NumberFormat('en-ZA', { style: 'currency', currency, maximumFractionDigits: 2 }).format(amount);
  return `${money} ${every}`;
}

export interface ErrorLike { status?: number; message?: string }

/** Plain-language message for an API error. The server's messages are already safe to show; this only covers transport cases. */
export function friendlyMembershipError(err: unknown): string {
  const e = (err ?? {}) as ErrorLike;
  if (e.status === 401) return 'Your session has expired. Please sign in again.';
  if (e.status === 429) return e.message || 'Too many attempts. Please wait a moment and try again.';
  if (typeof e.status !== 'number') return 'Could not reach the server. Nothing was changed; check your connection and try again.';
  if (e.status >= 500) return 'Something went wrong on our side. Nothing was changed; try again.';
  return e.message || 'Request failed';
}

/** UI hint only; the server checks the role on every call. */
export function canManageMembership(role: string | null | undefined): boolean {
  return role === 'OWNER' || role === 'ADMIN';
}
