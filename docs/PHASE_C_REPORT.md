# Phase C — Circle / Market / Checkout / Seller Experience Report

Date: 2026-08-14

## 1. Approach

Per Rule 1/Rule 5 (don't rewrite, understand before changing), this pass
started by inventorying every frontend component and page and cross-checking
each against (a) whether it was actually imported/rendered anywhere, and
(b) whether its expected data shape matched the real backend DTOs
(`GoalDtos`, `ExpenseDtos`, `RecurringDtos`, `InvitationDtos`, etc.,
confirmed field-by-field). That surfaced two significant, well-contained
gaps rather than many small ones — both fixed below — plus a couple of
smaller completeness items.

## 2. What was found already working (unchanged)

- `ProductDetailPage`, `BusinessProfilePage`, `OrdersPage`,
  `NotificationsPage`, `DashboardPage`, `CartPage` — all already fully
  wired to their backend endpoints with loading/empty/error states.
- The checkout money flow itself (idempotency key generation, sandbox
  outcome messaging) was already correct on the frontend; the gap was
  purely about *reflecting* an in-flight payment's eventual resolution,
  not about executing it (see below).
- Every backend DTO touched by this pass (`GoalResponse`, `ExpenseResponse`,
  `ExpenseShareResponse`, `InvitationResponse`, `RecurringPlanResponse`,
  `RecurringPeriodResponse`, `RecurringDueResponse`, `BusinessResponse`)
  matches its frontend TypeScript type field-for-field — confirmed by
  direct comparison, not assumption.

## 3. What was fixed

### Circle experience — the major gap
`CircleDetailPage.tsx` was a monolithic page showing only contributions,
members, and payment requests. `GoalsTab`, `ExpensesTab`, `ActivityTab`,
`RecurringTab`, and `InviteModal` all already existed as complete,
functional components — built against the real API, styled, with their own
loading/busy states — but were **never imported anywhere**. Also orphaned:
`GoalModal`, `ExpenseModal`, `RecurringModal`.

Rewrote `CircleDetailPage.tsx` into a tabbed layout (Overview / Goals /
Members / Money / Activity / Recurring — matching the master prompt's own
Phase 6 spec) using the pre-existing `.tab-bar`/`.tab` CSS classes (already
defined in `index.css`, already used by `SellerDashboardPage`, but unused
by any Circle page before this). Wired all six tab components and five
modals in, backed by real endpoints already confirmed to exist:
`/circles/{id}/goals`, `/circles/{id}/expenses`, `/circles/{id}/activity`,
`/circles/{id}/recurring`, `/circles/{id}/invitations`. Also extended
`ContributeModal` with an `initialGoalId` prop so the Goals tab's per-goal
"Contribute" button actually preselects that goal instead of defaulting to
an unearmarked circle contribution.

### Checkout/payment lifecycle
`OrderDetailPage` loaded the order once on mount and never again. A buyer
whose sandbox charge came back PROCESSING (mobile number ending `...0001`)
would land on a page showing a static "Payment Processing" badge with no
indication anything would ever change — even though
`PaymentReconciliationService` (Phase B.1) is actively polling and will
resolve it within 30s, or auto-expire it per the processing-timeout policy.

Added polling (every 4s, faster than the backend's 30s cadence purely for
UI responsiveness) while `order.status` is `PENDING_PAYMENT` or
`PAYMENT_PROCESSING`, with an explicit "still confirming — this will update
automatically" callout so the wait is explained instead of looking broken.
Polling stops itself once the order resolves to any other status.

### Market experience
`MarketHomePage` only ever queried `/api/products` — despite
`GET /api/businesses` (search, category filter, pagination) already being
fully implemented on the backend, and `BusinessCard` already existing,
styled, and correctly typed, but unused by any page. Added a
Products/Businesses tab toggle. Also added the missing `.business-grid`
CSS rule (the `.business-card` styles existed already, suggesting this was
planned and not finished) and two other previously-unstyled classes that
were already in use elsewhere (`.callout`, `.sandbox-hint` — used by
`ContributeModal` and now `OrderDetailPage`/`RecurringModal`/etc. but never
had CSS rules).

### Seller experience
`ProductController` has a working `DELETE /api/products/{id}` (archives —
`ProductService.archive`) that was never called from
`SellerDashboardPage`. Sellers could create and edit listings but had no
way to take one down. Added an "Archive" action per listing (with a
confirm prompt and a busy state), which the product list already correctly
displays via `ProductStatus` including the pre-existing `ARCHIVED` case.

## 4. What was deliberately left alone

- `AssistantPanel` and the AI action framework — explicitly Phase D, not
  touched.
- Admin console UI — `AdminService`/`AdminController` exist on the backend
  but there's no frontend for it; out of Phase C's stated scope
  (Circle/Market/checkout/seller), not added here.
- No Profile page — appears in the master prompt's Phase 42 (UX polish),
  not Phase C's four listed items; left for that phase.

## 5. Tests / build verification

**NOT EXECUTED**, same constraint as Phase B and B.1 — no network egress in
this sandbox, so `npm install && npm run build` could not be run. Every
edited/new file was reviewed by hand against the actual prop signatures of
every component it renders (`GoalsTab`, `ExpensesTab`, `MembersTab`,
`ActivityTab`, `RecurringTab`, `ContributeModal`, `GoalModal`,
`ExpenseModal`, `RecurringModal`, `InviteModal`, `BusinessCard`), and every
API call's response shape was cross-checked against the real backend DTO,
not assumed. That is source-level review, not a substitute for `tsc`/build.

**Please run `cd frontend && npm install && npm run build` locally before
trusting this.** The single highest-risk spot for a real compile error is
`CircleDetailPage.tsx`, since it's the largest rewrite and touches the most
components at once.

## 6. Definition of Done status (Phase C scope)

| Item | Status |
|---|---|
| Circle experience (Goals/Members/Money/Activity/Recurring reachable) | DONE (source-level; unverified by build) |
| Market experience (products + businesses browsable) | DONE (source-level; unverified by build) |
| Checkout/payment lifecycle reflected in UI without manual refresh | DONE (source-level; unverified by build) |
| Seller experience (create/edit/archive products, view orders/stats) | DONE (source-level; unverified by build) |
| Build verified | NOT VERIFIED — blocked by environment, same as prior phases |

Recommended next step, as with Phase B.1: run the frontend build locally
and fix whatever `tsc`/Vite catches on first real compile, since that is
the one thing this sandbox structurally cannot do.
