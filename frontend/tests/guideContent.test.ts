import { test } from "node:test"
import assert from "node:assert/strict"
import { ONBOARDING_STEPS, FAQ, SCAM_TIPS, FORBIDDEN_CLAIMS } from "../src/lib/guideContent.ts"

const allText = () => [...ONBOARDING_STEPS.map((s) => `${s.title} ${s.body}`), ...FAQ.map((f) => `${f.question} ${f.answer}`), ...SCAM_TIPS.map((t) => t.tip)]

test("onboarding states plainly that money is simulated and PULA is not a bank", () => {
  const demo = ONBOARDING_STEPS.find((s) => s.id === "demo")!
  assert.match(demo.body, /not a bank/i)
  assert.match(demo.body, /simulation|simulated/i)
  assert.match(demo.body, /no real money moves/i)
})

test("copy never claims licence, guarantees, insurance or a named commercial partner", () => {
  for (const text of allText()) {
    for (const re of FORBIDDEN_CLAIMS) {
      // "guaranteed returns" appears only inside a warning sign, which is a negation of the claim.
      if (re.source.includes("guarantee") && /warning sign|never|not\b/i.test(text)) continue
      assert.doesNotMatch(text, re, `forbidden claim in: ${text}`)
    }
  }
})

test("FAQ answers the money-integrity questions directly", () => {
  const byId = Object.fromEntries(FAQ.map((f) => [f.id, f.answer]))
  assert.match(byId["real-money"], /^No\./)
  assert.match(byId["bank"], /^No\./)
  assert.match(byId["confirm"], /^No\./)
  assert.match(byId["refunds"], /nothing is sent back to a real account/i)
})

test("ids are unique and content is non-empty", () => {
  for (const list of [ONBOARDING_STEPS, FAQ, SCAM_TIPS]) {
    const ids = list.map((i) => i.id)
    assert.equal(new Set(ids).size, ids.length)
  }
  for (const t of allText()) assert.ok(t.trim().length > 10)
  assert.equal(ONBOARDING_STEPS.length, 3)
})
