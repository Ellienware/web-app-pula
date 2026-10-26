# PULA Food — settlement, refunds and merchant protection: completion report

PULA Services was NOT started.

## Read this first
1. **The backend you uploaded did not compile.** Two genuine errors, both byte-identical in your upload, both fixed here: `Product.java` had getters/setters for `menuCategory` but no field; `DeliveryRequestService.java:195` captured a reassigned local in a lambda. Earlier reports could not have caught this because Maven never ran.
2. **I still could not run Maven, Hibernate or a Spring context.** What I did instead (and what it does and does not prove) is listed below. Run `./mvnw test` locally before relying on any of it.
3. **Not done / not claimed:** Paystack test-mode payment and refund (api.paystack.co is blocked here, no keys), production Paystack readiness, a real payout rail, an admin UI.

## How verification was done
JDK 21 `javac` plus JUnit 5 and Mockito 2 from Ubuntu mirrors, with hand-written stubs for Spring/JPA/Spring Data annotations and a few interfaces. This type-checks the real project sources and runs Mockito-based unit tests against the real service classes over in-memory repositories. It does **not** validate JPQL, Hibernate mapping, Spring wiring/transactions/proxies, JSON binding or security config. The 6 files that wire Spring Security/JWT/bootstrap were excluded (stubbed JwtService).

## Results actually executed
| Check | Result |
|---|---|
| Backend main compile (all files except 6 Security/JWT/bootstrap wiring files), against stubs | **0 errors** (after the 2 fixes above) |
| Context-free backend tests, 51 classes, 530 tests | **522 passed, 8 failed** |
| - of the 8: `StockCatalogueImportServiceTest` x4, `FundingReadinessServiceTest` x2 | harness limit: Mockito 2 cannot mock Java records (project's Mockito 5 can); **not run for real** |
| - of the 8: `UssdSessionServiceTest` x1, `FoodDiscoveryServiceTest` x1 | **pre-existing**, fail identically on your pristine upload; unresolved, outside this phase |
| `MoMoRideServiceTest` | did not compile in the harness (stub gap); not run |
| 3 test classes needing a real Spring context (`@SpringBootTest`/MockMvc) | not run |
| Web `npm run build` (tsc + vite) | pass |
| Mobile `tsc --noEmit` and `expo export --platform android` | pass |
| V33 on PostgreSQL 16, clean (V1-V33) and on pre-existing data | pass; backfill correct |
| V33 on H2 2.2.224 (PostgreSQL mode), clean and on pre-existing data | pass (with the pre-existing V2/H2 workaround from last report) |
| Paystack test-mode payment / refund / webhook against Paystack | **NOT RUN** |
| Real payout rail | none exists |

New tests added (all executed and passing): `SettlementCalculatorTest` 7, `RefundMathTest` 7, `StatusMachinesTest` 7, `PaystackRefundClientTest` 10 (real HTTP client against a local fake Paystack), `OrderRefundServiceTest` 31, `FoodSettlementServiceTest` 30 (includes an 8-thread payout race), `FoodFinanceJourneyTest` 10 (three required journeys + extras), `PaystackWebhookControllerTest` +7, `BusinessCashFlowServiceTest` +2. The previous phase's tests (timer, expectedTotal, etc.) were also executed for the first time and pass.

## Files changed / added
Backend main: `Order` (+cancellation_cause, ready_at), `Product` (compile fix), `DeliveryRequestService` (compile fix, + `DeliveryPoolService` consent fix, `ReviewService` scale fix), `OrderService` (cause, readyAt, settlement hooks), `OrderRefundService` (rewritten), `AdminService.forceRefund` (now via refund service), `FoodOrderMaintenanceTask`, `PayoutOrchestrator` (+RESTAURANT_SETTLEMENT purpose), `PaystackGateway`/`HttpPaystackGateway`/`MockPaystackGateway`/`PaymentOrchestrator` (refund API), `PaystackWebhookController` (refund events), `OrderRepository` (retry query), `BusinessCashFlowService`/`CashFlowDtos`, `application.yml`. New: `CancellationCause`, `RefundStatus`, `SettlementStatus`, `SettlementType`, `PaymentRefund`, `RestaurantSettlement`, repositories, `SettlementProperties`, `FoodSettlementService`, `PaystackRefundClient`, `SettlementCalculator`, `RefundMath`, `FoodFinanceDtos`, `AdminFoodFinanceController`, migration **V33**. Web/mobile: cash-flow types and a settlements note; mobile checkout guard from last phase.

## Migration added
`V33__food_refunds_and_restaurant_settlements.sql` only. V25-V32 untouched. Adds `payment_refunds`, `restaurant_settlements`, two `orders` columns; backfills causes for ended orders and creates **ON_HOLD** settlements for already-COMPLETED food orders (so history is visible and nothing historic is paid automatically until an admin releases it).

## Status by area
- **Settlements:** implemented, unit/journey tested; payout via sandbox provider only (no real money).
- **Merchant protection:** implemented and tested; driver payout is zero because no delivery completes.
- **Paystack refunds:** implemented against Paystack's documented Refund API; HTTP client tested against a local fake; **never exercised against Paystack**.
- **Delivery/driver payouts:** unchanged; existing payout tests pass.

## Remaining defects and decisions needed
1. Commission and all compensation policies are placeholders (commission 0%) pending PULA's commercial decision.
2. `UssdSessionServiceTest` and `FoodDiscoveryServiceTest` failures are unexplained and pre-existing.
3. H2 dev profile cannot boot clean (V2 DROP INDEX), unchanged from last report.
4. No admin UI; no automatic clawback after a restaurant is paid; no-driver cancellation remains customer-triggered.
5. Fixed along the way (outside this phase, found by actually running tests): new delivery-pool drivers were added as ACTIVE instead of an invited PENDING (consent bug); "no reviews" average had scale 0 instead of 2; two stale test stubs.
