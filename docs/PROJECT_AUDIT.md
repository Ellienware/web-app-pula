# MoMoCircle V5 — Project Audit (Phase 0)

Date: 2026-08-13

## 1. Repository shape

The repo currently contains **two frontends** at the root:

- `app/`, `components/`, `lib/`, root `package.json` — an unused **Next.js 16 / React 19** scaffold (shadcn starter). No routes wired to the actual product; only a placeholder `app/page.tsx`.
- `frontend/` — the real product frontend: **React 18 + TypeScript + Vite 5 + React Router 6**, with `pages/`, `components/`, `context/`, `lib/`.

This matches the master prompt's description exactly: the Next.js scaffold is dead weight and should be removed; `frontend/` is the target.

`backend/` is a **Spring Boot 3 (Java) / Maven** app with a real, fairly complete domain model already in place.

## 2. Backend inventory

**Domain entities present:** User, Circle, CircleMembership, CircleInvitation, CircleGoal, Contribution, PaymentRequest, Expense, ExpenseShare, RecurringPlan, RecurringDue, Payment, Business, Product, CartItem, Order, OrderItem, Review, Receipt, Notification, ActivityEvent, AuditLog — plus enums for every status/role type (PaymentStatus, OrderStatus, CircleRole, etc).

**Controllers (17):** Auth, Circle, CircleInvitation, Invitation, Goal, Expense, Recurring, Receipt, Activity, Notification, Business, Product, Cart, Order, Review, SellerDashboard, Assistant, Admin.

**Services (19):** mirror the controllers, plus `PaymentReconciliationService` and `AuditLogService`.

**Payments:** a real `PaymentProvider` interface with `SandboxPaymentProvider` and `MtnMomoPaymentProvider` implementations already exist, plus `ChargeRequest`/`ChargeResult` DTOs — i.e. the provider abstraction from Rule 4/Phase 11 is already there, not something to build from scratch.

**AI:** `AssistantController` / `AssistantService` / `AssistantDtos` exist. Config (`application.yml`) shows an AI Gateway URL + model (`openai/gpt-4o-mini` by default) with an explicit **offline heuristic fallback** when no gateway key is set — a sensible existing design, not something to throw away.

**Security:** `JwtService`, `JwtAuthFilter`, `SecurityConfig`, `CurrentUserService` exist. Dev profile uses a hard-coded fallback JWT secret (`change-me-in-prod-this-is-a-dev-only-secret-key...`) — **fine for dev, but needs a fail-fast check that prod can't boot without `JWT_SECRET` set**, per Rule 23.

**Database:** dev profile runs on in-memory H2 (`ddl-auto: update`), prod profile is wired for real PostgreSQL (`ddl-auto: validate`). **No Flyway/migration tool yet** — prod profile already expects `validate` mode, which will fail loudly if migrations aren't added before a real deploy. This is the single biggest structural gap for "production-credible."

**Tests:** only one test file exists — `SandboxPaymentProviderTest.java`. Everything else (Circle, orders, auth, authorization, AI action validation) is currently untested.

## 3. Frontend inventory

Pages: Auth, Dashboard, CircleDetail, Notifications, and a `market/` group (MarketHome, ProductDetail, Cart, BusinessProfile, Order, OrderDetail... wait Orders, SellerDashboard).

Components: modals for PaymentRequest, Contribute, Recurring, Invite, Expense, CreateCircle, Goal; `AssistantPanel`; a `circle/` tab set (Overview, Goals, Members, Money via Expenses, Activity, Recurring); a `market/` component set (BusinessCard, ProductCard, OrderStatusBadge, ReviewSection, StarRating, CreateBusinessModal, ProductFormModal).

This lines up well with Phases 7–9 (Home, Circle, central transaction) and Phase 14/15 (Market, Seller) — the page/tab skeleton the master prompt asks for already exists in large part.

**Build status:** `npm run build` currently **fails** — not on business logic, but because `@types/react`/`@types/react-dom` aren't resolving in this sandbox (`JSX.IntrinsicElements` errors cascading through every page). This is an environment/registry access issue in the auditing sandbox, not a confirmed defect in your code — needs to be re-verified in an environment with normal npm registry access. I could not get a clean `npm install` here to confirm further.

## 4. What's already solid (don't rebuild)

- Domain model covering Circles, Market, and Payments is already broad and matches the target architecture.
- Payment provider abstraction (sandbox + MTN) already exists as an interface, not hard-coded.
- AI assistant already has a controller/service split and an offline fallback mode.
- Frontend page/component structure already mirrors the intended IA (Home/Circle/Market/Seller/AI).

## 5. Gaps / risks, roughly in priority order

1. **Duplicate Next.js scaffold** at the repo root — dead code, should be deleted (Phase 1).
2. **No migrations (Flyway)** — prod profile will fail on `validate` with no migrations to validate against.
3. **No central Transaction abstraction yet** — Payment exists but Contribution/PaymentRequest/Order each seem to carry their own state rather than referencing one financial-transaction record; needs inspection at the entity-field level before deciding whether to introduce it (per Phase 9's explicit warning not to blindly duplicate Payment).
4. **Idempotency**: no visible idempotency key field/enforcement yet on Payment — needs verification against `Payment.java` and the controllers that create payments.
5. **Test coverage** is minimal — one payment provider unit test only.
6. **JWT prod fallback secret** should fail-fast rather than silently run with a dev secret.
7. Frontend build not verified clean in this environment (registry access issue) — needs re-check.

## 6. Recommended order (per the master prompt's own Phase A–G sequence)

A. Remove duplicate Next.js scaffold, get a clean `frontend` build confirmed.
B. Inspect `Payment`/`Contribution`/`Order`/`PaymentRequest` fields directly, decide on the Transaction abstraction, add Flyway migrations, add idempotency keys.
C. Fill in Circle/Market/checkout gaps found once B is done.
D. AI action framework hardening.
E. Security + tests.
F. MTN integration + Mini App adapter (blocked on real MTN sandbox credentials — I cannot fabricate these).
G. Polish, docs, final audit.

This is far too much to do in one pass safely — I'd rather work through it phase by phase with build/test checkpoints, as the brief itself insists on, than make sweeping changes in one shot.

## 7. Phase B update (payment/transaction architecture)

Closer inspection of `Payment`, `Contribution`, `Order`, `PaymentRequest`, and
`PaymentReconciliationService` showed the idempotency-key enforcement,
payment state machine, and reconciliation logic were **already correctly
implemented** — better than the initial skim above suggested. No central
`Transaction` entity was introduced, deliberately: `Payment` already is that
shared primitive (Contribution and RecurringDue both hold a 1:1 FK to it),
and duplicating it would violate the master prompt's own Phase 9 warning.

What was actually missing and has now been added:

- **`backend/src/main/resources/db/migration/V1__init.sql`** — a hand-authored
  Flyway baseline covering every entity in `domain/`, with FKs, unique
  constraints (idempotency key, invitation token, cart user+product, review
  author+target, receipt number, business owner), and indexes on the columns
  services actually query by (status, circle_id, buyer_id, etc). This is
  what the `prod` profile's `ddl-auto: validate` needed to have anything to
  validate against — previously it would have failed to boot against a real
  empty Postgres database.
- **`pom.xml`** — added `flyway-core` and `flyway-database-postgresql`.
- **`application.yml`** — dev now also runs on `ddl-auto: validate` +
  Flyway (against H2 in Postgres-compatibility mode) instead of
  `ddl-auto: update`, so schema drift is caught locally before it ever
  reaches prod. `JWT_SECRET` no longer has a fallback under the `prod`
  profile — it will now fail to start rather than silently run with the dev
  secret; `dev` keeps its own fallback for a zero-setup checkout.

**Known residual gap, intentionally not changed yet:** `Order.paymentId` is a
plain string column, not a foreign key to `payments.id` (unlike
`Contribution.payment`, which is a real `@OneToOne`). It works today because
every write path sets it correctly, but it's structurally weaker than the
Contribution side. Flagged in the migration's own comments; tightening it to
a real FK is a good candidate for a small follow-up migration once confirmed
safe.

**Not verified:** I could not run `./mvnw package`/`test` in this sandbox
(no network egress to Maven Central), so this migration has **not been
compiled or executed against a live database**. Please run it locally before
trusting it against Postgres — in particular double-check the two
`@ElementCollection` tables (`business_operating_hours`,
`product_image_urls`) against whatever Hibernate actually generates, since
those are the two spots hand-authored DDL is most likely to drift from
Hibernate's real naming.
