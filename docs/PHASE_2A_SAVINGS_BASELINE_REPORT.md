# Phase 2A — Community Savings characterisation baseline

Scope: tests only. No stokvel behaviour, no V34, no Savings production file changed (44 Savings-related production files
verified byte-identical to the uploaded project). Phase 2B has NOT been started.

## Tests added (98, all in `src/test/java/com/momocircle/savings/`)
`SavingsFixture` (real, unchanged Savings services over in-memory repositories; real sandbox payment provider, validation,
idempotency, activity), `CircleCharacterisationTest` 18, `InvitationCharacterisationTest` 12, `ContributionCharacterisationTest` 21,
`GoalCharacterisationTest` 10, `RecurringCharacterisationTest` 16, `ExpenseCharacterisationTest` 6, `SavingsAuthorisationCharacterisationTest` 10,
`SavingsAiBoundaryCharacterisationTest` 5.

## Actual results
- Savings baseline: 98 run, 98 passed, 0 failed, 0 skipped.
- Full context-free backend regression: 628 run, 620 passed, 8 failed (6 = harness Mockito-2 cannot mock records; 2 = pre-existing
  `UssdSessionServiceTest`/`FoodDiscoveryServiceTest`, identical on the untouched upload). Not run: MoMoRideServiceTest (harness stub gap) and
  3 classes needing a real Spring context.
- Mutation check (on a COPY of production code): breaking requireAdmin failed 12 tests, goal-circle check 4, due-ownership 1, amount validation 1. The tests can fail.
- Database: V1..V33 applied in order to clean PostgreSQL 16 and H2 (PostgreSQL mode, with the known V2 workaround); versions contiguous V1-V33, no V34.
  Full Savings data set inserted and read back on both; unique/FK/NOT NULL constraints reject bad data on PostgreSQL (duplicate membership H2 too).
  Entity-to-schema mapping check: 14 Savings-related entities, 0 mismatches (a script, not Hibernate).
- NOT VERIFIED: Maven build, the Flyway tool itself, Hibernate/JPA mapping and JPQL, Spring wiring, real transactions/rollback, controllers/HTTP layer, any web/mobile test.
