# Phase 5 - Unified marketplace settlement

## Read this first
Phase 5's own precondition ("earlier phases operational and relevant tests actually run") is **not met in a real build**: nothing in Phases 2B-5 has run under Maven, Spring, Flyway or a database, and no payment or payout provider has been exercised. I proceeded because the work was explicitly requested. **This is not production-ready** and must not be treated as such until the items below are verified and the external providers are configured and tested.

| Check | Result |
|---|---|
| `mvn test`, Spring wiring, Flyway V34-V37, Hibernate `validate`, **all JPQL** (new settlement queries; the existing food queries I edited) | **NOT VERIFIED** |
| Existing suites (Food settlement/refund, Order, Stock, Review, Phase 2A savings) after my edits | **NOT VERIFIED** (need Mockito; see "Behaviour changed in existing code") |
| Real Paystack calls/webhooks, real payout provider | **NOT VERIFIED / NOT CONFIGURED** |
| New + edited Java compiled against the real source tree (Spring/JPA annotations stubbed) | 0 errors |
| Executable tests (JUnit shim + `Proxy` in-memory repositories + a fake `PayoutProvider`, real engine/policy/payout/reconciliation classes), Phases 2B-5 | **156 run, 156 passed, 0 failed, 0 skipped** (Phase 5: 7 calculator + 21 engine + 2 guardrail) |
| Mutation check of 8 settlement safeguards (idempotent open, paid-only open, post-payout adjustment lock, completion gate, cancel-after-paid, pending-payout-not-PAID, seller IDOR, amount-mismatch detection) | each caught by a test; sources restored |
| Web `tsc` (settlement types/API) | exit 0. Web/mobile TSX: module-shim check only, not built or run |
Executed tests ran under a throw-away JUnit shim, not Surefire. `FoodSettlementService`'s Food-specific lifecycle (accept/reject, no-driver merchant protection) was **not** executed.

## Audit: what already existed (the engine was generalised, not rebuilt)
- **Food** already had a mature settlement system: `RestaurantSettlement` (status machine NOT_ELIGIBLE/ELIGIBLE/PROCESSING/PAID/FAILED/ON_HOLD/CANCELLED, SALE vs MERCHANT_PROTECTION, idempotency key, payout attempts), `FoodSettlementService` (create, recalculate, atomic payout claim, hold/release), `SettlementCalculator`, config `app.food.settlement.*`, `PayoutOrchestrator` + payout provider, a scheduler.
- **Refunds:** `PaymentRefund` + `OrderRefundService` already track provider status (`PENDING, PROCESSING, REFUNDED, FAILED, NEEDS_ATTENTION`) with idempotency, partial and cumulative refunds. **They are Order-only**: there is no refund path for stock orders or service bookings (stock has `REFUND_PENDING/REFUNDED` statuses that nothing drives).
- **Webhook safety (audited, not changed):** HMAC-SHA512 signature verified with a constant-time compare; body-hash dedupe; payment looked up by provider reference; the provider is re-queried and amount (minor units) and currency are checked (mismatch -> REVIEW, payment stays PROCESSING); payment state is updated before lifecycle hooks; `confirmByReference` is idempotent. A duplicate webhook therefore cannot trigger a second settlement (and `open` is itself idempotent: tested).
- **Driver payouts:** `DriverPayout`/`PayoutService` already serve deliveries; nothing per-domain exists and none was added.
- **Cash Flow:** business-scoped, already reads Food settlements (commission/compensation). No ledger exists and none was added.

## Architecture
```
Retail / Food / Stock / Services  ->  SettlementPolicy (one per domain)  ->  MarketplaceSettlementService (one engine)
                                                                              |  one calculator (MarketplaceSettlementCalculator)
                                                                              |  one payout machinery (SettlementPayoutService -> existing PayoutOrchestrator)
                                                                              |  append-only adjustments, deterministic idempotency
                                                                              v  settlements table (generalised), reconciliation, reports
```
- **One table:** `restaurant_settlements` (name is historical) is generalised by V37: `order_id` nullable, `source_type`, `source_id`, `seller_kind`, `gross_amount`, `payment_fee_amount`, `adjustment_amount`, unique `(source_type, source_id)`. Existing food rows are backfilled (`source_id = order_id`, `gross_amount = food_subtotal`) and keep their legacy key `settlement:{orderId}`; new rows use `settlement:{SOURCE_TYPE}:{sourceId}`.
- **Policies** (`com.momocircle.settlement`): `RetailSettlementPolicy`, `FoodSettlementPolicy`, `StockSettlementPolicy`, `ServiceSettlementPolicy` decide gross (delivery fees excluded), the "earned" condition, rates, dispute window and known refunds. Domains are deliberately not forced into identical rules:
  - Retail: earned when the order is COMPLETED. Stock: supplier earns the goods subtotal when DELIVERED. Services: provider earns the accepted-quote total when the job is COMPLETED **and** the payment is confirmed (payment is due after completion). Food: COMPLETED, using `app.food.settlement.*`.
- **Settlement never happens just because payment succeeded:** a row opens (NOT_ELIGIBLE) at confirmed payment, becomes ELIGIBLE only on the domain's completion condition (plus its dispute window), and is PAID only on provider-confirmed payout success.
- **Calculator** (`BigDecimal`, 2 dp, no floating point): `eligible = max(0, gross - refunded)`; commission; fixed fee; payment fee (rate + fixed); signed adjustments; net floored at 0. Fees are capped so they cannot exceed revenue. With zero payment fee and adjustments it equals the old food calculator, **proved numerically over 1,512 input combinations**. Food's `recalculate` now uses the shared formula.
- **Commission/fees are configuration, never hard-coded:** `app.marketplace.settlement.{retail|stock|service}.*` in `application.yml`, **all placeholders set to 0**. PULA must set the commercial terms; until then no commission is taken.
- **Payout machinery** (`SettlementPayoutService`) was extracted from `FoodSettlementService` unchanged and is used by every source: atomic claim, stable provider key `payout:settlement:{id}`, FAILED retries up to a ceiling, an unknown outcome (timeout) stays PROCESSING and is re-driven idempotently, PENDING from the provider is never PAID, zero-net closes without a provider call. There is no manual "mark paid". `FoodSettlementService` keeps its constructor and public methods and delegates to it.

## Merchant protection, refunds, driver payouts (kept separate, not hidden in one another)
Settlement views show separately: customer **refund** (`refunded`), restaurant **compensation** (merchant-protection settlement net), **driver payout** (existing `DriverPayout` totals in the admin summary), and **PULA operational loss** (`pulaFulfilmentLoss`). The Food merchant-protection logic itself is unchanged and not re-executed here.
Refund handling: retail and food refunds still run through the existing `OrderRefundService`; retail settlements now net recorded refunds (`onRefundChanged`), cancel an unpaid settlement on full refund, and **refuse an automatic refund after the seller was paid** (same rule Food already had). **I did not change provider refund logic** and did not find code that marks an order refunded before the provider confirms (statuses are provider-driven); that was read, not run.

## Adjustments and immutability
`settlement_adjustments` is append-only (`updatable=false` columns): signed CREDIT/DEBIT, reason, admin, idempotency key (unique per settlement). Only before anything is sent to the provider (NOT_ELIGIBLE/ELIGIBLE/ON_HOLD/FAILED); PROCESSING/PAID/CANCELLED refuse. Same key + same payload replays; same key + different payload is a 409. A settlement row is locked (`PESSIMISTIC_WRITE`) so an adjustment cannot interleave with a payout claim. History is never edited; corrections are new rows.

## Reconciliation
`MarketplaceReconciliationService` (read-only; changes nothing) checks recent paid sales and recent settlements per domain and reports `MATCHED`, `MISMATCH`, `MISSING_PAYMENT` (settlement without a confirmed payment), `MISSING_SETTLEMENT` (paid and complete, no settlement), `MISSING_PAYOUT` (PAID with no payout reference), `AMOUNT_MISMATCH` (payment vs sale total, or settlement gross vs sale gross), `REFERENCE_MISMATCH` (currency, or settlement pointing at a different payment). Rows carry sale total, payment amount/reference, settlement gross, payout reference and ids. Bounded (default 200 per source), computed on demand, not scheduled or persisted.

## Admin / seller / AI
- Admin (`/api/admin/marketplace`, ADMIN-only on every method, asserted by a source-scan test): summary (gross volume, PULA commission, fees, refunds, seller net, PULA loss, by source, by status, driver payouts, failed/held counts), settlements (filter by status/source, failed), detail, hold, release, adjustments, reconciliation, explain. **There is no endpoint to mark paid, change an amount directly, issue a refund or override a reconciliation**, and no seller endpoint can modify anything.
- Seller (`/api/marketplace/settlements/mine...`): own business only; another seller's id answers 404 (tested).
- AI: `SettlementInsightService` is read-only (explain a settlement's calculation, explain a reconciliation row); no assistant action type can settle, pay out, refund, reconcile or adjust (guard test).

## Cash Flow
No ledger added. Cash Flow reporting is the settlement data itself: the admin summary and a seller summary read settlements (and driver payouts) and hold no money. Existing Food Cash Flow numbers are preserved by adding an explicit `FOOD_ORDER` filter to the Food queries I touched. **`BusinessCashFlowService` was not extended** for retail/stock/service settlements; sellers get the new settlement summary instead.

## Behaviour changed in existing code (verify these first when Maven is available)
- `FoodSettlementService`: payout/hold/release/list now delegate to `SettlementPayoutService`; `list()` is food-only and uses different repository methods (a Mockito test that stubs the old ones will need updating); `recalculate` uses the shared calculator; new rows set the source fields.
- `RestaurantSettlementRepository`: food admin and cash-flow queries now filter `FOOD_ORDER`; new queries added. `AdminFoodFinanceController` uses `countFoodByStatus`.
- `RestaurantSettlement.order` is nullable. `OrderService`, `OrderRefundService`, `StockPaymentService`, `StockOrderService`, `ServiceBookingService` gained optional (field-injected) settlement hooks. **Hooks call "safe" variants** that log and swallow failures so a settlement problem cannot roll back a confirmed payment (`reconciliation` flags `MISSING_SETTLEMENT`). Spring marks a transaction rollback-only if a repository call throws inside it even when caught, so a database-level failure could still affect that transaction; this is unverified.
- Two payout schedulers now exist (`FoodOrderMaintenanceTask` and the new `MarketplaceSettlementTask`). They are safe together (atomic claim, stable provider key) but redundant for Food; remove Food's pass after verification.

## Migration
`V37__marketplace_settlement.sql` (V33-V36 untouched): alters `restaurant_settlements`, creates `settlement_adjustments`. Not run.

## Not done / limitations
- **No refund initiation for stock orders or service bookings** (none existed to reuse). Before payout, corrections use adjustments; after payout a clawback is a manual decision (`onCancelled` returns false and reconciliation flags it); no clawback engine.
- Driver earnings are shown as aggregate `DriverPayout` totals; there is no per-settlement link between a settlement and its driver payout.
- Merchant protection applies to Food only.
- Retail `Order.getSubtotal()` is assumed to be goods-only (delivery excluded), as Food already assumes.
- Reconciliation is on demand and bounded; no scheduled run, no stored history, no alerting.
- No automated clawback or negative balance handling: a debit adjustment can only reduce a pending payout to zero.
- Settlement of materials: a service booking's materials are the supplier's separate stock-order settlement, not part of the provider's gross.
- A paid service/stock/retail sale cancelled afterwards is flagged `MISMATCH`, not auto-reversed.
- Rates, fees and dispute windows are placeholders (0). PULA Ride's minibus taxi payments are untouched and separate from this settlement.
- JPQL, V37 and entity mapping unverified against a database; payout/refund/payment providers unverified. **Not production-ready.**
