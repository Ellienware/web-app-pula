# Phase E — Security Hardening Report

Date: 2026-08-17

## 1. Approach

Per the master brief's own Phase 47 checklist (hard-coded secrets, CORS,
missing authorization, IDOR, SQL injection, XSS, insecure file uploads,
weak password handling, JWT weaknesses, sensitive logging, missing
validation), this pass audited the codebase against each item by reading
the actual implementation — not assuming based on earlier phases' reports —
and fixed what was actually found broken. Several areas were checked and
found **already solid** (documented below so the audit trail is complete,
not just the fixes); three real, concrete gaps were found and fixed.

## 2. What was already solid (verified, not just assumed)

- **IDOR**: spot-checked `OrderService.get` (buyer-or-seller check),
  `ReceiptService.get` (payer-only), `NotificationService.markRead`
  (recipient-only), `AdminController` (all 10 endpoints call
  `currentUser.requireAdmin()` — confirmed 1:1 by count), and
  `BusinessService.requireOwnership`/`ProductService` (seller can't touch
  another seller's product). Every one of these enforces ownership at the
  service layer, not just hidden UI, consistent with every prior phase's
  own findings.
- **SQL injection**: no native queries or string-concatenated JPQL
  anywhere in the codebase — every query is either a Spring Data derived
  method or a parameterized `@Query` using `:param` binding.
- **XSS**: no `dangerouslySetInnerHTML` anywhere in the frontend; React's
  default JSX escaping covers every render path.
- **JWT**: uses the modern jjwt 0.12+ API (`Jwts.builder()...signWith(key)`,
  `Jwts.parser().verifyWith(key)`), which binds verification to a specific
  key/algorithm rather than trusting the token's own `alg` header — not
  vulnerable to classic algorithm-confusion/`alg:none` attacks. Prod fails
  fast with no default secret (confirmed unchanged from Phase B).
- **File uploads**: none exist in this codebase — the only binary-ish input
  is the AI assistant's `imageDataUrl`, addressed in §3.
- **Invitation tokens**: `UUID.randomUUID()` (JDK-backed by
  `SecureRandom`, not a weak PRNG), single-use via status transition
  (`PENDING` → `ACCEPTED`/`DECLINED`/`CANCELLED`/`EXPIRED`), expiry
  enforced before accept.
- **CORS**: `allowed-origins` defaults to a single explicit dev origin
  (`http://localhost:5173`), never a wildcard; `allowCredentials(true)` is
  paired with an explicit origin list, not `"*"` (which Spring would
  reject at runtime anyway).
- **H2 console**: `spring.h2.console.enabled: true` only appears in the
  `dev` profile block, not `prod` — genuinely dev-only, not just gated by
  a security-config matcher that happens to also permit it in prod.
- **Password policy**: minimum 8 characters enforced via `@Size` — in line
  with current NIST guidance (length over forced complexity rules).
- **Scheduled reconciliation job**: runs as a background system process
  operating directly on payment records, doesn't depend on or bypass any
  user-scoped authorization path — no security-context concern.

## 3. What was found and fixed

### Unhandled exceptions were completely unlogged
`GlobalExceptionHandler`'s catch-all `Exception` handler returned a generic
"Unexpected error" to the client (correct — no stack trace or internal
detail leaked) but **never logged the actual exception anywhere**. A real
bug causing a 500 would be invisible server-side — no way to diagnose it,
contradicting the brief's own "financial errors must be traceable without
exposing secrets" requirement. Fixed: every unhandled exception is now
logged server-side (full stack trace, via SLF4J) against a generated
`requestId`, and that same `requestId` (only that — no other detail) is
returned to the client. `ApiError` on the frontend now surfaces it
automatically in the displayed message (e.g. "Unexpected error (ref:
abc-123)") — a one-line change to the shared HTTP client that every
existing `err.message` display across ~15+ pages benefits from without
per-page changes.

### AI image input had zero validation
`AssistantService`'s `imageDataUrl` field was forwarded straight to the AI
Gateway (or silently accepted and ignored on the heuristic path) with no
check on format or size — a client could send an arbitrarily large payload
(cost/DoS risk against the AI Gateway) or non-image data smuggled through
the field. Fixed: `validateImageDataUrl()` now rejects anything not
starting with `data:image/` and anything decoding to over 8MB, computed
from the base64 string length directly (not by decoding first, so a huge
string can't be used to force a large in-memory buffer just to reject it).

### Zero brute-force protection on login
`POST /api/auth/login` had no rate limiting, lockout, or delay of any
kind — unlimited password guesses against any account. Added
`LoginRateLimiter`: a self-contained, in-process, per-email fixed-window
counter (default 8 failed attempts / 15 minutes, both configurable),
deliberately **not** Redis-backed — this app is a single-instance modular
monolith per the brief's own "no unnecessary infrastructure" guidance, and
a per-process limiter is a real, meaningful mitigation for that deployment
shape. Wired into `AuthService.login()`: checked before the password
comparison, recorded on every failure (unknown email and wrong password
both go through the same path, so probing many emails is rate-limited too,
not just repeated guesses against one account), cleared on success so a
legitimate user who mistyped their password isn't punished after getting
it right.

**Explicitly not done**: IP-based limiting (would need threading
`HttpServletRequest` down to `AuthService`, a larger change deferred to
keep this pass minimal and self-contained) and a distributed/Redis-backed
version (would need real justification — this app doesn't run multiple
instances today).

## 4. What was deliberately not done (Phase 23's larger items)

Per the master brief's own Phase 23 list — refresh tokens, refresh
rotation, token revocation/logout blacklist, session/device management,
password reset, email verification — **none of these were implemented this
pass**. They're real, legitimate features, but they're substantial new
functionality (new entities, new flows, new UI), not hardening of what
exists — out of scope for a security-audit pass per the brief's own
"harden the existing architecture, don't add unrelated features"
instruction that's applied consistently since Phase D.1.1. Flagged here as
known gaps, not silently skipped.

## 5. Tests added

- `LoginRateLimiterTest` — 5 cases: attempts under the limit are allowed,
  the configured threshold locks out, a successful login clears the prior
  failure count, limiting is scoped per-email (one account's lockout
  doesn't affect another), and email matching is case/whitespace
  normalized (closes an obvious evasion).

`validateImageDataUrl` and the `GlobalExceptionHandler` logging change
weren't given dedicated unit tests this pass — both are small, direct
`ApiException`/logging calls whose correctness is easy to verify by
reading the code, and adding tests for them would mean either mocking
SLF4J's `Logger` (low value) or duplicating `AssistantServiceHeuristicTest`
infrastructure for two lines of validation logic. Flagged as a reasonable
gap rather than silently omitted.

## 6. Build verification

**BLOCKED BY ENVIRONMENT** — same confirmed total network block from Phase
D.1.1 (`x-deny-reason: host_not_allowed` on every domain tested, including
ones on this sandbox's own allowed list) and the same absent Maven
toolchain. Re-confirmed this pass rather than assumed carried over:

```
$ curl -sI https://registry.npmjs.org
HTTP/2 403, x-deny-reason: host_not_allowed
$ which mvn
(not found)
```

Every file touched this pass was reviewed by hand for import correctness
and call-site consistency (confirmed no other test or service constructs
`AuthService` or references `GlobalExceptionHandler` in a way this change
would break). That is not a substitute for `./mvnw test`.

## 7. Definition of Done status

| Item | Status |
|---|---|
| IDOR / authorization audited | DONE — verified by reading code, not assumed |
| SQL injection / XSS audited | DONE — no vulnerable pattern found |
| JWT implementation audited | DONE — already solid |
| Unhandled exceptions traceable without leaking internals | DONE this pass |
| AI image input validated (format + size) | DONE this pass |
| Login brute-force protection | DONE this pass, with tests |
| Refresh tokens / revocation / password reset / email verification | NOT IMPLEMENTED — explicitly out of scope, flagged not silently skipped |
| Build verified | BLOCKED BY ENVIRONMENT (documented, confirmed again this pass) |

## Summary

Three real, concrete security/observability gaps found and fixed this
pass — unlogged/untraceable 500 errors, unvalidated AI image input, and
completely absent login brute-force protection — plus a documented audit
trail of everything checked and found already solid (IDOR, SQL injection,
XSS, JWT, CORS, tokens, H2 console scoping). Larger Phase 23-style features
(refresh tokens, password reset, etc.) were deliberately left out as
genuine new functionality rather than hardening. As with every phase
before it, nothing has actually compiled — the network and toolchain
blockers are unchanged and were re-confirmed, not assumed, this pass.
