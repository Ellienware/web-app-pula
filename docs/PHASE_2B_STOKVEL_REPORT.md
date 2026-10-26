# Phase 2B - Stokvel (extension of Community Savings / Circles)

## Verification status (read first)
| Check | Result |
|---|---|
| `mvn test` / Spring context / Flyway on a real DB | **NOT VERIFIED** - no Maven, no network, no database in the build environment |
| V34 applied by Flyway and `ddl-auto=validate` against entities | **NOT VERIFIED** (SQL reviewed by hand against entity mappings) |
| Phase 2A characterisation tests re-run after the CircleService/ExpenseService/InvitationService edits | **NOT VERIFIED** (they need Mockito). Reviewed statically: fixture builds `CircleService` without the guard (field is optional), and only OWNER/ADMIN/MEMBER roles are used there |
| New Java compiled against the real source tree, with stubbed Spring/JPA annotations only | **0 errors** (main + controller + all new tests) |
| New unit tests executed (JUnit shim, `java.lang.reflect.Proxy` in-memory repositories, real service classes) | **71 run, 71 passed, 0 failed, 0 skipped** |
| Web `tsc` on `stokvelTypes.ts` + `stokvelApi.ts` (+ `types.ts`, `api.ts`) | exit 0 |
| Mobile `tsc` on `types/stokvel.ts` | exit 0 |
| Web `StokvelPage.tsx`, mobile `app/stokvel/[id].tsx`, edited screens | **NOT VERIFIED** (no node_modules; not type-checked or run) |

"Executed" tests ran under a throw-away JUnit shim, not Maven/Surefire. They are ordinary JUnit 5 tests and should be
run with `mvn test` before merging.

## Design in one paragraph
A stokvel is a `Circle` with `type = STOKVEL` plus a one-to-one `stokvel_profiles` row. Ordinary circles gain no
columns. Contributions go through the existing `ContributionService` (payment, validation, goal earmarking, activity);
`stokvel_contribution_links` only says which period a contribution counts toward. Status (due/partial/paid/missed) is
computed from confirmed payments on every read. Requests and votes record group decisions and never move money.
There is no wallet, pool, ledger, cart or checkout. "Tracked contributions" = confirmed contributions minus recorded
payouts and approved/executed requests, and is labelled as not held by PULA.

## Migration
`V34__stokvel.sql` (V33 untouched): `stokvel_profiles`, `stokvel_rotation_slots`, `stokvel_cycles`,
`stokvel_contribution_links`, `stokvel_requests`, `stokvel_request_votes`, `stokvel_notice_log`, plus nullable
`orders.stokvel_request_id` and `stock_orders.stokvel_request_id` (unique).

## Roles and permissions (backend-authoritative: `StokvelPermissions`)
| Action | OWNER | ADMIN | TREASURER | CHAIRPERSON | MEMBER |
|---|---|---|---|---|---|
| Update settings | yes | yes | no | no | no |
| Override rotation (before 1st payout) | yes | no | no | no | no |
| Record payout (never own) | yes | yes | yes | no | no |
| Create request | yes | yes | yes | yes | no |
| Approve / reject | yes | yes | yes | yes | only if `membersCanVote` |
| Cancel any request | yes | yes | no | yes | requester only |
| Record external execution | yes | yes | yes | no | no |
Nobody can decide their own request. Existing ADMIN powers are unchanged. **Important fix:** `requireAdmin` and
`ExpenseService` previously treated every non-MEMBER role as admin, which would have silently given TREASURER and
CHAIRPERSON full ADMIN powers; both are now an explicit OWNER/ADMIN check.

## APIs (`/api/stokvels`)
`POST /`, `GET /`, `GET /{id}`, `PUT /{id}/settings`, `GET /{id}/progress`, `POST /{id}/contributions`,
`GET /{id}/contributions/status`, `GET /{id}/rotation`, `PUT /{id}/rotation/order`, `POST /{id}/rotation/payouts`,
`GET|POST /{id}/requests`, `GET /{id}/requests/{rid}`, `POST .../approve|reject|cancel|link-order|record-execution`,
`GET /{id}/insights` (read-only, for the assistant/UI).

## Rotation
Default order = membership join order (ties by membership id). Founder (OWNER) may reorder until the first payout;
recording the first payout locks the order. Payout amounts are computed by the server from confirmed contributions;
`expectedCycleNumber` makes a double submit a 409. Beneficiary cannot record their own payout. Departed members are
skipped; a locked rotation blocks removal of a member who has not yet been paid.

## Approvals
SINGLE, THRESHOLD(n) or MAJORITY, snapshotted on each request at creation (later rule changes do not alter it).
Eligible voters exclude the requester. Rejected as soon as approval is arithmetically impossible. Votes are
append-only; replaying the same vote is idempotent, changing it is 409. Amount is `updatable=false`.
Requests expire (default 14 days; approval restarts the window); expiry is also shown immediately on read.

## Funding / marketplace
Approved request -> named buyer -> normal PULA checkout -> `POST .../link-order` with the order id. Server checks:
caller is the named buyer, order is theirs (otherwise 404), order total <= approved amount, same currency, order not
already linked. Request becomes EXECUTED with `related_order_*`; the order carries `stokvel_request_id`.

## Not done / limitations
- Checkout itself does not require or accept a request id; linking is a separate call made after checkout. A buyer can
  therefore check out without linking (nothing is spent from a pool either way). Enforcing it in checkout is the
  natural Save-to-Buy step.
- No create-stokvel UI, no request-creation form, no settings UI on web or mobile (API only). Existing screens show
  overview, contributions, rotation, requests, approve/reject/cancel/contribute/record payout.
- PULA AI: a read-only insights service exists; no assistant action types were added (a test asserts none exist for
  approve/payout/rotation), but `AssistantService` is not yet wired to call the insights endpoint.
- Generic `/api/circles/{id}/contributions` still works on a stokvel circle; those contributions count toward the
  circle total but not toward a member's period status.
- Contributions are allocated to the oldest unpaid period; one contribution is never split across periods.
  Unconfirmed (PENDING/PROCESSING) payments do not count, so two quick payments can both target the same period.
- Phase 2A finding left as-is (not a stokvel change): `addMember`/invitations let an ADMIN assign role OWNER on
  ordinary circles. Blocked inside stokvels by `StokvelMembershipGuard`.
- Partial-contribution "due" notices fire once per period (not daily). Notices need the scheduler (hourly,
  `app.stokvel.maintenance-ms`).
- Invitation notification for stokvels reuses whatever the existing invitation flow does; no new invitation code.
- Save-to-Buy, Services and Marketplace Settlement: not started, as instructed.
