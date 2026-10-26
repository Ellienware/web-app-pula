import { test } from "node:test"
import assert from "node:assert/strict"
import { planLineDescription, planOutcome, planNotes, planStatusWord } from "../src/lib/planView.ts"

const fmt = (n: number) => `R${n.toFixed(2)}`
const calc = (over: Partial<{ valid: boolean; remaining: number; warnings: string[] }> = {}) => ({
  valid: true, incomeBasis: 1000, allocatable: 800, totalAllocated: 800, remaining: 0, warnings: [] as string[], createdAt: null, ...over,
})
const plan = (over = {}) => ({
  id: "p", name: "Plan", status: "ACTIVE", reserves: 0, commitments: 0, minOperatingBuffer: 0, effectiveReserves: 0, effectiveCommitments: 0,
  lines: [{ label: "Savings", type: "PERCENT", fixedAmount: null, percent: 20 }], items: [], ...over,
})

test("line descriptions read naturally", () => {
  assert.equal(planLineDescription({ label: "a", type: "FIXED", fixedAmount: 500, percent: null }, fmt), "R500.00 fixed")
  assert.equal(planLineDescription({ label: "a", type: "PERCENT", fixedAmount: null, percent: 20 }, fmt), "20% of what is available")
  assert.equal(planLineDescription({ label: "a", type: "PERCENT", fixedAmount: null, percent: 12.5 }, fmt), "12.5% of what is available")
  assert.equal(planLineDescription({ label: "a", type: "PERCENT", fixedAmount: null, percent: null }, fmt), "Percent (not set)")
})

test("no calculation yet says nothing moves", () => {
  const o = planOutcome(null, fmt)
  assert.equal(o.tone, "none")
  assert.match(o.detail, /nothing moves/i)
})

test("outcome words cover over, under, exact and invalid", () => {
  assert.equal(planOutcome(calc({ remaining: -50 }), fmt).headline, "Over-planned by R50.00")
  assert.equal(planOutcome(calc({ remaining: 120.5 }), fmt).headline, "R120.50 not yet planned")
  assert.equal(planOutcome(calc({ remaining: 0 }), fmt).tone, "ok")
  const bad = planOutcome(calc({ valid: false, warnings: ["Percent lines exceed 100%."] }), fmt)
  assert.equal(bad.tone, "invalid")
  assert.match(bad.detail, /exceed 100%/)
})

test("an exact plan is still described as an estimate, never an instruction", () => {
  assert.match(planOutcome(calc(), fmt).detail, /not an instruction to move money/i)
})

test("plan notes flag empty, non-active and over-100% plans", () => {
  assert.deepEqual(planNotes(plan()), [])
  assert.ok(planNotes(plan({ lines: [] })).some((n) => /no lines/i.test(n)))
  assert.ok(planNotes(plan({ status: "DRAFT" })).includes("This plan is a draft."))
  const heavy = plan({ lines: [{ label: "a", type: "PERCENT", fixedAmount: null, percent: 70 }, { label: "b", type: "PERCENT", fixedAmount: null, percent: 50 }] })
  assert.ok(planNotes(heavy).some((n) => /120%/.test(n)))
})

test("status wording matches the existing Future screens", () => {
  assert.equal(planStatusWord("ACTIVE"), "Active planning rule")
  assert.equal(planStatusWord("WEIRD"), "WEIRD")
})
