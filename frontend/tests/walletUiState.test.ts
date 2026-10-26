import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { createGuard, reviewView, roleDescription, txBadgeLabel, visibleTabs } from "../src/lib/walletLogic.ts"

const base = { direction: "OPEN_LINK" as const, canPay: false, canReject: false }

test("review card: every request state shows the right message and only the server-allowed buttons", () => {
  const open = reviewView({ ...base, status: "OPEN", canPay: true }, false)
  assert.equal(open.message, null)
  assert.equal(open.showPay, true)
  assert.equal(open.showDone, false)

  const targeted = reviewView({ ...base, direction: "INCOMING", status: "OPEN", canPay: true, canReject: true }, false)
  assert.equal(targeted.showReject, true)

  assert.match(reviewView({ ...base, status: "PROCESSING" }, false).message!, /being processed/)
  assert.match(reviewView({ ...base, status: "EXPIRED" }, false).message!, /expired/)
  assert.match(reviewView({ ...base, status: "CANCELLED" }, false).message!, /cancelled/)
  assert.match(reviewView({ ...base, status: "REJECTED" }, false).message!, /declined/)

  for (const s of ["EXPIRED", "CANCELLED", "REJECTED", "PAID"] as const) {
    const v = reviewView({ ...base, status: s }, false)
    assert.equal(v.showPay, false, `${s}: cannot pay`)
    assert.equal(v.showDone, true, `${s}: offers Done`)
  }
})

test("review card: a payment completed on this screen says simulated and no settlement; an earlier payment is plain", () => {
  const now = reviewView({ ...base, status: "PAID" }, true)
  assert.equal(now.kind, "paid-now")
  assert.match(now.message!, /simulated/i)
  assert.match(now.message!, /No real-world settlement/)
  const earlier = reviewView({ ...base, status: "PAID" }, false)
  assert.equal(earlier.kind, "paid-earlier")
  assert.match(earlier.message!, /already been paid/)
})

test("review card: your own open request cannot be paid and says so", () => {
  const own = reviewView({ ...base, direction: "OUTGOING", status: "OPEN", canPay: false }, false)
  assert.equal(own.kind, "own")
  assert.equal(own.showPay, false)
  assert.equal(own.showDone, true)
})

test("the client never offers Pay unless the server said canPay", () => {
  for (const status of ["OPEN", "PROCESSING", "PAID", "REJECTED", "CANCELLED", "EXPIRED"] as const) {
    assert.equal(reviewView({ ...base, status, canPay: false }, false).showPay, false, status)
  }
})

test("transaction badges read naturally", () => {
  assert.equal(txBadgeLabel("POSTED"), "Completed")
  assert.equal(txBadgeLabel("PENDING"), "Pending")
  assert.equal(txBadgeLabel("FAILED"), "Failed")
  assert.equal(txBadgeLabel("CANCELLED"), "Cancelled")
  assert.equal(txBadgeLabel("REVERSED"), "Reversed")
})

test("double-submit guard: concurrent calls run the action once; it is usable again afterwards, even after a failure", async () => {
  const guard = createGuard()
  let calls = 0
  let release!: () => void
  const gate = new Promise<void>((r) => { release = r })
  const slow = async () => { calls++; await gate; return "done" }
  const first = guard.run(slow)
  assert.equal(guard.busy, true)
  const second = await guard.run(slow) // double tap while the first is in flight
  const third = await guard.run(slow)
  assert.equal(second, undefined)
  assert.equal(third, undefined)
  release()
  assert.equal(await first, "done")
  assert.equal(calls, 1, "the money-moving action ran exactly once")
  assert.equal(guard.busy, false)

  await assert.rejects(guard.run(async () => { throw new Error("boom") }), /boom/)
  assert.equal(guard.busy, false, "a failure releases the lock")
  assert.equal(await guard.run(async () => "again"), "again")
})

// ---- structural guard: money-moving calls only ever happen from the confirmation handler -------------------------------

const MONEY_CALL = /(?:walletApi|scope)\.(transfer|payRequest|demoFunding|requestWithdrawal|cancelWithdrawal)\b/g

function functionBlocks(src: string): { name: string; body: string }[] {
  const out: { name: string; body: string }[] = []
  const re = /async function (\w+)\s*\(/g
  let m: RegExpExecArray | null
  while ((m = re.exec(src))) {
    let i = m.index + m[0].length
    let depth = 1
    while (i < src.length && depth > 0) { if (src[i] === "(") depth++; else if (src[i] === ")") depth--; i++ }
    const open = src.indexOf("{", i)
    let d = 0, j = open
    for (; j < src.length; j++) { if (src[j] === "{") d++; else if (src[j] === "}") { d--; if (d === 0) break } }
    out.push({ name: m[1], body: src.slice(open, j + 1) })
  }
  return out
}

const FILES = [
  "../src/components/wallet/WalletSummary.tsx",
  "../src/components/wallet/SendFlow.tsx",
  "../src/components/wallet/PayFlow.tsx",
  "../src/components/wallet/ActivityList.tsx",
]

test("money-moving API calls occur only inside the confirm handler that a ConfirmDialog triggers", () => {
  for (const rel of FILES) {
    const src = readFileSync(new URL(rel, import.meta.url), "utf8")
    const blocks = functionBlocks(src)
    const allowed = new Set(["confirm", "doCancel"])
    const callCount = (src.match(MONEY_CALL) ?? []).length
    assert.ok(callCount > 0, `${rel}: expected at least one money-moving call`)
    let inside = 0
    for (const b of blocks) {
      const n = (b.body.match(MONEY_CALL) ?? []).length
      if (n > 0) assert.ok(allowed.has(b.name), `${rel}: money call inside "${b.name}", not a confirmation handler`)
      if (allowed.has(b.name)) inside += n
    }
    assert.equal(inside, callCount, `${rel}: every money-moving call must be inside confirm/doCancel`)
    assert.match(src, /<ConfirmDialog/, `${rel}: must render a ConfirmDialog`)
    assert.match(src, /onConfirm=\{(confirm|doCancel)\}/, `${rel}: ConfirmDialog must be wired to the confirm handler`)
  }
})

test("resolving or scanning a code never calls a money-moving API", () => {
  const src = readFileSync(new URL("../src/components/wallet/PayFlow.tsx", import.meta.url), "utf8")
  const resolve = functionBlocks(src).find((b) => b.name === "resolve")
  // resolve is a useCallback arrow in PayFlow; assert on the whole region from `const resolve` to `const onScanned`
  const region = src.slice(src.indexOf("const resolve"), src.indexOf("const onScanned"))
  assert.equal((region.match(MONEY_CALL) ?? []).length, 0)
  assert.equal(resolve, undefined)
  const scanner = readFileSync(new URL("../src/components/wallet/QrScanner.tsx", import.meta.url), "utf8")
  assert.equal((scanner.match(MONEY_CALL) ?? []).length, 0, "the scanner never touches the money API")
})

test("business wallet tabs follow the permissions the server granted; personal wallets show everything personal", () => {
  assert.deepEqual(visibleTabs("personal", []), ["wallet", "send", "request", "pay", "activity"])
  const staff = ["VIEW", "REQUEST"]
  const finance = ["VIEW", "REQUEST", "SEND", "WITHDRAW", "FUND"]
  const owner = [...finance, "MANAGE_MEMBERS"]
  assert.deepEqual(visibleTabs("business", staff), ["wallet", "request", "activity"])
  assert.deepEqual(visibleTabs("business", finance), ["wallet", "send", "request", "activity"])
  assert.deepEqual(visibleTabs("business", owner), ["wallet", "send", "request", "activity", "members"])
  assert.deepEqual(visibleTabs("business", []), ["wallet"])
  // a business never shows the personal pay-a-code tab: customers pay a business through their own wallet
  for (const p of [staff, finance, owner]) assert.ok(!visibleTabs("business", p).includes("pay"))
})

test("role descriptions say what each role can do", () => {
  assert.match(roleDescription("OWNER"), /manag/i)
  assert.match(roleDescription("FINANCE"), /send, withdraw/)
  assert.match(roleDescription("STAFF"), /request payments only/)
})

test("the members screen and scanner never call a money-moving API", () => {
  for (const f of ["MembersPanel.tsx", "QrScanner.tsx"]) {
    const src = readFileSync(new URL(`../src/components/wallet/${f}`, import.meta.url), "utf8")
    assert.equal((src.match(MONEY_CALL) ?? []).length, 0, f)
  }
})

test("business money calls are all bound to the business-scoped endpoints, never the personal ones", () => {
  const src = readFileSync(new URL("../src/lib/walletApi.ts", import.meta.url), "utf8")
  const biz = src.slice(src.indexOf("export function businessScope"))
  assert.ok(biz.includes("${base}/transfers") && biz.includes("${base}/demo-funding") && biz.includes("${base}/withdrawals"))
  assert.ok(!/\$\{W\}\//.test(biz), "businessScope must not use the personal /money/wallet endpoints")
})
