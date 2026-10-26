# Phase 4 - Services marketplace

## Verification status (read first)
| Check | Result |
|---|---|
| `mvn test`, Spring context, Flyway V36 on a real DB, Hibernate `validate`, **JPQL queries** (incl. the discovery search query) | **NOT VERIFIED** (no Maven, network or database in the build environment) |
| Existing suites (Phase 2A savings, Food, Stock, Order, Review tests) re-run after the edits to `ReviewService`, `Review`, `StockPaymentService`, `SaveToBuyService` | **NOT VERIFIED** (need Mockito). No exhaustive `switch` over the extended enums exists outside the new code (searched) |
| New and edited Java compiled against the real source tree (Spring/JPA annotations stubbed) | 0 errors |
| Executable tests (JUnit shim + `Proxy` in-memory repositories, real service classes), Phases 2B + 3 + 4 | **126 run, 126 passed, 0 failed, 0 skipped** (Phase 4: 20 workflow + 15 pure-logic + 2 new Save-to-Buy) |
| Mutation check: removed fixed-price tamper check, quote-accept ownership check, paid-before-review rule, status-skip rule, stock-priced materials | each caught by the tests; sources restored |
| Web TS (`servicesTypes/Api`, saveToBuy types) | `tsc` exit 0 |
| Web/mobile TSX | no `node_modules`; module-shim check only (found and I fixed a missing icon import). Not built or run |
Executed tests ran under a throw-away JUnit shim, not Surefire. **The real payment webhook path and the real Paystack call were not run**: payment was exercised through the `ServicePaymentGateway` seam with a fake, and `confirmServiceBooking` in `StockPaymentService` was compiled but not executed.

## Audit findings (actual code)
- Capabilities are an enum on `Business`, **self-assigned by the owner** (not admin-granted). I added `CAN_PROVIDE_SERVICES`; trust comes from the separate admin-controlled provider verification, suspension and listing hide.
- `isService` exists only as a Product flag; it was not used. `BusinessType.SERVICE_PROVIDER` already existed (a business *kind*, unrelated to the capability).
- There is **no file-upload infrastructure**: images are URL strings. So Services accepts https links only (no upload endpoint = no upload attack surface).
- Reviews: one `Review` table (target BUSINESS/PRODUCT, `order_id` NOT NULL, unique `dedupe_key`, aggregates on Business/Product). Extended, not duplicated.
- Payments: `PaymentOrchestrator` + `Payment` + `StockPaymentService.confirmByReference`, which already routes by payment id to stock or retail orders after provider verification. Services adds a third branch.
- Stock: `WholesaleProduct`, `StockOrder` per supplier, `StockOrderService` checkout. Reused for materials; delivery/fulfilment untouched (including PULA Ride self-acceptance).
- Existing generic admin pattern: `CurrentUserService.requireAdmin()`; audit: `AuditLogService`; rate limits: ad-hoc in-memory limiters.
- Latest migration was V35, so this is V36.

## Reused vs new
Reused: `Business` (provider identity, contact, rating), capability enum, `Payment`/`PaymentOrchestrator`/webhook verification, `Review`/`ReviewService` (+aggregates), `WholesaleProduct`/`StockOrder`, Save-to-Buy hooks, `NotificationService`, `AuditLogService`, `FinancialValidationService` conventions, admin guard.
New: `service_categories` (admin-managed, seeded), `service_provider_profiles` (+areas, portfolio), `service_listings` (+areas, images), `service_requests`, `service_quotes`, `service_bookings`, `service_material_lines`; columns `reviews.service_booking_id` and nullable `reviews.order_id`. **No** payment provider, cart, checkout, wallet, ledger, review table, supplier or delivery system.

## Flow
Find (search/filter/compare/provider profile) -> Request (draft or submit) -> Quote (provider) -> Accept / Decline / Ask a question (customer) -> Booking -> Materials -> Job (start, complete) -> Payment -> Review.
- **Quote totals are computed only by the server** (`ServiceQuoteCalculator`, BigDecimal 2 dp): FIXED labour must equal the listed price; HOURLY labour = listed rate x hours (provider gives hours, not labour); STARTING_FROM cannot go below the listed price; QUOTE_REQUIRED takes provider labour. Materials = sum of provider-supplied PULA Stock lines priced from `WholesaleProduct` at the time. Delivery/other are provider inputs; the total is stored immutably (`updatable=false`) and copied into the booking when accepted. The customer sends no amount anywhere, including payment.
- Status changes go through `ServiceLifecycle`; skipping steps is refused.
- **Timing choice:** the brief lists "Completion -> Payment -> Review", so payment is due **after** the provider marks the job complete (Phase 5's example lists payment before completion; I followed Phase 4). Consequence: the provider carries payment risk until completion; PULA holds no money either way.
- Materials: lines point at real PULA Stock products, are priced by the server, editable only before a quote is live, and each party can add only what they supply. The supplying party orders through the **existing** stock checkout, then links that `StockOrder`; the server checks it is their own, not cancelled, contains the product in enough quantity, and is not linked to another service request.
- Payment: `POST /bookings/{id}/pay` -> `ServicePaymentGateway` (thin adapter over `PaymentOrchestrator.startMarketCheckout`) -> `Payment` (purpose MARKET_PAYMENT, reference `PULA-SV-{booking}-{attempt}`). A second tap resumes the same checkout; a failed payment can be retried as attempt n+1; confirmation arrives through the existing provider-verified `confirmByReference` path.
- Save-to-Buy: new `SERVICE` vertical and `SERVICE_BOOKING` order kind; pay accepts an optional `saveToBuyRequestId` and uses the same validate/attach/paid/failed hooks (stokvel purchases cannot exceed the approved amount).
- Reviews: only for a COMPLETED and PAID booking, by the customer, once per booking per target; rates the provider (Business aggregate) and optionally the listing; stored in the existing `reviews` table tied to the booking.

## APIs
Public/customer: `GET /api/services/categories|search|listings/{id}|compare|compare/insights|providers/{id}`; `POST/GET /api/services/requests`, `.../{id}/submit|cancel`, `.../quotes/{qid}/accept|reject|clarify`, `GET .../quotes/{qid}/explain`, materials `GET/POST/DELETE .../materials`, `PUT .../materials/{line}/stock-order`; `GET /api/services/bookings[/{id}]`, `POST .../cancel|pay|review`.
Provider: `/api/services/provider/` `profile` (GET/PUT), `verification-request`, `listings` (CRUD), `requests` (inbox), `requests/{id}/decline|quotes|materials`, `bookings`, `bookings/{id}/start|complete`.
Admin (`/api/admin/services`, ADMIN only): categories CRUD, pending providers, verification, suspension, listing visibility, read-only bookings.

## Security
Tenant isolation and IDOR: requests, quotes, bookings and materials are party-checked; non-parties and other providers get 404 (no existence leak); listing/booking lookups by (id AND business). Provider actions need ownership of the business and a non-suspended profile; admin-only moderation; there is no admin endpoint that changes a booking, quote or money. Price tampering and status tampering are refused (tests). Rate limiting (`ServiceRateLimiter`: 10 requests/h per customer, 30 quotes/h per provider, 60 material edits/h) with 429. Audit trail through `AuditLogService` for catalogue, listing, request, quote, booking, material, payment and review events. Input hygiene: areas normalised, images https-only, text lengths, no HTML-ish URLs.

## Admin
Categories (create/rename/deactivate, two levels), provider verification and suspension, listing hide, read-only bookings (web page `/admin/services`, no nav link; the server returns 403 to non-admins).

## Screens
Web: `/services` (discover, filters, compare, request, requests with quote accept/decline/ask/explain, bookings with pay and review), `/services/provider` (profile, listings, inbox + quote form, jobs), `/admin/services`; Save-to-Buy page gains the Service vertical; payment screen shows the Save-to-Buy banner. Mobile: `app/services/index.tsx` (customer), `app/services/provider.tsx` (requests, quoting, jobs), entry button on the Market tab, "Provide services" in the seller capability chooser, Service in Save-to-Buy.

## Not done / limitations
- **Phase 5 (settlement) was not started**: its own precondition (earlier phases "operational" and "relevant tests actually run") is not met; nothing has run under Maven, and the real payment/webhook path is unverified.
- JPQL (notably `ServiceListingRepository.search` with `member of` on element collections), V36 (incl. the seed `INSERT ... FROM (VALUES ...)` and `ALTER ... DROP NOT NULL`) and entity mapping are unverified against a database.
- Provider listing/profile management on mobile is not built (web only); mobile has the request inbox, quoting and jobs.
- No in-app chat; clarification is one question per quote.
- Provider-supplied materials are only priced from PULA Stock lines; materials bought elsewhere go in the "other" amount.
- A customer needs a business to buy materials through PULA Stock (stock checkout is business-based); otherwise they cannot use the customer-supplies flow through PULA Stock.
- A review through Services counts as the author's review of that business, so the existing retail one-review-per-business rule would block a later retail review of the same business by that customer.
- Payment is after completion (see above): an unpaid completed job is visible to the provider and to admins, but there is no dispute workflow beyond admin visibility, suspension and listing hide.
- AI: read-only `explain quote` and `compare` endpoints; `AssistantService` is not wired to them; a test asserts no assistant action can quote, book, order materials or pay.
- Attachments on requests: not supported (no upload infrastructure); portfolio/listing images are https links.
- Admin disputes: visibility only.
