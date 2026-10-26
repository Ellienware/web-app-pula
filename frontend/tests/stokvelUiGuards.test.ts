import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"

/**
 * Structural guards: a person is added to a stokvel (or a member is approved) only from the handler that a confirmation dialog
 * triggers. Previewing a code, searching, scanning and opening a request never call a membership-changing API.
 */
const CHANGING = /stokvelMembershipApi\.(accept|requestToJoin|approve|reject|createLink|revokeLink|saveSettings)\(/

function enclosingFunction(lines: string[], index: number): string | null {
  for (let i = index; i >= 0; i--) {
    const m = lines[i].match(/^\s*(?:async function (\w+)|const (\w+) = (?:\(\) =>|async \()|function (\w+))/)
    if (m) return m[1] ?? m[2] ?? m[3]
  }
  return null
}

function callSites(file: string): { fn: string | null; call: string }[] {
  const lines = readFileSync(new URL(file, import.meta.url), "utf8").split("\n")
  const out: { fn: string | null; call: string }[] = []
  lines.forEach((l, i) => { const m = l.match(CHANGING); if (m) out.push({ fn: enclosingFunction(lines, i), call: m[1] }) })
  return out
}

test("joining with a code happens only in the accept handler behind the confirmation dialog", () => {
  const sites = callSites("../src/pages/JoinStokvelPage.tsx")
  assert.deepEqual(sites, [{ fn: "accept", call: "accept" }])
  const src = readFileSync(new URL("../src/pages/JoinStokvelPage.tsx", import.meta.url), "utf8")
  assert.match(src, /<ConfirmDialog/)
  assert.match(src, /onConfirm=\{accept\}/)
  const open = src.slice(src.indexOf("async function open"), src.indexOf("useEffect(() =>"))
  assert.ok(open.includes("stokvelMembershipApi.preview("), "opening a code only previews")
  assert.ok(!/stokvelMembershipApi\.accept\(/.test(open))
})

test("asking to join from Discover happens only in the send handler behind the confirmation dialog", () => {
  const sites = callSites("../src/pages/DiscoverStokvelsPage.tsx")
  assert.deepEqual(sites, [{ fn: "send", call: "requestToJoin" }])
  const src = readFileSync(new URL("../src/pages/DiscoverStokvelsPage.tsx", import.meta.url), "utf8")
  assert.match(src, /onConfirm=\{send\}/)
})

test("approving, declining, inviting and settings changes in the admin panel run only from their handlers", () => {
  const sites = callSites("../src/components/stokvel/StokvelJoinPanel.tsx")
  const byFn = new Map<string | null, string[]>()
  for (const s of sites) byFn.set(s.fn, [...(byFn.get(s.fn) ?? []), s.call])
  assert.deepEqual(byFn.get("decide")?.sort(), ["approve", "reject"])
  assert.deepEqual(byFn.get("doRevoke"), ["revokeLink"])
  assert.deepEqual(byFn.get("createLink"), ["createLink"])
  assert.deepEqual(byFn.get("saveSettings"), ["saveSettings"])
  assert.equal(sites.length, 5)
  const src = readFileSync(new URL("../src/components/stokvel/StokvelJoinPanel.tsx", import.meta.url), "utf8")
  assert.match(src, /onConfirm=\{decide\}/)
  assert.match(src, /onConfirm=\{doRevoke\}/)
})

test("the members list and create modal never call a membership-changing API", () => {
  for (const f of ["../src/components/stokvel/StokvelMembersPanel.tsx", "../src/components/stokvel/CreateStokvelModal.tsx"]) {
    assert.equal(callSites(f).length, 0, f)
  }
  const create = readFileSync(new URL("../src/components/stokvel/CreateStokvelModal.tsx", import.meta.url), "utf8")
  assert.match(create, /stokvelApi\.create\(/, "creation uses the STOKVEL api")
  assert.doesNotMatch(create, /\/circles/, "never the generic circle endpoint")
})

test("the discovery API client only reads public discovery data and never sends an owner or user id", () => {
  const src = readFileSync(new URL("../src/lib/stokvelMembershipApi.ts", import.meta.url), "utf8")
  assert.doesNotMatch(src, /ownerId|ownerUserId|applicantId/)
  assert.ok(!/api\.(post|put)<[^>]*>\([^)]*\{[^}]*userId/.test(src), "no request body carries a user id")
  assert.match(src, /discover\?/)
})
