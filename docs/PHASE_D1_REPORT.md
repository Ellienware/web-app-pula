# Phase D.1 — Corrective and Completion Report

Date: 2026-08-16

## 1. Existing Phase D functionality preserved

Confirmed unchanged/untouched: JWT auth, Circle/Goal/Contribution/PaymentRequest/
Expense/RecurringPlan domain and services, Notifications, Receipts, Audit
logging, MoMoMarket (Business/Product/Cart/Order), seller dashboard, the
`PaymentProvider` abstraction (Sandbox + MTN), reconciliation, Flyway
migrations V1/V2, the payment state machine (`Payment.transitionTo`),
idempotency (`PaymentIdempotencyService`). No entity, controller, or service
from these areas was rewritten — only `AssistantService`/`AssistantDtos`/
`AssistantController`/`AssistantPanel` and the six financial-amount call
sites listed in §3 were touched, plus new, additive files.

## 2. Changes made, by objective

### §1 — Secure AI proposal binding (DONE, source-level)

Previously `POST /assistant/execute` trusted the client to resend the same
`intent`/`parameters`/`circleId` it was shown — nothing stopped a tampered
request from swapping in a different amount or circle between proposal and
confirmation.

- New `AssistantProposal` entity (`user`, `circleId`, `intent`,
  `parametersJson`, `status`, `expiresAt`) + `ProposalStatus` enum
  (PENDING/EXECUTED/EXPIRED) + `AssistantProposalRepository` +
  `V3__assistant_proposals.sql`.
- `AssistantService.interpret(User actor, AssistantRequest req)` now
  persists every *mutating* proposal before returning it; the client
  receives only `proposalId` plus display-only summary/parameters.
  SEARCH_MARKET is the one deliberate exception — it's read-only, so it's
  never persisted or routed through `execute()` at all; the frontend runs
  it directly against `GET /api/products`.
- `ExecuteActionRequest` slimmed to `{proposalId, currentCircleId}` — no
  intent, no parameters, no circleId-to-act-on. `execute()` loads the
  stored proposal and checks, in order: ownership (else 404 — same
  response as a genuinely missing id, so a stranger's proposal id can't be
  probed for existence), not-already-executed (409), not-expired (400,
  and marks it EXPIRED), circle-context-unchanged (400). Only then does it
  parse `parametersJson` and run the actual action.
- Frontend (`AssistantPanel.tsx`, `lib/types.ts`) updated to match:
  `AssistantAction.proposalId`, confirm sends only
  `{proposalId, currentCircleId}`.

**Tests**: `AssistantServiceProposalTest` — all 5 scenarios the brief asked
for (another user, expired, duplicate execution, changed circle context,
successful execution using stored not client-resent parameters), plus a
6th for a missing-proposal-id and a 7th proving a market action (ADD_TO_CART)
also goes through the same stored-parameter path, not just circle actions.

### §2 — Financial validation limits (DONE, source-level)

New `FinancialValidationService` — amount `> 0`, `≤ 2` decimal places, a
configurable max (`app.payments.max-amount`, default 10,000,000), currency
must be in the configured supported list. Wired into the actual creation
methods, not the AI layer, so manual forms and AI-confirmed actions are
protected identically: `ContributionService.contribute` +
`.charge` (shared by direct and recurring-due contributions) +
`.createPaymentRequest`, `GoalService.create`, `ExpenseService.create`,
`RecurringContributionService.create`, `ProductService.create` + `.update`,
`CircleService.create` (currency + optional goalAmount).

**Tests**: `FinancialValidationServiceTest` — zero, negative, null,
excessive, too-many-decimals amounts rejected; exact-max and normal amounts
accepted; unsupported/blank currency rejected; supported currency (including
case-insensitivity) accepted.

### §3 — Currency configuration (DONE — and found two real pre-existing bugs)

New `CurrencyProperties` (`app.currency.default-currency`,
`app.currency.supported`, both env-overridable) + `GET /api/config/currency`
(public, no auth) so the frontend reads config instead of hard-coding it.
`application.yml` documents the exact two env vars to set to switch market
(e.g. `DEFAULT_CURRENCY=GHS`, `SUPPORTED_CURRENCIES=GHS` for Ghana).

Replaced hard-coded `"XAF"` in `ProductService` and `AssistantService`
(circle-creation default + AI heuristic default).

**Two real bugs found while auditing, not just style cleanup:**
1. `CreateCircleModal.tsx` offered **"USD" as a currency option in the
   dropdown**, which isn't in the backend's default supported list — before
   this pass that request would have silently created a circle with an
   unvalidated currency; after §2's validation lands, it would have started
   failing with a confusing 400 instead. Fixed by rendering the dropdown
   from `GET /api/config/currency` instead of a hard-coded list (also fixed
   its default-selected value, which was hard-coded to `"GHS"`, inconsistent
   with the backend's `"XAF"` default).
2. `lib/format.ts`'s `formatMoney()` had a hard-coded `currency = "GHS"`
   default parameter, and `DashboardPage.tsx`'s "Total saved" card was
   summing `totalContributed` **across all of a user's circles regardless
   of currency** — silently adding XAF and GHS amounts together into one
   number if a user had circles in both. Fixed by making `currency` a
   required parameter (a deliberate breaking change — every call site was
   swept and confirmed to already pass one explicitly, except two now-fixed
   ones) and changing the dashboard to group and display one total per
   currency instead of one incorrect blended total.

New `frontend/src/lib/currencyConfig.ts` — a small cached hook
(`useCurrencyConfig()`) other components can share instead of each
duplicating the fetch; used by `CreateCircleModal`, `AssistantPanel`, and
`SellerDashboardPage` (which had its own `stats.currency ?? undefined`
pattern that would have been a real TypeScript compile error once
`formatMoney`'s currency parameter became required — fixed to fall back to
`useCurrencyConfig()`'s default instead of `undefined`).

### §4 — AI market actions (DONE, source-level)

Added `SEARCH_MARKET`, `ADD_TO_CART`, `CREATE_BUSINESS`, `CREATE_PRODUCT` to
`ActionType`, each routed through the real, already-authorized service
(`ProductService.search`/`.browse` for search, `CartService.add` — which
already validates the product exists and is `ACTIVE` — for cart,
`BusinessService.create`/`ProductService.create` for the seller-facing
ones). No new authorization logic was written for any of them; each
inherits whatever the underlying service already enforced.

`SEARCH_MARKET` deliberately does **not** go through the proposal-binding
system at all — it's read-only, so `requiresConfirmation=false` and the
frontend calls `GET /api/products` directly and renders results inline.
`ADD_TO_CART`'s `productId` is never taken from the AI's own output — only
from `AssistantRequest.productId`, a new field mirroring `circleId`, set by
`AppLayout.tsx` from the current route (`/market/products/:id`) the same
way `circleId` already was from `/circles/:id`. The system prompt is
explicit that the model must not invent or guess a productId.

**Tests**: heuristic-level tests for all four in
`AssistantServiceHeuristicTest` (search-without-confirmation, add-to-cart
requires product context and refuses to guess, business creation, product
listing), plus the execute()-level ADD_TO_CART test in §1 above proving the
full stored-parameter flow for a market action specifically.

### §5 — Receipt/image AI (DONE, source-level, narrower than ideal)

System prompt now explicitly instructs: on low-confidence or missing-field
receipt extraction, return `NONE` and ask the user to confirm/correct
rather than guessing, and never claim OCR precision the configured provider
doesn't actually have. Separately — and this is the more concrete fix — the
**offline heuristic fallback previously silently ignored any uploaded
image** (it only ever read `req.message()`), which would have looked like
the assistant understood a receipt when it hadn't looked at it at all. It
now explicitly tells the user it can't read images without an AI Gateway
configured and asks them to type the amount/description instead.

**Limitation, unchanged from Phase D**: the actual image-understanding path
(`callModel()`) requires a real AI Gateway key this sandbox doesn't have,
so the "extracts merchant/amount/date/line items" behavior itself is
unverified beyond the prompt text — same caveat as before.

### §6 — Voice input (DONE, source-level)

Web Speech API (`SpeechRecognition`/`webkitSpeechRecognition`) wired into
`AssistantPanel` behind a feature-detect (`getSpeechRecognition()`); the
mic button is **not rendered at all** when unsupported, rather than shown
disabled with no explanation. Transcript appends into the existing text
input — reuses the exact same `interpret()` flow as typed text, so nothing
about the proposal-binding security model changes for voice input; it's
just a different way of populating the same textbox.

### §7 — AI action UX (DONE, source-level — and found a bigger pre-existing gap)

Distinct "I'll do this" (pending) vs "Done" (resolved) `ActionCard` states,
matching the brief's example layout. While implementing this, found that
**the entire assistant panel had no CSS anywhere in the codebase** — the
chat log, bubbles, action cards, confirm bar, and input row were all
unstyled default HTML; only the trigger button (`.btn-assistant`) had ever
been styled. Wrote a complete stylesheet for it (`index.css`, ~230 new
lines) since shipping the UX improvements this section asks for on top of
literally unstyled markup would not have achieved the goal.

## 3. Files changed

**Backend, new**: `domain/AssistantProposal.java`,
`domain/enums/ProposalStatus.java`,
`repository/AssistantProposalRepository.java`,
`config/CurrencyProperties.java`, `service/FinancialValidationService.java`,
`controller/ConfigController.java`,
`db/migration/V3__assistant_proposals.sql`,
`test/.../AssistantServiceProposalTest.java`,
`test/.../FinancialValidationServiceTest.java`.

**Backend, modified**: `service/AssistantService.java` (full rewrite),
`dto/AssistantDtos.java`, `controller/AssistantController.java`,
`service/ContributionService.java`, `service/GoalService.java`,
`service/ExpenseService.java`, `service/RecurringContributionService.java`,
`service/ProductService.java`, `service/CircleService.java`,
`security/SecurityConfig.java` (allow `/api/config/**`),
`application.yml`, `test/.../AssistantServiceHeuristicTest.java` (updated
for the new constructor + new market-action cases).

**Frontend, new**: `lib/currencyConfig.ts`.

**Frontend, modified**: `components/AssistantPanel.tsx` (full rewrite),
`lib/types.ts`, `components/AppLayout.tsx` (productId context),
`components/CreateCircleModal.tsx`, `pages/DashboardPage.tsx`,
`pages/market/SellerDashboardPage.tsx`, `lib/format.ts`,
`pages/market/CartPage.tsx`, `index.css`.

## 4. Tests added

- `AssistantServiceProposalTest` — 7 cases (§1 above).
- `FinancialValidationServiceTest` — 11 cases (§2 above).
- `AssistantServiceHeuristicTest` — extended from 9 to 15 cases: the
  original 9 updated for the new constructor/response shape, plus 6 new
  (market search, add-to-cart-without-context, add-to-cart-with-context,
  image-without-gateway, business creation, product listing).

## 5. Tests executed

**NOT EXECUTED.** Same environment constraint as every prior phase — no
network egress in this sandbox (confirmed again this pass: `./mvnw` isn't
present, no system `mvn`, and `npm install` still gets `403 Forbidden` from
the registry). Every new/changed file was reviewed by hand against the real
signatures of everything it calls — every DTO field order, every service
method signature, every repository query — cross-checked by reading the
actual source, not assumed, including catching the `SellerDashboardPage`
`undefined`-currency issue and the `CartPage` optional-chaining issue that
`formatMoney`'s new required parameter would have surfaced as real compile
errors. That is a substitute for careful review, not for a compiler.

## 6. Build results

**BLOCKED BY ENVIRONMENT**, unchanged from every prior phase.
`cd backend && ./mvnw test && ./mvnw package` and
`cd frontend && npm install && npm run build` need to be run locally before
merging. Given the size of this pass (a full `AssistantService` rewrite,
five other services touched, a new migration, a full `AssistantPanel`
rewrite, and a `formatMoney` signature change swept across ~15 call sites),
**this is the single most important phase yet to actually compile before
trusting** — there is more new surface area here than in Phases B.1, C, and
D combined.

## 7. Known limitations

- No scheduled cleanup job for expired `AssistantProposal` rows — they're
  lazily marked EXPIRED on access but otherwise just accumulate. Low
  priority (they're small rows with a clear expiry timestamp, easy to add
  a `@Scheduled` sweep for later) but worth flagging as intentionally not
  done rather than overlooked.
- `SEARCH_MARKET`'s `maxPrice` parameter is applied as a **client-side
  filter** on the returned page, not a real backend query parameter —
  `ProductController` has no `maxPrice` filter and I deliberately didn't
  add one this pass (see Rule 2's spirit: don't extend an API's surface
  area as a side effect of an unrelated task). This means "find products
  under 500" can return a page that, after filtering, has fewer results
  than the page size even though more matches might exist on the next
  server-side page. Fine for a first pass; a real `maxPrice` backend
  filter would be a clean, separate, small addition later.
- Image/document AI extraction quality is still entirely unverified beyond
  prompt text — needs a real AI Gateway key to test at all.
- Multi-currency circles are now *correctly displayed* (grouped, not
  summed together) on the dashboard, but nothing in this pass adds actual
  currency conversion — that's a materially bigger feature, out of scope.

## 8. External dependencies not yet available

- MTN MoMo production credentials — unchanged from every prior phase.
- A real AI Gateway key — needed to verify anything about `callModel()`'s
  actual behavior (image extraction quality, JSON adherence, prompt
  injection resistance in practice rather than just in instruction text).

## 9. Definition of Done status

| Item | Status |
|---|---|
| Existing functionality still works | Preserved by inspection; not build-verified |
| AI proposals securely bound to authenticated user | DONE (source-level; tested; unverified by build) |
| Proposal parameters cannot be changed by client before execution | DONE (source-level; tested; unverified by build) |
| Expired proposals rejected | DONE (tested) |
| Duplicate proposal execution rejected | DONE (tested) |
| Financial limits enforced server-side | DONE (source-level; tested; unverified by build) |
| Currency configuration centralized | DONE — plus 2 real pre-existing bugs found and fixed |
| AI can search MoMoMarket | DONE (source-level; unverified by build) |
| AI can propose adding products to cart | DONE (source-level; tested; unverified by build) |
| AI can propose creating businesses and products | DONE (source-level; unverified by build) |
| Receipt/image workflows produce proposals, not automatic changes | Already true by construction; low-confidence guidance added |
| Voice input works where supported, degrades gracefully | DONE (source-level; unverified by build) |
| Tests added | DONE — 33 new/updated test cases across 3 files |
| Frontend build succeeds or blocker documented | BLOCKED BY ENVIRONMENT (documented) |
| Backend tests/package succeed or blocker documented | BLOCKED BY ENVIRONMENT (documented) |
| `docs/PHASE_D1_REPORT.md` created | DONE (this document) |

Every substantive item is done at the source level and, where the brief
asked for tests, has them — but as with every phase before it, **none of
this has actually compiled**. Given the size of this particular pass, that
gate matters more here than it has before.
