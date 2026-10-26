# Phase G.2 — Basket Comparison Report

Date: 2026-09-15

## 1. Scope

The brief's §9 "Compare My Basket": given a list of items and quantities,
show an estimated total per seller, which sellers carry the complete
basket, the cheapest complete-basket seller, and potential savings. This is
explicitly **not** §10's "Smart Basket Optimization" (delivery fees,
service fees, promotions, minimum-basket requirements, multiple
optimization objectives) — that's real future work with its own design
questions, not something to fold in here under the same phase.

## 2. What was added

- `BasketComparisonService` — groups EXACT_MATCH listings by business,
  sums `price × quantity` per requested canonical product, and reports:
  every store touched (`storeTotals`), the cheapest store carrying every
  requested item (`cheapestCompleteStore`, null if none does), and the gap
  between the cheapest and priciest complete-basket store
  (`potentialSavings`, null unless at least two stores are complete).
- Every response value is named `estimatedSubtotal`, and the service's own
  javadoc is explicit that it doesn't verify stock covers the requested
  quantity and doesn't model delivery/service fees — matching the brief's
  own instruction (§16) to use honest language like "estimated" rather than
  imply more precision than the data supports.
- `POST /api/baskets/compare` (`BasketComparisonController`), validated
  (`@NotEmpty`, max 50 items, `@Min(1)` quantity) and rejecting unknown
  canonical-product ids loudly rather than silently dropping them from
  every store's total.
- Web frontend: new `/market/compare-basket` page — search canonical
  products, add with a quantity, compare, see per-store totals with the
  cheapest complete basket highlighted and a savings figure. Linked from
  the MoMoMarket home page.
- Tests: `BasketComparisonServiceTest` — empty-basket rejection, cheapest
  store + savings calculation, partial-store handling (missing items
  listed, no `cheapestCompleteStore`/`potentialSavings` when nothing is
  complete), quantity multiplication.

## 3. A test bug caught before it shipped

The first draft of `BasketComparisonServiceTest` built a fresh `Business`
object per `listing()` call — meaning two products from "the same store"
(e.g. Shoprite milk and Shoprite bread) would actually get different random
UUIDs and be silently treated as two different stores by the grouping
logic, which groups by `businessId`. That would have made the "cheapest
complete store" test pass for the wrong reason (each store would only ever
have one item, so "complete" would trivially mean "has that one item").
Caught by re-reading the test against what it claims to verify before
finalizing it, not by running it (still can't run Java tests in this
sandbox) — fixed by sharing one `Business` instance across all of a given
store's listings in the test.

## 4. Regression sweep performed

Grepped every instantiation site of every DTO touched in this phase and the
two prior ones (`CompareBasketRequest`, `BasketItemRequest`,
`StoreBasketTotal`, `BasketComparisonResponse`, plus re-checking
`CreateBusinessRequest`/`UpdateBusinessRequest`/`BusinessResponse`/
`ProductResponse`/`CanonicalProductResponse` from Phases F.2 and G.1). All
consistent — no stragglers this time.

## 5. What was explicitly NOT done this phase

- Smart Basket Optimization (delivery/service fees, promotions, multiple
  objective preferences like `FASTEST`/`FEWEST_DELIVERIES`) — brief's §10,
  a distinct and larger piece of work.
- No UI affordance for "most convenient basket" — that needs a location/
  distance model that doesn't exist yet (brief's own §31, not yet built).

## 6. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean.
- Backend: **still not build-verified in this sandbox** (no Maven Central
  access). Given the regression caught in Phase G.1, please run
  `mvn clean test` locally before continuing to stack further phases on
  top of unverified Java changes.

## 7. Regression check against §35

Auth, Circle/Community Savings, MoMoRide, and Payment code paths untouched.
No existing endpoint's request/response shape changed in this phase — only
new files plus the DTO/service additions from Phase G.1 (already verified
consistent in §4 above).
