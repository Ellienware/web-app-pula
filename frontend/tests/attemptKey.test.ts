import { test } from "node:test"
import assert from "node:assert/strict"
import { AttemptKey } from "../src/lib/attemptKey.ts"

function counter() {
  let n = 0
  return () => `k${++n}`
}

test("same signature reuses the key (retry after unknown outcome is idempotent)", () => {
  const a = new AttemptKey(counter())
  assert.equal(a.keyFor("TX1|10|ZAR"), "k1")
  assert.equal(a.keyFor("TX1|10|ZAR"), "k1")
})

test("a different taxi, amount or currency gets a new key", () => {
  const a = new AttemptKey(counter())
  assert.equal(a.keyFor("TX1|10|ZAR"), "k1")
  assert.equal(a.keyFor("TX1|20|ZAR"), "k2")
  assert.equal(a.keyFor("TX2|20|ZAR"), "k3")
})

test("settle() rotates the key so a new payment is never a replay", () => {
  const a = new AttemptKey(counter())
  assert.equal(a.keyFor("TX1|10|ZAR"), "k1")
  a.settle()
  assert.equal(a.keyFor("TX1|10|ZAR"), "k2")
})

test("network error, timeout, 5xx, 408, 409, 425, 429 keep the key; other 4xx rotate it", () => {
  for (const [err, keep] of [
    [new TypeError("Failed to fetch"), true],
    [Object.assign(new Error("x"), { status: 500 }), true],
    [Object.assign(new Error("x"), { status: 503 }), true],
    [Object.assign(new Error("x"), { status: 400 }), false],
    [Object.assign(new Error("x"), { status: 409 }), true], // "already in progress": must NOT rotate
    [Object.assign(new Error("x"), { status: 429 }), true],
    [Object.assign(new Error("x"), { status: 408 }), true],
    [Object.assign(new Error("x"), { status: 401 }), false],
    [Object.assign(new Error("x"), { status: 404 }), false],
    [Object.assign(new Error("x"), { status: 422 }), false],
    [null, true],
  ] as const) {
    const a = new AttemptKey(counter())
    const first = a.keyFor("S")
    a.onError(err)
    const second = a.keyFor("S")
    assert.equal(first === second, keep, `status ${(err as any)?.status} keep=${keep}`)
  }
})
