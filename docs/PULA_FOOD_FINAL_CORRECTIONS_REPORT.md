# PULA Food — final corrections report

Date: 2026-10-05. Scope: the two backend corrections, plus verification. PULA Services was NOT started.

## 1. Files changed

Backend (`backend/src/main`):
- `service/FoodCheckoutService.java` — `requireExpectedTotal` now rejects a missing total (400) and a different total (409); numeric compare, so 85 == 85.00.
- `dto/MarketDtos.java` — `expectedTotal` javadoc only (required for food, ignored for non-food).
- `domain/DeliveryRequest.java` — new `driverSearchStartedAt` field.
- `repository/DeliveryRequestRepository.java` — unclaimed-delivery query uses `driverSearchStartedAt`, not `updatedAt`.
- `service/FoodDeliveryService.java` — timer set at dispatch, reset on driver withdrawal, untouched by the notification; cancellation window counts from it; cancel flushes so a racing driver acceptance (`@Version`) turns into a 409 before any refund starts.
- `resources/db/migration/V32__food_delivery_driver_search_timer.sql` — NEW.

Backend tests (`backend/src/test`): `FoodCheckoutServiceTest` (edited), `FoodDeliveryServiceTest` (edited), `DeliveryAcceptanceConcurrencyServiceTest` (+2), `FoodExpectedTotalCheckoutWiringTest` (NEW, 5 tests).

Mobile: `app/market/food/checkout.tsx` — refuses to place an order without a reviewed quote (previously `quote?.total` could be undefined). Web: no change needed (it already sends `expectedTotal: quote.total`).

## 2. Database migrations added
- `V32` only. V25–V31 are byte-identical to the originals (checked by md5). Nothing renumbered or deleted.
- V32 adds nullable `delivery_requests.driver_search_started_at` and backfills open (REQUESTED) order deliveries from `COALESCE(updated_at, created_at)`, i.e. exactly the waiting time the old logic was using. Accepted/stock deliveries are not touched.

## 3. Verification actually executed

| Check | Result |
|---|---|
| Web `npm ci` + `npm run build` (tsc + vite) | PASS |
| Mobile `npm ci` + `npx tsc --noEmit` | PASS (also after the checkout.tsx edit) |
| Mobile `npx expo export --platform android` (Metro JS bundle) | PASS |
| PostgreSQL 16, clean DB, V1–V32 in order | PASS (74 tables) |
| PostgreSQL 16, DB built at V31 with pre-existing deliveries, then V32 | PASS; open food delivery backfilled, accepted + stock rows untouched |
| H2 2.2.224 (`MODE=PostgreSQL`), V1–V32 | PASS **only** with V2's `DROP INDEX` line removed in a temp copy (see defects) |
| H2, DB built at V31 with seeded rows, then V32 | PASS, same backfill result |
| Syntax parse (javalang) of the 8 changed/new Java files that it can parse | PASS |

## 4. NOT executed — do not treat as passed
- **Maven compile and all backend tests** (including every test added here). Maven Central is blocked in this sandbox and `mvn` is not installed. I could not compile against Spring, so the Java changes have been reviewed and syntax-parsed but never compiled. `MarketDtos.java` cannot be parsed by javalang (it fails the same way on the untouched original), so that file's syntax was checked by diff only (the change is comment-only).
- Native mobile build (Gradle/Xcode), `expo-doctor` (needs network), any device/browser run.
- Real Paystack test mode (no keys, and the Paystack API is not reachable from the sandbox).
- The full end-to-end journey in item 7 and the negative cases (refund on rejection/timeout, duplicate webhook, tenant isolation, etc.). These are covered by existing unit tests, which have also never been run. Run `./mvnw test` locally first.

## 5. Payment status
Configuration read from `application.yml`: defaults are `PAYSTACK_MODE=mock`, `MARKET_CHECKOUT=legacy`, `PAYMENT_PROVIDER=sandbox`. Mock mode makes no external call and moves no money. Code review shows: the webhook is public but HMAC-SHA512 signed and returns 404 unless mode is `paystack` with a key; bodies are de-duplicated by SHA-256; the payload is only a hint, because `confirmByReference` re-verifies with the provider, checks amount and currency, and is a no-op unless the payment is still PROCESSING. A mismatch is flagged for review rather than marked paid; a failed payment cancels the order. **Production Paystack readiness is NOT claimed**: the real provider flow (initiate, hosted checkout, webhook, verify, refund) has not been run.

## 6. Delivery status
Timer rules implemented as specified (dispatch sets it; notification does not move it; withdrawal resets it and clears the notification marker; cancellation counts from it). Driver-acceptance-after-cancellation is rejected by the existing status check plus the `@Version` lock, and is now unit-tested. Note the stub-based concurrency tests do not exercise real Spring transactions.

## 7. Known limitations / remaining defects
1. **H2 dev profile cannot boot from a clean database.** V2 runs `DROP INDEX IF EXISTS idx_order_payment` right after adding the FK; H2 2.2.224 refuses because the index belongs to that FK. PostgreSQL is fine. This predates this task. I did not edit V2 (changing an applied migration changes its Flyway checksum on existing databases). Options: run dev on PostgreSQL, or fix V2 deliberately and `flyway repair` where needed.
2. Rows created before V32 that are open food deliveries get an approximate timer (their last update); open deliveries with a NULL timer would never get the no-driver notification, but the backfill prevents that for existing data.
3. Web/mobile fall back to a plain `{businessId}` checkout if the restaurant lookup fails; the backend now rejects that for food with a clear 400 instead of charging without a reviewed total.
4. Partial (delivery-fee-only) refunds are still unsupported (pre-existing).
