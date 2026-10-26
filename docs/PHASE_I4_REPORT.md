# Phase I.4 — ScamShield Report

Date: 2026-09-15

## 1. Scope

Brief §19: users submit a suspicious message/payment request/URL; the
system flags common risk indicators (urgency, requests for OTP/PIN/
password, suspicious links, impersonation, unexpected payment requests)
and returns LOW/MEDIUM/HIGH risk with reasons. Hard constraint from the
brief itself: **never ask users to submit their actual passwords, PINs,
OTPs, or other secrets.**

This phase completes the Money Confidence domain (I.1–I.4). Next per the
plan is J.1/J.2 — wiring these four features into the AI assistant's
existing propose→confirm→execute framework.

## 2. The safety design this phase turns on

`ScamShieldService` has two strictly separate responsibilities, in a fixed
order: `redact()` always runs **before** anything is persisted, stripping
any 4–8 digit sequence that follows a keyword like "OTP", "PIN", "CVV", or
"password" — so if a user pastes a forwarded scam message that happens to
contain their own real bank OTP, that value is never written to the
database. Only after redaction does `score()` run its (separate) keyword
heuristic over the now-safe text. `ScamAssessment.submittedText` is
documented, in its own javadoc, as always being the redacted version —
this is stated as an invariant a future developer can rely on, not just an
implementation detail.

The risk-indicator keyword lists are deliberately short and pattern-level
(a handful of phrases per category, e.g. "otp", "verify your account",
"confirm your details" for the sensitive-info category) rather than an
exhaustive, mechanism-annotated catalogue of scam phrasing — a short list
of clear categories has real protective value without functioning as a
tuning guide for writing scam messages that dodge detection.

`LOW` risk is never phrased as "this message is safe" — every LOW-risk
response with no indicators found explicitly says this isn't a guarantee,
only that this heuristic's specific known indicators weren't present.

## 3. What was added

- `ScamRiskLevel` enum, `ScamAssessment` entity, `V13__scam_assessments.sql`.
- `ScamShieldService`: `redact()` (regex-based, keyword + trailing digit
  sequence), five weighted indicator rules (sensitive-info request,
  urgency, unexpected payment, too-good-to-be-true, institution mention)
  plus a separate link check (extra weight for known URL-shortener
  domains, since a shortened link hiding its real destination is a
  stronger signal than a link on its own). Score buckets: ≥7 HIGH, ≥4
  MEDIUM, else LOW.
- `POST /api/money/scams`, `GET /api/money/scams` on a new, dedicated
  `ScamShieldController` (kept separate from `MoneyController`, matching
  the codebase's existing convention of one controller per sub-feature
  within a domain rather than one large controller).
- Web frontend: a ScamShield card on `/money` — paste a message, get a
  risk level, indicators, and guidance. The prompt text itself tells the
  user never to paste a real PIN/password/OTP, ahead of the safety net the
  redaction provides.
- `ScamShieldServiceTest`, weighted toward the redaction logic specifically
  (multiple codes in one message, colon-separated, unrelated numbers left
  untouched) — the test file's own header note says why: a redaction bug
  is a much worse failure mode than a wrong risk classification, so it
  gets the most scrutiny.

## 4. A test bug caught and fixed before finalizing

The first draft of `lowRiskGuidanceNeverClaimsTheMessageIsSafe` banned the
literal word "genuine" from the guidance text — but the actual guidance
correctly says "that's not a guarantee it's genuine," the *correct* hedge,
which would have failed that crude substring check. A test that fails on
correct behavior is worse than no test. Fixed to check for the absence of
an affirmative safety claim ("this is safe", "is a genuine") and the
presence of the hedge ("not a guarantee") instead of banning a word that
can appear in exactly the sentence we want.

## 5. What was explicitly NOT done this phase

- No AI-assistant `CHECK_SCAM` intent yet (J.1) — this phase is the
  endpoint that intent will eventually call, mirroring the AI assistant's
  existing propose→confirm→execute pattern where applicable (though a
  scam check is read-only, so it likely doesn't need the full proposal
  machinery — that's a decision for the J.1 phase, not this one).
- No reputation/allowlist system for URLs — flagging "contains a link" and
  "contains a shortened link" is the full scope; anything more (domain
  reputation, real-time lookups) is a distinct, larger feature.
- No image/screenshot analysis — text only, per the brief's own listed
  input types (SMS, WhatsApp text, payment requests).

## 6. Verification performed

- Web frontend: `npm run build` (tsc + vite) passes clean.
- Backend: **not build-verified in this sandbox.** The redaction regex in
  particular is exactly the kind of logic that can look right and still
  have an edge case a compiler can't catch — please run `mvn clean test`
  and, given what's at stake if redaction has a gap, consider trying a few
  manual inputs against a running instance too.

## 7. Regression sweep performed

Grepped every instantiation site of `ScamAssessmentResponse` and
`AssessScamRequest` — confined to the owning service and its test. This
phase added a new controller rather than extending `MoneyController`
further, so no risk of the DTO-shape drift seen in earlier phases within
that file.

## 8. Regression check against §35

Auth, Circle/Community Savings, MoMoMarket, MoMoRide, AI assistant, and
Payment code paths untouched. No existing file was modified in this phase
— every change is a new file.

## 9. Money Confidence domain — summary across I.1–I.4

| Phase | Feature | Status |
|---|---|---|
| I.1 | FinancialProfile, IncomeRecord, Budget, irregular-income planner | Done |
| I.2 | Affordability engine | Done (explicitly scoped around the no-stored-balance limitation) |
| I.3 | Responsible credit educational calculator | Done |
| I.4 | ScamShield | Done |

None of these four have been wired into the AI assistant yet, and none of
this has been verified against a real `mvn test` run since Phase G.2. Four
phases of new domain logic have now accumulated on an unverified backend —
I'd strongly recommend a build/test checkpoint before starting the AI
integration phase (J.1/J.2), which will need to correctly call into all
four of these services through the assistant's proposal framework.

## 10. Addendum: real bug found by the actual `mvn test` run

A real `mvn clean test` run (155 tests, 153 passing) found a genuine bug in
`redact()` that no amount of code review had caught: the gap pattern
between a keyword and its digits was `[^0-9a-zA-Z]{0,20}` - "any
non-alphanumeric character" - which excludes *letters*. That correctly
bridges punctuation/whitespace ("PIN: 4321", "OTP 111111") but silently
fails to bridge a connecting word like "is" or "number" ("OTP **is**
123456", "PIN **number is** 4321") because those words contain letters the
character class explicitly excluded. Two tests failed
(`redactsOtpFollowedByDigits`, `assessedTextStoredIsAlwaysTheRedactedVersion`)
on exactly this phrasing.

This is the most important bug caught in the whole MoMoCircle evolution
work so far - not because of its complexity, but because of what it
protects: a redaction gap in ScamShield means a real OTP could have been
persisted to the database in exactly the phrasing most people actually
use ("your OTP is 123456"), while the narrower phrasing used in most of
the test suite ("OTP 123456") happened to work and masked the gap.

Fixed by widening the gap to `[^0-9]{0,20}` - any non-digit character,
letters included - verified against all six existing test phrasings plus
two new ones specifically covering word-connector phrasing
(`redactsThroughAConnectingWordNotJustPunctuation`) before resubmitting,
using an independent Python regex check (identical semantics for this
pattern) rather than re-reasoning through the Java engine by eye a second
time, given eye-reasoning is exactly what missed this the first time.

**Takeaway for the rest of this project**: pattern-matching logic over
free text is a category where "I traced through it by hand and it looks
right" is not reliable - this is the second time in this codebase's
history a hand-traced regex/logic bug only surfaced under a real test run
(the first being the `MoMoRideService` lambda issue, though that was a
compile error, not a silent logic gap like this one). Any future
free-text pattern matching in this codebase should get either a real test
run before being trusted, or at minimum the same independent
cross-check-in-another-language treatment given to this fix and to the
credit calculator's amortization math in Phase I.3.
