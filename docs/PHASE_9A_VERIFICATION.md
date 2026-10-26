# Phase 9A — Verification and Acceptance Gate: status and runbook

## Status: **NOT ACCEPTED. Verification could not be performed in the authoring environment.**
Re-inspected on this attempt: no Maven, no Docker/Podman, no PostgreSQL, no `javac` binary, and no network (Maven Central, npm,
PyPI and Ubuntu mirrors all unreachable). Nothing could be installed. Therefore every item below that needs those tools is
**NOT RUN**. No result in this document is inferred; the table is exactly what the gate script reports for this tree.

```
python3 backend-web/ci/verify_phase9a.py --backend backend-web/backend      # run here: exit code 1
```
| Required check | Result here |
|---|---|
| Maven compile / package | **NOT RUN** |
| LedgerEngineTest, LedgerOperationsAuditTest, JpaLedgerStoreRetryTest (under Maven/JUnit) | **NOT RUN** (earlier stand-in runs are *not* Maven results) |
| LedgerH2PersistenceTest · MigrationUpgradeH2Test | **NOT RUN** |
| LedgerPostgresPersistenceTest · MigrationUpgradePostgresTest | **NOT RUN** |
| LedgerPostgresGuardsTest (V41 on PostgreSQL) | **NOT RUN** |
| Clean install V40 · upgrade V39→V40 with historical rows, on PostgreSQL | **NOT RUN** |
| Full backend suite · web build · mobile tsc | **NOT RUN** |

## What this attempt added (no new features, no Phase 9B, no provider change)
1. **`ci/verify_phase9a.py`** reads real Surefire XML and reports PASS / FAIL / SKIPPED / NOT RUN per required class. It exits 0 only
   if every required class is PASS: skipped (e.g. Testcontainers without Docker), missing, zero-test, or too-few-test classes all fail
   the gate, and any failure anywhere in the full suite fails it. Its own 8 tests (`ci/test_verify_phase9a.py`) **PASS** (executed here).
2. **`ci/phase9a-verification.github-actions.yml`**: a copy-ready CI template (Docker-capable runner; YAML parses) that runs compile, tests,
   package, the gate, and the web/mobile checks. It is not installed anywhere.
3. **One defect fixed by desk review** (not by running): under V41 a bare `ledger_transactions` row with no postings cannot commit, but three
   inherited raw-SQL tests in `LedgerPostgresGuardsTest` inserted one and expected success. They are now overridden in the guards class with
   explicit "refused at commit" assertions (the same constraints stay proven by `LedgerPostgresPersistenceTest`, which runs without the guards).
   This is an unexecuted fix.

## How to run the gate (environment with JDK 17, Maven 3.9+, Docker, Node 22)
```
cd backend-web/backend
mvn -B -DskipTests compile
mvn -B -Dtest='LedgerEngineTest,LedgerOperationsAuditTest,JpaLedgerStoreRetryTest' test
mvn -B -Dtest='LedgerH2PersistenceTest,MigrationUpgradeH2Test' test
mvn -B -Dtest='LedgerPostgresPersistenceTest,MigrationUpgradePostgresTest,LedgerPostgresGuardsTest' test   # Docker required
mvn -B test && mvn -B -DskipTests package
python3 ../ci/verify_phase9a.py --backend . --markdown ../docs/PHASE_9A_GATE_RESULT.md
cd ../frontend && npm ci && npm run build && node --test tests/*.test.ts
cd ../../mobile/mobile && npm ci && npx tsc --noEmit && node --test tests/attemptKey.test.mjs
```
Use a disposable PostgreSQL only (Testcontainers creates one). Never run Flyway clean against a database that holds data.
**Do not enable V41 outside a disposable database until `LedgerPostgresGuardsTest` passes.** If V41 fails, fix it with a *new* forward
migration (e.g. `V42__…`) in `db/postgresql-guards`; never edit an applied migration.

## Where my desk review expects the first real failures (check in this order)
1. **Compilation** of code never built under Maven: `LedgerOperationsAuditTest` subclasses `AuditLogService` with `super(null)`; entities, repositories and JPQL were checked only against my own stand-ins.
2. **Spring context startup in `LedgerH2PersistenceTest`.** `@DataJpaTest` loads *every* entity and runs `ddl-auto=validate` against the whole schema, so a failure here may be an **existing** entity/schema mismatch unrelated to the ledger (the repo had no database test before). Read the first `SchemaManagementException` carefully before blaming V40. Repository JPQL (`sumIssued` entity theta-join, `coalesce(sum(..),0L)`, enum parameters) is parsed at startup.
3. **Hibernate type validation** of `@Enumerated(STRING)` / `Instant` columns against `VARCHAR` / `TIMESTAMP` on H2 and PostgreSQL.
4. **Concurrency tests on H2**: 16–24 threads against Hikari's default pool of 10 and H2 lock timeouts. If they time out, tune `spring.datasource.hikari.maximum-pool-size` / `LOCK_TIMEOUT` in the test properties (a test-config fix), but do not reduce thread counts or weaken assertions to pass.
5. **PostgreSQL V41**: the PL/pgSQL, `CREATE CONSTRAINT TRIGGER … EXECUTE FUNCTION`, `RAISE … USING CONSTRAINT`, and the exact exception type Spring surfaces for a *commit-time* trigger failure (the tests accept `RuntimeException` for that reason).
6. **Retry detection on real drivers**: confirm the PostgreSQL/H2 error text really contains `uq_ledger_idem` / `uq_ledger_single_reversal` (the duplicate-key concurrency test fails loudly if not).

## Failure log (fill in per failure; the brief's required evidence)
| # | Exact command | Exact error | Root cause | Smallest safe fix | Regression test added | Rerun result |
|---|---|---|---|---|---|---|
| | | | | | | |

Rules: do not weaken or delete tests, bypass authentication, or disable constraints to get green; a skipped PostgreSQL test is not a pass.

## Provisional limits (for your confirmation, not agreed policy)
Rolling **30-day** window for the R20,000 per-user demo-issuance cap is **my provisional choice**, configurable via
`app.ledger.limits.issuance-window-days` (default 30). Other provisional limits, all configurable under `app.ledger.limits.*`:
transfer R10,000, issuance R5,000 per request and R20,000 per user per window, withdrawal R10,000.

## Acceptance gate (all must be true before Phase 9A is accepted and Phase 9B may start)
- [ ] Maven compile and package succeed
- [ ] H2 persistence + migration tests pass
- [ ] PostgreSQL persistence, migration, concurrency tests **execute and pass** (0 skipped)
- [ ] V41 guard tests execute and pass on a disposable PostgreSQL
- [ ] Historical payment rows verified unchanged after V39→V40 **on PostgreSQL**
- [ ] Full backend suite, web build, mobile tsc pass
- [ ] `verify_phase9a.py` exits 0
- [ ] Remaining risks documented (see PHASE_9A_REPORT.md §7)
