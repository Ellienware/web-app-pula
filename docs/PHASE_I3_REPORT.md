# Phase I.3 — Responsible Credit Educational Calculator Report

Date: 2026-09-15

## 1. Scope

Brief §18: an educational calculator for loan cost — total cost, interest,
fees, repayment, affordability, warning signs. Explicit constraint: MUST
NOT encourage borrowing, must promote informed decisions, must not be
presented as a real loan product.

## 2. What was added

- `CreditEducationEntry` entity (stored history, same rationale as
  `AffordabilityAssessment` — lets a user see past checks, and gives a
  later AI-assistant phase something to reference by id).
- `V12__credit_education.sql`.
- `CreditEducationService.calculate` — standard reducing-balance
  amortization (`M = P·r·(1+r)^n / ((1+r)^n − 1)`). The exponentiation runs
  in `double` rather than BigDecimal, documented as a deliberate choice:
  building a BigDecimal-precision iterative solver would be real added
  complexity for a calculator whose own job is producing an *estimate*: the
  brief's §18 doesn't ask for regulatory-grade precision, and the final
  figures are rounded to 2 decimals regardless.
- Warning heuristics, each a named constant: interest rate above 60%/year,
  fees above 5% of principal, total repayment more than 30% above
  principal, and — reusing `IncomeRecordService.weeklySummary` the same
  way `AffordabilityService` does — a monthly-repayment-vs-typical-income
  check. Every response also carries a fixed disclaimer
  ("MoMoCircle does not lend money or arrange credit... not financial
  advice") on every single result, not just flagged ones.
- `POST /api/money/credit`, `GET /api/money/credit` on `MoneyController`,
  same ownership-scoping convention as the rest of that controller.
- Web frontend: a loan-cost calculator card on `/money` showing monthly
  repayment, total interest, total repayment, and cost percentage, with
  warnings displayed and the disclaimer always visible underneath.
- `CreditEducationServiceTest` — every expected figure was **independently
  computed with a standalone Python script**, not derived from reading the
  service's own code, specifically so a bug in the amortization formula
  would actually be caught rather than the test just mirroring whatever the
  implementation happens to do. Covers: a typical interest-bearing loan, a
  zero-interest loan (payment splits evenly, no warnings), each warning
  trigger individually, and confirms the disclaimer is always present.

## 3. A real gap caught and fixed before finalizing, not after

While writing the response DTO, defined a `DISCLAIMER` constant in the
service but never actually wired it into any returned field — the response
had no disclaimer field at all in the first draft. Caught by re-reading
the finished service against the requirement ("MUST NOT encourage... must
promote informed decisions") before calling it done, not by a build error
(a missing-disclaimer bug doesn't fail to compile) — added a `disclaimer`
field to `LoanCalculationResponse` and confirmed it's populated on every
path, then added a test (`everyResponseCarriesTheDisclaimer`) specifically
so this can't silently regress later.

This is a different class of bug than the ones caught in earlier phases —
those were compile-time (wrong DTO shape) or logic-correctness (test
mismatches actual behavior); this one was a genuine "did the code actually
fulfill the requirement" gap that only re-reading against the brief itself
would catch. Worth naming because it's evidence the discipline needs to
include re-checking requirements, not just re-checking that code compiles
and tests are internally consistent.

## 4. What was explicitly NOT done this phase

- No real credit product, application, or offer — by design, permanently,
  not just "not yet."
- No AI assistant `EXPLAIN_CREDIT` intent yet (J.1) — this phase is the
  endpoint that intent will eventually call.
- No affordability-engine integration (e.g., checking a hypothetical
  repayment against the user's budget the way `AffordabilityService`
  checks a purchase) — the brief's §17 lists "potential credit repayments"
  as an affordability target; wiring that cross-reference is a reasonable
  small follow-up now that both services exist, deliberately deferred
  rather than rushed into this phase's diff.

## 5. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean.
- Backend: **not build-verified in this sandbox.** The amortization math
  was verified independently (§2) precisely because it can't be verified
  by compiling — a wrong formula that still type-checks would ship
  silently without either a real `mvn test` run or the independent
  cross-check performed here. Please still run `mvn clean test`.

## 6. Regression sweep performed

Grepped every instantiation site of `LoanCalculationResponse` and
`CalculateLoanRequest` — confined to the owning service and its test.
Re-viewed the full `MoneyController.java` after four sequential edits
across three phases to confirm no leftover braces or duplicate mappings.

## 7. Regression check against §35

Auth, Circle/Community Savings, MoMoMarket, MoMoRide, AI assistant, and
Payment code paths untouched.
