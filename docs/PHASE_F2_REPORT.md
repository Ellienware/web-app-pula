# Phase F.2 — Seller Typing Report

Date: 2026-09-15

## 1. Scope

Per the ecosystem-evolution plan's phase table, this pass adds seller-type
differentiation (`BusinessType`: `FORMAL_RETAILER` / `LOCAL_BUSINESS` /
`INFORMAL_TRADER` / `SERVICE_PROVIDER`) and fulfilment-option capability
(`FulfilmentOption`: `CUSTOMER_PICKUP` / `SELLER_DELIVERY` /
`MOMORIDE_DELIVERY` / `SUPPORTED_PARTNER_DELIVERY`) to `Business`. Full
service-listing domain modeling and price-comparison work (Phases G.1/G.2)
are deliberately deferred — this phase only touches the seller model itself.

## 2. What was reused, not rebuilt

`Business` already had exactly one entity per owner, an `active` flag, and
an `@ElementCollection` pattern already proven for `operatingHours` — the
new `fulfilmentOptions` field follows that same established pattern (own
join table, same naming convention) rather than introducing a new one.
`BusinessService`/`BusinessController`/`BusinessRepository`'s existing
create/update/browse structure was extended in place; none of it was
rewritten.

## 3. What was added

- `domain/enums/BusinessType.java`, `domain/enums/FulfilmentOption.java` — new enums.
- `Business.java` — `businessType` (defaults to `LOCAL_BUSINESS`, a neutral
  non-stigmatizing default per the brief's own §24 instruction — existing
  rows get this automatically via the migration's column default) and
  `fulfilmentOptions` (`Set<FulfilmentOption>`, eager-fetched like
  `operatingHours`).
- `V8__business_type_fulfilment.sql` — adds `businesses.business_type`
  (`NOT NULL DEFAULT 'LOCAL_BUSINESS'`, so it's non-breaking for existing
  rows) and a new `business_fulfilment_options` join table, indexed on
  `business_id`, primary-keyed on `(business_id, fulfilment_option)` so the
  DB itself enforces the Set's no-duplicates semantics.
- `MarketDtos.CreateBusinessRequest` / `UpdateBusinessRequest` /
  `BusinessResponse` — both new fields added; `businessType` is optional on
  create (falls back to the entity default) and update (null = unchanged),
  matching every other optional field's existing convention in those DTOs.
- `BusinessRepository` — two new derived-query methods
  (`findByActiveTrueAndBusinessType`, `findByActiveTrueAndCategoryAndBusinessType`)
  following the existing `findByActiveTrueAndCategory` naming style.
- `BusinessService.browse` — gained a `businessType` parameter, dispatching
  to the right repository method by which filters are present (same
  branching shape as the pre-existing category-only logic, just extended).
- `BusinessController` — `GET /api/businesses` gained an optional
  `businessType` query param. No breaking change to the existing `category`
  param or response shape (only additive fields).
- Web frontend (`frontend/src/lib/types.ts`, `CreateBusinessModal.tsx`,
  `BusinessCard.tsx`, `index.css`): new `BusinessType`/`FulfilmentOption`
  TS types with **respectful, non-stigmatizing labels**
  (`INFORMAL_TRADER` displays as "Local Business", same as
  `LOCAL_BUSINESS` — per the brief's explicit instruction not to make
  "informal" a negative consumer-facing label) — a select + checkbox group
  added to the seller-signup modal, and a muted badge added to
  `BusinessCard` so the new field is actually visible somewhere, not just
  plumbed through silently.
- `BusinessServiceTest.java` — three new unit tests: default-type-on-create,
  explicit-type-and-fulfilment-on-create, and update changing both fields.

## 4. What was explicitly NOT done this phase

- No service-listing domain (`service_listings`/bookings) yet — the brief's
  own §12 instruction not to force services into the grocery `Product`
  model means that needs its own migration and entity, scoped for a later
  phase rather than rushed in alongside this one.
- No mobile (Expo) changes — the mobile app doesn't yet have its own
  seller-signup flow to extend; when it does, it should read from the same
  `businessType`/`fulfilmentOptions` fields added here.
- No MoMoRide delivery wiring yet — `MOMORIDE_DELIVERY` exists as a
  selectable fulfilment option but nothing consumes it yet; that's Phase
  H.1 per the plan.

## 5. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean, confirmed in
  this sandbox, both before and after the `BusinessCard`/`CreateBusinessModal`
  edits.
- Backend: **not build-verified in this sandbox** — no Maven Central network
  access here (same limitation noted in Phase D.1.1's own report). Please
  run `mvn clean test` locally before merging. Nothing in this phase touches
  payment/auth/AI logic; the highest-risk item to double-check locally is
  the new migration's DDL against a real Postgres instance (the
  `business_fulfilment_options` composite primary key in particular).

## 6. Regression check against §35

Auth, Circle/Community Savings, MoMoRide, AI assistant, and Payment code
paths were not touched in this phase — only `Business`-adjacent files
listed above. `GET /api/businesses`'s existing `category`-only callers
continue to work unchanged (new param is optional, appended, and
non-breaking).
