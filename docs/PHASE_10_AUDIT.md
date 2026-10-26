# Phase 10 — Retire Paystack, Stitch and MTN MoMo: audit and status

**Status: MVP SCOPE DECIDED. The app deliberately runs on the simulated payment system only.** Step 1 (remove real-network code, fail closed) and Step 2-lite (demo labelling, client build fixes) are done. The large re-homing/rename refactor is intentionally deferred (cosmetic for the MVP). Java gates are still NOT RUN, so provider retirement is not verified end to end.

### MVP decision (owner)
Simulated payments only; no real provider will be added. Consequences: no new statuses or migrations; `SUCCESSFUL` keeps its legacy meaning in data, and the UI now says demo wherever money appears paid, refunded or paid out. Deferred: renaming `PaystackGateway`/`/orders/checkout/paystack`/`consumerCheckout=paystack` (internal names only).

### Step 2-lite — what changed (clients)
- New `DemoPaymentNotice` (web `components/ui`, mobile `components`) with per-context wording that states what is *not* happening.
- Placed on: web cart, food checkout, stock cart, taxi pay, driver payouts, seller settlements, admin marketplace finance; mobile cart, food checkout, stock cart, taxi pay, driver payouts, seller settlements, circle contribute.
- Removed user-visible provider names ("Paystack is not configured on this server", "payment provider") from web/mobile payment panels and stock-order screens.
- Fixed two pre-existing build failures: web `NotificationsPage` (added `EXPENSE_REQUEST`, `EXPENSE_UPDATE`), mobile `StyleSheet.absoluteFillObject` -> `absoluteFill`.
- `refund` notice kind exists but is not placed: no dedicated client refund screen was identified; place it when one is confirmed.
- Not changed: backend response labelling; wallet/split-bill screens (already labelled).

## 1. Premises verified against the repository

| Claim in the brief | Verified result |
|---|---|
| Paystack could move real money with `PAYSTACK_MODE=paystack` + key | **True** before this phase (`HttpPaystackGateway`, `PaystackRefundClient`, signed webhook). Removed in Step 1. |
| MTN MoMo is a stub whose methods throw | **True** (`MtnMomoPaymentProvider`, `app.payments.provider=mtn`). It was unreferenced; removed. |
| Stitch not a functioning integration | **True.** Only comments and an `app.payouts.provider` doc string. No client, callback or credential. |
| Defaults | Shipped defaults were already fully simulated (`mock`, `sandbox`, `MARKET_CHECKOUT=legacy`). |

`momo` appears ~400 times only because the Java package is `com.momocircle`; it is not provider usage.

## 2. Key finding: "Paystack" is also the name of the simulated checkout

`PaystackGateway` is the interface behind the **default mock flows**: stock orders, bulk purchases, service bookings, food checkout, hosted-checkout sessions and market refunds. `MockPaystackGateway` is a simulator, but the *types, routes, config key and client switch* are Paystack-named. Deleting the real client was safe; **renaming/re-homing the simulated flows is the real remaining work**, and must be done flow by flow with tests.

Second finding: `SandboxPaymentProvider` returns `SUCCESSFUL`/`REFUNDED` and `SandboxPayoutProvider` returns `SUCCESSFUL`. Those statuses are indistinguishable from real settlement in the UI and reports. This conflicts with brief §2 ("never fake success") and needs a product decision (see Open decisions).

## 3. Dependency and retirement matrix

| Flow | Runtime path today | Needs | Step 1 | Remaining |
|---|---|---|---|---|
| Savings / recurring contributions, Save-to-Buy, Stokvel, assistant | `ContributionService` → legacy `PaymentProvider` (sandbox) | Simulated ledger transfer | untouched | Replace; label simulated |
| Retail & food checkout (legacy default) | `OrderService` → `PaymentProvider` | Purchase allocation + explicit external obligation | untouched | Replace |
| Hosted checkout (`MARKET_CHECKOUT=paystack`) | `ConsumerPaystackCheckoutService` → `PaystackGateway` (mock) | Simulated allocation | untouched | Rename/re-home; remove client branch |
| Stock orders, bulk purchases | `StockPaymentService` → gateway (mock) | Allocation; **supplier not paid** | untouched | Re-home; keep supplier obligation PENDING |
| Service bookings | `OrchestratorServicePaymentGateway` | Allocation | untouched | Re-home |
| Food checkout / refunds | `FoodCheckoutService`, `OrderRefundService` | Auditable reversal, not "money sent" | untouched | Replace refund wording/outcome |
| PULA Ride taxi payments | `MoMoRideService` → `PaymentProvider` | Demo-only, never settles driver/association | untouched | Parity tests, then migrate |
| Reconciliation | `PaymentReconciliationService` → `getStatus`/gateway | Idempotent, history-safe | untouched | Re-verify after re-homing |
| Merchant/driver payouts | `PayoutOrchestrator` → `SandboxPayoutProvider` | Status must not read as paid | untouched | Re-label PENDING/DEMO |
| Real Paystack HTTP + refunds | `HttpPaystackGateway`, `PaystackRefundClient` | none | **REMOVED** | — |
| Paystack webhook `POST /api/payments/paystack/webhook` | `PaystackWebhookController`, `SecurityConfig` rule | none (404 in mock mode, nothing depended on it) | **REMOVED** | — |
| MTN stub | `MtnMomoPaymentProvider` | none | **REMOVED** | — |
| Stitch | comments only | none | comments updated | — |

## 4. Step 1 — what changed

Removed (all had closed reference sets, verified by grep): `payment/MtnMomoPaymentProvider`, `payment/HttpPaystackGateway`, `payment/PaystackRefundClient`, `controller/PaystackWebhookController`, plus tests `PaystackRefundClientTest`, `PaystackWebhookControllerTest`, and the `SecurityConfig` permit rule for the webhook.

Added: `SimulatedProviderAudit.findRetiredRealProviders` + `ProductionSafetyCheck` now **throws in every profile** if `app.payments.provider`, `app.payments.paystack.mode` or `app.payouts.provider` selects anything other than `sandbox`/`mock`. Three new JUnit cases.

Config: removed `app.payments.paystack.secret-key`; comments corrected. `callback-url` kept (still read by three services).

**Retained on purpose:** `PaymentWebhookEvent` entity + repository (historical `payment_webhook_events` rows), all Flyway migrations, `PaystackGateway`/`MockPaystackGateway` (live simulated flows), `provider="paystack"` interpretation in `OrderRefundService`/`StockOrder`/`PaymentRefund`. No rows or migrations were modified.

**Coverage lost with the webhook tests:** webhook replay/signature tests are gone with the route. Idempotency of `confirmByReference` itself needs a dedicated test in Step 2.

## 5. Test evidence

| Gate | Command | Result |
|---|---|---|
| Backend compile / `mvn test` | `mvn -q test` | **NOT RUN** — no Maven; Maven Central unreachable from sandbox |
| JUnit `SimulatedProviderAuditTest` | — | **NOT RUN** (assertions mirrored in a standalone harness compiled with the JDK compiler module: PASS 5/5; this is not the JUnit run) |
| Java syntax scan of touched files | `javac -proc:none` (symbol errors ignored) | PASS (no syntax errors; isolated compile cannot resolve Spring) |
| PostgreSQL clean-install / upgrade | — | **NOT RUN** (no PostgreSQL/Docker) |
| Marketplace/food/stock/bulk/service/refund/reconciliation/payout regression | — | **NOT RUN** |
| PULA Ride taxi/booking/payment-request/driver/USSD tests | — | **NOT RUN** |
| Web | `cd frontend && npm ci && npm run build` | **PASS** (after fixing the pre-existing `NotificationsPage.tsx` TS2739; chunk-size warning only). No web test suite exists. |
| Mobile | `cd mobile && npm ci && npx tsc --noEmit` | **PASS** (after fixing the pre-existing `absoluteFillObject` error). No mobile test suite exists; not run on a device/emulator. |
| Idempotency / duplicate callback | — | **NOT RUN** |

## 6. Static search (active code, after Step 1)

`paystack`: 23 main-Java files (117 lines), 10 test files, 4 web files, 6 mobile files, 2 migrations (historical), `application.yml` (5). `mtn`: 6 main files (comments/market limits), 1 mobile doc. `stitch`: comments only (2 main files, 1 test string).
Real-network Paystack/MTN/Stitch clients: **0**. Remaining Paystack-named code is the simulator and historical-record handling; it is **not** yet renamed, so the retirement is not complete.

## 7. Remaining real-money paths

None can be activated: no real client code remains, and any retired setting fails startup. (Verified by static search; not by a running application.)

## 8. Decisions (resolved for MVP: simulated only; see top)

Original questions, kept for the record:

1. **Status semantics.** Add explicit simulated outcomes (e.g. `SIMULATED`/`DEMO`) instead of `SUCCESSFUL`, or keep `SUCCESSFUL` and add a `simulated` flag/label everywhere? Either needs a forward-only migration and touches reports.
2. **Hosted checkout.** Keep a simulated hosted-checkout flow under a neutral name, or collapse into direct allocation?
3. **Obligations.** Confirm supplier orders, merchant payouts and taxi fares stay `PENDING`/external after a simulated allocation.

## 9. Confirmation

No real payment or payout was simulated as successful in this phase, and no real-money code path was added. Existing simulated `SUCCESSFUL` statuses were not changed and still require the Step 2 treatment above.
