# Verification status (audit follow-up pass)

## Actually run
- Web: `npm ci && npm run build` (tsc + vite) - PASS.
- Mobile: `npm ci && npx tsc --noEmit` - PASS (after the rebrand and route/hook renames).
- Database: all 24 Flyway migrations (V1-V24) applied in order to a real PostgreSQL 16 - PASS, 56 tables, zero errors.
- Entity/schema cross-check (script substituting for Hibernate `ddl-auto=validate`, column-name level only):
  no missing columns. 4 apparent mismatches were @ElementCollection tables that exist.
- Java syntax check (javac) on the two files changed this pass - no syntax errors.

## NOT run (still unverified)
- `./mvnw test` - Maven Central is unreachable from this environment. NO backend test has been executed,
  including the newly added `driverWithARegisteredTaxiAlsoHoldsTaxiDriverCapability`.
- Real Paystack (test keys), real Stitch, any browser/device UI run, the hero journey end to end,
  taxi-payment and Community Savings regression.

## Changed this pass
- Mobile: app name "PULA"; camera/photo/mic/speech permission text -> PULA / PULA AI; splash background white;
  route `ask-momo` -> `ask-pula`; hooks renamed `useAskPulaVoice` / `useAskPulaImage`; comments updated.
  Left as-is on purpose (internal identifiers): URL scheme `momocircle`, bundle id, slug, token storage key,
  API enum values such as MOMORIDE_DELIVERY.
- Backend: `DriverCapability.TAXI_DRIVER` derived from a registered taxi; `DeliveryPoolService` now takes `TaxiRepository`.
  Dispatch behaviour unchanged.

---
## PULA Food (added after the sections above) — UNVERIFIED
Nothing in PULA Food has been compiled or run (no Maven/npm access). See `docs/PULA_FOOD.md` for scope, decisions, limitations and the manual
verification checklist. New backend tests (~163 methods) and the edits to `DeliveryRequestServiceTest`, `PayoutServiceTest`,
`BusinessCashFlowServiceTest`, `ConsumerPaystackCheckoutServiceTest` have never executed.


---
## Update — restaurant settlement, Paystack refunds, merchant protection (V33)
See `PULA_FOOD_SETTLEMENT_REFUNDS_REPORT.md` for the factual completion report and `FOOD_SETTLEMENTS_AND_REFUNDS.md` for the rules.
Verified offline with a JDK, JUnit and Mockito from Ubuntu mirrors and **stubs** for Spring/JPA annotations (no Spring context, no Hibernate, no Maven build). Maven Central and api.paystack.co are not reachable from the sandbox.
