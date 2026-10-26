import { test } from "node:test"
import assert from "node:assert/strict"
import { hasSeenOnboarding, markOnboardingSeen, clampStep, ONBOARDING_KEY } from "../src/lib/onboardingState.ts"

const memory = () => { const m = new Map<string, string>(); return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) } }

test("shown until finished, then never again", () => {
  const s = memory()
  assert.equal(hasSeenOnboarding(s), false)
  markOnboardingSeen(s)
  assert.equal(hasSeenOnboarding(s), true)
  assert.equal(s.getItem(ONBOARDING_KEY), "done")
})

test("blocked or missing storage never throws", () => {
  const broken = { getItem: () => { throw new Error("denied") }, setItem: () => { throw new Error("denied") } }
  assert.equal(hasSeenOnboarding(broken), false)
  assert.doesNotThrow(() => markOnboardingSeen(broken))
  assert.equal(hasSeenOnboarding(null), false)
  assert.doesNotThrow(() => markOnboardingSeen(undefined))
})

test("step index is always valid", () => {
  assert.equal(clampStep(-3, 3), 0)
  assert.equal(clampStep(9, 3), 2)
  assert.equal(clampStep(1.9, 3), 1)
  assert.equal(clampStep(NaN, 3), 0)
})
