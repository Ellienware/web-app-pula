# Phase 6 — Bulk-purchase allocation between businesses

## Verification status (read this first)

| Item | Status |
|---|---|
| Pricing / shortage / deadline logic tests (21) | **RAN, 21/21 passed** (offline JUnit-5-style shim, real `javac`) |
| `BulkPurchaseService` tests with in-memory repositories (59) | **RAN, 59/59 passed** |
| Mutation check (3 deliberate breaks: price lock, "paid" without payment, IDOR) | **RAN, each caught by a failing test** |
| New backend code compiled against the real source tree | **RAN, 0 errors** (Spring/JPA annotations stubbed) |
| Web + mobile new files type-checked | **RAN, 0 errors in new files** (React/RN/Expo typings stubbed; this is NOT a real build) |
| Maven build, Spring context start, Hibernate `ddl-auto: validate`, **Flyway V38 on a real database** | **NOT VERIFIED** (no Maven / network / DB in the sandbox) |
| Pessimistic row lock, concurrent joins/confirmations, transaction rollback of a failed finalise | **NOT VERIFIED** (in-memory fakes cannot reproduce them) |
| Existing PULA Market, Stock, Stokvel, Save-to-Buy and PULA Ride test suites | **NOT VERIFIED** (need Mockito/Maven). Regression argument only: no existing backend file was modified |
| `vite build`, Expo build, real-device behaviour | **NOT VERIFIED** |
| Save-to-Buy end-to-end through bulk (join with a request, finalise attaches the order) | **NOT VERIFIED** (code path written; only the "not wired → 400" branch is tested) |
| Event visibility query (`findVisibleToBusiness`) | **NOT VERIFIED** (SQL, needs a DB) |

The in-memory tests prove service logic, not SQL, mappings or locking.

## Audit findings

Reused as-is: `WholesaleProduct` (price/MOQ/stock in **packs**), `StockOrder`/`StockOrderItem`, the atomic `reserve`/`release`
stock guard, `StockOrderService.cancelAndRelease` / `advanceStatus`, `StockPaymentService` + `PaymentOrchestrator` (provider-verified
payment, `stock-order:{id}` idempotency), PULA Ride / wholesaler delivery through the order, Phase 5 settlement per `StockOrder`,
`SaveToBuyService` (`validateForCheckout`, `requireWithinApproval`, `attachOrder`), `ServiceRateLimiter`, `ApiException`.

Missing (built): volume-discount tiers, groups, per-business allocations, shortage handling, a parent procurement reference, audit events.
Also missing and **not built**: refunds for stock orders (see limitations), a tax model, an outbound supplier purchase-order channel.

## Design

* A group is a **coordination record**. It holds no money. Each business gets its **own `StockOrder`** (same fields and the same
  idempotent-key discipline as the normal checkout) and pays it through the existing stock payment flow. Nothing in the bulk code
  creates a payment, wallet, ledger entry or checkout.
* The server computes every price. Clients send quantities and the price they *acknowledge*; a mismatch returns `PRICE_CHANGED` /
  `QUANTITY_CHANGED` data with the new quote and confirms nothing.
* **Price lock:** confirming records `agreedMaxPricePerPack`. Any change that would push the price above it (another business
  withdrawing, dropping unconfirmed businesses, a quantity change) moves the business to `PENDING_RECONFIRMATION`. Finalisation
  refuses while any agreement is stale. A price drop never needs re-confirmation; a rise always does.
* **Payment is observed, never asserted.** An allocation becomes `PAID` only when its order shows `Payment.status == SUCCESSFUL`.
  One business paying never marks another paid, and does not place the order.
* **Order placement:** automatic once *every* remaining share is paid at the original volume. The organiser may explicitly proceed with
  paid shares only, and only if the volume price still holds (otherwise `PRICE_WOULD_INCREASE`); unpaid orders are then cancelled and stock released.
* **Shortage policy (documented, deterministic):** pro-rata by ordered quantity, whole packs, largest-remainder rounding, remainder ties to
  the earliest confirmation then id. Nobody receives more than they ordered; the total equals what the supplier confirmed. The price each
  business agreed is honoured for the packs it receives; the unsupplied part is recorded as `refundDueAmount`.
* **Deadline policy (configured per group):** CANCEL; EXTEND (up to 3×, then cancel); ASK_PARTICIPANTS (needs MOQ met, moves to confirmation).
  If demand is there but the organiser has not started confirmation, one 24h+ confirmation window is opened, then cancel. Groups that already have orders are never touched.
* **Lifecycle:** DRAFT → OPEN → TARGET_REACHED → CONFIRMATION → PAYMENT_PENDING (added: orders exist, awaiting each business's own payment)
  → ORDER_PLACED → SUPPLIER_CONFIRMED → FULFILMENT → COMPLETED | PARTIALLY_FULFILLED; CANCELLED / FAILED.
* **Isolation:** the caller's allocation is always looked up from the caller's own business (no allocation id is accepted from the client);
  other businesses' DRAFT/closed groups answer 404; participants never receive the participant list; the supplier view carries quantity,
  destination, payment/order status only (no allocation prices); the supplier sees only its own groups.

## Database

`V38__bulk_purchase.sql` (V37 was the latest; nothing existing is edited): `wholesale_volume_tiers`, `bulk_purchase_groups`,
`bulk_group_tiers`, `bulk_allocations` (unique `(group, business)`, unique `stock_order_id`), `bulk_group_events`. No existing table altered.

## API (all authenticated; ownership derived server-side)

| Method + path | Who |
|---|---|
| `GET/PUT /api/stock/products/{id}/volume-tiers` | read: any user; write: the product's supplier |
| `POST /api/bulk/groups`, `GET /api/bulk/groups` (open, paged), `GET /api/bulk/groups/mine`, `GET /api/bulk/groups/supplier` | buyer / supplier |
| `GET/PATCH /api/bulk/groups/{id}`, `POST .../open`, `.../start-confirmation`, `.../finalise`, `.../place-order`, `.../cancel` | organiser (GET: any party with access) |
| `POST .../refresh`, `GET .../events` | any party with access (events filtered per role) |
| `GET .../quote`, `POST .../join`, `GET/PATCH .../allocation`, `POST .../allocation/confirm`, `.../allocation/withdraw`, `.../allocation/issues`, `GET /api/bulk/allocations/mine` | the caller's own business |
| `GET .../supplier`, `POST .../supplier/confirm` | the group's supplier |
| `GET /api/admin/bulk/refund-due` | admin, read-only |

## Files

Backend (all new): `bulk/{BulkPricing,BulkShortageAllocator,BulkDeadlinePolicy}`, 5 entities, 3 enums, `BulkDtos`, 4 repositories,
`BulkPurchaseService`, `BulkPurchaseMaintenanceTask`, `BulkPurchaseController`, `V38`, 4 test classes.
Web: new `bulkTypes.ts`, `bulkApi.ts`, `BulkBuyPage.tsx`, `BulkGroupPage.tsx`, `VolumeTiersPanel.tsx`; small edits to `App.tsx` (2 routes),
`StockMarketPage.tsx` (link), `StockCataloguePage.tsx` (tiers panel).
Mobile: new `types/bulk.ts`, `services/bulkApi.ts`, `app/bulk/index.tsx`, `app/bulk/[id].tsx`; one button added in `app/(tabs)/market/index.tsx`.

## Manual verification steps (needs a running stack)

1. Start the backend on a real Postgres; confirm Flyway applies V38 and Hibernate `validate` passes.
2. Supplier sets tiers (100 → 90, 200 → 80) on a product (price 100, MOQ 50).
3. Business A creates a group (target 100) and opens it; A, B, C join with 20/30/50 → TARGET_REACHED, price 90.
4. A starts confirmation; each confirms at 90. Try a wrong price: expect `PRICE_CHANGED` and no confirmation.
5. Have C withdraw: A and B must show "Please confirm again". Re-join C and re-confirm.
6. A finalises: three separate stock orders (1800/2700/4500). Pay each in "My stock orders" (sandbox shows SIMULATED). After the third, the group becomes ORDER_PLACED.
7. Supplier confirms 80 of 100: allocations 16/24/40, refund-owed 360/540/900, nothing marked refunded.
8. Fire two concurrent joins/confirms against one group and confirm they serialise (this is the unverified lock).
9. Check a second business's token gets 404 on the group's allocation/supplier endpoints.

## Remaining dependencies and limitations

* **No refund executor exists for stock orders.** Cancellations after payment and shortages record `refundDueAmount`, move the order to
  `REFUND_PENDING`, and surface in the UI and the admin list. Nothing is ever marked refunded. Someone must refund manually.
* **Supplier payout vs shortage:** the Phase 5 settlement is computed from the order total; a shortage is **not** automatically deducted from the
  supplier's settlement. An admin must apply a DEBIT adjustment before payout (the existing admin adjustment API supports it).
* **No outbound supplier PO.** "The supplier receives one combined order" is the group record plus the supplier view (ordered/paid packs and each
  business's destination); there is no integration that transmits a purchase order.
* All quantities are **packs** because the catalogue prices and orders in packs (`packSize` is shown). No tax model exists in stock orders, so none is shown.
* Delivery fee is the supplier's flat fee per delivered business (self-collection is free); each business has its own address.
* Stokvel: a Save-to-Buy request can be referenced on joining; the existing approval rules are enforced by `SaveToBuyService` at join and again at
  finalisation (a request that is no longer valid sends that business back to re-confirm). No new savings feature, no pooled wallet. The web/mobile
  join forms do not expose the Save-to-Buy field yet (API only), and the order is not given a `stokvelRequestId` directly.
* Mobile covers discovery, join, quote, confirm, payment/fulfilment tracking, history and issue claims. **Creating groups, organiser actions and
  supplier actions are web-only.**
* Rate limiting is the existing in-memory `ServiceRateLimiter` (60 writes/min/user); it is per-instance.
* Shortage does not re-price (nobody is charged more); if a supplier will not honour a tier below its threshold, the supplier should cancel.
