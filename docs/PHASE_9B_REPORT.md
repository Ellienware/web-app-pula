# Phase 9B: PULA Money demo wallet, identity, QR and internal payments

**Status: IMPLEMENTED (including business wallets), NOT ACCEPTED.** Built on Phase 9A, which was itself never accepted (`ci/verify_phase9a.py` still prints
`GATE: NOT ACCEPTED`; every required ledger test class is `NOT RUN`). Phase 9B was built on that unverified ledger by explicit
instruction ("Option B"). Nothing here may be treated as working until the Phase 9A gate and the Phase 9B commands below pass
on a machine with Maven, Docker (Testcontainers/PostgreSQL) and Node.

All value is SIMULATED demo funds. No payment provider, bank API or external withdrawal exists in this phase.

## 1. What was and was not verified (exact results)

| Check | Result |
|---|---|
| Phase 9A gate (`python3 ci/verify_phase9a.py --backend backend`) | **NOT ACCEPTED** (all 8 ledger classes + full suite NOT RUN, no Surefire reports) |
| Backend `mvn test` incl. new `WalletFlowsH2Test` (31), `BusinessWalletFlowsH2Test` (19), `WalletEndToEndSecurityTest` (14), `BusinessWalletEndToEndTest` (8), `BusinessWalletEngineTest` (9), `QrTokenServiceTest` (5), `WalletControllerTest` (5) | **NOT RUN** as a Maven build: no Maven, Docker or network in the build sandbox |
| Pure-logic backend tests executed for real with a tiny JUnit shim (no Spring, no database): the EXISTING 9A `LedgerEngineTest` (21) and `LedgerOperationsAuditTest` (6), the new `BusinessWalletEngineTest` (9), and `QrTokenServiceTest` (5) | **PASS 41/41**. This is the first real execution of 9A's pure engine tests and is a genuine regression check on the 9A-core changes in section 9. It is NOT the Maven run and says nothing about persistence |
| Flyway V42/V43 against H2 / PostgreSQL; Spring start-up; Hibernate `ddl-auto=validate` for the new entities and the new `owner_business_id` column | **NOT RUN** |
| V42 constraint logic exercised in SQLite (16 accept/reject cases) | **PASS 16/16** (logic only; SQLite is not H2/PostgreSQL). V43 was not exercised the same way |
| All new backend main code compiled with the real JDK 21 compiler against hand-written framework stubs | **PASS** (exit 0). Proves types/signatures against the REAL repo classes; does not prove Spring wiring, JPQL or SQL |
| `WalletFlowsH2Test` and `BusinessWalletFlowsH2Test` type-checked against the real services (stubbed Spring/JUnit) | **PASS** (exit 0). `WalletEndToEndSecurityTest`, `BusinessWalletEndToEndTest`, `WalletControllerTest`: syntax only (MockMvc/Mockito absent) |
| Web: `node --test tests/*.test.ts` (walletLogic 7, walletUiState 12, attemptKey, api) | **PASS 29/29** |
| Web: strict `tsc` on `walletLogic.ts`, `walletTypes.ts`, `walletApi.ts` | **PASS** (exit 0) |
| Web: `npm run build`, full `tsc`, TSX type-check | **NOT RUN**: `node_modules` absent, no network. TSX files only syntax-parsed (0 errors) |
| Mobile: `node --test tests/*.test.mjs` (walletLogic 7, walletUiState 12, qrEncode 3, attemptKey 4) | **PASS 26/26** |
| Mobile: strict `tsc` on `walletLogic.ts`, `types/wallet.ts`, `qrEncode.ts` | **PASS** (exit 0) |
| Mobile: full `tsc --noEmit` (screens, `walletApi.ts`, `useWalletScope.ts`), Expo build, device run, camera scan | **NOT RUN**: no `node_modules`, no device. Files syntax-parsed only |
| Mobile QR encoder validated by an independent decoder (OpenCV `QRCodeDetector`) | **PASS 6/6** (versions 1, 4, 6; the 48-char payment code decodes exactly) |
| UI states (loading/empty/error/confirmation/pending/expired/rejected/cancelled/paid) | **Logic tested, rendering NOT tested.** The review-card state machine, the double-submit guard, tab visibility by role, and a structural guard (money-moving calls only inside the `confirm`/`doCancel` handler wired to a `ConfirmDialog`; scanning/resolving/member screens never call a money API) run as unit tests on web and mobile. Component rendering is NOT tested: no React test renderer is installed in either package |
| Regression of existing PULA Money tools / PULA Ride | **NOT RUN** (Maven). Verified by diff instead: see section 8 |

To run (on a machine with the toolchain):

```
python3 ci/verify_phase9a.py --backend backend          # 9A gate first; must be ACCEPTED
cd backend && mvn -B test                                # includes the 9B tests
cd backend && mvn -B -Dtest=WalletFlowsH2Test test
cd backend && mvn -B -Dtest='WalletEndToEndSecurityTest,BusinessWalletEndToEndTest,BusinessWalletFlowsH2Test,WalletFlowsH2Test' test
cd frontend && npm ci && npm run build && node --test tests/*.test.ts
cd mobile  && npm ci && npx tsc --noEmit && node --test tests/*.test.mjs
```

## 2. Files

Modified, wallet-related and additive: `LedgerTransactionRepository.java` (+`findByProviderReference`), `LedgerPostingRepository.java` (+`sumByAccountAndDirection`), web `App.tsx` (+2 routes), web `MoneyPage.tsx` (+link card),
mobile `app/(tabs)/money/index.tsx` (+link card).

New, backend: `V42__wallet_payment_requests.sql`, `V43__business_wallets.sql`, opt-in `db/postgresql-guards/V44__ledger_guard_business_owner.sql`; business: `domain/wallet/{BusinessWalletRole,BusinessWalletProfileEntity,BusinessWalletMemberEntity}`, `repository/wallet/{BusinessWalletProfileRepository,BusinessWalletMemberRepository}`, `service/wallet/{BusinessWalletAccessService,BusinessWalletMemberService}`, `controller/BusinessWalletController`; `domain/wallet/{WalletProfileEntity,WalletPaymentRequestEntity,PaymentRequestStatus}`;
`repository/wallet/{WalletProfileRepository,WalletPaymentRequestRepository}`; `service/wallet/{WalletService,PaymentRequestService,
WalletIdentityService,WalletHistoryQuery,PaymentRequestSweeper}`; `wallet/{QrTokenService,WalletException,WalletExceptionAdvice}`;
`dto/WalletDtos`; `controller/{WalletController,WalletAdminController}`; tests: `WalletFlowsH2Test`, `BusinessWalletFlowsH2Test`, `WalletEndToEndSecurityTest`, `BusinessWalletEndToEndTest`, `BusinessWalletEngineTest`, `QrTokenServiceTest`, `WalletControllerTest`.

New, web: `lib/{walletApi,walletLogic,walletTypes}.ts`, `pages/{WalletPage,AdminWalletPage}.tsx` (WalletPage also exports the business pages), `components/wallet/MembersPanel.tsx`, `components/wallet/*` (SimulatedBanner, WalletSummary,
SendFlow, RequestFlow, PayFlow, ActivityList, QrScanner, useGuard, wallet.css), `tests/{walletLogic,walletUiState}.test.ts`.

New, mobile: `app/wallet/{index,send,request,pay,activity,business,members}.tsx`, `lib/useWalletScope.ts`, `components/wallet/{shared,rows}.tsx`, `lib/{qrEncode,walletLogic,useGuard}.ts`,
`services/walletApi.ts`, `types/wallet.ts`, `tests/{walletLogic,walletUiState,qrEncode}.test.mjs`.

## 3. Migration

`V42__wallet_payment_requests.sql` adds two NEW tables only: `wallet_profiles` (user_id, opaque public_id) and
`wallet_payment_requests`. No balance column exists anywhere in it; balances remain solely in the Phase 9A ledger.
Constraints: status CHECK, amount > 0, currency format, requester != target/payer, PAID requires payer and ledger tx,
UNIQUE(public_ref), UNIQUE(ledger_transaction_id) (one ledger transaction settles at most one request), FKs to users and
ledger_transactions.

**Flyway ordering hazard (inherited from 9A):** the opt-in `db/postgresql-guards/V41` shares the global version space. If it is
enabled after V42 is applied, Flyway flags V41 as out of order unless `spring.flyway.out-of-order=true`. Enable and verify the
guards before applying V42, or enable out-of-order.

`V43__business_wallets.sql` (see section 9): adds `ledger_accounts.owner_business_id`, REPLACES the three 9A CHECK constraints so `BUSINESS_WALLET`
is a legal kind, adds `UNIQUE(owner_business_id, currency, kind)`, and creates `business_wallet_profiles` (opaque `BW-` ids) and
`business_wallet_members` (FINANCE/STAFF only; the owner is derived from `businesses.owner_id`), plus `wallet_payment_requests.requester_business_id`.
The opt-in `V44` guard extends the 9A PostgreSQL update-guard to freeze `owner_business_id`; apply it only where `V41` is applied.

## 4. API contract (`/api/money/wallet/*`, JWT required, user derived from the session)

| Method and path | Purpose |
|---|---|
| `GET /` | overview: wallet id (`PW-...`), balance string from the ledger, limits, simulated flag |
| `GET /transactions?page&size&type&status&direction&from&to` | paged ledger-backed history |
| `GET /transactions/{reference}` | detail by `SIM-...` reference (404 if the wallet took no part) |
| `POST /demo-funding` `{amount}` + `Idempotency-Key` | simulated issuance within 9A limits |
| `POST /recipients/lookup` `{identifier}` | exact wallet id/email/phone to masked name + wallet id (15/min) |
| `POST /transfers` `{recipientId, amount}` + `Idempotency-Key` | internal simulated transfer (201 new, 200 replay) |
| `POST /withdrawals` `{amount}` + key; `POST /withdrawals/{ref}/cancel` | simulated withdrawal hold / cancel |
| `POST /payment-requests` `{amount, purpose, payerRecipientId?, expiresInMinutes?}` | create request |
| `GET /payment-requests?box=outgoing\|incoming`, `GET /payment-requests/{id}` | list / read (requester or target only) |
| `POST /payment-requests/resolve` `{code}` | review a scanned/pasted code; read-only for funds |
| `POST /payment-requests/pay` `{code, expectedAmount, expectedCurrency}` | the ONLY path that moves value for a request |
| `POST /payment-requests/reject` `{code}`, `POST /payment-requests/{id}/cancel` | decline (target only) / cancel (requester only) |
| `GET /admin/withdrawals`, `POST /admin/withdrawals/{ref}/complete\|fail`, `GET /admin/reconcile/{walletId}` | ADMIN role only; pending-withdrawal queue, simulated outcomes, per-wallet reconciliation |

Business wallets, base `/api/money/business-wallets` (a business is addressed only by its opaque `BW-` id; every call passes `BusinessWalletAccessService`):

| Method and path | Permission |
|---|---|
| `GET /` (business wallets I can act on: id, name, role, permissions) | any authenticated user |
| `GET /{id}`, `GET /{id}/transactions[/{ref}]`, `GET /{id}/payment-requests[/{reqId}]` | VIEW |
| `POST /{id}/demo-funding` (+ `Idempotency-Key`) | FUND |
| `POST /{id}/recipients/lookup`, `POST /{id}/transfers` (+ key; recipient may be `PW-` or `BW-`) | SEND |
| `POST /{id}/withdrawals` (+ key), `POST /{id}/withdrawals/{ref}/cancel` | WITHDRAW |
| `POST /{id}/payment-requests`, `POST /{id}/payment-requests/{reqId}/cancel` | REQUEST |
| `GET/POST /{id}/members`, `PUT/DELETE /{id}/members/{memberWalletId}` | MANAGE_MEMBERS (owner only) |

Roles: OWNER (derived from the business, all permissions), FINANCE (VIEW, REQUEST, SEND, WITHDRAW, FUND), STAFF (VIEW, REQUEST). A user with no role gets 404
(indistinguishable from an unknown wallet); a member lacking the permission gets 403 `FORBIDDEN_ROLE`. Customers pay a business through the normal
`/api/money/wallet/payment-requests/pay` with the QR code; the business wallet is credited, never the owner's personal wallet. Business members cannot pay
their own business's request (`SELF_PAYMENT`).

Errors: `{timestamp,status,error,code,message}` with stable codes (`INSUFFICIENT_FUNDS`, `IDEMPOTENCY_CONFLICT`, `REQUEST_EXPIRED`,
`INVALID_CODE`, `REQUEST_CHANGED`, `ALREADY_PAID`, `IN_PROGRESS`, `SELF_PAYMENT`, `TRY_AGAIN`, ...). Ledger/wallet errors are mapped by
`WalletExceptionAdvice` (wallet controllers only); 401/429/validation still go through the existing `GlobalExceptionHandler`.

## 5. Frontend routes and screens

Web: `/money/wallet` (tabs Wallet, Send, Request, Pay a code, Activity) linked from `/money`; `/money/wallet/business` and `/money/wallet/business/:walletId` (business wallets: same screens as the personal wallet, tabs by role, plus a People tab for owners); `/admin/wallet` (operator queue for simulated withdrawals + wallet reconciliation; server enforces ADMIN). Mobile: `/wallet`, `/wallet/send`,
`/wallet/request`, `/wallet/pay`, `/wallet/activity`, linked from the Money tab. Both: simulated banner on every screen, confirmation
dialog before every money-moving action, double-submit guard (`useGuard`) plus idempotency keys reused only while an outcome is unknown
(`AttemptKey`), loading/empty/error/pending/expired/rejected/cancelled/paid states, accessible labels. QR display: web uses the existing
`qrcode` package; mobile uses a built-in encoder (`lib/qrEncode.ts`) because no QR library was installed and none could be verified
against SDK 57 offline. Scanning: web uses `BarcodeDetector` + camera where supported, otherwise paste; mobile uses the already-installed
`expo-camera` through a new wallet-specific modal (the taxi `QrScanModal` is untouched).

## 6. Security decisions

- Owner is never client-supplied. Every endpoint uses `currentUser.require()`; ledger calls use `Actor.ofUser(principal)`; wallets are
  looked up/created from the principal. A wallet id in a body only NAMES a counterparty and is resolved server-side to an active user.
- Other users are addressed by an opaque `PW-` id, never an internal id; names are masked ("Thandi M."). Recipient lookup is exact-match only and rate limited.
- Transactions the caller's wallet did not take part in return 404 (no existence leak); targeted payment requests are invisible (`INVALID_CODE`) to everyone but requester and target.
- QR/share code `PULAPAY1.<128-bit random ref>.<HMAC-SHA256 tag>` carries no amount, identity or instruction; the signature is verified before any lookup. Amount, status and expiry are read from the server at confirmation; the client must echo the amount it reviewed (`REQUEST_CHANGED` on mismatch). A scan/resolve never moves funds.
- Double payment: payer first CLAIMS the request with one guarded UPDATE (OPEN to PROCESSING); only the claimant reaches the ledger, with deterministic key `payreq:<id>`. Ledger-definitive refusals release the claim; unknown outcomes keep it so the same payer's retry replays the same ledger transaction. Stale claims are settled by a sweeper (paid if the ledger posted, else released).
- No second balance authority; all postings via `LedgerOperationsService`; corrections use 9A reversals.
- Business access is decided only in `BusinessWalletAccessService` from server-side facts (business owner or a `business_wallet_members` row). The ledger engine adds a second, independent layer: a business wallet can be debited only by an `Actor.forBusiness(user, thatBusiness)`, such an actor can never debit a personal wallet, and a plain user actor (even the business owner acting as themselves) can never debit a business wallet. Platform ADMIN does not imply business access. Business and personal client idempotency keys are scoped separately so one key cannot collide across wallets.
- Rate limits via the existing `ServiceRateLimiter` on funding, lookup, transfer, withdrawal, request create, resolve, pay.
- `QrTokenService` secret: `app.wallet.qr-secret`, defaulting to a value derived from the JWT secret. Set it explicitly in production.

## 7. Remaining limitations and open decisions

1. Phase 9A is unverified; every 9B guarantee that depends on the ledger (atomicity, idempotency, overdraft safety) is only as good as 9A.
2. Nothing was executed against a real database, Spring context or device. Expect first-run fixes (JPQL, Hibernate validation, test-slice wiring in `WalletFlowsH2Test`, MockMvc wiring in `WalletEndToEndSecurityTest`).
3. **Business wallets are implemented but change the 9A ledger core** (section 9), which is itself unverified. Known limits: a delegated member's per-user demo-funding cap is shared between their personal and business funding; a business withdrawal can be cancelled only by the member who requested it (or an operator); businesses are one per owner in the existing schema (`findByOwnerId`), so a user who owns a business has at most one owned business wallet plus any wallets they are a member of; a business cannot yet pay a payment request as a payer; the business wallet is not shown in the admin withdrawal queue's owner column (reference and amount only).
4. UI rendering is not covered by automated tests (only the extracted state logic and a structural guard; see section 1). The end-to-end test uses MockMvc, not a browser.
5. Unauthenticated requests are answered 403 by the existing security config (no custom entry point), while the web client treats only 401 as "session expired". This is pre-existing behaviour and was left unchanged; the end-to-end test accepts either status.
6. A stuck PROCESSING claim after a crash blocks other payers until the sweeper settles it (60 min default; `app.wallet.stale-processing-minutes`).
7. Payment requests have no memo on transfers (the ledger has no memo field); `purpose` lives on the request only.
8. The web QR scanner depends on browser `BarcodeDetector`; paste is the fallback. Mobile has no clipboard copy (no `expo-clipboard`); the wallet id and code are selectable text. There is no admin screen on mobile (operator work is web/API).
9. Production: set `app.wallet.qr-secret` (env `APP_WALLET_QR_SECRET`) explicitly; decide the V41/V42 Flyway ordering (section 3).

## 8. Existing functionality

Diff against the Phase 9A archives: 12 pre-existing files changed (11 backend/web, 1 mobile). Seven are the 9A ledger core and its test,
described in section 9: `Account`, `Actor`, `LedgerEngine`, `LedgerAccountEntity`, `LedgerAccountRepository`, `LedgerAccountService`,
`AbstractMigrationUpgradeTest`. Two are one-method additions to ledger repositories (`LedgerTransactionRepository`, `LedgerPostingRepository`).
Three are UI links/routes: web `App.tsx` (imports and routes), web `MoneyPage.tsx` (one link card), mobile Money tab (one link card).
`MoneyController`, the profile/income/budget/affordability/credit/scam code, and all PULA Ride code (taxi payments, bookings, drivers,
payment requests, USSD) are byte-identical. The demo wallet is not wired to taxi fares, supplier orders or payouts.
Existing automated tests were NOT RUN under Maven, so "preserved" rests on the diff plus the pure-logic run of the existing 9A engine tests, not on a green suite.

## 9. Changes to the Phase 9A ledger core (needs re-verification)

Business wallets required editing 9A itself, which was unverified. Every change is small and listed here so 9A can be re-checked:

- `Account` record: new kind `BUSINESS_WALLET`, new component `ownerBusinessId`, a compatibility constructor keeps the old 5-argument form, and the compact constructor enforces the invariants (business wallet has a business owner, no user owner, never negative; no other kind has a business owner).
- `Actor` record: new component `actingBusinessId` (compat constructor kept) and `Actor.forBusiness(userId, businessId)`, documented as creatable only after `BusinessWalletAccessService` verified access.
- `LedgerEngine.requireWallet` accepts both wallet kinds; `requireOwner` implements the rule in section 6 (business wallets only for a matching business actor; a business actor never touches a personal wallet).
- `LedgerAccountEntity`/`LedgerAccountRepository`/`LedgerAccountService`: `owner_business_id`, `getOrCreateBusinessWallet`, and `ensureSystemAccount` refuses `BUSINESS_WALLET`.
- `V43` replaces the 9A CHECK constraints (`ck_ledger_acct_kind`, `ck_ledger_wallet_owner`, `ck_ledger_wallet_nonneg`) and adds a unique constraint.
- 9A migration tests assumed V40 is the latest version (`"40"`); that was already false once V42 existed. `AbstractMigrationUpgradeTest` now pins the V39-to-V40 tests to `target("40")` and adds latest-version tests (>= 43) including an upgrade over populated data.
- Verified: the 21 existing `LedgerEngineTest` and 6 `LedgerOperationsAuditTest` tests still pass after these changes (pure-logic run, section 1). NOT verified: persistence, the PostgreSQL guard tests, and every 9A test that needs a database.
