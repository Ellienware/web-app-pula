# Unified wallet checkout — implementation, status and evidence

**Everything here is SIMULATED demo value. No real money, payout, withdrawal or SMS call exists in this flow, and no new payment provider was introduced.**

> **Not production-verified.** The Spring/JPA code has been written and syntax-scanned but **never compiled or run against a database**: this environment has no Maven, no PostgreSQL, and cannot reach Maven Central. Run the Maven suite (see §6) before deploying.

## 1. Source note

The archives named in the brief (`...phase9c-plus-implementation.zip`) were **not uploaded**. Only the `phase9c-split-bills` archives were available, so this work is built on those plus the earlier Phase 10 changes (`docs/PHASE_10_AUDIT.md`). Anything that exists only in the "plus" archives is not reflected here.

## 2. Architecture

```
Authenticated buyer (server-side principal)
 → WalletCheckoutController
 → step 1  POST /api/orders/checkout/wallet
      ConsumerPaystackCheckoutService.startWallet   (server prices the cart, risk gate, stock reserved,
                                                     order PAYMENT_PROCESSING, Payment provider="wallet"; NO charge)
 → step 2  POST /api/orders/{id}/pay/wallet           (buyer confirms the amount they saw)
      WalletCheckoutService.pay
        → LedgerOperationsService.transfer(buyer wallet → seller SIMULATED business wallet, key "mkt:<paymentId>")
        → StockPaymentService.confirmByReference      (shared path; verifies the ledger tx, THEN fulfils)
 → Payment + Order records, wallet transaction history
```

* **One balance.** No second wallet or balance exists; value moves only inside the shared ledger.
* **Paid only after verification.** `WalletPaymentVerification` accepts a payment only if the linked ledger transaction is POSTED, an internal transfer started by the buyer, in the order's currency, with exactly one debit and one credit of exactly the stored amount. Anything odd is REVIEW, never paid.
* **Amount never trusted.** The order total is computed by the server in step 1. Step 2's `expectedAmount` is only compared with the stored total (mismatch → `AMOUNT_CHANGED`), never used to charge.
* **Idempotency.** Ledger key `mkt:<paymentId>` is deterministic and scoped to the buyer by the ledger. Retries, double taps and concurrent requests replay one debit. Client-side double-tap is blocked as well.
* **Authorization.** Buyer comes from `currentUser.require()`. Someone else's order is reported as `ORDER_NOT_FOUND`. The ledger enforces wallet ownership.
* **Recovery (saga).** Ledger and business records commit separately. If the debit commits but the process dies before the payment is linked/confirmed, verification **adopts the ledger transaction by its deterministic key**, so the sweep completes the order instead of expiring a payment the buyer was already debited for. A refused debit (e.g. insufficient balance) posts nothing; the order stays awaiting payment and the existing expiry sweep cancels it and releases stock.
* **Concurrency.** `confirmByReference` now loads the payment under a row lock (`findByProviderReferenceForUpdate`) so a pay call, the sweep and a refresh cannot all fulfil one order.
* **Refunds.** A wallet-paid order is refunded by a **compensating ledger reversal** (`OrderRefundService.advanceWallet`), full amount only, idempotent, joined to the refund's transaction. If the seller's simulated wallet no longer holds the funds the ledger refuses (never overdraws) and the refund is flagged `NEEDS_ATTENTION`. It never edits a posted entry and never reports a refund as done unless the reversal posted.
* **Reconciliation.** `GET /api/admin/wallet-payments/reconcile` (admin) reports payments whose payment record, ledger transaction and order disagree (`WalletPaymentAudit`). It reports; it never repairs.
* **Audit.** `WALLET_CHECKOUT_DEBITED` / `WALLET_CHECKOUT_REFUSED` events (plus the ledger's own audit). No secrets or PINs are logged.

## 3. Hazards found in existing code and fixed

1. `PaymentReconciliationService` polled any non-Paystack `PROCESSING` payment against the legacy sandbox provider, which would have reported a wallet payment as paid with no debit. Wallet payments are now skipped there.
2. `OrderRefundService` treated every `MARKET_PAYMENT` as a gateway refund, which would have marked a wallet order `REFUNDED` while returning nothing. Wallet payments now have their own branch, and `isPaystack` no longer matches them.
3. `/api/payments/sandbox/{ref}/complete` now refuses wallet payments.
4. `confirmByReference` had no row lock and `BaseEntity` has no `@Version`; see Concurrency above.
5. An existing test stubbed the unlocked lookup; it was updated (`StockPaymentServiceTest`).

## 4. Integration matrix (actual status)

| Flow | Status | Notes |
|---|---|---|
| PULA Market (retail) | **Wallet checkout implemented** (backend, web, mobile). NOT RUN against a DB. | Seller business is credited in its **simulated** business wallet. |
| PULA Food | **Implemented via the same path**; restaurant branch/menu/delivery validation reuses the existing quoting. Restaurant reject/timeout refunds go through the wallet refund branch. | Not separately traced end-to-end. |
| Wholesale stock orders | **NOT integrated** | Payer would be a business wallet: needs business-wallet permission checks and per-business allocation rules. `verify` already understands wallet payments; no start endpoint exists. |
| Bulk / group purchases | **NOT integrated** | Same, plus group target/contribution totals must never be treated as spendable funds. |
| PULA Services bookings | **NOT integrated** | Same shared confirm path would apply; no start endpoint or UI. |
| PULA Ride taxi payments | **NOT integrated, unchanged** | Still `MoMoRideService` + legacy sandbox provider (labelled simulated). Payment-request and USSD flows untouched. |
| Stokvel / Save-to-Buy / shared expenses | **Unchanged** | Split bills already settle through the ledger (Phase 9C). Contributions still use the legacy sandbox provider. No reservation/release accounting was invented. |

## 5. Database

**No migration was added.** `payments.ledger_transaction_id` (unique, FK to the ledger) already exists from V40 and `payments.provider` is free text, so wallet payments need no schema change. Historical rows are untouched. Not tested on H2 or PostgreSQL here. The new `SELECT ... FOR UPDATE` lookup and the idempotency/uniqueness behaviour need a PostgreSQL run.

Recovery procedures: a wallet payment that is debited but unresolved is resolved automatically by the sweep (adoption by key); run the admin reconcile endpoint to list any that are not. A refund in `NEEDS_ATTENTION` needs an operator decision because the seller's simulated wallet no longer holds the funds.

## 6. Tests and results

| Test | Kind | Result |
|---|---|---|
| `LedgerEngineTest` (21), `BusinessWalletEngineTest` (9) | pure engine | **PASS** on a minimal stand-in JUnit runner |
| `WalletCheckoutLedgerTest` (12) — payment, replay, tampered amount, insufficient funds, wrong owner, concurrency (8 racing identical payments debit once; 12 racing orders cannot overspend), reversal, fail-closed refund | real `LedgerEngine`, in-memory store | **PASS** on stand-in runner |
| `WalletPaymentVerificationTest` (9), `WalletPaymentAuditTest` (6), `SimulatedProviderAuditTest` (7) | pure logic | **PASS** on stand-in runner |
| `WalletCheckoutServiceTest` (12, Mockito) | Spring-layer orchestration | **NOT RUN** (syntax-scanned only) |
| Updated `StockPaymentServiceTest` | Spring-layer | **NOT RUN** |
| `mvn test` (full backend suite), `mvn -q package` | — | **NOT RUN** |
| PostgreSQL clean install / upgrade, row-lock and constraint behaviour | — | **NOT RUN** |
| Market/Food/Ride/Stokvel/USSD regression suites | — | **NOT RUN** |
| Web: `npm run build` | build + type-check | **PASS** |
| Mobile: `npx tsc --noEmit` | type-check | **PASS** |
| Web/mobile unit or end-to-end tests | — | none exist; **NOT RUN** |

"Stand-in runner" = a ~20-line reflection runner with a stub `@Test` and assertions, compiling the **real** source and test files with the JDK compiler module. It is not JUnit.

Commands to run on a machine with Maven and PostgreSQL: `cd backend && mvn -q test`, then the same against a PostgreSQL profile; `cd frontend && npm ci && npm run build`; `cd mobile && npm ci && npx tsc --noEmit`.

## 7. Known limitations and risks

* **Not compiled against Spring.** Method names were cross-checked by hand against the repository, but a compile error is possible until Maven runs.
* **Seller settlement.** The marketplace/food settlement engines still open a (simulated) settlement for a wallet-paid order, in addition to the credit to the seller's simulated business wallet. These are two separate simulated records, not extra money, but merchant dashboards may show both. Needs a product decision.
* **Self-purchase.** A user buying from a business they own moves demo funds between their own simulated wallets. Not blocked.
* **Partial refunds** of wallet orders are rejected (full refunds only).
* **Wallet history** shows a generic internal transfer; it does not yet name the order or merchant.
* **Order page** requires the buyer to press Pay on the order screen after creating the order (two explicit steps by design). Unpaid orders expire via the existing sweep.
* **Alias names.** Internal classes are still called `ConsumerPaystackCheckoutService`/`PaystackGateway` (they now only mean "simulated checkout").

## 8. Simulated vs real

All wallet checkout transactions are simulated. A successful debit means a demo balance changed in PULA's own ledger. It does **not** mean a shop, restaurant, supplier, driver or taxi association received real money, and nothing triggers a real payout. Marketplace payouts and settlements remain simulated records (see `PHASE_10_AUDIT.md`). No flow in this application involves real money.
