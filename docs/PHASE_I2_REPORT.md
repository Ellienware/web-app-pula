# Phase I.2 — Affordability Engine Report

Date: 2026-09-15

## 1. Scope

Brief §17, "Can I afford this?" — apply to a product/basket/service amount
and return `AFFORDABLE`/`CAUTION`/`NOT_RECOMMENDED`/`INSUFFICIENT_INFORMATION`
with an explanation, never presented as regulated financial advice.

## 2. The design decision this phase turns on

Before writing any logic, checked whether the app tracks a wallet/account
balance anywhere: **it doesn't.** Money moves entirely through the external
MTN MoMo provider via `Payment`; there is no internally tracked ledger
balance for any user. The brief's own §17 description assumes an
"available balance" input the engine can check against — that input simply
doesn't exist as real data in this system, and the brief's §30 and §23 are
both explicit that fabricated financial figures must never be presented as
real.

So `AffordabilityService` answers a narrower, honestly-scoped question
instead: **does this amount fit the budget/income numbers the user has
actually told us about** — not "can you afford this right now." That
limitation is documented in the service's own class-level javadoc, not
just this report, so it's visible to whoever reads the code next, not only
whoever reads phase reports.

## 3. What was added

- `AffordabilityResult` enum, `AffordabilityAssessment` entity (stored, so
  a user has a history — and so a later AI-assistant phase can reference a
  specific past assessment by id).
- `V11__affordability_assessments.sql`.
- `AffordabilityService.assess`, which:
  1. Tries to match the caller's free-text `category` against one of the
     user's `Budget` categories (case-insensitive, substring-tolerant in
     both directions) and compares the amount to that category's
     allocation.
  2. If no budget/no match, falls back to comparing against
     `IncomeRecordService.weeklySummary`'s average — reused directly from
     Phase I.1 rather than re-querying income data, so the two features
     can't silently disagree about what "average income" means.
  3. If neither exists, returns `INSUFFICIENT_INFORMATION` with a concrete
     next step ("add a budget category or a few income entries"), not a
     bare error.
  4. Appends a one-line, number-free note if the user has any active
     Community Savings goal in any circle they belong to — added via a new
     safe, single-hop `CircleGoalRepository.findByCircleIdInAndStatus`
     query rather than a risky multi-table derived-query name across
     `CircleGoal → Circle → CircleMembership → User`, which would have been
     much harder to verify correct without a real compiler.
  5. Every response ends with the same disclaimer sentence — not financial
     advice.
- Thresholds are named constants (`AFFORDABLE_THRESHOLD` = 50% of a matched
  budget category, tighter 30%/70% bands when falling back to weekly
  income) with a comment explaining they're a simple, tunable heuristic,
  not a validated model — so a reviewer can find and question them rather
  than hunting for magic numbers.
- `POST /api/money/affordability` (create + persist), `GET
  /api/money/affordability` (history) on the existing `MoneyController` —
  same no-id, `currentUser.require()`-only scoping as the rest of that
  controller.
- Web frontend: a "Can I afford this?" card on the `/money` page, with the
  four results color-coded and the reasoning shown verbatim (not
  re-summarized, so nothing gets lost in translation between backend and
  UI).
- `AffordabilityServiceTest` — all four result branches, case-insensitive
  category matching, and the fallback-to-income path when no category
  matches.

## 4. A test bug caught and fixed before finalizing

The first draft of the category-matching test asserted a tautological,
wrong comparison (`assertEquals("Groceries & food", condition ? null :
response.category())`) that wouldn't have caught a real bug either way —
and the expected value was wrong regardless, since the service stores the
*request's* raw category text (`"groceries"`), not the *matched* budget
category's name (`"Groceries & food"`) — matching only picks which
allocation to compare against, it doesn't rewrite what the user typed.
Reworked to assert the actually-correct, actually-meaningful thing.

## 5. What was explicitly NOT done this phase

- No "do you have enough money right now" check — see §2, this is the
  scope boundary that shapes everything else this phase.
- No affordability check for potential credit repayments — that needs the
  credit-education calculator (I.3) to exist first.
- No AI assistant `CHECK_AFFORDABILITY` intent yet (J.1) — this phase is
  the endpoint the intent will eventually call.

## 6. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean.
- Backend: **not build-verified in this sandbox.** Please run
  `mvn clean test` — this phase adds one new table, one new service with
  real branching logic, and touches an existing repository
  (`CircleGoalRepository`), which is exactly the kind of cross-cutting
  change most likely to hide a subtle issue a grep sweep alone can't catch.

## 7. Regression sweep performed

Grepped every instantiation site of `AffordabilityAssessmentResponse` and
`AssessAffordabilityRequest` — confined to the owning service and its test.
Re-viewed the full `MoneyController.java` after three sequential edits
across two phases to confirm no leftover braces/duplicate methods from the
str_replace sequence. Confirmed `GoalStatus.ACTIVE` (used in the new
repository query) exists as a real enum constant, matching `CircleGoal`'s
own default value.

## 8. Regression check against §35

Auth, Circle/Community Savings, MoMoMarket, MoMoRide, AI assistant, and
Payment code paths untouched, aside from the one additive method on
`CircleGoalRepository` (a new derived-query method; the existing
`findByCircleIdOrderByCreatedAtDesc` is unchanged).
