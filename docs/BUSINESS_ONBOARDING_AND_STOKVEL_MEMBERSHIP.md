# Business onboarding and Stokvel membership: Web/Mobile parity

**Status: IMPLEMENTED, NOT ACCEPTED.** Nothing here has run under Maven, a real database, a web build or a device (see section 5). Built on the
Phase 9B projects, which are themselves unverified (Phase 9A gate: NOT ACCEPTED). No payment provider was added; stokvel contributions remain
*tracked by PULA, not held by PULA*, and simulated wallet funds are not involved in any of this.

## 1. Audit: what the spec assumed vs what was there

| Spec assumption | Finding |
|---|---|
| Mobile has no business creation route | Partly wrong. Mobile already had a 3-step create form **inside Seller Hub** (`app/seller/index.tsx`). It never sent fulfilment options, sent the registration number even when "not registered", had no phone validation and no success navigation. A dedicated, complete route is now `app/business/new.tsx`; Seller Hub hands new users to it. |
| Web `CreateBusinessModal` is the reference | It lacked the Services capability (`CAN_PROVIDE_SERVICES`, which the backend and mobile have) and showed two types ("Local business" and informal trader) with the same label. Fixed (behaviour change, see 6). |
| "Ensure Stokvel creation uses the correct API" | **No Stokvel creation UI existed on either platform.** Web had an unused `stokvelApi.create`; mobile had no create or list call. Both now have a create flow on `POST /api/stokvels`. |
| No self-service discover-and-join | Confirmed, and more: the backend had only **email-bound** circle invitations (`CircleInvitation`, no expiry set, no share link/code), direct admin-add-by-email, no discoverability, no join requests. |
| Reuse CircleMembership | Done. A member is still a `circle_memberships` row; the new feature adds none of its own. |

## 2. Feature-parity matrix (all rows: implemented; verification per section 5)

| Capability | Backend | Web | Mobile |
|---|---|---|---|
| Business creation (types incl. informal trader, spaza, retailer, wholesaler, restaurant, service provider, other; name, category, description, location, phone; capabilities; fulfilment; registration status + optional number; validation; double-submit guard; ownership check; query refresh; success navigation) | existing `POST /api/businesses` (unchanged) | `CreateBusinessModal` (updated to the shared rules) | **new** `app/business/new.tsx` |
| Keep readiness + document screens | unchanged | unchanged | unchanged |
| Stokvel creation (STOKVEL api) | existing | **new** `CreateStokvelModal` (Dashboard "+ New stokvel") | **new** `app/stokvel/create.tsx` (Circles tab "＋ Stokvel") |
| Created stokvel appears in the creator's list and opens | existing list | navigates to `/stokvels/:id` after create | `router.replace('/stokvel/:id')`, circles query invalidated |
| Discoverability settings (private by default) | **new** `GET/PUT /{id}/membership-settings` | Join & invites tab | `app/stokvel/membership.tsx` |
| Discover screen with search + filters | **new** `GET /discover`, `/discover/{id}` | `/stokvels/discover` | `app/stokvel/discover.tsx` |
| Public requirements shown before asking | in card | yes | yes |
| Request to join; pending; cancel; my requests | **new** `POST /{id}/join-requests`, `/join-requests/mine`, `.../cancel` | Discover page | Discover screen |
| Admin review: approve / reject, history | **new** `GET /{id}/join-requests`, `.../approve|reject` | Join & invites tab | membership screen |
| Invite links/codes: create, list, revoke, expiry, use limits | **new** `POST/GET /{id}/invite-links`, `.../revoke` | Join & invites tab (copy code / link) | membership screen (Share sheet) |
| Preview + accept an invite (join, or request if approval required) | **new** `POST /invite-links/preview`, `/accept` | `/stokvels/join?code=` | `app/stokvel/join.tsx` (deep link `momocircle://stokvel/join?code=`) |
| New members appear in the existing membership list | `circle_memberships` | Members tab | `app/stokvel/members.tsx` |
| Private-stokvel privacy | enforced in service | n/a | n/a |

## 3. Migration and API details

**`V45__stokvel_membership.sql`** (forward-only; no existing row changes). Version 44 is intentionally absent from `db/migration`: the opt-in
`db/postgresql-guards/V44` file from the business-wallet work occupies it.
- `stokvel_profiles` + `discoverable` (default FALSE: every stokvel stays private), `area`, `public_summary`, `membership_requirements`, `max_members` (CHECK 2..500), `invite_requires_approval`.
- `stokvel_join_requests`: status CHECK (PENDING/APPROVED/REJECTED/CANCELLED), `pending_key` = `circle:user` only while PENDING with `UNIQUE(pending_key)` (one open request per person per stokvel on any database), CHECKs tying `pending_key`/`decided_at` to status.
- `stokvel_invite_links`: only the **SHA-256 of the code** is stored (`code_hash` UNIQUE), `max_uses` 1..100, `uses_count <= max_uses` CHECK, `expires_at`, `revoked_at`.
- `stokvel_invite_link_uses`: `UNIQUE(link_id, user_id)`, so no person can redeem a link twice.

**Rules enforced in `StokvelMembershipService`** (the UI only mirrors them):
- A stokvel is private unless an OWNER/ADMIN opts in; a private stokvel answers "not found" to discovery, previews and join requests from outsiders. Discoverable requires a 10+ character public summary. Discover returns only the public card (summary, requirements, area, counts, organiser name masked); never balances, totals, members, private description, emails or user ids.
- Every path that adds a member first takes the stokvel profile row lock (`findByCircleIdForUpdate`), serialising capacity and duplicate checks. A person is added only by an admin approving their PENDING request, or by redeeming a live invite that does not require approval. New members are always MEMBER; admin roles still come only from the existing role-change flow.
- Request transitions are single guarded `UPDATE`s whose WHERE names the legal source state (PENDING); an approval racing a cancel or another approval cannot both win. Capacity is re-checked at approval. A rejected person waits 24h (`app.stokvel.rejoin-cooldown-hours`) before asking again; an admin-issued invite is not subject to the cooldown.
- Invite use is counted by one guarded `UPDATE` (live, not revoked, not expired, uses left) so concurrent redemptions cannot exceed `max_uses`. Codes are 80-bit (16 Crockford chars), shown once at creation, never listed, never logged by the API (POST bodies); every invalid/expired/revoked/exhausted case returns the same generic 404 so codes cannot be probed. Retrying a redemption is idempotent (`ALREADY_MEMBER` / `ALREADY_REQUESTED`) and does not consume another use. Max 20 active links per stokvel.
- Rate limits via the existing `ServiceRateLimiter`: discover 60/min, join requests 10/hour, invite create 20/hour, preview+accept 10/min.
- Admin = OWNER or ADMIN (existing `requireAdmin`). Activity-feed entries and in-app notifications use existing infrastructure (new `ActivityType` values; notifications reuse `STOKVEL_APPROVAL_REQUESTED/COMPLETED`, so no notification-table change).

**API** (base `/api/stokvels`, authentication required on all): `GET /discover`, `GET /discover/{circleId}`, `GET|PUT /{circleId}/membership-settings`, `POST /{circleId}/join-requests`, `GET /join-requests/mine`, `GET /{circleId}/join-requests[?status=]`, `POST /{circleId}/join-requests/{id}/approve|reject|cancel`, `POST|GET /{circleId}/invite-links`, `POST /{circleId}/invite-links/{id}/revoke`, `POST /invite-links/preview`, `POST /invite-links/accept`.

## 4. Business onboarding details
Shared rules `lib/businessSetup.ts` (identical copy on web and mobile): option lists, per-step validation, phone normalisation (+27, 0xx, international), registration number checked for plausibility only, payload builder (omits empty optionals; sends a registration number only when registered or registering; sends fulfilment only for sellers). Registration is self-declared: both UIs show *"PULA does not register businesses with CIPC and does not check registration numbers."*

## 5. Test results (exact)

| Check | Result |
|---|---|
| Backend `mvn test` (new `StokvelMembershipH2Test` 22, `StokvelMembershipEndToEndTest` 7, `StokvelInviteCodesTest` 5; plus all earlier suites) | **NOT RUN**: no Maven, Docker or network in the build sandbox |
| Flyway V45 on H2 / PostgreSQL; Spring start-up; Hibernate `ddl-auto=validate` for the three new entities and the new `stokvel_profiles` columns | **NOT RUN** |
| Pure Java tests executed for real with a JUnit shim: `StokvelInviteCodesTest` (5), existing `LedgerEngineTest` (21) and `LedgerOperationsAuditTest` (6), `BusinessWalletEngineTest` (9), `QrTokenServiceTest` (5) | **PASS 46/46** (logic only) |
| All new backend main code + `StokvelMembershipH2Test` / `BusinessWalletFlowsH2Test` / `WalletFlowsH2Test` compiled with the real JDK 21 compiler against stubbed Spring/JUnit | **PASS** (types and signatures against the real repo classes; not JPQL/SQL/wiring). `StokvelMembershipEndToEndTest`: syntax only |
| Web `node --test tests/*.test.ts` (adds businessSetup 10, stokvelMembership 9, stokvelUiGuards 5) | **PASS 53/53** |
| Web strict `tsc` on the pure `lib` modules | **PASS** |
| Web `npm run build`, full `tsc`, component rendering | **NOT RUN** (no `node_modules`/network). New and changed TSX files syntax-parsed only (0 errors) |
| Mobile `node --test tests/*.test.mjs` (adds businessSetup 10, stokvelMembership 9, stokvelUiGuards 4) | **PASS 49/49** |
| Mobile strict `tsc` on the pure `lib` modules | **PASS** |
| Mobile full `tsc --noEmit`, Expo build, device run, deep-link open, Share sheet | **NOT RUN** |
| Full authenticated end-to-end flow (register, login, business, stokvel, discovery, request, approval, invite, member access, private-stokvel denial, expired/revoked/duplicate/concurrent cases) | **Written (`StokvelMembershipEndToEndTest`, `StokvelMembershipH2Test`) but NOT RUN.** No feature is marked verified end to end |
| Regression of PULA Money, Market, Community Savings, Save-to-Buy, Services, Ride | **NOT RUN.** By diff only: no PULA Ride, Market, Services or Save-to-Buy file changed; the existing backend files changed are `StokvelProfile` (new columns) and `ActivityType` (new values) |

To run: `python3 ci/verify_phase9a.py --backend backend`; `cd backend && mvn -B test`; `cd frontend && npm ci && npm run build && node --test tests/*.test.ts`; `cd mobile && npm ci && npx tsc --noEmit && node --test tests/*.test.mjs`.

## 6. Behaviour changes and remaining blockers/risks
1. **Web business modal changed:** SERVICE_PROVIDER now starts with `CAN_PROVIDE_SERVICES` + `CAN_BUY_STOCK` (was selling-only); sellers must keep at least one fulfilment option; invalid phone numbers are refused; a registration number is no longer sent for unregistered businesses; the type list uses distinct descriptive labels in the picker (labels elsewhere unchanged).
2. **Mobile Seller Hub:** a user with no business now sees a hand-off to `/business/new`; the old inline create path is left in the file but unreachable (remove after verification). Editing an existing business on mobile still has no fulfilment-option control (web parity gap, not requested).
3. **Existing email-bound invitations** (`CircleInvitation`) are untouched and still have no default expiry; the new link/code system covers the spec's expiry/revocation/duplicate-use needs for stokvels. Consider migrating Stokvel invites to it.
4. **Existing behaviour to review:** `GET /api/stokvels/{id}` answers 403 (not 404) to non-members, so a known id reveals that a stokvel exists; and `GET /api/circles/{id}/members` returns members' email addresses to other members. The new UI never displays emails, but the endpoint is unchanged.
5. Invite links use the app scheme `momocircle://` (mobile) and `/stokvels/join` (web); no universal/app links. Clipboard copy is web-only (mobile uses the Share sheet).
6. No automated test renders the components; the guards assert structure (membership-changing calls occur only in the confirm handlers). Rate limits are in-process.
7. Phase 9A is still unverified; the V41/V42/V43/V45 Flyway ordering note (guards file V44) applies when enabling the opt-in guards.

## 7. Files
**Backend/web, new:** `V45__stokvel_membership.sql`; `StokvelMembershipController`, `StokvelMembershipService`, `StokvelDiscoveryQuery`, `StokvelInviteCodes`, `StokvelMembershipDtos`; entities `StokvelJoinRequest`, `StokvelInviteLink`, `StokvelInviteLinkUse`; enums `JoinRequestStatus`, `JoinRequestSource`; repositories `StokvelJoinRequestRepository`, `StokvelInviteLinkRepository`, `StokvelInviteLinkUseRepository`; tests `StokvelMembershipH2Test`, `StokvelMembershipEndToEndTest`, `StokvelInviteCodesTest`; web `lib/{businessSetup,stokvelMembership,stokvelMembershipApi}.ts`, `components/stokvel/{CreateStokvelModal,StokvelMembersPanel,StokvelJoinPanel}.tsx`, `pages/{DiscoverStokvelsPage,JoinStokvelPage}.tsx`, `tests/{businessSetup,stokvelMembership,stokvelUiGuards}.test.ts`.
**Backend/web, modified:** `StokvelProfile.java`, `ActivityType.java`, `App.tsx`, `DashboardPage.tsx`, `StokvelPage.tsx`, `CreateBusinessModal.tsx`.
**Mobile, new:** `app/business/new.tsx`, `app/stokvel/{create,discover,join,members,membership}.tsx`, `lib/{businessSetup,stokvelMembership}.ts`, `services/stokvelMembershipApi.ts`, `tests/{businessSetup,stokvelMembership,stokvelUiGuards}.test.mjs`.
**Mobile, modified:** `app/seller/index.tsx`, `app/(tabs)/circles/index.tsx`, `app/stokvel/[id].tsx`.
