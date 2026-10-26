# Security & Regression Audit — Full Ecosystem Evolution

Date: 2026-09-16

Scope: every backend endpoint/service added or modified across Phases
F.1-J.1 (currency fix, seller typing, price/basket comparison, the full
Money Confidence domain, AI assistant integration, and the MoMoRide
delivery integration). This is the dedicated audit pass promised after
those phases, distinct from each phase's own scoped review.

## 1. Method

Read every new/modified controller end to end, cross-referencing each
endpoint against: (a) whether it should be public or authenticated, (b)
whether ownership is enforced server-side and can't be bypassed via a
client-supplied id, (c) whether request DTOs allow a client to set a
field that should only ever be server-determined ("whose data is this"),
and (d) whether financial amounts go through the app's own stated
validation invariant. Controllers audited: `MoneyController`,
`ScamShieldController`, `CanonicalProductController`,
`BasketComparisonController`, `DeliveryController`, plus the modified
`BusinessController`, `ProductController`, `OrderController`, and
`SecurityConfig` itself.

## 2. Real finding, fixed: two services skipped the app's own validation invariant

`FinancialValidationService`'s own class-level javadoc states: "Every
financial amount in the app... passes through here before being
persisted, regardless of whether it came from a manual form, the AI
assistant, or... any other API client." This is a documented,
load-bearing invariant other developers reasonably rely on.

Grepping for actual usage found that `IncomeRecordService.create` and
`BudgetService.createOrUpdate` - both from Phase I.1 - never called it at
all. They relied solely on DTO-level Bean Validation (`@DecimalMin`),
which:
- Doesn't run at all when a service method is called directly (as
  `AssistantService`'s `execute()` does for the AI-driven paths, and as
  any future internal caller would) - only `@Valid @RequestBody`
  controller bindings trigger it.
- Doesn't enforce the shared max-amount ceiling or the "no more than 2
  decimal places" / overflow-precision checks the rest of the app gets.
- Doesn't validate that a client-supplied currency is actually a
  supported one - `IncomeRecordService`/`BudgetService` would have
  silently stored `currency: "XXX"` or any other garbage string a client
  sent.

**A real design nuance surfaced while fixing this**: `BudgetCategoryRequest
.allocatedAmount` deliberately allows zero via its own `@DecimalMin("0.00")`
- a R0 budget category is a legitimate "not spending on this right now"
choice. `FinancialValidationService.requireValidAmount` rejects zero
(correctly, for contexts like a contribution or loan amount where zero
makes no sense). Applying it directly to budget categories would have
silently broken that intended behavior while "fixing" the security gap -
not a hypothetical: it would have been a real regression introduced by
the fix itself if not checked. Resolved by adding a new, purely additive
method, `requireValidNonNegativeAmount`, rather than either widening the
existing method's contract (risking every other caller) or skipping
validation for budget categories to dodge the conflict.

**Fixed**: both services now inject `FinancialValidationService` and
validate amount + currency before persisting. Six new tests added across
`IncomeRecordServiceTest`, `BudgetServiceTest`, and
`FinancialValidationServiceTest` - including one that specifically proves
the zero-vs-non-zero distinction holds
(`nonNegativeVariantAcceptsZeroUnlikeTheStrictVariant`).

## 3. Confirmed safe, with reasoning shown rather than asserted

- **No IDOR anywhere in the new Money/ScamShield/Delivery/Market surface.**
  Every Money/ScamShield endpoint takes no "whose data" parameter at all -
  scoped exclusively via `currentUser.require()`. Every Delivery endpoint
  that does take a resource id (`{deliveryId}`, `{orderId}`) verified to
  delegate to a service method that checks real ownership
  (`DeliveryRequestService`'s seller/driver/buyer checks,
  `OrderService.setFulfilment`'s business-ownership check) before doing
  anything - confirmed by reading each service method, not assumed from
  the controller alone.
- **No mass-assignment surface.** Grepped every new request DTO
  (`MoneyDtos`, `ScamShieldDtos`, `DeliveryDtos`) for any client-settable
  `userId`-shaped field - the only `userId` field in the whole set is on
  a response record (`FinancialProfileResponse`), never a request.
  "Whose data" is determined exclusively by the authenticated session,
  never by anything the client sends.
- **AI assistant's new Money intents inherit ownership protection "for
  free."** All five new mutating intents flow through the exact same
  `AssistantProposalConcurrencyService.claim(proposalId, actor,
  currentCircleId)` call every other intent already goes through, before
  the `execute()` switch statement is even reached - confirmed by reading
  the method, not assumed from the fact that it's a shared code path.
  There is no separate, easier-to-miss ownership check specific to the
  new intents that could have been forgotten.
- **`SecurityConfig`'s permitAll list is accurate.** Every GET added
  there this evolution (`/api/canonical-products` and its two variants)
  is genuinely public browsing data, matching the existing
  `/api/products`/`/api/businesses` pattern; every POST/PATCH/PUT
  endpoint added anywhere in this evolution correctly falls through to
  `.anyRequest().authenticated()`.

## 4. Lower-priority observations (not fixed, judged not to warrant it)

- **`POST /api/canonical-products` is open to any authenticated user, by
  design** (documented in Phase G.1's own report: "there is no separate
  curator role in this slice"). This is a data-quality/spam risk (a bad
  actor could flood the canonical-product catalogue with junk entries),
  not a privacy or cross-user-data-access risk. Worth a moderation/report
  mechanism eventually; not urgent enough to block on here.
- **No rate limiting on the new Money/ScamShield/Delivery endpoints.**
  The codebase has dedicated rate limiters for specific abuse patterns
  (`LoginRateLimiter`, `PaymentCodeRateLimiter`, `UssdPinRateLimiter`) -
  all guarding against credential/code-guessing attacks. None of the new
  endpoints have that shape (nothing to brute-force); worst case is a
  user spamming their own account with junk rows, which has no
  cross-user impact. Noted, not treated as urgent.
- **`/h2-console/**` is `permitAll` with no environment gate in
  `SecurityConfig`, and no `application-prod.yml` exists to override it.**
  This predates this evolution entirely (not introduced by any phase in
  this work) and was very likely already known from the project's own
  original audit. Flagging it here anyway because a security audit that
  only reports what it was asked to check is a worse audit than one that
  reports what it actually found - this genuinely matters for a real
  deployment and shouldn't go unmentioned just because it isn't new.

## 5. Verification performed

- Read every touched controller and its corresponding service method
  end-to-end rather than pattern-matching on "does it have `@PreAuthorize`
  or `currentUser.require()` present."
- Grepped for every constructor call site of `IncomeRecordService`,
  `BudgetService`, and `FinancialValidationService` before changing
  signatures - 2 test files found and updated, matching the discipline
  used throughout every prior phase.
- Balance-checked (braces/parens) every touched file.
- Re-confirmed the ScamShield redaction regex fix from the earlier
  test-run-caught bug is still in place and wasn't inadvertently reverted
  by a later phase's edits.

## 6. Not build-verified in this sandbox

Same limitation as every backend phase - no Maven Central access. This
phase's changes are lower-risk than most (additive constructor
parameters, two new validation calls, no new tables, no new control flow
beyond a validation check), but please still run `mvn clean test` before
relying on it, particularly the new
`nonNegativeVariantAcceptsZeroUnlikeTheStrictVariant` test, which is the
one most worth confirming actually passes given it encodes a subtle,
easy-to-get-backwards distinction.

## 7. What this audit did not cover

- Frontend (web or mobile) code was not re-audited for XSS/injection -
  each phase's own report already covered relevant frontend changes, and
  neither frontend does any raw HTML injection anywhere in this
  evolution's changes (confirmed by grep for `dangerouslySetInnerHTML`
  during earlier phases' own review, not repeated here).
- Infrastructure/deployment configuration (secrets management, TLS,
  actual production environment hardening) - out of scope for an
  application-code audit; the H2-console finding in section 4 is the one
  deployment-adjacent thing that surfaced naturally while reading
  `SecurityConfig`.
- Load/performance testing - not attempted; this is a correctness and
  authorization audit, not a capacity one.

## 8. Addendum: H2-console finding, fixed

On closer inspection, the H2-console risk from section 4 was less severe
than first flagged: `spring.h2.console.enabled` defaults to `false`, and
the `prod` profile block in `application.yml` never sets it `true`, so
the console bean itself is never created when running with `prod` active
- `SecurityConfig`'s unconditional `permitAll` on `/h2-console/**` was
permitting a path with nothing behind it in that profile, not actually
exposing a live console.

Fixed anyway, as real defense-in-depth: `SecurityConfig` now injects
`Environment` and only includes `/h2-console/**` in the permitAll list
when the `prod` profile is not active - one config mistake (someone
adding `h2.console.enabled: true` to the shared, non-profile-scoped
block) away from actually mattering, and the security rule should not
depend on a second, unrelated property staying correct forever. No
existing test constructs `SecurityConfig` directly (confirmed by grep -
it's a `@Configuration` bean, only ever built via Spring's DI container),
so this change carries no test-breakage risk.
