# Phase B.1 — Payment/Transaction Hardening Report

Date: 2026-08-14

## 1. What was already implemented (before this pass)

Confirmed by direct inspection of `Payment`, `Contribution`, `Order`,
`PaymentRequest`, `PaymentReconciliationService`, `PaymentProvider`,
`SandboxPaymentProvider`, `MtnMomoPaymentProvider`, `PaymentRepository`,
`OrderRepository`, `ContributionRepository`:

- `Payment` as the single shared financial primitive, with `Contribution`
  holding a real `@OneToOne` FK to it (not duplicated status fields).
- A working idempotency check (`findByIdempotencyKey`) and a unique DB
  constraint backing it.
- `PaymentReconciliationService` polling PROCESSING payments every 30s via
  `PaymentProvider.getStatus`, mirroring MTN's documented status-check
  endpoint, and never trusting a client-reported "success."
- `OrderStatus.canTransitionTo()` — a whitelist enum method already gating
  the one place order status is *manually* changed (seller-initiated
  `updateStatus`).
- `PaymentProvider` abstraction cleanly separating sandbox from MTN, with
  MTN correctly left unimplemented (throws `UnsupportedOperationException`)
  rather than faking a real integration.

No new `Transaction` entity was introduced, per instruction — `Payment`
already is that abstraction.

## 2. What was changed

### Payment state machine (`Payment.java`)
Added `Payment.transitionTo(PaymentStatus)` enforcing:
```
PENDING    -> PROCESSING, FAILED
PROCESSING -> SUCCESSFUL, FAILED
SUCCESSFUL -> REFUNDED
FAILED, REFUNDED -> (terminal)
```
Same-status calls are a no-op (a provider re-reporting an unchanged
PROCESSING isn't a real transition). Anything else throws
`IllegalStateException`. The old public `setStatus()` was **removed
entirely** — every call site (`ContributionService`, `OrderService`,
`AdminService`, `PaymentReconciliationService`) now goes through
`transitionTo()`. `AdminService.forceRefund` catches the
`IllegalStateException` and converts it to a clean `ApiException.badRequest`
instead of letting it surface as an uncaught 500.

### Order → Payment relationship (`Order.java`, `OrderRepository.java`)
`Order.paymentId` (a plain, unenforced `String`) replaced with a real
`@OneToOne(fetch = LAZY) @JoinColumn(name = "payment_id", unique = true)`
`Payment payment` field — matching how `Contribution` already referenced
`Payment`. `OrderRepository.findByPaymentId(String)` kept its exact name and
signature (so `OrderService`/`PaymentReconciliationService` callers didn't
need to change) but is now backed by `@Query("... where o.payment.id =
:paymentId")` instead of a derived-property query. `AdminService.forceRefund`
now reads `order.getPayment()` directly instead of a second
`paymentRepository.findById(order.getPaymentId())` lookup.

New migration: `V2__order_payment_fk.sql` — adds the FK constraint and a
unique constraint on `orders.payment_id`, dropping the old plain index.
Comment in the migration explains why it's safe on top of V1 (no historical
data to backfill in this codebase).

### Idempotency concurrency (`PaymentIdempotencyService.java`, new)
The old pattern — `findByIdempotencyKey()` then insert if absent — has a
race window: two concurrent requests can both see "not found" and both
attempt to insert. Added `PaymentIdempotencyService.reserve(...)`, which:
- Re-checks `findByIdempotencyKey` inside its own `REQUIRES_NEW`
  transaction, then attempts `saveAndFlush`.
- Catches `DataIntegrityViolationException` from the DB's unique
  constraint on `idempotency_key` if a concurrent request won the race, and
  returns *that* request's Payment instead of failing or double-charging.
- Runs in `REQUIRES_NEW` specifically so a conflict here can't mark the
  caller's outer transaction (which also creates a Contribution/Order) as
  rollback-only — a plain try/catch inside the same `@Transactional` method
  would not have been enough once Spring flags the transaction.

Wired into `ContributionService.contribute()`, `ContributionService.charge()`
(shared by ad-hoc and recurring contributions), and `OrderService.checkout()`.
In `checkout()`, the reservation now happens *before* stock is touched
(subtotal is computed from cart items first), so a losing duplicate request
never provisionally reserves stock it would just have to release.

### PROCESSING timeout / expiration policy (`PaymentReconciliationService.java`)
Per Rule 4/Phase 10's "never assume a provider will eventually resolve":
added `app.payments.processing-timeout-minutes` (default 30, env override
`PAYMENT_PROCESSING_TIMEOUT_MINUTES`). Any Payment that has sat in
PROCESSING past that window — whether it never reached the provider at all
(`providerReference == null`, e.g. crash mid-charge) or the provider itself
keeps reporting PROCESSING — is auto-expired to FAILED. That in turn runs
the existing failure path (`OrderService.applyResolvedFailure` releases
reserved stock; `Contribution` reads FAILED off its `Payment`). Stock is
deliberately **not** released before that deadline — a payment still
legitimately in flight keeps its reservation. Full policy documented in the
class's javadoc.

## 3. Tests added

- `backend/src/test/java/com/momocircle/domain/PaymentTest.java` — 12 cases
  covering every legal transition and every invariant called out in the
  brief (successful can't become failed/processing; failed can't become
  successful; refunded can't become successful again; illegal jumps like
  PENDING→SUCCESSFUL are rejected).
- `backend/src/test/java/com/momocircle/payment/PaymentIdempotencyServiceTest.java`
  — 2 cases: a plain repeat (key already committed) returns the existing
  payment without inserting; a genuine race (both callers pass the initial
  check, one loses the insert due to the unique constraint) resolves to the
  winner's payment via a mocked `DataIntegrityViolationException`.

## 4. Tests executed

**NOT EXECUTED.** This sandbox has no network egress (`bash_tool` network is
disabled), so `./mvnw test` cannot download the Maven Central artifacts
needed even for a first build (this was also true of the Phase B pass), and
there is no pre-populated local `.m2` cache to fall back to. Every file
above was reviewed by hand for compile correctness (imports, method
signatures, call-site consistency across services) and cross-checked
against the actual current interfaces of every class it touches, but that
review is a substitute for compilation, not equivalent to it.

## 5. Build results

**BLOCKED BY ENVIRONMENT.** Same constraint as above —
`./mvnw test` / `./mvnw package` and `npm install && npm run build` could
not be run here (no network egress). Please run both locally before merging;
in particular:
```
cd backend && ./mvnw test
cd frontend && npm install && npm run build
```

## 6. Migration / database validation results

**NOT VERIFIED.** Flyway's own execution against a live database (H2 or
Postgres) could not be exercised here for the same network reason — running
the Spring Boot app at all requires resolving Maven dependencies first.
`V1__init.sql` (hand-authored last pass) and the new `V2__order_payment_fk.sql`
have not been run.

Specific things to check on first real run, called out here since they're
the most likely spots for drift between hand-written SQL and what Hibernate
actually expects:
- `business_operating_hours` / `product_image_urls` — the two
  `@ElementCollection` tables. Their join-column and value-column names
  follow Hibernate's default naming strategy as best I could determine by
  reading the entity annotations, but that's inference, not confirmation.
- `V2`'s `DROP INDEX IF EXISTS idx_order_payment` / `ADD CONSTRAINT ...
  UNIQUE` syntax — written to be valid on both H2 (`MODE=PostgreSQL`) and
  real Postgres, but only actually parsed by neither engine yet.
- Whether `ddl-auto: validate` accepts the schema V1+V2 produce as matching
  every current `@Entity` exactly — the single highest-value thing to check
  first, since a mismatch here fails the app at boot in both `dev` and
  `prod` now.

## 7. PostgreSQL validation

**BLOCKED BY ENVIRONMENT.** No PostgreSQL instance available in this
sandbox and no network access to provision one. Documented here rather than
silently skipped, per the brief's explicit instruction not to claim this is
verified if Postgres is unavailable.

## 8. Security integration tests (Section 10 of the brief)

**NOT ADDED THIS PASS.** Deliberately scoped out rather than rushed:
writing meaningful `@SpringBootTest`/`MockMvc` integration tests (User A
can't touch User B's Circle/Order/Receipt; seller can't touch another
seller's product/order; non-admins can't reach `/api/admin/**`) needs test
JWT issuance, a seeded multi-user fixture, and a full Spring context — real
infrastructure to get right, and with zero ability to compile or run it in
this sandbox, writing a large batch of untested integration test code would
risk shipping tests that look plausible but don't actually compile. Flagging
this as the single largest remaining item from the Definition of Done and
recommending it as a focused next step with the ability to actually run
`./mvnw test` against it.

## 9. Known limitations / residual risk

- Everything in this report is source-level review, not a verified build.
  The financial-invariant unit tests (`PaymentTest`,
  `PaymentIdempotencyServiceTest`) are the most self-contained pieces (no
  Spring context, no DB) and the most likely to just work once compiled;
  the migration SQL is the least verified piece.
- MTN integration remains intentionally unimplemented — blocked on real
  sandbox credentials, unchanged from Phase B.
- Security integration test suite (Section 10) is the main open item before
  Phase B.1's Definition of Done can be checked off in full.

## 10. Definition of Done status

| Item | Status |
|---|---|
| Payment transitions enforced | DONE (source-level; unverified by build) |
| Invalid Payment transitions rejected | DONE (test written; not executed) |
| Order has proper Payment relationship | DONE (source-level; unverified by build) |
| Database FK exists | DONE (V2 migration written; not run) |
| Idempotency is concurrency-safe | DONE (source-level; unverified by build) |
| Processing payment timeout policy exists | DONE (source-level; unverified by build) |
| Stock reservation recovery works | DONE — reuses existing `applyResolvedFailure` path, now also triggered by timeout |
| Flyway executes successfully | NOT VERIFIED — blocked by environment |
| Hibernate validation succeeds | NOT VERIFIED — blocked by environment |
| Payment tests exist | DONE |
| Order/payment tests exist | PARTIAL — idempotency service covered; no Order-level test yet |
| Security integration tests exist | NOT DONE — scoped out this pass, recommended as next step |
| Build is verified | NOT VERIFIED — blocked by environment |
| Documentation updated | DONE (this report + `PROJECT_AUDIT.md`) |

Given the unresolved items above (mostly build/runtime verification blocked
by this sandbox's lack of network access, plus the security test suite),
Phase B.1 is **not yet fully closable** by its own Definition of Done. The
highest-priority next actions, in order: (1) run `./mvnw test` and
`./mvnw package` locally and fix whatever the hand-authored migration or
refactor gets wrong on first real compile/run, (2) exercise Flyway against
real Postgres, (3) write the security integration test suite once (1) is
green so tests can actually be run as they're written.
