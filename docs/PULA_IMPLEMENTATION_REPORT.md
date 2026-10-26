# PULA implementation report

**Read first:** the backend could NOT be compiled or tested where these changes were made (Maven Central is blocked).
Web (`tsc` + `vite build`) and mobile (`tsc --noEmit`) type-check/build cleanly. Backend Java was only checked with `javac`
against missing dependencies: no syntax errors and no unresolved project-internal references, but nothing was run.
**Run `./mvnw test` before trusting any backend change.** New tests were written but never executed.

## Migrations (V1-V19 untouched)
V20 stock checkout-group key · V21 payment purpose/url + webhook dedupe · V22 stock deliveries, pool members, driver payouts · V23 pool-member consent status · V24 payout attempt counter.

## Backend
- **Stock-order integrity:** buyer-scoped idempotency (+ per-supplier unique index), atomic stock reservation, release on cancel/expiry (30 min), suppliers cannot set payment states, dispatch on READY, re-dispatch of cancelled deliveries.
- **Payments:** `PaymentOrchestrator` (purpose routing), Paystack real client + SANDBOX mock (default), signed webhook (HMAC-SHA512, dedupe, re-verify), amount/currency match, review flag, reconciliation sweeps for stock and consumer orders; legacy reconciliation skips Paystack payments.
- **Consumer Market on Paystack:** `POST /api/orders/checkout/paystack`, payment-session + refresh endpoints; OFF by default (`MARKET_CHECKOUT=paystack` to enable); clients ask `/api/config/market-payments`.
- **Payment safety prompts:** deterministic risk warnings (large amount, >3x usual, new supplier, first order with a supplier) require explicit acknowledgement before a stock payment starts.
- **Delivery & pools:** stock orders use the shared delivery engine; WHOLESALER_DELIVERY reserved for the supplier pool, PULA_RIDE_DELIVERY open to independent drivers; pool invites require driver acceptance; derived driver capabilities.
- **Payouts:** `PayoutOrchestrator` + labelled SANDBOX provider (no real money); one payout per delivery; driver notification; failed payouts retried automatically (max 3 attempts, same idempotency key).
- **Funding readiness** (checklist only, no score/approval), **stock planner** (proposal only), **catalogue CSV import** (500 rows, per-row validation), **cash-flow trend** (weekly/monthly) and **delivery-cost double-count fix**, **business journey** endpoint.
- **AI:** read-only intents PLAN_STOCK, VIEW_CASH_FLOW, VIEW_FUNDING_READINESS answered server-side; business figures not sent to the model.
- **SMS:** order/payment/delivery notifications also go out through the existing SmsProvider (sandbox logs only) - best effort, never blocks the action.

## Web / Mobile
Web: 3-step Business Setup wizard (type, details, capabilities + self-declared registration readiness), stock orders (pay, risk prompt, sandbox complete, status check, cancel, redispatch), consumer Paystack checkout + payment panel, funding readiness (+printable summary), journey card, cash-flow trend, driver pool page, pool invites, payouts, planner, CSV import; pre-existing build breakers fixed.
Mobile: 3-step business setup wizard for new businesses (existing ones edit on one screen), wholesale catalogue screen with paste-CSV import; stock orders (pay, risk prompt, sandbox, status, cancel, redispatch), consumer Paystack checkout + payment panel, funding readiness, journey card, trend, driver pool screen, pool invites, payouts, planner.

## Also added
Admin read-only lists of payments held for review and failed payouts (`GET /api/admin/payments/review`, `/api/admin/payouts/failed`); duplicate simultaneous stock checkouts now replay instead of erroring.

## Integrations
- Paystack: sandbox mock by default; real client written, **never run against Paystack**.
- Stitch: **NOT implemented** (sandbox payout provider only; needs credentials).
- Taxi provider / Community Savings provider: code untouched, not regression-tested here.
- CIPC registration: not integrated. USSD: not implemented (existing USSD untouched).

## Not done
Paystack as taxi provider (deliberately skipped); ScamShield text-scan on payment requests (deterministic risk prompts added instead); real SMS gateway; USSD for drivers.
