# PHASE 9C - Split bills and shared expenses on the shared ledger

All settlement in this phase is **simulated demo-wallet value**. Paying a share moves PULA Money demo funds between two wallets
through the Phase 9A ledger. It does not pay the real bill and is never described as real-world settlement.

## Verification status (read this first)

| Check | Result |
|---|---|
| `SplitCalculator` (pure Java, 20,000-case property run + 22 assertions), compiled with `jdk.compiler` and executed | **PASS** (22/22) |
| Web `splitBillLogic` unit tests (`node --experimental-strip-types --test tests/splitBillLogic.test.ts`) | **PASS** (9/9) |
| Mobile `splitBillLogic` unit tests (`tests/splitBillLogic.test.mjs`) | **PASS** (9/9) |
| TypeScript: `splitBillLogic.ts` type-checked with `tsc --strict` | **PASS** |
| TypeScript/TSX syntax parse of every new/changed web and mobile file | **PASS** (syntax only; no type or import resolution) |
| Backend JUnit/Mockito tests (`SplitCalculatorTest`, `SplitBillLifecycleTest`, `SplitBillSettlementTest`, updated characterisation tests) | **NOT RUN** (no Maven, no network) |
| Database tests: Flyway V46 on H2/PostgreSQL, JPA `ddl-auto=validate`, JPQL guarded updates (`SplitBillSettlementH2Test`) | **NOT RUN** |
| Backend compile of the Spring code | **NOT RUN** (reviewed by hand only; expect to fix compile slips on first build) |
| Web `tsc && vite build` | **NOT RUN** (no `node_modules`) |
| Mobile `tsc`, Expo build, device/simulator check | **NOT RUN** |
| Web/mobile regression of existing screens | **NOT RUN** |

Commands to run: `cd backend && mvn test` (includes the H2 test), `cd frontend && npm ci && npm run build && node --experimental-strip-types --test tests/*.test.ts`,
`cd mobile && npm ci && npx tsc --noEmit && node --experimental-strip-types --test tests/*.test.mjs`.

## 1. Audit of what already existed

* `expenses` / `expense_shares` (V1), `ExpenseService`, `ExpenseController`, web `ExpensesTab` + `ExpenseModal`, mobile `CreateExpenseSheet`. Equal and custom splits, any circle type, any member can record.
* Circle roles OWNER/ADMIN/MEMBER (+ stokvel roles). Leaving or being removed **deletes** the membership row; expense rows keep `users` FKs, so history survives.
* Phase 9B: `WalletService.transfer` (ownership, balance, limits, idempotency key), `PaymentRequestService` (claim -> ledger -> mark paid), `LedgerOperationsService.reverse` (operator only), `LedgerEngine` (no overdraft).
* Notifications: `NotificationService`; activity feed: `ActivityService`; audit: `AuditLogService`.
* No payment-request or consent concept for expenses.

**Findings in the existing code (all fixed):**
1. `settleShare` let the **debtor or any circle admin mark a share paid** with no money moving: a debtor could clear their own debt, an admin could clear anyone's.
2. Any member could record that **someone else paid**, attributing debts to people who never agreed.
3. Custom amounts with more than 2 decimals were silently rounded.
4. No consent: added participants simply owed money.
5. No edit, cancel, history or due date.

**Reuse decision.** The `expense_shares` row *is* the participant's split-bill payment request (amount, requester = payer, consent status, link to ledger transaction). I did **not** reuse `wallet_payment_requests`: it can only be created by the requester, expires after at most 7 days and is built around QR codes, so it does not fit a per-share lifecycle with due dates, disputes and edits. What *is* reused is the Phase 9B pattern (guarded claim, deterministic ledger key, definitive-refusal release, sweep) and the Phase 9B transfer service itself. No second group, expense, transfer or balance system was created. "Friends" and "households" are small Circles; communities are Circles of any type.

## 2. Schema migration

`backend/src/main/resources/db/migration/V46__split_bills.sql` (forward-only; V41/V44 are intentionally absent as before).

* `expenses`: `note`, `created_by_id`, `expense_date`, `due_date`, `status` (ACTIVE/CANCELLED), `revision`, `lock_version`, `client_request_key` (unique per creator), cancel metadata; split types now EQUAL/CUSTOM/PERCENTAGE/ITEM_BASED (CHECK).
* `expense_shares`: `status`, `percentage_bp`, `settlement_method` (LEDGER/SELF/EXTERNAL/LEGACY/NONE), `ledger_transaction_id` and `refund_transaction_id` (FK to `ledger_transactions`, each UNIQUE), `claim_user_id`, `settle_round`, response fields. CHECKs: a LEDGER-paid share names its ledger transaction; PROCESSING has a claimant; REFUNDED names the refund transaction.
* New: `expense_items`, `expense_item_assignees`, `expense_events` (immutable history).
* Backfill: settled shares become PAID (`SELF` for the payer's own share, `LEGACY` otherwise, **no ledger transaction invented**); unsettled shares become PENDING (nobody is treated as having consented).

## 3. Split-calculation rules (`expense/SplitCalculator.java`, integer minor units, no floating point)

* Amounts are held in hundredths; more than 2 decimals is refused, never rounded.
* **Equal**: `floor(total/n)` each; leftover cents one each to the first participants in list order.
* **Percentage**: integer basis points summing to exactly 10,000 (two decimals max). `floor(total*bp/10000)`, leftover cents to the largest fractional remainders, ties to the earlier participant.
* **Custom**: exact amounts, must sum to the total.
* **Item-based**: each item split equally among its assignees (leftover cents to the first assignees in participant order); items must sum to the total and every item needs an assignee.
* `POST .../expenses/preview` uses the same code path as create; clients never do money arithmetic.
* `recordedAmount` (what the bill cost) is separate from `transferredAmount` (simulated funds moved, net of refunds), `externalSettledAmount` and `refundedAmount`.

## 4. Consent, settlement, corrections

* Creating or asking again never debits anyone. A participant may accept, dispute (reason required), decline; the payer/creator may ask again.
* **Pay** (`POST /api/split-bills/shares/{id}/pay`): only the participant; needs `confirm=true` plus the amount and currency they reviewed (re-checked server-side). One guarded UPDATE claims the share (matching its amount) -> `WalletService.transfer` with key `expshare:<id>:r<round>` -> guarded UPDATE to PAID naming the ledger transaction. Marked PAID only after the transfer returned. Retries and double taps replay the same ledger transaction. Definitive refusals (insufficient funds, limits) release the claim; unknown outcomes keep it and a sweep finishes or releases stale claims. No overdraft or credit: insufficient funds leaves the share unpaid.
* **Repaid outside PULA** (replaces the old settle): only the payer, recorded as `EXTERNAL`, moves nothing, can be retracted.
* **Refund** = a new, explicitly confirmed ledger transfer from the payer back to the participant (never an edit of the original). Refused with insufficient funds if the payer spent it. **Operator reversal** uses the ledger's own reversal and is likewise refused if funds are gone.
* **Edit** (creator only, stale `expectedRevision` refused): settled shares are never rewritten (edit refused until refunded/retracted); changed unsettled shares return to PENDING and the participant is notified; removed participants stay as CANCELLED rows.
* **Cancel** (creator or circle admin): refused while any share is settled by wallet or outside PULA, or processing. Path: refund each wallet payment / retract each external record, then cancel. History is kept.
* **Former members**: rows stay; they keep read access to their own expense and can still respond and pay (routes under `/api/split-bills` need no circle membership).

## 5. APIs

Circle-scoped (`/api/circles/{circleId}/expenses`): `GET`, `POST` (optional `Idempotency-Key`), `POST /preview`, `PUT /{id}`, `POST /{id}/cancel`.
Membership-free (`/api/split-bills`): `GET /mine?role=OWE|OWED`, `GET /expenses/{id}`, `GET /expenses/{id}/history`, `POST /shares/{id}/accept|dispute|decline|reopen|mark-received|retract-received|pay|refund`, `POST /admin/shares/{id}/reverse` (operator).
**Removed:** `POST /api/circles/{c}/expenses/{e}/shares/{s}/settle`.
Wallet errors for the new controller use the existing `WalletExceptionAdvice` (added to its scope).

## 6. Frontend

Web: `components/splitbill/ExpenseFormModal.tsx` (create/edit, review step), `ExpenseDetailModal.tsx` (shares, actions, confirm dialogs, history), rewritten `circle/ExpensesTab.tsx`, new route `/split-bills` (`pages/SplitBillsPage.tsx`), `lib/splitBillApi.ts`, `lib/splitBillLogic.ts`. `ExpenseModal.tsx` removed.
Mobile: `components/SplitBillSheet.tsx` (replaces `CreateExpenseSheet`), `app/split-bills/index.tsx`, `app/split-bills/[expenseId].tsx`, `services/splitBillApi.ts`, `lib/splitBillLogic.ts`, `types/splitBill.ts`; circle Expenses section links to them.
States covered: loading, empty, error with retry, validation, paid/unpaid/disputed/overdue/cancelled/refunded/reversed. Every screen carries the simulated notice.

## 7. Files changed

See the file lists in the delivered archives; backend: entities `Expense`, `ExpenseShare`, new `ExpenseItem`, `ExpenseEvent`, enums, repositories, `ExpenseService`, new `ExpenseSettlementService` + sweeper, `ExpenseController`, new `SplitBillController`, `ExpenseDtos`, `WalletExceptionAdvice`, V46; tests `SplitCalculatorTest`, `SplitBillLifecycleTest`, `SplitBillSettlementTest`, `SplitBillSettlementH2Test`, updated `SavingsFixture`, `ExpenseCharacterisationTest`, `SavingsAuthorisationCharacterisationTest`.

## 8. Intentional behaviour changes to Phase 2A characterisation

1. The debtor/any admin could mark paid -> only the payer can record repayment outside PULA.
2. A plain member could record that another member paid -> now 403 (circle OWNER/ADMIN may record on behalf).
3. Custom amounts are validated (no silent rounding).
4. `ExpenseService` constructor gained dependencies (fixture updated).

## 9. Unsupported / limits

* The demo wallet supports only its default currency; shares in another circle currency show a clear "cannot be settled here" reason (the record and consent flow still work).
* Item-based split has no tax/tip lines or per-item uneven weights.
* No part-payments of a share; no multi-payer expenses; no recurring split bills.
* No push/SMS beyond the existing in-app notification mechanism (`PAYMENT_SUCCESS` also triggers the existing SMS hook).
* Percent split input accepts two decimals only.
* A crash between the refund ledger commit and the share update is recovered by repeating the refund (same key replays); there is no automatic sweep for refunds.
* Concurrency of the ledger itself is covered by the Phase 9A tests; the settlement tests here simulate the transfer service, and true database concurrency is only covered by the (NOT RUN) H2 test.
