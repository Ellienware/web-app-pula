# Phase 7 — Intelligent Transaction-Risk Layer

## Verification status (read this first)

| Item | Status |
|---|---|
| Rule engine tests (16): rules, precedence, bounds, missing signals, wording | **RAN, 16/16 passed** (offline JUnit-5-style shim) |
| Gate / bind / backstop / review / tenant tests (29) with in-memory repositories | **RAN, 29/29 passed** |
| All Phase 6 + Phase 7 tests together (125) | **RAN, 125/125 passed** |
| Mutation check (5 deliberate breaks: tampered amount accepted, STEP_UP treated as allowed, backstop never throws, expiry ignored, ownership rule removed) | **RAN, every break made tests fail** |
| All 7 modified backend classes + new code compiled against the real source tree | **RAN, 0 errors** (Spring/JPA annotations stubbed) |
| `application.yml` still parses (4 documents) with the new `app.risk` block | **RAN** |
| New/edited web + mobile files type-checked | **RAN**; no errors in new/edited lines (stubbed React/RN typings; NOT a real build) |
| Maven build, Spring context start, Hibernate `ddl-auto: validate`, **Flyway V39 on a real database** | **NOT VERIFIED** |
| `REQUIRES_NEW` behaviour (audit surviving a rolled-back payment transaction), the unique-constraint race path | **NOT VERIFIED** (needs a real DB) |
| Real Paystack calls | **NOT VERIFIED** (sandbox mock only; the real provider is never reached in tests) |
| Existing Market, Stock, Food, Services, Stokvel, Savings and **PULA Ride** test suites | **NOT VERIFIED** (need Mockito/Maven). Regression argument only, see below |
| `vite build`, Expo build, a running UI | **NOT VERIFIED** |

## Audit: payment-initiation paths found

| Path | Class | Provider | Risk integration |
|---|---|---|---|
| Wholesale stock + Phase 6 bulk shares | `StockPaymentService.start` | Paystack | gate + backstop |
| Consumer retail/food checkout | `ConsumerPaystackCheckoutService` | Paystack | gate + backstop |
| Service bookings | `ServiceBookingService` (via `OrchestratorServicePaymentGateway`) | Paystack | gate + backstop |
| Legacy retail/food order | `OrderService` | legacy `PaymentProvider` | gate |
| User-initiated circle/stokvel contribution | `ContributionService.contribute` | legacy `PaymentProvider` | gate |
| PULA Ride (`payTaxi`, `payTaxiFromUssd`, `fulfillPaymentRequest`) | `MoMoRideService` | legacy `PaymentProvider` | explicit hook, **OFF by default** |
| Scheduled recurring dues | `ContributionService.charge` | legacy `PaymentProvider` | **not gated** (see limitations) |
| Simulated-bank transfer / demo purchase | does not exist in the repository | — | nothing to integrate |

All three Paystack paths funnel through `PaymentOrchestrator.startMarketCheckout`; that single choke point now also enforces the gate.

**Weakness found and replaced:** the old stock-payment "risk" check let the client pass `riskAcknowledged=true` to proceed. That flag is now only the user's own advisory acknowledgement of supplier/order warnings. It has **no influence** on the new server-side decision.

## Design

`com.momocircle.risk`, plus one insertion per path:

1. Ordinary authentication, ownership, state and amount validation run first (unchanged).
2. `PaymentGate.authorise(user, GateRequest)` creates or reuses an **idempotent `TransactionIntent`** keyed by (user, type, reference, **fingerprint**). The fingerprint is a SHA-256 over type, user, amount (2 dp), currency, recipient and simulated/real context.
3. `RiskEngine` evaluates (pure, deterministic). The verdict is stored as an immutable `RiskAssessment` with score, level, decision, rule ids, unavailable signals, rule-set version, mode and expiry. Same intent + still valid = same verdict (replay-safe).
4. The gate enforces the verdict; non-allowed results throw a structured error `CODE|assessmentId|safe message`.
5. `gate.bind(...)` re-verifies amount, currency and simulated/real context against the intent and ties it to the provider reference.
6. The provider is called. `PaymentOrchestrator` additionally calls `requireBound(...)` and refuses anything not assessed, not bound to that exact reference/amount/currency/mode, held, blocked or expired.
7. `gate.recordOutcome(...)` records INITIATED / FAILED. Existing webhook verification and reconciliation are untouched.

For the legacy paths the gate runs **before** `idem.reserve(...)`, because `reserve` commits in its own transaction; gating afterwards would leave a stuck PENDING payment behind a hold.

### Rules (all configurable per type; defaults are PLACEHOLDERS, not calibrated on real loss data)

Hard rules (decided in this order; the first that fires wins, and a score can never downgrade it):

| Rule | Decision |
|---|---|
| `H_OWNERSHIP_MISMATCH`, `H_ACCOUNT_INACTIVE`, `H_RECIPIENT_INACTIVE`, `H_INSUFFICIENT_FUNDS`, `H_AMOUNT_OVER_HARD_LIMIT` | BLOCK |
| `H_REPEATED_FAILURES` (>= threshold failures in 24h) | REVIEW |

Soft signals add points (score 0..100, capped): very high / high amount (40/25), very high / high attempt frequency (45/30), recent failures (20), pattern deviation (25, needs >= 5 prior initiations), unverified recipient (15, never fires today), recent HIGH ScamShield result by this user (20), first payment to a recipient with a non-small amount (10).

Score bands: `< 40` ALLOW, `40-69` STEP_UP, `>= 70` REVIEW. A score alone **never BLOCKs** unless an operator sets `score-can-block` for that type. Missing signals are neutral and listed as unavailable; none is invented.

Default hard limits (ZAR-scale placeholders): stock 500 000, retail/food/services 50 000, contributions 100 000, taxi 2 000.

### Decisions and what they enforce

* **ALLOW** proceeds to the existing flow (which still applies all of its own validation, provider authentication and webhook verification).
* **STEP_UP**: no OTP/PIN service exists in PULA, so step-up is the authenticated owner **re-confirming this exact amount and currency**; the server verifies both, records it against the assessment and audits it. A client-supplied "confirmed" boolean is never accepted. It does not carry over to a different amount.
* **REVIEW**: held; an authenticated admin approves or rejects; no self-review; each action is audited (`RiskEvent` + `AuditLogService`).
* **BLOCK**: rejected; cannot be overridden by step-up or review.
* **Outage**: if the risk layer fails, the payment is **held** (`RISK_UNAVAILABLE`, HTTP 503, "Nothing was charged") for every protected path. There is no fail-open configuration.
* **Expired/missing assessment**: fails safe (`RISK_ASSESSMENT_EXPIRED` / `RISK_NOT_ASSESSED`). Default validity 15 minutes.
* **Shadow mode** (`app.risk.mode=SHADOW`, operator-only): assess and audit but never hold or block, for a controlled rollout. Unrecognised values enforce.

## Simulated vs real

The simulated/real context is part of the fingerprint, stored on the intent and assessment, and verified at bind and again by the Paystack backstop. A simulated assessment cannot authorise a real provider call (`RISK_CONTEXT_MISMATCH`), and simulated and real history are kept apart. Sandbox behaviour is unchanged. There is no simulated-bank adapter in this repository.

## PULA Ride

Explicit hooks in `doPayTaxi` and `fulfillPaymentRequest`, behind `app.risk.taxi-enabled` (default **false**). While off, the gate returns a no-op ticket and persists nothing, so endpoints, driver-first semantics, state transitions and the provider call are unchanged. Taxi fares are not merged into marketplace settlement. If enabled, STEP_UP/REVIEW cannot be presented to USSD users, so enable it only after that UX exists.

## Configuration (operator-only; no request, header or role can change it)

`app.risk.mode`, `rule-set-version`, `validity-minutes`, `taxi-enabled`, and per-type overrides `app.risk.types.<type>.{hard-limit, review-amount, step-up-amount, max-attempts-per-hour, failure-lock-threshold, step-up-score, review-score, block-score, score-can-block}`. Only PULA Ride can be switched off; every other path is always protected.

## Database

`V39__transaction_risk.sql`: `transaction_intents`, `risk_assessments`, `risk_events`. No payment table is created or altered. Nothing stores device fingerprints, phone numbers, emails or card data.

## API

| Method + path | Who |
|---|---|
| `GET /api/risk/assessments/{id}` | the paying user only (no score, rule ids or thresholds) |
| `POST /api/risk/assessments/{id}/confirm-step-up` | the paying user only |
| `GET /api/admin/risk/assessments` (filters: type, decision, reviewStatus, userId), `GET .../{id}` | admin |
| `POST /api/admin/risk/assessments/{id}/review` | admin (not their own transaction) |

## Frontend

Web `StockOrdersPage` and mobile `stock-orders` handle the outcomes: step-up shows a confirm dialog with the exact amount; review/blocked/try-again show the server's safe, non-accusatory message; the pay button stays disabled while a request is pending. Shared parsers: `lib/riskOutcome.ts` (web), `services/riskOutcome.ts` (mobile). **Not yet wired:** the consumer cart/food checkout screens, service-booking payment and contribution screens show the server's error text generically (they already surface API errors) but have no dedicated step-up dialog. No admin web page was built; the admin endpoints exist.

## Regression stance for existing paths

All edits are additive. Each modified service gets a field-injected, optional `PaymentGate`: it is always injected in a running application and `null` only in hand-constructed unit tests, where behaviour is unchanged. Webhooks, refresh and reconciliation code were not modified. `startMarketCheckout` has exactly three callers, all gated. The existing suites were not run (see the table).

## Limitations

* **STEP_UP is a re-confirmation, not strong authentication.** It protects against accidental, automated and stale submissions, not against someone who controls the account. A real OTP/PIN step-up is a future extension.
* **No device, session, geo or external fraud data exists in PULA**, so none is used. Frequency, failure and history signals come from this layer's own records, so new users have none and those signals report "unavailable". Merchant verification is not collected (registration is self-declared), so that signal never fires. Funds sufficiency is unknown for provider-hosted checkout; the rule exists but no caller supplies it.
* **Thresholds are placeholders.** They must be calibrated on real data before launch.
* **Review approval expires with the assessment** (default 15 minutes). If the user does not retry in time, a new assessment is created and may be held again. Consider a longer review validity and a user notification on approval.
* **Recurring contribution dues** (`ContributionService.charge`) are scheduled and non-interactive and are not gated.
* Legacy `PaymentProvider` paths rely on the call-site gate (the orchestrator backstop covers Paystack only).
* Gate persistence relies on `REQUIRES_NEW` so audit survives a rollback; this is NOT VERIFIED without a database.
* The admin review queue has no push notification and no admin web page.

## Future extension points

OTP/PIN step-up provider behind the same `STEP_UP` contract; a `ScamSignalPort` for recipient-level ScamShield data if it is ever collected; ML/AI scoring as an additional **advisory** signal (it could raise a score but can never approve or move money); admin UI; per-business policy overrides; backstop for the legacy provider.
