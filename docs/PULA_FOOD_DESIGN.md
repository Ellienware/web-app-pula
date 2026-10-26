# PULA Food — Design (Phase 2)

> Written before the build. Where the build differs, `PULA_FOOD.md` is authoritative. Known differences:
> `product_food_details` has its own id (unique `product_id`) instead of using `product_id` as primary key;
> a modifier group's `required` flag is derived from `min_select >= 1` instead of stored; `product_modifier_groups`
> has its own id; the order-review migration is V31 and the canonical-food migration is V30; a checkout quote
> endpoint and an `expectedTotal` guard were added; the customer cancel rules are in `PULA_FOOD.md`.

Scope: extend PULA Market (Shop → Food). No new app, no rebuilt subsystems. Nothing here edits an applied migration (V1–V24); all schema changes are new migrations V25+.

Evidence base: Phase 1 audit (code read, not executed; backend tests have never been run in this environment).

---

## 1. Agreed decisions

| # | Decision |
|---|----------|
| D1 | One shared menu per business. Per-branch availability and optional per-branch price override. A business with zero branch rows behaves as a single location. |
| D2 | Coordinates stored on branches (merchant pin or device GPS at setup, never silent geocoding). Customers: GPS if permitted, else manual area. Customer coordinates are used server-side for distance only and are never returned to restaurants or other users. |
| D3 | Menu items are `Product` rows plus a one-to-one food-details extension. No `MenuItem`/`FoodProduct` entity. |
| D4 | Food comparison extends `CanonicalProduct` and `PriceComparisonService`. No parallel comparison system. |
| D5 | Order state machine is not changed. PROCESSING = "Preparing", READY = "Ready for collection / pickup" (label chosen by fulfilment). "Out for delivery" is derived from `DeliveryRequest.PICKED_UP`. Restaurant accept/reject is a decision flag on the order, not a new status. |
| D6 | Delivery dispatch is automatic on READY for food delivery orders, via a new `FoodDeliveryService` modelled on `StockDeliveryService`. The existing manual seller `request` endpoint stays for non-food orders. |
| D7 | 80/20 split lives in a new allocation record, created once per delivery. Stock delivery payouts keep today's behaviour. |
| D8 | Cancellation/refund rules in section 8. |

---

## 2. Findings that shape the design

1. `delivery_requests.order_id` is `UNIQUE` (V14), so dispatch is naturally idempotent. A duplicate event cannot create a second delivery.
2. **`OrderService.checkout` does not add any delivery fee to the order total today.** `Business.deliveryFee` exists but is never charged. The fee snapshot and its inclusion in the total are therefore new work, not a tweak.
3. `PayoutService.createForCompletedDelivery` pays the full `estimatedFee` and is idempotent by key `payout:delivery:{id}`. The split must slot in without breaking that key.
4. `ReviewService` already enforces: own order, order COMPLETED, product/business belongs to order. Its duplicate check is `author + targetType + targetId` (global), which blocks reviewing the same dish after a second order, and is check-then-insert with no DB unique index. Both need fixing for food.
5. `Business.owner_id` is unique: one business per owner. Branches are a new table.
6. `Product.stock` is an int with provisional reservation at checkout. Food reuses this when a daily quantity is configured; otherwise availability is a status/flag.
7. The AI treats every action except NONE and SEARCH_MARKET as confirmation-required (`AssistantService` ~line 784) and lists action types in its system prompt (~line 97). New read-only food intents must be added to that exemption, and the prompt updated.
8. Web has `pages/market/*`; mobile has `app/(tabs)/market/*` and `app/market/*`. Both are extended in place.

---

## 3. Data model (migrations)

All new tables use the existing `BaseEntity` pattern (VARCHAR(36) id, created_at, updated_at).

### V25 — Food business types and branches
- `businesses.business_type` is VARCHAR(32): add enum values `RESTAURANT, CAFE, FAST_FOOD, FOOD_VENDOR, HOME_KITCHEN, SPAZA, BAKERY, CATERING` in Java. No existing value removed. No DDL needed unless a CHECK constraint exists (verify).
- `business_branches`: `business_id`, `name`, `address`, `area` (suburb/township, used for "near Soweto"), `latitude`, `longitude` (nullable), `active`, `temporarily_closed`, `prep_time_minutes`, `delivery_fee`, `minimum_order_amount`.
- `branch_operating_hours`, `branch_fulfilment_options` (element collections, mirroring `Business`).
- `branch_delivery_areas` (branch_id, area).
- Single-location businesses keep using `Business.location`, `operatingHours`, `fulfilmentOptions`, `deliveryFee`, `minimumOrderAmount`. Branch values override only when a branch exists.

### V26 — Menu, food details, availability
- `menu_categories`: `business_id`, `name`, `sort_order`.
- `products.menu_category_id` (nullable FK).
- `product_food_details` (PK = `product_id`): `food_type`, `protein`, `starch`, `portion_size`, `serving_size`, `spice_level`, `portion_description`, `prep_time_minutes`, `daily_quantity` (nullable capacity). Plus `product_food_sides`, `product_dietary_tags`, `product_allergens` element collections. All optional except what the merchant fills.
- `branch_item_availability`: `branch_id`, `product_id`, `available`, `price_override` (nullable). Unique (branch_id, product_id).

### V27 — Modifiers
- `modifier_groups`: `business_id`, `name`, `min_select`, `max_select`, `required`.
- `modifiers`: `group_id`, `name`, `price_delta` (>= 0), `available`.
- `product_modifier_groups`: (product_id, group_id).
- `cart_item_modifiers` and `order_item_modifiers` (name and price snapshots, quantity). Order snapshots are never recomputed after payment.

### V28 — Canonical food
Extend, don't duplicate:
- `canonical_food_attributes` (PK = `canonical_product_id`): `food_type`, `protein`, `starch`, `portion_class`, `sides_signature`.
- `canonical_food_aliases`: (canonical_product_id, alias_normalised). Examples: "beef and pap", "beef stew with pap".
- Canonical rows for meals use `category = 'MEAL'` (no barcode). `Product.canonicalProduct` and `matchClassification` are reused as the explicit link.

### V29 — Order and delivery
- `orders`: `kind` (RETAIL default, FOOD), `branch_id`, `restaurant_decision` (PENDING/ACCEPTED/REJECTED, null for retail), `decision_at`, `rejection_reason`, `ready_at`, `delivery_fee_snapshot`, `customer_note`.
- `orders.total` includes the delivery fee snapshot for delivery orders. Retail orders are unchanged (fee null).
- `delivery_earnings_allocations`: `delivery_request_id` UNIQUE, `delivery_fee_snapshot`, `driver_share`, `pula_share`, `split_policy` (`PULA_RIDE_80_20`), `created_at`. CHECK `driver_share + pula_share = delivery_fee_snapshot`.

### V30 — Reviews
- `reviews`: add nullable `taste`, `portion_size`, `value_for_money`, `food_quality`, `packaging`, `delivery_experience` (1–5), `verified_order` boolean (set server-side, true for all order-based reviews), `source` VARCHAR default `'PULA'`.
- Unique index `(author_id, order_id, target_type, target_id)` backs the application check.
- External reviews, if ever added, go in a separate table with a source label and are never counted in PULA aggregates.

---

## 4. Ordering and pricing

- Cart accepts product + modifier selections. Checkout validates: each modifier belongs to a group attached to the product, group min/max respected, modifier available, product ACTIVE and branch-available, branch open and not temporarily closed, minimum order met.
- Unit price = branch override or product price + sum of modifier deltas. Line totals, delivery fee snapshot and order total are computed in `OrderService`. Frontend totals are display only.
- Capacity: when `daily_quantity` is set, reservation uses an atomic conditional update (decrement only if remaining >= qty), released on payment failure, same as the existing stock reservation.
- Duplicate submit protection reuses the existing checkout idempotency key and `PaymentIdempotencyService`.
- Restaurant flow: PAID → restaurant accepts (decision ACCEPTED + PAID→PROCESSING) or rejects (decision REJECTED + PAID→REFUND_PENDING with reason). PROCESSING → READY → COMPLETED. Unanswered orders get a configurable accept timeout (default 10 min) after which the system rejects and refunds.
- Collection COMPLETED is set by the restaurant when the customer collects. Delivery COMPLETED is set only by the existing driver "delivered" path.

---

## 5. Delivery automation

`FoodDeliveryService.dispatchIfEligible(order)`, called inside the same transaction as the PROCESSING → READY update:

1. Only if `kind = FOOD`, `fulfilmentMethod = MOMORIDE_DELIVERY`, decision ACCEPTED.
2. Pickup = branch address (or business location), dropoff = order delivery address, `estimatedFee` = order `delivery_fee_snapshot`.
3. Find-or-create by `order_id` (UNIQUE) → safe on retries and duplicate events.
4. Collection and SELLER_DELIVERY orders never reach this code path.
5. Eligibility, browse, accept, pickup and delivered all reuse `DeliveryRequestService` and `DeliveryAcceptanceConcurrencyService` unchanged.

Exceptions:
- Driver withdraws before pickup: existing cancel + redispatch pattern returns it to REQUESTED.
- No driver within the timeout (default 20 min): one automatic re-offer, then notify the customer with choices (keep waiting, switch to collection, cancel and refund).
- Nothing is marked accepted, picked up, delivered or refunded except on the confirming event.

---

## 6. 80/20 allocation and payouts

- On `markDelivered` for a food delivery, create one `delivery_earnings_allocations` row (unique per delivery).
- Math in cents: `driver_share = fee × 0.80` rounded HALF_DOWN to 2 dp; `pula_share = fee − driver_share`. They always sum to the fee. R25 → R20 / R5.
- `PayoutService` pays `driver_share` when an allocation exists; otherwise legacy full-fee behaviour (stock deliveries) is untouched. Payout idempotency key is unchanged.
- Restaurant's own riders (`SELLER_DELIVERY`): no `DeliveryRequest`, no allocation, no PULA share. Fee treatment follows the existing seller-delivery configuration. No new rider payout mechanism is invented.

---

## 7. Cash Flow

`BusinessCashFlowService` already reads COMPLETED `Order`s. Additions for food orders:
- **Food sales** = sum of item line totals including modifiers, excluding delivery fee.
- **Delivery fee via PULA Ride** is not restaurant sales. It is shown separately as a pass-through; the allocation table is the source for driver earnings and PULA income.
- **Seller-delivery fee** is restaurant delivery income.
- Manual food expenses use the existing expense entries.

---

## 8. Cancellation and refund rules

| Stage | Customer cancel | Outcome |
|-------|-----------------|---------|
| Before restaurant accepts | Yes | Full refund |
| Restaurant rejects or times out | n/a | Automatic full refund |
| Preparing | Restaurant discretion | Restaurant approves or declines; if approved, full refund |
| READY, delivery not yet accepted by a driver | Switch to collection or cancel | Fee refunded on switch; delivery cancelled in the same transaction |
| No driver found | Keep waiting / switch to collection / cancel | Customer chooses; full refund on cancel |
| Driver accepted, not picked up | Driver withdraws | Silent redispatch |
| Picked up | No automatic cancel | Support/dispute path |

Refunds use `REFUND_PENDING` → `REFUNDED` through `PaymentOrchestrator`. A failed refund stays `REFUND_PENDING` and is retried; it is never shown as refunded early. These are consumer-protection rules and should be reviewed against your terms of service before launch.

---

## 9. Canonical food matching

Deterministic, explainable, attribute-first. No fuzzy auto-linking.

1. Query is normalised (lowercase, `&`/`+`/`with`/`and` unified, filler words removed) and resolved through `canonical_food_aliases`; if no alias hits, fall back to text search over product names and descriptions.
2. A menu item is linked to a canonical food only by an explicit link (merchant confirms a suggestion, or admin maps it). Name similarity is used only to generate suggestions on the existing `canonical-suggestions` endpoint.
3. Comparison levels, returned with reasons:
   - **EXACT MATCH**: same canonical food, or same food_type + protein + starch + sides signature + portion class.
   - **SIMILAR MEAL**: same food_type, protein and starch, but sides or portion differ (reasons listed, e.g. "includes spinach").
   - **ALTERNATIVE**: same protein, different food_type. Shown in a separate list.
   - Different food_type with different protein, or any category mismatch (beef burger + chips vs beef stew + chips), is not shown as a match at all.
4. Missing attributes lower the level and are reported as "unknown" rather than assumed equal.
5. Results are sorted by a neutral key (distance when known, otherwise name), never labelled "cheapest", "best" or "winner". Sort controls are customer-selected.

The response per entry: price, portion, included sides, extras available, rating and review count (item and restaurant, PULA verified only), delivery fee, estimated time, collection availability, prep time, distance (if both sides have coordinates), availability now.

---

## 10. Reviews

- Eligibility checks stay as is (own order, COMPLETED, target belongs to order).
- For FOOD orders, duplicate rule becomes one review per (author, order, target), enforced by the unique index plus a catch for constraint violation. Retail rules are unchanged.
- Restaurant (BUSINESS) and item (PRODUCT) ratings are separate, using the existing `ReviewTargetType`.
- Overall rating drives aggregates. Sub-ratings are optional and averaged on read. `delivery_experience` is shown separately and is not part of the restaurant's food rating.
- Every order-based review is labelled "Verified PULA order".

---

## 11. API surface (follows existing `/api/market/...` and `/api/products` conventions)

Public/customer: `GET /api/market/food/search`, `GET /api/market/food/{canonicalId}/compare`, `GET /api/market/restaurants`, `GET /api/market/restaurants/{businessId}`, `GET /api/market/restaurants/{businessId}/menu`, `GET /api/products/{id}` (extended with food details and modifiers), `GET /api/reviews?targetType=PRODUCT&targetId=` (existing).

Merchant: menu categories CRUD, modifier groups CRUD, product food-details on the existing product create/update, branch CRUD, branch item availability, `POST /api/orders/{id}/accept`, `POST /api/orders/{id}/reject`, existing `PATCH /api/orders/{id}/status` for READY/COMPLETED.

Final paths are confirmed against the controllers during Phase 3.

---

## 12. AI

New `ActionType` values: `SEARCH_FOOD`, `SEARCH_RESTAURANTS`, `COMPARE_FOOD`, `VIEW_MENU` (read-only, no confirmation) and `ADD_FOOD_TO_CART` (confirmation required, mirrors `ADD_TO_CART`). `CREATE_FOOD_ORDER` is not an executable action: the AI hands off to cart and checkout, and the customer pays through the normal flow. The AI never creates a payment, picks a driver, or requests delivery. All data comes from the same backend services as the UI.

---

## 13. Frontend

- **Web**: `pages/market/` gets FoodHomePage, RestaurantPage (menu + modifier sheet), FoodComparePage, merchant MenuManagerPage, and a kitchen order queue added to the seller orders view. Routes registered in the existing router.
- **Mobile**: new screens under `app/market/food/`, entry point inside the existing Market tab. Every `router.push` target is checked against an existing file, and `npx tsc --noEmit` runs after each step.
- Shared rules stay on the backend; clients render what the API returns.

---

## 14. Security and concurrency checklist

Ownership checks on every merchant endpoint (business and branch must belong to the caller); order ownership on accept/reject/ready; modifier and branch IDs validated against the product's business (tenant isolation); backend-only price, total, availability and payment status; payment idempotency reused; unique constraints for review-per-order, delivery-per-order, allocation-per-delivery, payout-per-delivery; atomic capacity decrement; customer coordinates never persisted on orders and never returned to merchants.

---

## 15. Test plan

Mapped to the spec's section 31, each as a service-level test in the style of the existing suite (`DeliveryRequestServiceTest`, `PayoutServiceTest`, `PriceComparisonServiceTest`, `ReviewService` coverage to be added):
food business/menu/category/item/price/availability/modifiers/branch availability; canonical matching (exact, similar, alternative, burger-vs-stew negative, missing attributes); location filtering; reviews (completed-only, duplicates per order, item vs restaurant, rating math); ordering (modifier pricing, min/max rules, backend totals, collection vs delivery); delivery (auto-dispatch on READY, none for collection, duplicate READY event, eligibility, acceptance race, withdrawal, no-driver); payments (success, failure, duplicate, refund failure); allocation (80/20, rounding, unique, no allocation for seller riders); Cash Flow classification.

**Step 0 of Phase 3: get `./mvnw test` running to establish a baseline before touching payout/delivery code.**

---

## 16. Open business rules (need your answer before the affected step)

1. **Who sets the PULA Ride delivery fee?** There is no distance pricing in the codebase. Default in this design: a flat fee configured per restaurant/branch, snapshotted on the order. Needed before step 3.
2. **How do restaurants receive food proceeds?** Driver payouts and stock payouts exist; I did not find a seller payout path for retail market orders. I'll verify in Phase 3 and confirm with you whether food proceeds follow the existing market settlement or need a new mechanism (I will not invent one).
3. **Accept timeout and no-driver timeout** defaults (10 min and 20 min) are placeholders.
4. **Per-order review rule for retail** stays as is unless you want it aligned with food.

---

## 17. Phase 3 build order

0. Test baseline. 1. V25–V27 + domain + merchant APIs. 2. Ordering core (modifiers, delivery fee in total, accept/reject, timeout). 3. Delivery automation, allocation, payout change, Cash Flow. 4. V28 + canonical matching + search/compare. 5. V30 + reviews. 6. AI intents. Then web (Phase 4) and mobile (Phase 5).
