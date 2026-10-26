# Phase J.1 — AI Assistant Integration for Money Confidence Report

Date: 2026-09-16

## 1. Scope

Brief §20/§21: extend the existing AI assistant with new intents for the
Money Confidence domain (I.1–I.4), while strictly preserving the existing
propose→confirm→execute framework — no AI action bypasses authorization or
executes a mutation without explicit user confirmation.

New intents: `CREATE_BUDGET`, `CHECK_AFFORDABILITY`, `PLAN_IRREGULAR_INCOME`,
`CHECK_SCAM`, `EXPLAIN_CREDIT` (all confirmation-required, mutating), and
`COMPARE_PRODUCTS`, `COMPARE_BASKET`, `ANALYZE_SPENDING` (all read-only, no
confirmation — same treatment as the existing `SEARCH_MARKET`).

## 2. The confirmation-requirement decision

`AssistantDtos.ProposedAction`'s own existing comment stated
`requiresConfirmation` is "true for every mutating action... false only for
SEARCH_MARKET." Four of the five new mutating intents
(`CHECK_AFFORDABILITY`, `CHECK_SCAM`, `EXPLAIN_CREDIT`) technically only
*persist a diagnostic/history row* rather than moving money — arguably
lower-stakes than, say, `CREATE_BUSINESS`. I deliberately did **not**
expand the no-confirmation carve-out to cover them: the existing invariant
was a narrow, single, considered exception, and unilaterally widening a
stated security boundary in a financial app's AI-safety layer is not a
call this phase should make on its own judgment. All five new mutating
intents go through the exact same `AssistantProposal` persist → user
confirms → `execute()` re-validates flow as everything else, no exceptions.
The comment on `requiresConfirmation` was also updated to name the full,
now-six-item read-only list explicitly, so the next person to add an
intent has an accurate picture of the actual boundary, not a comment that
was already slightly stale (it never mentioned `FIND_RIDE`/`VIEW_TAXI`,
which pre-date this phase and are already read-only).

## 3. What was added

- `ActionType` gained the 8 new values. **Exhaustiveness verified by
  script**, not by eye: extracted every enum constant and every `case`
  label from the `execute()` switch expression and diffed them — Java
  switch expressions over an enum with no `default` branch require every
  constant covered, so a single miss here is a guaranteed compile failure,
  not a style nitpick. All 25 values (24 real + `NONE`) confirmed covered
  exactly once.
- `execute()` gained 5 real cases (calling `BudgetService`,
  `AffordabilityService`, `FinancialProfileService`, `ScamShieldService`,
  `CreditEducationService` respectively — five new constructor-injected
  dependencies) and one combined defensive-throw case for the three
  read-only intents, matching the existing `FIND_RIDE, VIEW_TAXI` pattern
  exactly.
- Every DTO record constructed inside these new cases
  (`CreateOrUpdateBudgetRequest`, `BudgetCategoryRequest`,
  `AssessAffordabilityRequest`, `UpdateFinancialProfileRequest`,
  `AssessScamRequest`, `CalculateLoanRequest`) had its **exact field order
  re-checked against the actual record declaration** before use — this is
  precisely the class of mistake that caused the Phase G.1 regression
  (stale positional-constructor call sites), so every one was verified
  fresh rather than assumed from memory of writing them in earlier phases.
- `CREATE_BUDGET`'s category-list parsing is hardened against a real gap:
  these DTOs are constructed manually here, not through `@Valid` HTTP
  binding, so Bean Validation annotations never fire. A null/blank
  category name or unparseable amount would otherwise reach the database
  as a raw constraint violation instead of a clean `ApiException`. Added
  an explicit null/blank check with a proper error message before
  constructing each `BudgetCategoryRequest`.
- The system prompt (used when a real AI Gateway is configured) gained
  parameter descriptions for all 8 new intents, including an explicit
  instruction for `CHECK_SCAM` to pass the user's message through
  **completely verbatim** — never summarized — since the ScamShield
  detection logic depends on exact wording, and an explicit instruction
  never to echo back a real OTP/PIN/password even if the user included one
  (the backend's `ScamShieldService.redact()` is the real safety net, but
  the prompt itself shouldn't casually repeat a secret either).
- The offline heuristic parser (used with zero AI Gateway configured — the
  project's stated default operating mode) gained real natural-language
  triggers for 4 of the 8: `CHECK_AFFORDABILITY` ("can I afford..."),
  `CHECK_SCAM` ("is this a scam..."), `EXPLAIN_CREDIT` ("what would a loan
  cost...", with amount/rate/term extraction), and `ANALYZE_SPENDING`
  ("how am I spending..."). `CREATE_BUDGET`, `PLAN_IRREGULAR_INCOME`,
  `COMPARE_PRODUCTS`, and `COMPARE_BASKET` are **deliberately left
  AI-Gateway-only** — see §4.
- `AssistantServiceHeuristicTest` gained 6 new tests covering the 4
  heuristic-reachable intents, including the "missing information, ask
  instead of guess" path for both affordability and credit (matching the
  existing pattern used elsewhere in this file, e.g. ride-offering).
- All three existing call sites that construct `AssistantService` directly
  (`AssistantServiceHeuristicTest`, and two sites in
  `AssistantServiceProposalTest`) were updated with the 5 new mock
  parameters — found via `grep -rn "new AssistantService("` across the
  entire `src` tree, not just the files I expected to need updating.

## 4. Why 4 of the 8 intents are AI-Gateway-only

`CREATE_BUDGET` needs a structured list of `{name, allocatedAmount}`
categories — reliably parsing "set up my budget: rent 3000, food 1200,
savings 500" with hand-written regex is a real natural-language-understanding
problem, and a wrong heuristic parse here would silently propose the wrong
financial plan to confirm, which is a worse failure mode than the feature
simply not having a heuristic shortcut. `PLAN_IRREGULAR_INCOME` similarly
needs to distinguish "my income is irregular" (a income-type statement) from
"I got 800 last week" (an income-log entry, which isn't even a distinct AI
intent yet — see §5) — genuinely ambiguous without an LLM. `COMPARE_PRODUCTS`
needs a `canonicalProductId`, which free text alone cannot provide (the
system prompt tells the AI only to propose this when the app already
supplied the id via context, mirroring how `ADD_TO_CART` already relies on
`req.productId()` rather than parsing a product name). `COMPARE_BASKET`
needs the same for a whole list of items. All four still have real `enum`
values and real `execute()` cases — they work correctly if an AI Gateway
with real language understanding produces them; they're just not reachable
through the zero-config heuristic fallback path, which is stated and
scoped explicitly rather than silently absent.

## 5. What was explicitly NOT done this phase

- No AI intent for logging a specific income entry (distinct from setting
  the income *type*) — flagged above as a real, reasonable follow-up.
- No mobile-app wiring — this phase is backend-only; the existing mobile
  Ask MoMo UI will pick up new intents automatically once the backend
  returns them (it already renders whatever `ProposedAction` it's given),
  but hasn't been specifically tested against these new ones.
- `COMPARE_PRODUCTS`/`COMPARE_BASKET` AI-Gateway behavior (whether a real
  configured LLM can actually resolve a canonical product id from
  conversation context) is unverified — this depends on how context is
  fed to the model, which is outside this phase's diff.

## 6. Verification performed

- Web frontend: `npm run build` passes clean (unaffected by this
  backend-only phase, confirmed rather than assumed).
- Backend: **not build-verified in this sandbox.** Given this phase
  touches the file where a real regression already happened once
  (`AssistantService.java`, Phase G.1's `CREATE_BUSINESS` DTO-shape bug),
  extra scrutiny went into this pass specifically: exhaustive switch
  coverage verified by script, every DTO field order re-checked against
  the live record declaration, every existing constructor call site found
  and updated, and structural brace/paren balance checked across every
  touched file. None of that replaces `mvn clean test` — please run it.

## 7. Regression sweep performed

- Exhaustive `ActionType` enum vs. `execute()` switch case comparison (§3).
- Every DTO constructed in the new cases checked against its actual record
  declaration, not assumed from earlier phases' memory.
- `grep -rn "new AssistantService("` across the entire `src` tree —
  confirmed exactly 3 call sites, all updated.
- Brace/paren balance check across `AssistantService.java`,
  `AssistantDtos.java`, and both modified test files.
- Confirmed `List`/`Map` already imported in `AssistantService.java`
  before relying on them in the new cases.

## 8. Regression check against §35

Auth, Circle/Community Savings, MoMoMarket, MoMoRide, and Payment code
paths untouched by logic changes — `AssistantService.java` gained new
cases and fields but every existing case/branch is byte-for-byte
unchanged. The AI assistant's core safety property (no execution without a
stored, confirmed proposal) applies identically to every new mutating
intent — none of the five new `execute()` cases bypass
`AssistantProposal`/ownership/expiry checks, which live earlier in the
same method and are untouched.
