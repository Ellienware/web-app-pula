# Phase G.1 — Canonical Products & Price Comparison Report

Date: 2026-09-15

## 1. Scope

Per the plan: split the "one flat Product" model into a shared
`CanonicalProduct` catalogue plus each seller's existing `Product` acting as
the "retailer product," a `MatchClassification` marking which listings are
directly comparable, and a price-comparison endpoint returning every
EXACT_MATCH seller's price for a canonical product, cheapest first — the
brief's "Full Cream Milk 2L: Shoprite R32.99, Local Shop R31.00…" example.

## 2. Design decision: no automatic fuzzy matching

The brief distinguishes `EXACT_MATCH` / `SIMILAR_ALTERNATIVE` /
`UNIQUE_PRODUCT` and says only `EXACT_MATCH` should be shown as a direct
comparison. This phase implements matching as **explicit, seller-initiated
linking** (`PUT /api/products/{id}/canonical-link`), not name-similarity
heuristics or NLP matching. Reasoning, documented in `MatchClassification`'s
javadoc: this is a financial-trust surface — a wrong automatic match would
show a customer a false "this is cheaper elsewhere" comparison, which is
worse than showing no comparison at all. `SIMILAR_ALTERNATIVE` is defined
but not yet assigned by anything — reserved for a future algorithmic pass
once there's real usage data to design and evaluate it against, rather than
shipping unverified matching logic now.

## 3. What was added

- `MatchClassification` enum, `CanonicalProduct` entity, `CanonicalProductRepository`.
- `Product` gained a nullable `canonicalProduct` FK and a `matchClassification`
  column (`NOT NULL DEFAULT 'UNIQUE_PRODUCT'`) — non-breaking for existing rows.
- `V9__canonical_products.sql` — new table + two new columns on `products`.
  Uses a plain `UNIQUE` index on `barcode` rather than a Postgres-specific
  partial index, since ANSI SQL already treats every `NULL` as distinct —
  simpler and more portable than the alternative.
- `ProductRepository.findExactMatchesForCanonicalProduct` — the query the
  comparison engine reads from.
- `CanonicalProductService` (search/create — any authenticated user can
  create one; search-before-create in the UI is the only de-dup mechanism
  right now, documented as an intentional, revisit-able limitation).
- `PriceComparisonService` — pure read: fetches EXACT_MATCH listings, sorts
  by price, and marks each as `available` (out-of-stock or archived listings
  are shown, not hidden, so a comparison doesn't silently disappear).
- `ProductService.linkCanonicalProduct` — the only place `matchClassification`
  is ever set, always derived (`EXACT_MATCH` iff linked), never independently
  settable, so the "EXACT_MATCH always means a real seller-confirmed link"
  invariant can't drift.
- New `CanonicalProductController` (`/api/canonical-products`, search/create/get,
  plus `/{id}/comparison`) and a new `PUT /api/products/{id}/canonical-link`
  on the existing `ProductController`.
- Web frontend: `CanonicalProduct`/`PriceComparison` TS types, and a new
  `PriceComparisonCard` rendered on `ProductDetailPage` whenever a product
  is linked and has more than one seller to compare against.
- Tests: `CanonicalProductServiceTest` (create, barcode-dedupe-on-create),
  `PriceComparisonServiceTest` (sort order, out-of-stock still shown as
  unavailable rather than hidden, services always available regardless of
  stock count).

## 4. Regression caught and fixed during this phase

Grepping for every call site of the DTOs touched in this *and the prior*
phase (per the plan's own "grep before landing" discipline) found that
`AssistantService`'s `CREATE_BUSINESS` handler still built
`CreateBusinessRequest` with the old 8-argument shape from before Phase F.2
added `businessType`/`fulfilmentOptions` — a compile-breaking regression
that Phase F.2's own verification pass missed (its `npm run build` check
only covers the frontend; there's no way to compile-check the Java side in
this sandbox). Fixed here: the AI-driven business creation now passes
`null` for `businessType` (the seller sets/refines it later from their
dashboard) and the new `fulfilmentOptions` slot.

**This is exactly the class of regression the plan flagged as a risk** and
is a concrete argument for running a real `mvn compile` locally after each
phase, not just at the end — please do that before continuing to trust that
these Java changes compile.

Also fixed in the same pass: `CartService.toItemResponse` built a
`ProductResponse` positionally and needed the two new fields
(`canonicalProductId`, `matchClassification`) added — caught by the same
grep sweep, not missed.

## 5. What was explicitly NOT done this phase

- Basket comparison ("Compare My Basket") — next phase (G.2), builds on
  this one's canonical-product linkage.
- No UI for sellers to actually search/link a canonical product yet
  (backend endpoints exist; the seller dashboard doesn't have the picker
  UI yet) — the consumer-facing comparison display was prioritized since
  it's the direct value described in the brief's own example.
- No cross-currency handling — noted in `PriceComparisonService`'s javadoc
  as a forward-looking concern, not an active bug while this deployment
  supports only ZAR.

## 6. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean.
- Backend: **not build-verified in this sandbox** (no Maven Central
  access). The regression described in §4 is proof this matters — please
  run `mvn clean test` locally before merging, not just before the final
  phase.

## 7. Regression check against §35

Auth, Circle/Community Savings, MoMoRide, and Payment code paths untouched.
AI assistant's `CREATE_PRODUCT` handler untouched (its DTO wasn't changed
this phase). `CREATE_BUSINESS` handler was touched to fix the regression in
§4 — behaviorally equivalent to before, just compiling again.
