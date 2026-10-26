# PULA Food — implementation report

PULA Food extends **PULA Market → Shop → Food**. It is not a separate app. Menu items are `Product` rows, orders use
the existing order/payment architecture, delivery uses the existing PULA Ride delivery engine, reviews use the existing
review infrastructure, and Cash Flow and PULA AI were extended in place.

## STATUS: NOTHING HERE HAS BEEN COMPILED OR RUN
This was built in an environment with no Maven Central, no npm registry and no Java type-check classpath.
- Backend: parsed with the JDK compiler for syntax only (no type-check). New SQL V25–V29 and V31 pieces were run in SQLite
  (not PostgreSQL). ~163 new test methods and edits to existing tests have never executed.
- Web / mobile: type-checked against hand-written stand-ins for React / React Native / Expo Router / React Query, not the real
  packages. Mobile route targets were checked against the files on disk. No browser or device run.
Run before relying on any of it: `./mvnw test`, `npm run build` (web), `npx tsc --noEmit` (mobile), then the manual
checks at the end of this file.

## Data model (new migrations only; V1–V24 are byte-identical to what you supplied)
| Migration | Adds |
|---|---|
| V25 | `business_branches` (+ hours, fulfilment options, delivery areas). Optional: a business with no branch is a single location. |
| V26 | `menu_categories`, `products.menu_category_id`, `product_food_details` (+ sides/dietary/allergens), `branch_item_availability`. |
| V27 | `modifier_groups`, `modifiers`, `product_modifier_groups`, `cart_item_modifiers`, `order_item_modifiers`. |
| V28 | Cart line identity = (user, product, modifier set); `orders` food columns; `businesses.temporarily_closed` / `accept_timeout_minutes`. |
| V29 | `delivery_earnings_allocations` (one per delivery, `driver + pula = fee` CHECK); `delivery_requests.no_driver_notified_at`. |
| V30 | `canonical_food_attributes`, `canonical_food_aliases` (extends canonical products). |
| V31 | Review sub-ratings, `verified_order`, `source`, portable `dedupe_key` unique column (replaces `uq_review_author_target`). |
`ddl-auto` is `validate`: every new entity field was cross-checked against the migration columns by script.

## Behaviour
- **Business types**: RESTAURANT, CAFE, FAST_FOOD, FOOD_VENDOR, HOME_KITCHEN, SPAZA, BAKERY, CATERING (existing types unchanged).
  Menu tools require a food type.
- **Pricing is backend-only** (`FoodCheckoutService`): item price (or branch override) + modifiers, delivery fee snapshot,
  minimum order, branch/availability/capacity checks. `POST /api/orders/checkout/quote` previews the full total; `expectedTotal`
  on checkout makes the server refuse if the total changed since the customer reviewed it.
- **Restaurant decision**: paid food orders must be accepted or rejected (`/accept`, `/reject`); unanswered orders are rejected and
  refunded after the accept window (default 10 min, per-business up to 15; `app.food.*`). Accept/reject/timeout are row-locked and idempotent.
- **PULA Ride delivery**: chosen by the customer at checkout. When the restaurant marks the order READY, `FoodDeliveryService`
  creates the delivery automatically (idempotent: `delivery_requests.order_id` is unique). Collection and own-rider orders never
  dispatch. A driver withdrawing before pickup re-offers the same delivery. Manual seller delivery requests are refused for food orders.
- **80/20**: on delivery, one allocation row from the order's delivery-fee snapshot; driver = fee × 0.80 rounded half-down to the cent,
  PULA = remainder. The driver payout pays the driver share only (same idempotency key). Stock deliveries are unchanged.
- **Customer cancel** (`POST /api/orders/{id}/cancel`, full refund): before the restaurant decides, or when a PULA Ride delivery has had
  no driver for the no-driver timeout (default 20 min). Not while preparing or once a driver has accepted. Partial refunds don't exist in
  the payment provider interface, so there is no "switch to collection and refund the fee".
- **Reviews**: only the buyer of a COMPLETED order; food = one per (author, order, target), retail = original once per (author, target); both
  enforced by `dedupe_key`. Optional 1–5 sub-ratings (food orders only); delivery experience is separate from the food rating.
  Every review is "Verified PULA order", set by the server; external reviews are never stored or counted.
- **Matching** (`FoodMatchingService`): attribute-first, explainable EXACT / SIMILAR / ALTERNATIVE with reasons; unknown attributes lower the level and are
  flagged; an explicit canonical link is trusted unless attributes contradict it; name similarity only *suggests* links to merchants.
  Editing canonical food attributes is admin-only (`PUT /api/canonical-products/{id}/food`).
- **Discovery** (public, read-only): `/api/market/food/search`, `/food/canonical/{id}/compare`, `/food/items/{id}/compare`, `/restaurants`,
  `/restaurants/{id}`, `/restaurants/{id}/menu`. No "winner" field anywhere; ordering is customer-chosen. Customer coordinates are used only
  for distance and never stored or returned; offers with unknown location are excluded from distance searches and counted.
- **Cash Flow**: food sales exclude delivery fees; the PULA Ride fee is not restaurant income and is reported separately with the driver 80% / PULA 20%.
- **PULA AI**: SEARCH_FOOD, SEARCH_RESTAURANTS, COMPARE_FOOD, VIEW_MENU answered server-side from PULA data; ADD_FOOD_TO_CART resolves the dish on
  the server (a model-supplied productId is ignored), needs confirmation, and only adds to the cart. There is no order/pay action.

## Known limitations and open decisions
1. **No restaurant payout path exists** in the codebase for retail/food proceeds (only driver payouts). Decide how restaurants are paid.
2. **Paystack has no refund call** in this codebase. Rejected/expired/cancelled Paystack-paid orders stay `REFUND_PENDING` for a manual refund
   (they are deliberately not sent to the sandbox provider). Sandbox-paid orders refund automatically and retry every 30 s.
3. **Customer cancel after the food is made** (no driver found) refunds the customer in full, so the restaurant absorbs the cost. Decide the policy.
4. Portion caps (`dailyQuantity`) are a remaining-portions counter set by the merchant; there is no automatic daily reset.
5. Delivery area is not validated (addresses are free text). Delivery fee is a flat fee per restaurant/branch; no distance pricing.
6. Food search loads up to 1000 candidate items and filters in memory; fine now, needs an index if the catalogue grows.
7. Canonical-food admin has no UI (API only).
8. Mobile has no GPS (needs `expo-location`; see `docs/PULA_FOOD_MOBILE.md`).
9. `AssistantService` receives `FoodAssistantService` by optional setter injection so its long constructor (and its hand-built tests) stay unchanged.

## Final audit performed (what was and was not checked)
Checked mechanically: V1–V24 unchanged; no files deleted; no existing test removed (3 existing test files updated for new constructors, +9 tests);
untouched: Community Savings, taxi/MoMoRide, ScamShield, Money Confidence, auth, PaymentOrchestrator/PaymentIdempotencyService/Payment;
entity↔migration columns; constraint names dropped by V28/V31 exist in V1; every new public route is read-only and `anyRequest()` stays authenticated;
all constructor/record call sites match their new arity; every frontend API call maps to a real endpoint; no user-facing "MoMo" names in new UI
(PULA Ride delivery label is used); mobile route targets exist.
Touched shared code (highest regression risk — review these first): `OrderService.checkout`, `ConsumerPaystackCheckoutService.start`, `CartService.add`,
`DeliveryRequestService` (markDelivered/cancel/request), `PayoutService` (overload only), `ReviewService.create`, `BusinessCashFlowService.summary`,
`AssistantService`, `SecurityConfig`, `Order/Business/Product/Review/CartItem/OrderItem` entities. Retail paths were left on their original code paths.
NOT checked (cannot be, here): compilation, any test run, PostgreSQL migration run, Paystack, real devices/browsers, performance, load.

## Manual verification checklist
1. `./mvnw test` (baseline first, then with these changes); fix any compile/test failures.
2. Start the app against a real DB: Flyway should apply V25–V31 and Hibernate `validate` should pass.
3. Retail regression: add a retail product to cart, checkout, pay, seller moves it through statuses, review it.
4. Food happy path: food business → category/dish/extras → customer search/compare → cart with extras → quote → pay → restaurant accepts → ready →
   driver accepts/picks up/delivers → check allocation row (80/20), driver payout, Cash Flow, review.
5. Rejection and timeout paths (check `REFUND_PENDING`/`REFUNDED`), no-driver notice at 10 min, customer cancel at 20 min.
6. Existing: Community Savings, taxi payment code flow, ScamShield, Money Confidence, Ask PULA, login.
