# Phase 3 - Save-to-Buy

## Verification status (read first)
| Check | Result |
|---|---|
| `mvn test`, Spring context, Flyway V35 on a real DB, Hibernate `validate` | **NOT VERIFIED** (no Maven, network or database in the build environment; V35 reviewed by hand against the entity mappings) |
| Existing suites (Phase 2A savings, Food, Stock, Order tests) re-run after the checkout edits | **NOT VERIFIED** (need Mockito). Every existing call site of the two changed records was arity-checked: `CheckoutRequest` 1/5/6-arg and `PlaceStockOrderRequest` 3-arg all still have constructors |
| New + edited Java compiled against the real source tree (Spring/JPA annotations stubbed) incl. `OrderService`, `ConsumerPaystackCheckoutService`, `StockOrderService`, `StockPaymentService` | 0 errors |
| New unit tests executed (JUnit shim + `Proxy` in-memory repositories, real service classes), Phase 2B + Phase 3 | **89 run, 89 passed, 0 failed, 0 skipped** (18 are Save-to-Buy) |
| Mutation check: disabled the stokvel cap and the goal-reached gate | tests failed as expected (2 of 17 at the time), source restored |
| Web/mobile TS (lib/types/services) | `tsc` exit 0 |
| Web/mobile TSX | no `node_modules`; type-checked with a wildcard module shim only. This found and I fixed a real bug (below). Not built, not run |
Executed tests ran under a throw-away JUnit shim, not Surefire. The real checkout classes were compiled but **not executed**: only the four hook methods they call, plus structural tests that the hooks are wired, were run.

## Precondition findings (actual code, not assumptions)
- Goals live in `circle_goals` inside a `Circle`; a "personal goal" is a goal in a circle with one member.
- Retail and food share one cart/checkout with **two** order-creating paths (`OrderService.checkout`, `ConsumerPaystackCheckoutService.start`); payment resolves in `OrderService.applyResolvedSuccess/Failure`.
- Stock is separate: `StockOrderService.checkout` creates one `StockOrder` per supplier; payment confirms in `StockPaymentService.confirmByReference`; failures/expiry/cancel go through `cancelAndRelease`.
- **No Services marketplace exists** -> not implemented. PULA Ride was not touched. Cash Flow is business-scoped; nothing to reuse and nothing added.
- Latest migration was V34 (stokvel), so this phase is V35.

## What was reused / what is new
Reused: `CircleGoal` (extended, not replaced), contributions/goal progress, the stokvel approval engine (`StokvelRequestService`: voters, thresholds, no self-approval, expiry), existing carts, checkouts, payment providers and orders, activity feed, `FinancialValidationService`.
New: `save_to_buy_requests` (tracking record), `SaveToBuyService`, `SaveToBuyInsightService` (read-only), `SaveToBuyController`, two nullable `save_to_buy_request_id` columns on `orders`/`stock_orders`, two nullable columns on `circle_goals` (`purchase_vertical`, `purchase_purpose`). No wallet, balance, ledger, cart, checkout, payment provider or transaction table.

## Flow
Personal: designate goal -> create DRAFT plan -> **owner confirms (only once the goal is reached)** -> APPROVED -> named buyer (= owner) continues in the existing marketplace; the checkout request carries `saveToBuyRequestId` -> order created as normal (CHECKOUT_STARTED) -> payment success -> PURCHASED.
Stokvel: designate (owner/admin) -> authorised member (owner/admin/treasurer/chair) creates DRAFT naming a member as buyer -> **submit** (goal must be reached) creates a normal stokvel request -> stokvel's own approval rules -> APPROVED (mirrored) -> named buyer checks out -> PURCHASED, and the stokvel request becomes EXECUTED with the order reference.
A failed/cancelled payment returns the request to APPROVED and clears the order link so it can be retried; a purchased request cannot be reused.
Goal reached never triggers anything by itself. Nothing in this phase pays: the charge is always the checkout's own backend-computed total.

## Funding language and partial funding
Views carry `goalTarget`, `saved`, `requestedAmount`, `shortfall` (requested - saved), `differenceFromTarget` (requested - target) and `fullyFunded` (goal reached AND saved covers the request). A personal purchase may cost more than the goal; the difference is simply paid at checkout. A stokvel purchase may not exceed the approved amount (checked before the order is created; for stock, after pricing, rolling the checkout back). Every view includes the notice that PULA does not hold the savings.

## Order linking
`orders.save_to_buy_request_id` / `stock_orders.save_to_buy_request_id` (set at creation); on payment the request stores `purchased_amount`, and for stokvel purchases the order also gets `stokvel_request_id`. `GET /api/save-to-buy/by-order/{kind}/{orderId}` answers: which goal, which stokvel approval, who the named buyer was, which order, requested vs purchased amount. No order, payment or transaction is duplicated.

## APIs (`/api/save-to-buy`)
`GET /goals[?all=true]`, `PUT /goals/{id}/intent`, `POST /`, `GET /`, `GET /circle/{circleId}`, `GET /{id}`, `GET /by-order/{kind}/{orderId}`, `POST /{id}/confirm|submit|cancel|complete`, `GET /{id}/insights`. Existing endpoints gained an optional `saveToBuyRequestId` field: `POST /orders/checkout`, `/orders/checkout/paystack`, `/stock/orders/checkout`.

## Security
Goal ownership, stokvel membership/role, named-buyer check, request ownership, IDOR (other people's goals and requests answer 404; a member who is not the named buyer gets 403), amount validation, idempotency (create key; checkout key replay recognised; duplicate open request refused; one live order per request via row lock + unique index), tenant isolation. Personal plans are refused on shared (multi-member) circles so one member cannot spend group goals; stokvel plans need the stokvel's approval.
Fixed during this phase: the mobile stokvel screen from Phase 2B used `spacing.lg/md/sm`, which do not exist in this theme (numeric keys), so padding would have been undefined. Found by the TSX shim check; fixed in both screens.

## Screens
Web: `/save-to-buy` (`SaveToBuyPage`), `SaveToBuyBanner` shown inside the existing retail/food cart and stock cart, nav link. Mobile: `app/save-to-buy/index.tsx`, entry button on the Circles tab; the existing cart, food checkout and stock checkout now send the active request id. There are no Save-to-Buy shop or cart screens.

## Not done / limitations
- Services marketplace does not exist, so no Services integration. Taxi and general plans are confirm-then-self-declared ("Mark as done"); PULA Ride is untouched and nothing verifies a ride payment.
- A food/retail order that is later cancelled or refunded **after** payment does not revert PURCHASED; the order reference keeps the history.
- Stock Save-to-Buy needs a single-supplier cart (one request <-> one order).
- Stokvel verticals: retail, food, stock only.
- AI: read-only insights endpoint (saving advice uses a fixed 3-month horizon; it does not read the goal deadline). `AssistantService` is not wired to it. A test asserts no assistant action type can approve, check out, purchase or pay.
- Plans do not expire on a schedule; expiry is applied when they are read or used.
- The web/mobile UI creates stokvel plans with the creator as buyer; naming another member is available through the API only. There is no edit of a DRAFT (cancel and recreate).
- Cash Flow: no integration (nothing applicable); no ledger added.
