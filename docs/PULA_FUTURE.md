# PULA Future — backend slice status (planning & simulation only)

## Implemented (backend)
- V50__pula_future.sql: `circle_goals.category/priority` (nullable); new `allocation_plans`, `allocation_plan_lines`,
  `allocation_calculations`, `allocation_calculation_lines`, `projection_scenarios`. No link to payments/wallet/ledger/contributions.
- `com.momocircle.future`: AllocationCalculator, ProjectionCalculator (pure, integer-cents / BigDecimal), entities, repositories,
  FutureService, FutureController (`/api/future/...`), FutureDtos.
- Endpoints: GET dashboard; POST/GET plans; GET plan; POST plans/{id}/status; POST/GET plans/{id}/calculations; POST simulate;
  POST/GET scenarios.

## Rules enforced
- Plans are owner-scoped (other users get 404). Business plans require the caller to own the business. Goal lines require circle membership.
- Calculations are idempotent per (plan, key). Invalid scenarios (over-allocation, >100%) are returned with warnings and NOT persisted.
- PAUSED/ARCHIVED plans cannot be calculated; ARCHIVED is terminal. No status means invested/transferred/settled.
- Income basis is USER_ENTERED or BUSINESS_MANUAL_ENTRIES (manual `BusinessIncomeEntry` sum). Orders, payments, settlements and wallet
  credits are NOT summed (they may be the same economic event; no verified settlement source exists). Labelled unverified.
- FutureService depends on no wallet/ledger/payment/payout/settlement/contribution-creation service (asserted by a test).
- Dashboard separates PERSONAL (single-member circle) from GROUP_TRACKED goals and shows the caller's own recorded contributions separately.

## Verification actually performed
- Calculators compiled and run via plain-Java harness: ALL PASSED; projection figures cross-checked with Python Decimal.
- Spring-dependent files: javac syntax/symbol check only (all unresolved symbols are missing framework jars). NOT a compile.
- NOT run: Maven build, JUnit tests (incl. src/test/.../future/*), Flyway V50 on PostgreSQL, any existing regression test.
  Command to run: `cd backend && ./mvnw test` (needs Maven Central) and apply migrations to PostgreSQL 16.

## Not yet implemented
Business reserve line items beyond plan-level totals; refund/settlement-aware merchant income basis; Save-to-Buy suggested schedules;
stokvel role-aware obligations; education content; AI coach; notifications; web UI; mobile UI; goal create/update/pause for the new fields
(CircleGoal columns exist but no endpoint sets them yet).

## Update 2 — goal metadata, schedule estimates, education (backend)
- `PATCH /api/future/goals/{id}/meta` sets category/priority only; requires circle admin via existing `CircleService`; non-members get 404.
- `GET /api/future/goals/{id}/schedule?frequency=WEEKLY|MONTHLY`: suggested per-period amount (rounded up). Pure estimate; schedules nothing.
  `readyForExistingPurchaseWorkflow` is true only for a REACHED Save-to-Buy goal and merely points to the existing confirmation flow.
- `GET /api/future/education[/{id}]`: 10 lessons + glossary, static content with no tax rules, limits, provider names or return promises.
- `ScheduleCalculator` verified in the plain-Java harness (ALL PASSED). New JUnit tests (unrun): ScheduleCalculatorTest, EducationContentTest,
  extra FutureServiceTest cases.
- Pausing: the existing `GoalStatus` has ACTIVE/REACHED/CLOSED only. A goal "pause" would need a new enum value and a migration to any DB
  check on the column; not done. Plans (allocation rules) can be paused. Archiving a goal = existing CLOSE, which keeps contribution history.

## Update 3 — web application
- Route `/future` and a "Future" nav item (i18n key `nav.future`, English; other languages fall back to English).
- Tabs: Overview (personal vs group-tracked goals, own recorded contributions shown separately, plans, disclosures, first-goal empty state),
  Goals (category/priority via PATCH, schedule estimate), Smart Allocation (create plan, activate/pause/archive, calculate, history),
  Simulator (3 illustrative scenarios + assumptions, save/list), Learn (lessons + glossary).
- A persistent banner states: "Simulated balance — not real money. Planning estimate — no money has been transferred."
- No financial arithmetic in the browser: `futureLogic.ts` only validates inputs and drives a progress bar; all amounts come from the API.
  The dashboard is fetched fresh on each visit (no cache).
- Calculation requests carry an idempotency key that changes when the income input changes, so retries replay and edits recalculate.

### Verified
- `node --test tests/*.test.ts`: 71 tests, 71 pass (62 pre-existing + 9 new in `futureLogic.test.ts`).
- TypeScript strict check of the new files against hand-written stubs for react / react-router / lucide: 0 errors in new files.
  This is NOT `npm run build`: node_modules are not installed and the registry is unreachable here.
### Not verified
- `npm ci && npm run build`, rendering in a browser, mobile-width layout, accessibility audit, any end-to-end call against the backend.
### Web gaps
- Business-linked plans and the "business manual entries" income source are supported by the API but have no UI yet (no business selector).
- No create-goal form inside Future (goals are created in Circles; the empty state links to Home).
- Allocation lines can link to goals but there is no "go contribute" shortcut; contributions stay in the existing flows by design.
- Only English label for the new nav item.

## Files changed vs `pula-backend-web-phase9c-part2.zip`
Modified: backend `CircleGoal.java` (+category, priority), `ContributionRepository.java` (+own-contribution sum query);
frontend `App.tsx`, `AppLayout.tsx`, `index.css`, `lib/i18n/translations.ts`.
Added: backend `db/migration/V50__pula_future.sql`, `com/momocircle/future/*` (calculators, entities, repositories, service, controller, DTOs,
education content), tests under `src/test/java/com/momocircle/future/`; frontend `lib/futureLogic.ts`, `lib/futureApi.ts`,
`pages/FuturePage.tsx`, `pages/future/*`, `tests/futureLogic.test.ts`.

## How to finish verification on a machine with internet
    cd backend && ./mvnw test                      # runs existing + new JUnit tests; applies V50 on the test DB
    cd frontend && npm ci && node --test tests/*.test.ts && npm run build
    # then start the backend against PostgreSQL 16 so Flyway applies V50, and open /future

## Update 4 — AI coach, reminders, settlement-aware income, business items, stokvel view, goal creation, goal pausing
Migration **V51** (applies after V50): relaxes `ck_ac_source` to add `MERCHANT_SIMULATED_SETTLEMENTS`, adds `allocation_calculations.income_notes`,
creates `business_plan_items`, `reminder_preferences`, `reminder_log`. Goal pausing needs no schema change: `circle_goals.status` is VARCHAR(32)
with no check constraint, so `GoalStatus.PAUSED` is just a new value.

### New / changed endpoints (all authenticated, all scoped to the caller)
- `GET /api/future/businesses`, `GET /api/future/businesses/{id}/settlement-income?from&to` (read-only preview)
- `POST /api/future/plans/{id}/items`, `DELETE /api/future/plans/{id}/items/{itemId}` (business plans only)
- `POST /api/future/plans/{id}/calculations` now accepts `incomeSource = MERCHANT_SIMULATED_SETTLEMENTS`
- `POST /api/future/goals` (create), `POST /api/future/goals/{id}/pause|resume`
- `GET /api/future/stokvels`, `GET|PUT /api/future/reminders`, `POST /api/future/coach`

### Merchant income (refund- and settlement-aware)
Basis = sum of `netAmount` of the business's SALE settlements that are PAID, by `paidAt` in the chosen UTC date range. A settlement's net already equals
gross - refunds - commission - fees (+ adjustments), so refunds are never subtracted twice. Orders, payments, wallet credits and ledger entries are NOT
added (they are other representations of the same sale). ELIGIBLE / PROCESSING / ON_HOLD / FAILED settlements are reported as "pending, excluded".
PAID means paid by the platform's SIMULATED payout, so the result is labelled "Settled in PULA (simulated) — not bank-confirmed", never "verified".
Limits: merchant-protection compensation settlements are not included; a refund processed after payout counts only if the existing system adjusts that settlement.

### Business reserves and commitments
`business_plan_items` kinds: OPERATING_EXPENSES, SUPPLIER_COMMITMENTS, RENT_UTILITIES, PAYROLL, TAXES, PLANNED_INVENTORY (commitments) and
EMERGENCY_RESERVE (reserve). Effective reserves/commitments = plan-level figures + items; calculations store the effective totals. Planning inputs only.

### Goal pausing
Admin-only ACTIVE <-> PAUSED via existing `CircleService` admin rule. `GoalService.requireAcceptingContributions` is called in `ContributionService`
at both entry points (before any payment is reserved), so a paused goal returns 409 and creates no payment. Records, progress and history are untouched.
Save-to-Buy purchase confirmation is deliberately unchanged. Activity feed gets GOAL_PAUSED / GOAL_RESUMED.

### Goal creation
Goes through the existing `CircleService.create` + `GoalService.create` (all existing validation/admin rules apply). Without a circle it creates a personal
one-member circle. An identical repeat request (same title and target in the caller's own active personal goal) returns the existing goal.

### Stokvel view
Read-only. Role from `CircleMembership`, permissions from `StokvelPermissions.allows(...)`, obligation from `StokvelProfile`, own vs group recorded
contributions shown separately. Per-period paid/missed status, payouts and approvals stay in the stokvel itself.

### Reminders
Opt-in (no row = off); kinds GOAL_DEADLINE (7 and 1 days before), PLAN_REVIEW (monthly), SAVE_TO_BUY_PROGRESS (50% / 90%). `FutureReminderTask`
runs daily (`app.future.reminders-ms`, default 86400000) and only creates `FUTURE_REMINDER` notifications; `reminder_log (user_id, reminder_key)` is unique so
nothing repeats. Stokvel contribution reminders are NOT duplicated (the existing stokvel notice workflow already sends them). No money-moving dependency exists
in the reminder classes (asserted by a test).

### AI coach
Reuses the existing `app.ai.gateway-url` / `api-key` / `model` config. Explains server-calculated figures only; no tools, no side effects. Input is redacted
(digit runs of 4+ and emails removed, 500-char cap); only amounts and warnings are sent (no names, ids, business names). Output must pass `CoachGuard.isSafe`
(rejects guarantees, "risk-free", product advice, adviser claims, claims of having moved money) or the deterministic fallback answers instead. 20 questions/user/hour.
The fallback answers every topic with no AI configured and itself passes the same guard (tested).

## Verification performed for Update 4
- Plain-Java harness (`Check.java`, `Check2.java`, `Check3.java`): ALL PASSED (allocation, projection, schedule, merchant basis, coach guard/fallback, reminder planner).
- Backend Java: javac with `-XDshould-stop.ifError=FLOW` attributes method bodies despite missing framework jars: no errors other than unresolved
  JPA/Spring/validation/JUnit/Mockito classes in main and test sources. Still NOT a Maven build.
- Web: `node --test tests/*.test.ts` 78/78; strict tsc against stubs: 0 errors in new files.
- Mobile: `node --test tests/*.test.mjs` 74/74; strict tsc against stubs: 0 errors in new files.
- NOT run: Maven/JUnit, Flyway V50+V51 on PostgreSQL, `npm run build`, Expo, browser/device, any live AI gateway call, any end-to-end flow.

## Remaining gaps
- No UI to add a *shared* goal to a circle you only belong to as a non-admin (backend correctly refuses).
- Reminder times are UTC-day based; there is no per-user time zone or delivery-time setting, and no push/SMS channel (in-app only).
- Coach has no conversation memory (each question is independent) and no streaming.
- Stokvel view does not show per-period paid/missed status.
- `income_notes` stores the explanation at calculation time; pending settlements may change later and are not re-derived for old calculations.
- Pausing does not block the Save-to-Buy purchase request flow.

## Overall status (honest)
Built: planning/simulation backend, goal metadata/create/pause, schedule estimates, education, settlement-aware income, business items, stokvel view,
reminders, AI coach with deterministic fallback, web UI, mobile UI.
Never executed here: the Spring build and tests, database migrations, a browser, a device, live AI.
