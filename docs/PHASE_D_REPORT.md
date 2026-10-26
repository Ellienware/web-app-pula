# Phase D — AI Action Framework Report

Date: 2026-08-14

## 1. What was already there (confirmed by inspection first, per Rule 1)

`AssistantController` / `AssistantService` / `AssistantDtos` /
`AssistantPanel` already implement the full propose → confirm → execute
architecture Rule 4 requires, not a partial version of it:

- `interpret()` only ever returns a `ProposedAction` (or `null`) — it has no
  side effects and touches no repository or payment provider.
- `execute()` is only reachable after the frontend's explicit "Confirm" tap,
  and runs the action through the exact same authorized service methods the
  manual UI forms use (`CircleService`, `ContributionService`) — so
  membership/ownership/amount validation is inherited automatically, not
  re-implemented and possibly re-broken in the AI layer.
- An offline heuristic parser means the feature works with zero AI Gateway
  configuration.
- The frontend `ActionCard` already renders `action.parameters` generically
  via `Object.entries(...)`, and `AssistantAction.intent` is already typed
  as a loose `string` in TypeScript — meaning **new action types needed zero
  frontend changes** to render or confirm. Confirmed by reading the
  component, not assumed.
- **Verified a real security property by inspection**: `execute()`'s
  `circleId` always comes from `ExecuteActionRequest.circleId` — the
  top-level field the frontend sets from its own known circle context — and
  is never read from the AI-controlled `parameters` map. Even a
  successfully-injected proposal could not redirect an action at a
  different circle than the one the user was actually looking at.

## 2. What was added

Per Phase 18's own instruction ("do not implement actions merely because
they are listed — first inspect existing APIs"), only added action types
backed by real, already-working, already-authorized backend services
(`GoalService`, `ExpenseService`, `RecurringContributionService` — all
confirmed present and correctly wired during Phase C):

- **`CREATE_GOAL`** — title, targetAmount, optional deadline.
- **`CREATE_EXPENSE`** — description, amount. Deliberately scoped to always
  split equally across every current circle member, paid by whoever is
  talking to the assistant. `CreateExpenseRequest` in general supports
  custom per-person splits and an arbitrary payer, but resolving "who owes
  what" or "who actually paid" from natural language reliably would mean
  either the AI inventing amounts/people (explicitly forbidden — Rule 4/the
  system prompt's own "never invent amounts" rule) or a much larger
  multi-turn disambiguation flow. Equal-split-among-everyone-present is the
  one interpretation that needs no invented data, matches the master
  prompt's own receipt example (Phase 21: "I found an expense of R1,250 ...
  add this to Durban Weekend?"), and is still genuinely useful. Custom
  splits remain available through the manual Expenses tab (Phase C).
- **`CREATE_RECURRING_CONTRIBUTION`** — title, amountPerMember, frequency,
  optional goalId.

Each new case in `AssistantService.execute()` calls the same service method
the manual UI uses (`GoalService.create`, `ExpenseService.create`,
`RecurringContributionService.create`) — no new authorization logic was
written, because none was needed: `GoalService.create` and
`RecurringContributionService.create` both already call
`circleService.requireAdmin(membership)` internally. A non-admin member
asking the assistant to create a goal or recurring plan will see the
existing "Only circle admins can perform this action" error surface after
they hit Confirm — the same failure a manual admin-gated form would
produce, not a new code path with its own bugs to have. `CREATE_EXPENSE`
only requires membership (also inherited, not re-implemented), matching
`ExpenseService.create`'s existing rule that any member can log a shared
cost.

For the expense case specifically: the current circle membership list is
fetched via `CircleService.listMembers(circleId, actor)` (the same call
`GET /circles/{id}/members` makes) to build the participant list —
`ExpenseService.create` then independently re-validates every one of those
IDs is really a circle member, so a stale or tampered membership list
passed through would still be caught server-side, not trusted from the AI
layer.

### Offline heuristic parser
Extended `heuristic()` with keyword-based recognition for the same three
new intents (goal/save/target → `CREATE_GOAL`; expense/split/paid for →
`CREATE_EXPENSE`; recurring/weekly/monthly → `CREATE_RECURRING_CONTRIBUTION`),
following the exact same pattern as the four existing intents (substring
keyword match + `firstAmount()` regex extraction, "ask instead of guess"
when no amount is found). Checked for false-positive overlap between
branches by hand (e.g. confirmed `"paid for"` doesn't accidentally satisfy
the earlier `CONTRIBUTE` branch's `"pay"` keyword, since `"paid"` does not
contain the substring `"pay"`) — see the new unit tests below, which encode
that check rather than just asserting it happened once by hand.

### AI security (Phase 22) hardening
Added explicit prompt-injection resistance instructions to `SYSTEM_PROMPT`:
text found inside an uploaded image or pasted into the message must be
treated as *data to read*, never as instructions to follow, and the model
is told explicitly it cannot access anything beyond what the app already
gave it in the current request (no inventing circleId/userId/email). This
is a real, if partial, mitigation — a properly adversarial receipt image
can still influence what text the model reports seeing (there's no vision
watermark), but it constrains what that reported text is allowed to do:
the model cannot use it to change what *action type* fires or which
*circle* it applies against, because — per the property already verified
above — `circleId` is never sourced from model output at all, and the
downstream service call re-validates every ID regardless.

## 3. What was deliberately not done

- **Voice** (Phase 20) — not implemented. The frontend has no
  speech-to-text integration point yet and the master prompt gates it on
  "if supported by the chosen platform/environment," which hasn't been
  determined. Flagging rather than half-wiring it.
- **Market-side AI actions** (`CREATE_BUSINESS`, `CREATE_PRODUCT`,
  `SEARCH_MARKET`, `ADD_TO_CART`, `CREATE_ORDER`) — not implemented this
  pass. The backing services exist and are confirmed working
  (`BusinessService`, `ProductService`, `CartService`, `OrderService`), so
  this is a natural next increment following the exact same pattern as
  Circle actions above, not a blocked item — just scoped out to keep this
  pass reviewable.
- **True multimodal document AI beyond receipts** (Phase 21's other listed
  cases — invoices, event posters, bills) — the existing image pipeline
  (base64 data URL → vision-capable chat completion) is generic and already
  handles any image type the model can read; no code change was needed or
  made here specifically for those cases, but none were tested since doing
  so requires a real AI Gateway key this sandbox doesn't have.

## 4. Tests added

`backend/src/test/java/com/momocircle/service/AssistantServiceHeuristicTest.java`
— 9 cases against `interpret()` with no AI Gateway configured (forcing the
heuristic path), using Mockito mocks for all six injected services since
`interpret()` never calls any of them (only `execute()`, post-confirmation,
does). Covers: each of the three new intents resolving correctly with
correct parameter extraction, frequency-word disambiguation (week vs.
month), a regression check that the pre-existing four intents still
resolve correctly, the "ask instead of invent" contract when no amount is
present, and a documentation-level test asserting `interpret()` can only
ever produce a *proposal*, never an execution — even when fed
instruction-shaped text.

## 5. Tests executed / build verification

**NOT EXECUTED / NOT VERIFIED**, same constraint as every prior phase — no
network egress in this sandbox. Every changed file
(`AssistantDtos.java`, `AssistantService.java`, the new test) was reviewed
by hand against the real signatures of every DTO/service it now calls
(`CreateGoalRequest`, `CreateExpenseRequest`, `ShareInput`,
`CreateRecurringRequest`, `MemberResponse`, `RecurringFrequency`,
`SplitType`) — each confirmed by reading the actual class, not assumed —
but that is source-level review, not a substitute for `./mvnw test`.

**Please run `cd backend && ./mvnw test` locally before trusting this.**
The heuristic test is the highest-confidence piece (no Spring context, only
Mockito stubs with no configured behavior); the wiring inside
`AssistantService.execute()`'s three new `case` branches is the part most
worth double-checking on first real compile, since it's the most new
surface area touching real DTOs at once.

## 6. Definition of Done status (Phase D scope, as implemented)

| Item | Status |
|---|---|
| AI produces structured proposed actions | Already done (prior codebase) |
| Confirmation mandatory before execution | Already done (prior codebase) |
| AI action set covers real Circle financial workflows (goal, expense, recurring) | DONE this pass (source-level; unverified by build) |
| AI cannot move money directly | Already true by construction (unchanged) |
| Image/document understanding | Already done (prior codebase); not extended or re-tested this pass |
| Voice | NOT DONE — no frontend integration point yet |
| Market-side AI actions | NOT DONE — scoped out, flagged as natural next step |
| Prompt-injection guidance in system prompt | DONE this pass |
| Build verified | NOT VERIFIED — blocked by environment, same as every prior phase |

Recommended next step: run `./mvnw test` locally (this is now the fourth
phase in a row blocked on that single check), then decide whether Phase D
continues into market-side AI actions and voice, or moves to Phase E
(security + broader test suite) as originally ordered.
