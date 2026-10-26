# Phase H.1 — MoMoRide Delivery Integration Report

Date: 2026-09-16

## 1. Scope

Brief §14: connect MoMoMarket orders to MoMoRide drivers for delivery,
keeping MoMoRide "a separate domain module" — not tightly coupled into
marketplace logic, communicating through explicit interfaces/services.

## 2. Design: the one deliberate touchpoint between the two domains

`DeliveryRequestService` is now the only class in the codebase that
depends on both a MoMoMarket concept (`Order`, via `OrderService`) and a
MoMoRide concept (`Driver`, via `DriverRepository`). Nothing in the
`momoride` package knows MoMoMarket exists, and nothing else in MoMoMarket
knows MoMoRide exists — `OrderService` gained two small, narrowly-scoped
package-private methods (`requireOrderEntity`, `markCompletedByDelivery`)
specifically so `DeliveryRequestService` never needs to bypass ownership
checks improperly or duplicate Order's own state-transition whitelist; it
calls into `OrderService`'s existing authority rather than reaching around
it. `DeliveryRequest` is its own entity with its own `DeliveryStatus`
lifecycle (`REQUESTED → ACCEPTED → PICKED_UP → DELIVERED`, or
`→ CANCELLED`), deliberately not reusing `OrderStatus` — a delivery's
driver-facing states aren't the same shape as an order's seller-facing
ones, and conflating them would have pushed driver-specific states into
`OrderStatus`, which the rest of the app (including the AI assistant's
`OrderStatus`-driven notification logic) doesn't need to know about.

## 3. A real architectural lesson applied, not just discovered

Before writing the accept-a-delivery logic, I read `RideSeatConcurrencyService`
to check how this codebase already solves the identical problem (two
actors racing for one limited resource) — and found it isn't solved with a
try/catch around a plain `.save()` inside the main service. It's a
**separate Spring bean**, because `@Transactional(REQUIRES_NEW)` only
takes effect through Spring's proxy and a same-class method call bypasses
that proxy entirely; and it uses `saveAndFlush()`, not `save()`, so the
`@Version` check actually happens inside that isolated transaction rather
than being deferred to whenever the caller's own transaction happens to
commit.

My first draft of `DeliveryRequestService.accept()` did neither of these —
it called `deliveryRequestRepository.save()` directly, wrapped in a
try/catch, inside the same `@Transactional` method. That would very likely
not have actually caught the race condition it claimed to handle. Caught
by reading the existing pattern before trusting my own version of it, not
by a test failure — this class of bug (looks structurally right, silently
wrong at runtime) is exactly the kind a compiler can't catch and a
superficial code review can miss. Fixed by extracting
`DeliveryAcceptanceConcurrencyService`, a new bean mirroring
`RideSeatConcurrencyService` exactly: `REQUIRES_NEW` + `saveAndFlush()` +
a caller-side retry loop.

## 4. A second bug caught in the same pass

The concurrency-safe `accept()` sets the delivery's driver via an id-only
reference object (`new Driver(); driverRef.setId(driverId)`) rather than a
fully loaded entity, specifically to avoid a second repository dependency
in that isolated bean. That means the `DeliveryRequest` object it returns
has an unhydrated `driver.user` — calling `.getDriver().getUser().getFullName()`
on it to build the response DTO would NPE. Caught by tracing the data flow
after writing the concurrency service, before wiring it into
`DeliveryRequestService.accept()`: fixed by re-fetching the delivery fresh
from the main repository after the concurrency-safe write succeeds, rather
than using the entity the concurrency call returned directly.

## 5. What was added

- `DeliveryStatus` enum with its own transition whitelist (mirroring
  `OrderStatus.canTransitionTo`).
- `DeliveryRequest` entity (1:1 with `Order`, nullable `Driver` until
  accepted, `@Version` for the concurrency protection above).
- `Order` gained nullable `fulfilmentMethod`/`deliveryAddress` — reusing
  the existing `FulfilmentOption` enum from Phase F.2 rather than a new one.
- `V14__delivery_requests.sql` — both new `orders` columns nullable
  (non-breaking), new `delivery_requests` table.
- `OrderService.setFulfilment` (seller-only, validates the business
  actually supports the chosen method and that a delivery address is
  present when required) and the two package-private helpers described in
  §2.
- `DeliveryRequestService`: `request` (seller-only), `browseOpen`
  (**driver-only** — see §6), `myDeliveries`, `accept`, `markPickedUp`,
  `markDelivered` (also completes the underlying order), `cancel`
  (seller or assigned driver, before pickup), `getForOrder` (buyer or
  seller can check status).
- `DeliveryAcceptanceConcurrencyService` (§3).
- New `DeliveryController` (`/api/deliveries/*`) and two additions to the
  existing `OrderController` (`PATCH /api/orders/{id}/fulfilment`,
  `GET /api/orders/{id}/delivery`).
- Both existing `OrderResponse` construction sites found and updated
  (`OrderService` and, critically, `AdminService` — found only by grepping
  the whole tree, exactly the discipline that's caught every similar miss
  since Phase G.1).
- Web frontend: `OrderDetailPage` gained a seller-side fulfilment picker
  (choose `CUSTOMER_PICKUP`/`SELLER_DELIVERY`/`MOMORIDE_DELIVERY` + address)
  and, once `MOMORIDE_DELIVERY` is set, a "Request MoMoRide delivery"
  button and a live delivery-status display for both buyer and seller.
- Tests: `DeliveryRequestServiceTest` (ownership, fulfilment validation,
  duplicate-request rejection, status-transition guard, the underlying
  order actually completing on delivery, buyer/seller-only visibility) and
  `DeliveryAcceptanceConcurrencyServiceTest` — built to the same rigor as
  `RideSeatConcurrencyServiceTest`, including a real multi-threaded stress
  test (8 threads racing for one delivery, `@RepeatedTest(5)`), not just a
  mocked-into-passing unit test.

## 6. A privacy gap caught before shipping

The first draft of `browseOpen` (drivers browsing available delivery jobs)
had no check that the caller actually has a driver profile — any
authenticated user could have called it. Unlike a Ride's origin/destination
(already effectively public once a driver posts it), a delivery's
`dropoffLocation` is a real customer's home address. Fixed by requiring a
driver profile, the same way `myDeliveries` already did.

## 7. What was explicitly NOT done this phase

- **No driver-facing UI** — the backend fully supports a driver browsing
  open jobs, accepting, and marking picked-up/delivered, but no frontend
  page was built for it this pass. This is real, substantial scope
  deliberately cut given time: a driver dashboard is its own UI surface,
  not a small addition. `/api/deliveries/open`, `/accept`,
  `/picked-up`, `/delivered` are all implemented and tested at the service
  layer, just not wired into any page yet.
- No delivery fee estimation — `estimatedFee` exists on the entity/DTO but
  is always null; nothing calculates it. Left explicitly null rather than
  fabricated, per the brief's own instruction never to present an invented
  figure as real.
- No checkout-time fulfilment selection — a buyer can't choose fulfilment
  when placing an order; only the seller can set it afterward via the new
  endpoint. Collecting this at checkout is a reasonable follow-up once
  there's a real checkout-flow redesign, not squeezed into this phase.
- Mobile app untouched — this phase is backend + web frontend only, same
  scope boundary as every phase since F.1's currency fix.

## 8. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean, including after
  the fulfilment-picker addition.
- Backend: **not build-verified in this sandbox.** This phase's two most
  important properties — the concurrency-safety of delivery acceptance and
  the correctness of the `OrderStatus`/`DeliveryStatus` transition guards —
  are exactly the kind of thing a compile-only check can't confirm. Please
  run `mvn clean test`, and note that
  `DeliveryAcceptanceConcurrencyServiceTest` includes `@RepeatedTest(5)`
  with real threads, so it will take a little longer than the rest of the
  suite.

## 9. Regression sweep performed

- Grepped every `new OrderResponse(` and `new DeliveryRequestResponse(`
  site — both `OrderResponse` sites (`OrderService`, `AdminService`)
  updated for the two new fields.
- Grepped `new OrderService(`/`new DeliveryRequestService(` for any
  positional-constructor test call sites — none found (no existing test
  constructs either directly).
- Brace/paren balance checked across all 11 touched/new files.
- Re-verified `Business.getFulfilmentOptions()`'s actual return type
  (`Set<FulfilmentOption>`) against how it's used in the new
  `OrderService.setFulfilment` and test setup, rather than assuming from
  memory of writing it in Phase F.2.

## 10. Regression check against §35

Circle/Community Savings and Payment code paths untouched. MoMoRide's
existing Ride/RideBooking/TaxiPaymentRequest flows untouched — confirmed
by the fact that `DeliveryRequestService` only imports `Driver` and
`DriverRepository` from the `momoride` package, nothing else. AI assistant
untouched this phase (delivery-related intents are a reasonable J-phase
follow-up, not attempted here). `Order`'s existing fields/behavior
unchanged — the two new columns are nullable and additive only.
