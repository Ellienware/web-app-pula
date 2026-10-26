import { test } from "node:test"
import assert from "node:assert/strict"
import {
  coachSourceLabel, goalStatusLabel, incomeSourceLabel, incomeTrustLabel, ITEM_KINDS, itemKindLabel, validateDateRange, validateGoalForm,
  INCOME_SOURCES, reminderLabel,
  categoryLabel, describeFutureError, GOAL_CATEGORIES, parseNonNegativeAmount, parsePercent, parseSignedPercent,
  percentHint, percentTotalBp, planStatusLabel, progressPercent,
} from "../src/lib/futureLogic.ts"

test("amounts normalise exactly and allow zero", () => {
  assert.deepEqual(parseNonNegativeAmount("0"), { ok: true, value: "0.00" })
  assert.deepEqual(parseNonNegativeAmount("R 1 000,5"), { ok: true, value: "1000.50" })
  assert.deepEqual(parseNonNegativeAmount("007.10"), { ok: true, value: "7.10" })
})
test("bad amounts are rejected", () => {
  for (const bad of ["", "-1", "1.234", "1,000.50", "abc", "1.2.3", "999999999999"]) {
    assert.equal(parseNonNegativeAmount(bad).ok, false, bad)
  }
})
test("percent parsing and bounds", () => {
  assert.deepEqual(parsePercent("10%"), { ok: true, value: "10" })
  assert.deepEqual(parsePercent("12,5"), { ok: true, value: "12.5" })
  for (const bad of ["", "101", "-1", "1.234", "x"]) assert.equal(parsePercent(bad).ok, false, bad)
})
test("signed percent respects the backend bounds", () => {
  assert.equal(parseSignedPercent("-50", -50, 50, "Return").ok, true)
  assert.equal(parseSignedPercent("51", -50, 50, "Return").ok, false)
  assert.equal(parseSignedPercent("-0.5", 0, 50, "Inflation").ok, false)
})
test("percent totals use integer basis points", () => {
  assert.equal(percentTotalBp(["10", "5", "10"]), 2500)
  assert.equal(percentTotalBp(["33.33", "33.33", "33.34"]), 10000)
  assert.equal(percentHint(10000), null)
  assert.ok(percentHint(10001))
})
test("progress is clamped and safe", () => {
  assert.equal(progressPercent(50, 200), 25)
  assert.equal(progressPercent(300, 200), 100)
  assert.equal(progressPercent(0, 200), 0)
  assert.equal(progressPercent(10, 0), 0)
})
test("labels", () => {
  assert.equal(categoryLabel("EMERGENCY_FUND"), "Emergency fund")
  assert.equal(categoryLabel(null), "Uncategorised")
  assert.equal(new Set(GOAL_CATEGORIES.map((c) => c.value)).size, GOAL_CATEGORIES.length)
})
test("plan statuses never imply money moved", () => {
  for (const s of ["DRAFT", "ACTIVE", "PAUSED", "ARCHIVED"]) {
    assert.doesNotMatch(planStatusLabel(s).toLowerCase(), /invested|transferred|settled|paid/)
  }
})
test("errors never leak internals", () => {
  assert.equal(describeFutureError(new Error("<html>502</html>")), "Something went wrong. Please try again.")
  assert.equal(describeFutureError("x"), "Something went wrong. Please try again.")
  assert.equal(describeFutureError(new Error("Plan not found")), "Plan not found")
})

test("income trust labels never call simulated or manual income verified", () => {
  for (const s of INCOME_SOURCES) {
    assert.doesNotMatch(incomeTrustLabel(s.value).toLowerCase(), /\bverified (income|settlement)\b|bank-verified|confirmed by (the )?bank/)
  }
  assert.match(incomeTrustLabel("MERCHANT_SIMULATED_SETTLEMENTS"), /simulated/)
  assert.match(incomeTrustLabel("BUSINESS_MANUAL_ENTRIES"), /unverified/)
  assert.equal(incomeSourceLabel("USER_ENTERED"), "A figure I enter")
})
test("only business sources need a business", () => {
  assert.deepEqual(INCOME_SOURCES.filter((s) => s.needsBusiness).map((s) => s.value),
    ["BUSINESS_MANUAL_ENTRIES", "MERCHANT_SIMULATED_SETTLEMENTS"])
})
test("date range validation", () => {
  assert.equal(validateDateRange("2026-10-01", "2026-10-31"), null)
  assert.ok(validateDateRange("", "2026-10-31"))
  assert.ok(validateDateRange("2026-02-30", "2026-03-01"))
  assert.ok(validateDateRange("2026-10-31", "2026-10-01"))
  assert.ok(validateDateRange("2025-01-01", "2026-10-01"))
  assert.equal(validateDateRange("2026-10-05", "2026-10-05"), null)
})
test("business item kinds: only the emergency cash reserve is a reserve", () => {
  assert.deepEqual(ITEM_KINDS.filter((k) => k.treatedAs === "Reserve").map((k) => k.value), ["EMERGENCY_RESERVE"])
  assert.equal(itemKindLabel("PAYROLL"), "Payroll")
})
test("goal status wording says nothing about money moving", () => {
  assert.match(goalStatusLabel("PAUSED"), /not accepting contributions/)
  for (const s of ["ACTIVE", "PAUSED", "REACHED", "CLOSED"]) assert.doesNotMatch(goalStatusLabel(s).toLowerCase(), /paid|transferred|invested|settled/)
})
test("goal form validation", () => {
  assert.equal(validateGoalForm("", "100", "").ok, false)
  assert.equal(validateGoalForm("Laptop", "0", "").ok, false)
  assert.equal(validateGoalForm("Laptop", "abc", "").ok, false)
  assert.equal(validateGoalForm("Laptop", "100", "30/11/2026").ok, false)
  assert.deepEqual(validateGoalForm("Laptop", "1 500,5", "2026-11-30"), { ok: true, target: "1500.50" })
})
test("coach and reminder labels", () => {
  assert.match(coachSourceLabel("ai"), /AI model/); assert.match(coachSourceLabel("fallback"), /no AI/)
  assert.equal(reminderLabel("PLAN_REVIEW"), "Monthly plan review")
})
