# PULA Phase 9A — Simulated Banking Adapter and Shared Ledger: Database-Backed Persistence

## Verdict: IMPLEMENTED, NOT VERIFIED. Acceptance criteria are NOT yet met. Do not start Phase 9B.

All persistence code, the V40 migration, the PostgreSQL guards, and the integration tests are written. **None of it has been
compiled under Maven, booted under Spring, or run against H2 or PostgreSQL**, because this environment has no Maven, no Docker,
no PostgreSQL and no network. The brief requires "migrations verified against PostgreSQL"; that is **NOT RUN**. What *was*
executed is listed exactly in §4. Evidence key: **PASS** executed and observed · **FAIL** executed and failed · **NOT RUN**
not executable here · **STAND-IN** executed against my own minimal stand-ins for Spring/JUnit APIs (proves my code is
self-consistent with my understanding of those APIs, **not** that it builds or works).

Corrections to my earlier statements: in Phase 8 I said three existing test files use a real Spring context. That was wrong:
those files only *mention* `@SpringBootTest` in comments. The repository had **no** Spring-context or database test before
this phase; the new tests are the first.

## 1. Files

### Added (backend)
| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V40__shared_ledger.sql` | Forward-only migration (§2) |
| `src/main/resources/db/postgresql-guards/V41__ledger_postgresql_guards.sql` | PostgreSQL-only triggers, **opt-in, not executed** (§2) |
| `…/domain/ledger/LedgerAccountEntity`, `LedgerTransactionEntity`, `LedgerPostingEntity` | JPA entities mapped to V40 (postings are `@Immutable`) |
| `…/repository/ledger/LedgerAccountRepository`, `LedgerTransactionRepository`, `LedgerPostingRepository` | Spring Data repositories (explicit JPQL; postings repository is read-only by construction) |
| `…/service/ledger/JpaLedgerStore` | The `LedgerStore` implementation: ordered row locking, bounded retries, error translation |
| `…/service/ledger/LedgerAccountService` | Race-safe wallet / system-account provisioning |
| `…/service/ledger/LedgerReconciliationService` | Runs `Reconciler` over a consistent DB snapshot (service-level only; no HTTP endpoint) |
| `…/service/ledger/LedgerOperationsService` | Facade over the engine that writes `audit_logs` entries |
| `…/service/ledger/LedgerConfig`, `LedgerMapper` | Bean wiring with configurable limits; entity→domain mapping |
| `src/test/…/service/ledger/AbstractLedgerPersistenceTest` (+ `LedgerH2PersistenceTest`, `LedgerPostgresPersistenceTest`, `LedgerPostgresGuardsTest`) | Real-DB scenarios (§4) |
| `src/test/…/service/ledger/AbstractMigrationUpgradeTest` (+ `MigrationUpgradeH2Test`, `MigrationUpgradePostgresTest`) | V39→V40 upgrade and clean install |
| `src/test/…/service/ledger/JpaLedgerStoreRetryTest` | DB-free tests of retry/translation logic |
| `src/test/…/ledger/LedgerOperationsAuditTest` | Audit behaviour (no DB) |

### Changed (backend)
| File | Change |
|---|---|
| `…/ledger/LedgerLimits` | + rolling `issuanceWindow` (default 30 days); validates positive window |
| `…/ledger/LedgerStore` | `issuedTotalMinor(user, currency, since)` (window-aware) |
| `…/ledger/LedgerEngine` | uses the window; |
| `…/ledger/LedgerException` | + `CONCURRENCY_FAILURE` code |
| `…/ledger/Reconciler` | + reversal audit: missing / duplicate / malformed reversals, orphan reversals |
| `…/domain/Payment` | + nullable `ledgerTransactionId` (maps the new column; **never backfilled**) |
| `pom.xml` | + test-scope `org.testcontainers:junit-jupiter`, `postgresql` (versions from the Spring Boot 3.3.4 BOM) |
| `src/test/…/ledger/LedgerEngineTest`, `InMemoryLedgerStore` | window signature; +4 tests. No existing test weakened |

Earlier phases' changes (Phase 8: replay-ownership guards, taxi idempotency keys, web 401 handling, login-limiter eviction,
startup safety check) remain in the cumulative patch. **Deleted files: none** (I removed only my own superseded
`V40__shared_ledger.sql.proposed`).

No frontend or mobile file changed in this phase. No existing API route changed. No wallet endpoint exists yet.

## 2. Migrations (V39 was verified to be the latest; V40 was unused)

**V40__shared_ledger.sql** (auto-applied, portable PostgreSQL/H2): `ledger_accounts`, `ledger_transactions`, `ledger_postings`;
2 seeded ZAR system accounts (`DEMO_FUNDING`, `WITHDRAWAL_CLEARING`); `payments.ledger_transaction_id` (nullable, FK,
unique index). Touches **no existing row**. Database-enforced here: PKs/FKs; kind/type/status/direction value checks; positive
amounts; ISO-shaped currency; **composite FKs so a posting's currency must equal both its account's and its transaction's**;
`simulated = TRUE` only; unique provider reference; **idempotency `UNIQUE (initiator_user_id, idempotency_key)`**; **at most
one reversal per transaction** (`uq_ledger_single_reversal`) and reversal/original shape check; wallets need an owner and can
never be negative (`ck_ledger_balance_floor`); one wallet per (user, currency).

**db/postgresql-guards/V41__…sql** (PostgreSQL 11+ only, **opt-in**, **never executed**): commit-time (deferred) checks that
every transaction has ≥ 2 postings, one currency, debits = credits; rejection of UPDATE/DELETE/TRUNCATE on postings and
DELETE/TRUNCATE on transactions and accounts; transactions may change only status (legal transitions) / failure_reason /
updated_at; wallet balance projection must equal the sum of its postings at commit. It is *not* in the Flyway locations by
default because auto-running unverified trigger SQL on a production database is the riskier choice. Enable only after
verifying on a disposable database: `spring.flyway.locations=classpath:db/migration,classpath:db/postgresql-guards`.
Rollback is manual (drop the listed triggers/functions); Flyway Community has no undo.

**Data preservation:** V40 only adds objects and one nullable column. `AbstractMigrationUpgradeTest` is written to prove
historical `payments` rows (provider `paystack`, `paystack-mock`, references, keys) are byte-identical after upgrade;
**NOT RUN**. The same property was shown on SQLite only (§4).

## 3. Design

**Authority.** The immutable postings are the record; `ledger_accounts.balance_minor` is a projection changed only by
`addToBalance` in the same transaction as the postings, floored by a CHECK, and reconciled. (With the opt-in guards the
database additionally refuses any wallet drift at commit.) No other balance store exists.

**Locking and ordering.** `JpaLedgerStore.atomically` locks the account rows with `SELECT … FOR UPDATE` in ascending id order
(one global order ⇒ opposite-direction transfers cannot deadlock), then validates ownership, limits and balance and writes the
transaction, postings and balance deltas in the same transaction. Every issuance also locks the shared funding account, which
serialises per-user issuance-limit checks.

**Idempotency.** Scoped to (initiator, key) in the database. Same key + same parameters ⇒ the original result (`replayed`);
same key + different parameters ⇒ `IDEMPOTENCY_CONFLICT`; another actor's key is never replayed. A race on the key (or on a
reversal) is resolved by the unique constraint: the loser's transaction rolls back, is retried from scratch, and then reads the
winner's committed row.

**Retries.** Up to 4 attempts with short randomised backoff, only for deadlock/serialization (SQLSTATE 40001/40P01), lock
failures, and a lost idempotency/reversal race. Each attempt is a new transaction that rolled back fully, so no retry can
duplicate an effect. Business-rule failures are never retried. After the bound: `LedgerException(CONCURRENCY_FAILURE)`. If the
caller is already inside a transaction the work joins it and **cannot be retried**. Raw database exceptions never escape.

**Simulation labelling and audit.** Every ledger transaction is simulated (field, DB CHECK, `SIM-` reference); audit entries
(`audit_logs`) are prefixed `SIMULATED`. `LedgerOperationsService` audits each successful operation and denials
(`NOT_OWNER`, `LIMIT_EXCEEDED`); replays are not re-audited; an audit-write failure is logged and does not undo a committed
ledger operation. Wallet ownership comes only from the `Actor`, which must be built from the authenticated server-side principal.

**Limits (provisional, configurable, minor units).** Transfer R10,000 (`app.ledger.limits.transfer-max-minor=1000000`);
issuance R5,000 per request (`issuance-per-request-max-minor=500000`) and R20,000 per user per **rolling 30 days**
(`issuance-per-user-window-max-minor=2000000`, `issuance-window-days=30`); withdrawal R10,000 (`withdrawal-max-minor=1000000`).
The 30-day window length is **my choice**; please confirm or change it. A reversed issuance stops counting against the cap.

## 4. Commands executed and results

| # | Command / method | Result |
|---|---|---|
| 1 | `java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all -d out backend/src/main/java/com/momocircle/ledger/*.java` (real JDK, **no stubs**) | **PASS** exit 0, 0 warnings. Java-17 *API* use unchecked (`--release 17` unsupported here) |
| 2 | `LedgerEngineTest` (21 tests) against real ledger source with a stand-in `@Test`/assertions and reflection runner; repeated ×20 | **STAND-IN, PASS** 21/21, 0 failures in 20 repeats. Includes balance, issuance classification, rolling window, reversals + reversal audit, withdrawal lifecycle, 50-thread overspend, 30-thread duplicate key, opposite-direction transfers. Mutation checks (overdraft / locking / owner / window) each failed specific tests |
| 3 | Persistence + service code type-checked against hand-written Spring/JPA/Hibernate/SLF4J stand-ins | **STAND-IN, PASS** 0 errors. Proves self-consistency with *my* understanding of those APIs only |
| 4 | `JpaLedgerStoreRetryTest` (11 tests, failure classification, bounded retry, no raw-error leakage) with stand-ins; mutation-checked | **STAND-IN, PASS** 11/11 |
| 5 | `LedgerOperationsAuditTest` (6 tests) with stand-ins | **STAND-IN, PASS** 6/6 |
| 6 | V40 DDL executed in Python `sqlite3` (38 constraint cases incl. composite-FK currency checks, per-initiator idempotency, single reversal, simulated-only, wallet floor, payment-link uniqueness, historical row unchanged) | **PASS on SQLite only.** Not PostgreSQL, not H2 |
| 7 | Parse-level check of the Spring/Testcontainers integration tests | No syntax errors; every unresolved symbol is an external library class |
| 8 | `node --test tests/*.test.ts` (web) · `node --test tests/attemptKey.test.mjs` (mobile) | **PASS** 10/10 · 4/4 (Phase 8 suites, still green) |
| 9 | `mvn test`, `mvn package`, backend compilation under Maven | **NOT RUN** (no Maven / network) |
| 10 | `LedgerH2PersistenceTest`, `MigrationUpgradeH2Test` (also proves `ddl-auto=validate` entity/schema agreement) | **NOT RUN** |
| 11 | `LedgerPostgresPersistenceTest`, `MigrationUpgradePostgresTest` (Testcontainers PostgreSQL 16) | **NOT RUN** (no Docker). Annotated to SKIP, not pass, without Docker |
| 12 | `LedgerPostgresGuardsTest` and V41 triggers (commit-time balance, immutability, projection integrity) | **NOT RUN**. The V41 SQL has never been executed anywhere |
| 13 | Flyway clean install and V39→V40 upgrade on PostgreSQL | **NOT RUN** |
| 14 | Web `npm run build`; mobile `tsc` / build (no frontend change this phase) | **NOT RUN** |
| 15 | Existing 943+ backend tests / payment, marketplace, Stokvel, Save-to-Buy, PULA Ride regression | **NOT RUN** |
| 16 | "No outbound request to retired providers" | **NOT VERIFIED** (nothing retired); the new ledger code has no provider or network references (grep) |

### Acceptance criteria status
| Criterion | Status |
|---|---|
| DB-backed ledger persists correctly | Implemented; **NOT RUN** |
| Posted transactions balance within each currency | Engine **STAND-IN PASS**; DB currency agreement via composite FKs **SQLite PASS**; commit-time balance only via opt-in V41 (**NOT RUN**) |
| Posted entries cannot be modified | `@Immutable` + no delete API (**NOT RUN**); DB triggers in opt-in V41 (**NOT RUN**) |
| Idempotency enforced by the database | UNIQUE verified on **SQLite**; JPA/PostgreSQL race path **NOT RUN** |
| Ownership and overdraft rules tested | Engine **STAND-IN PASS**; DB-backed **NOT RUN** |
| Reversals auditable | Reconciler checks + audit facade **STAND-IN PASS**; `uq_ledger_single_reversal` **SQLite PASS** |
| Reconciliation detects inconsistencies | Reconciler **STAND-IN PASS**; DB service **NOT RUN** |
| Migrations verified against PostgreSQL | **NOT RUN** |

## 5. Remaining Paystack / provider dependencies (unchanged; nothing was removed)
Paystack still appears in: `PaymentOrchestrator`, `PaystackGateway`, `HttpPaystackGateway`, `MockPaystackGateway`,
`PaystackRefundClient`, `PaystackWebhookController`, `StockPaymentController`, `OrderController`, `ConfigController`,
`SecurityConfig` (webhook route), `ConsumerPaystackCheckoutService`, `StockPaymentService`, `FoodCheckoutService`,
`OrchestratorServicePaymentGateway`, `ServicePaymentGateway`, `OrderRefundService`, `PaymentReconciliationService`,
`AdminService`, `PaymentGate`, `RiskAssessmentService`, `RiskType`, `PaymentRefund`, `StockOrder`, `RefundStatus`,
`OrderRepository` (+ Phase 8's `SimulatedProviderAudit` / `ProductionSafetyCheck`, which only *mention* it), both frontends,
V21 and V33. **MTN:** `MtnMomoPaymentProvider` stub remains (throws; no HTTP). **Stitch:** not integrated; comments only.
Real-money capability still exists via `HttpPaystackGateway`/`PaystackRefundClient` when `PAYSTACK_MODE=paystack`.

## 6. PostgreSQL vs H2 differences that matter here
- **No PL/pgSQL on H2:** commit-time double-entry, immutability and projection integrity (V41) cannot exist on H2. On H2 they are enforced only by `LedgerEngine` + `Reconciler`.
- **Locking and isolation:** H2 row locks and default lock timeouts differ from PostgreSQL; H2 passing is not evidence of PostgreSQL race safety. PostgreSQL READ COMMITTED + `FOR UPDATE` is the intended model.
- **Error text:** constraint names appear differently (H2 upper-cases/suffixes index names); retry detection lower-cases and matches the constraint name, and also honours SQLSTATE 40001/40P01 (PostgreSQL).
- **Hibernate `validate`:** enum/boolean/timestamp type mapping can differ per dialect; entities follow the repo's existing `Instant`→`TIMESTAMP` and `@Enumerated(STRING)`→`VARCHAR` pattern.
- **Timestamps:** nanosecond `Instant`s are truncated to the column precision on read-back.
- **Flyway:** PostgreSQL needs `flyway-database-postgresql` (already present).

## 7. Outstanding risks (highest first)
1. **First boot may fail.** `ddl-auto=validate` is on in every profile; any entity/V40 mismatch stops the app. Run `LedgerH2PersistenceTest` first. If V40 reaches a database and a mismatch is found, V40 cannot be edited: it must be fixed by a new forward migration or entity change.
2. **Everything is unexecuted** (§4): Spring wiring, JPQL, retries against a real driver, trigger SQL, Testcontainers setup.
3. **Guards are opt-in**, so by default the database does not enforce commit-time balance, immutability or projection integrity.
4. Withdrawals stay PENDING (funds held) until an operator completes, fails or cancels them; there is no expiry job.
5. Reconciliation loads all transactions in memory (paged); fine for demo volumes, not for large ledgers. The V41 projection trigger sums a wallet's postings per commit.
6. Audit is best-effort after the ledger commit (by design, §3). `audit_logs.details` is limited to 1,000 characters.
7. Seeded system accounts cover ZAR only; other currencies are provisioned by `LedgerAccountService` (requires a call).
8. `ledger_transaction_id` on `payments` is mapped but nothing sets it yet (Phase 9C/F).
9. Provisional limits and the 30-day window are placeholders awaiting your confirmation.
10. Phase 8 findings F-05, F-06, F-07 (refund failure handling, Save-to-Buy/Stokvel reversal, stokvel period allocation) remain open.

## 8. Confirmation
No provider integration and no existing business flow was removed or switched. Paystack, its webhook, the MTN stub and all
Stitch comments are untouched. Marketplace, food, stock, supplier-service, bulk-purchase, refund, Stokvel, Save-to-Buy and
PULA Ride payment code, routes, driver workflows and USSD are untouched in this phase (the only PULA Ride change in the
cumulative patch is Phase 8's client-side idempotency key, with no route or contract change). PULA Money's profile, income,
budgeting, affordability, credit-education and scam-check code is untouched. No historical row is rewritten. The ledger is simulated
only: no code path can mark a transaction as real money or call an external provider. Real-money capability remains solely in the
untouched Paystack code.

## 9. Exact commands to finish verification (in an environment with Maven 3.9+, JDK 17, Docker, Node 20+)
```
cd backend-web/backend
mvn -q -DskipTests compile
mvn -Dtest='LedgerEngineTest,LedgerOperationsAuditTest,JpaLedgerStoreRetryTest' test
mvn -Dtest='LedgerH2PersistenceTest,MigrationUpgradeH2Test' test                      # also proves ddl-auto=validate
mvn -Dtest='LedgerPostgresPersistenceTest,MigrationUpgradePostgresTest' test          # Docker required
mvn -Dtest='LedgerPostgresGuardsTest' test                                            # applies V41 to a disposable container
mvn test && mvn package
cd ../frontend && npm ci && npm run build && node --test tests/*.test.ts
cd ../../mobile/mobile && npm ci && npx tsc --noEmit && node --test tests/attemptKey.test.mjs
```
Review the surefire reports for *skipped* PostgreSQL tests: skipped is not passed. Only after all of the above are green, and
V41 has been run on a disposable PostgreSQL, enable the guards for real environments and treat Phase 9A as accepted.
