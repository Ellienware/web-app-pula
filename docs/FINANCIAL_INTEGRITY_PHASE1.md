# Financial integrity, refunds and checkout coverage — slice 1 (status: PARTIAL)

Everything here is SIMULATED value. No real payment, payout or settlement was added or activated.

## 1. Initial findings (from the source)

| Concept | Record | Authoritative for |
|---|---|---|
| Order | `orders` (`Order`) | what was bought, total, order status |
| Payment | `payments` (`Payment`), `ledger_transaction_id` for wallet rail | capture status and amount |
| Wallet movement | `ledger_transactions` + `ledger_postings` | the only simulated balance movement |
| Refund | `payment_refunds` (`PaymentRefund`), now `ledger_transaction_id` (V47) | refund amount/status |
| Merchant settlement | `restaurant_settlements` (used for food, retail, stock, services) | merchant-payable accounting (gross, commission, net) |
| Provider earning | `driver_payouts`, `delivery_earnings_allocations` | ride/delivery shares |

**Where one sale appeared more than once (root cause).** A wallet-paid sale credited the seller's simulated business wallet at
payment time (ledger transfer) AND opened a settlement that the payout sweeper later "paid" through the sandbox payout provider:
two representations of one sale, each presented as money received. Separately, the seller dashboard summed order totals without
subtracting completed refunds, so a partial refund never reduced revenue.

**Fix.** `SettlementPayoutService.pay()` closes a settlement whose payment is on the wallet rail as an accounting record only
(`payout_provider = wallet-ledger-simulated`, `payout_reference = ledger tx id`, no provider call, no second credit). The seller
dashboard now counts each sale once from the order, net of COMPLETED refunds.

## 2. Partial refunds (wallet rail)
- Any admin refund of a wallet-paid order may now be partial. Cumulative cap and 0 < amount use `RefundMath` (BigDecimal 2dp,
  converted to integer minor units for the ledger) under the order row lock; failed refunds are excluded, pending ones are counted
  as in flight so they cannot be double-spent.
- Sole full refund: unchanged (reverses the original ledger transaction).
- Any other refund: `LedgerEngine.refundTransfer` posts a NEW compensating transfer seller wallet -> buyer wallet, key
  `refund:<refundId>` (idempotent per initiator+key; same key with another amount is rejected). The original stays POSTED and is
  never edited. The ledger refuses if the seller's wallet cannot fund it (refund -> NEEDS_ATTENTION, never an overdraft).
- Payment becomes REFUNDED, and the order REFUNDED, only when completed refunds equal the payment.
- Fees/commission: not adjusted by hand. The settlement engine recomputes from the refunded total; if a settlement is already
  PAID the existing "dispute" rule applies. **Commission is not collected from the seller's simulated wallet on wallet-paid
  sales** (the seller was credited the gross); this is an open policy decision, flagged, not invented.

## 3. Reconciliation
`GET /api/admin/financial-records/reconcile` (admin only, read-only) via `FinancialRecordReconciler`: wallet payment without
ledger tx, ledger tx shared by payments, refunds exceeding payment, payment/refund status mismatch, completed wallet refund with
no ledger evidence, settlement paid by payout on a wallet sale (double credit), duplicate SALE settlements, settlement refund
mismatch, settlement gross > payment. It never repairs anything. `/api/admin/wallet-payments/reconcile` no longer false-flags a
payment fully refunded by compensating transfers.

## 4. Integration matrix (this slice)
| Vertical | Status |
|---|---|
| Marketplace | wallet checkout (earlier work) + partial refunds + de-duplicated settlement + net dashboard |
| Food | same path; restaurant settlement closed against the wallet credit; refund summary on cash-flow unchanged |
| Wholesale | Slice 2: wallet checkout IMPLEMENTED in code (backend + web + mobile), NOT run. Buying business's wallet -> supplier business's wallet, SEND permission required, deterministic ledger key, amount from stored payment. No refund flow exists for stock orders (card or wallet), so wholesale refunds remain unimplemented |
| Bulk purchase | Slice 4: covered by the wholesale wallet checkout (each participant's allocation already has its OWN stock order); code-only, NOT run; refunds remain manual "refund due" |
| Services | Slice 3: wallet checkout IMPLEMENTED in code (backend + web + mobile), NOT run. Customer wallet -> provider business wallet, only after the provider marks the booking COMPLETED (existing rule); payment status stays separate from booking status; no provider payout, no earnings release |
| PULA Ride | Wallet checkout deliberately NOT built (see section 11). One safe fix made: USSD taxi payment is now retry-safe |

## 5. Migration
`V47__refund_ledger_link.sql` (forward-only, nullable column, FK, unique index). Not run against PostgreSQL or H2.

## 6. Verification actually performed
Pure-Java classes (ledger, refund math, reconciler, audit) compiled with the JDK and 58 tests run with a stand-in JUnit runner:
58 passed, 0 failed (22 new). Spring classes were only parse-checked. NOT run: Maven, any Spring/DB test, Flyway, PostgreSQL,
web and mobile builds, any end-to-end test. Command to finish: `cd backend && mvn -B verify`.

## 7. Still to do
Cash-flow `salesIncome` net of partial refunds (DTO change), customer/merchant refund history UI (web+mobile), admin refund
controls UI, Wholesale/Bulk/Services/Ride checkout, Spring-level tests for `OrderRefundService` wallet partials and the settlement
close, PostgreSQL concurrency run, commission-collection policy for wallet sales.

## 8. Slice 2 — wholesale (stock order) wallet checkout
- `POST /api/stock/orders/mine/{id}/pay/wallet/start` (no charge; same ownership/status/risk-warning rules as card start) then
  `POST /api/stock/orders/mine/{id}/pay/wallet` `{confirm, expectedAmount}` (debits). Debited wallet is always the order's own
  buying business wallet, credited wallet the order's supplier; neither is client-supplied. Actor needs SEND on the business wallet.
- Order becomes PAID only through `StockPaymentService.confirmByReference` after the ledger transfer verifies; the confirm path now
  also checks the debit was initiated by the buying business's owner and can adopt an unlinked debit (crash window) by its key.
- The supplier settlement for a wallet-paid stock order is closed against the wallet credit (no second payout), as for retail.
- Limits: no stock refund/cancel-after-paid flow exists at all; bulk-purchase per-participant payments are not done; mobile/web
  UI is a single "Pay from demo wallet (simulated)" action with a native confirm, not a full checkout screen.
- Tests: 6 new ledger-rule tests (business-to-business payment, replay, cross-business denial, overspend, partial refund of the
  supplier credit) pass on the stand-in runner; `StockWalletCheckoutService` itself has no Spring test yet. Web/mobile not built
  (no node_modules here).

## 9. Slice 3 — Services wallet checkout
- `POST /api/services/bookings/{id}/pay/wallet/start` then `POST /api/services/bookings/{id}/pay/wallet`. The start reuses the existing
  `ServiceBookingService` rules (customer ownership, "payable once the provider marks it complete", risk gate, Save-to-Buy) with
  the payment on the wallet rail; a failed attempt allows a new attempt with a new payment row and ledger key.
- Debit: customer's own simulated wallet; credit: the booking's provider business wallet; amount is the stored payment amount and
  must equal the booking total. The booking becomes paid only via `confirmByReference` after the ledger transfer verifies
  (initiator must be the booking's customer). Booking lifecycle state is not changed by payment.
- The provider's settlement is closed against the wallet credit (no second payout), as for retail and stock.
- Limits: no service refund flow was added (none exists for bookings); a cancelled/disputed completed booking cannot be refunded
  through the wallet yet; Spring-level tests and web/mobile builds not run.

## 10. Slice 4 — bulk purchase
Audit result: the bulk group is a coordination record and never holds money. Each participating business's allocation owns its
OWN `StockOrder` (own payment, own amount, own status), and `BulkPurchaseService.syncPayments` marks an allocation PAID only
when that order shows a SUCCESSFUL payment. So no new payment record or endpoint was needed: a participant pays their own share
through the wholesale wallet checkout (slice 2) from the Stock orders page (the bulk page already links there).
- Authorisation is by the stock order's buying business, so one participant cannot pay or start another's share, and one business's
  payment is never evidence that the others have paid.
- Fix: a wallet-paid share now always reports `paymentSimulated = true` (before, it was true only in sandbox card mode, so a wallet
  payment could have been labelled as real when the card provider is live).
- Unchanged and still open: a group cancelled or short-supplied after payment records "refund due" only; there is no executor for
  stock-order refunds (card or wallet), and `PaymentRefund` is order-bound (retail/food), so wallet refunds of paid shares must be
  handled manually until a stock refund model is defined. A started-but-unfinished wallet payment keeps the share
  PAYMENT_PROCESSING (cancel blocked) until the existing processing-expiry sweep, as with card payments.
- Not tested: no Spring test of syncPayments with a wallet payment.

## 11. Slice 5 — PULA Ride (audit result and one fix)
**How taxi payments work today.** `MoMoRideService.doPayTaxi` / `fulfillPaymentRequest` reserve a `Payment` through
`PaymentIdempotencyService` and call the configured `PaymentProvider` (sandbox by default). There is NO ledger entry, no driver
wallet, no taxi-association payable and no driver-payout record for taxi fares: "payment successful" and "you received X"
notifications/SMS are produced from the provider result alone. In-app `payTaxi` takes a client idempotency key;
`fulfillPaymentRequest` is retry-safe through the request status machine (OPEN -> PROCESSING -> FULFILLED/FAILED).

**Why a wallet debit was not added.** A wallet debit must post a balanced credit somewhere. Crediting the driver's wallet would
be exactly the automatic simulated driver payment this phase forbids; recording the amount separately needs a new ledger account
kind (for example a ride-payable clearing account), changes to the `ck_ledger_acct_kind` / owner CHECK constraints, a driver and
association payable model and an approval/settlement workflow. Those are financial-model decisions, so none was invented.
USSD (no smartphone, no wallet PIN step) and driver-first semantics are therefore untouched.

**Fix made.** USSD taxi payment used a RANDOM idempotency key, so a gateway re-delivery of "1. Confirm" could charge the passenger
twice. `payTaxiFromUssd(..., sessionId)` now derives the key from payer + USSD session + taxi code + amount, so a re-delivery
replays the first payment; a new dial is a new session and a new payment. `UssdSessionService` uses it; the two existing mock lines
in `UssdSessionServiceTest` were updated to the new overload. Not run (no Maven): confirm with `mvn -Dtest=UssdSessionServiceTest test`.

**Still open for Ride.** Demo labelling: the "you received" notification/SMS wording does not say it is simulated; define the
ride-payable account model and approval workflow before any wallet rail; the legacy in-app `payTaxi` without a client key is not
retry-safe.
