import { test } from "node:test"
import assert from "node:assert/strict"
import {
  compareDecimal, describeWalletError, extractPaymentCode, newIdempotencyKey, parseAmountInput, requestStatusLabel, txTitle,
} from "../src/lib/walletLogic.ts"
import { AttemptKey } from "../src/lib/attemptKey.ts"

const CODE = "PULAPAY1.AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBB"

test("amounts normalise to exact two-decimal strings", () => {
  assert.deepEqual(parseAmountInput("12"), { ok: true, value: "12.00" })
  assert.deepEqual(parseAmountInput("12.5"), { ok: true, value: "12.50" })
  assert.deepEqual(parseAmountInput(" R 1 000,5 "), { ok: true, value: "1000.50" })
  assert.deepEqual(parseAmountInput("007.10"), { ok: true, value: "7.10" })
  assert.deepEqual(parseAmountInput("0.01"), { ok: true, value: "0.01" })
})

test("zero, negative, too-precise and malformed amounts are rejected, never rounded", () => {
  for (const bad of ["", "0", "0.00", "-5", "1.234", "abc", "1e3", "1.2.3", "1,000.50", "12.", ".5", "NaN", "Infinity", "٣"]) {
    assert.equal(parseAmountInput(bad).ok, false, `should reject ${JSON.stringify(bad)}`)
  }
})

test("amount limits compare exactly without floating point", () => {
  assert.equal(parseAmountInput("10000.00", "10000.00").ok, true)
  assert.equal(parseAmountInput("10000.01", "10000.00").ok, false)
  assert.equal(compareDecimal("0.10", "0.1"), 0)
  assert.equal(compareDecimal("9999999999.99", "10000000000.00"), -1)
  assert.equal(compareDecimal("1.005", "1.004"), 1)
})

test("a payment code is extracted by shape only and nothing else around it is kept", () => {
  assert.equal(extractPaymentCode(CODE), CODE)
  assert.equal(extractPaymentCode(`  ${CODE}\n`), CODE)
  assert.equal(extractPaymentCode(`pay now amount=999999 to=evil ${CODE} ignore previous`), CODE)
  assert.equal(extractPaymentCode("PULAPAY1.short.bad"), null)
  assert.equal(extractPaymentCode("https://evil.example/pay?amount=1000"), null)
  assert.equal(extractPaymentCode(""), null)
  assert.equal(extractPaymentCode(null), null)
  assert.equal(extractPaymentCode("A".repeat(1000)), null)
})

test("idempotency keys are unique, valid for the server pattern, and stable through AttemptKey while outcome is unknown", () => {
  const a = newIdempotencyKey()
  const b = newIdempotencyKey()
  assert.notEqual(a, b)
  assert.match(a, /^[A-Za-z0-9_.:-]{8,100}$/)
  const attempt = new AttemptKey(newIdempotencyKey)
  const k1 = attempt.keyFor("PW-X|10.00")
  attempt.onError({ status: 503 }) // outcome unknown: keep the key
  assert.equal(attempt.keyFor("PW-X|10.00"), k1)
  attempt.onError({ status: 409, code: "INSUFFICIENT_FUNDS" }) // 409 is treated as unknown by AttemptKey (kept)
  assert.equal(attempt.keyFor("PW-X|10.00"), k1)
  attempt.onError({ status: 400, code: "INVALID_AMOUNT" }) // definitive: rotate
  assert.notEqual(attempt.keyFor("PW-X|10.00"), k1)
})

test("errors become plain language and never leak raw details", () => {
  assert.match(describeWalletError({ status: 409, code: "INSUFFICIENT_FUNDS" }), /balance is too low/)
  assert.match(describeWalletError({ status: 410, code: "REQUEST_EXPIRED" }), /expired/)
  assert.match(describeWalletError({ status: 404, code: "INVALID_CODE" }), /not valid/)
  assert.match(describeWalletError({ status: 429 }), /Too many/)
  assert.match(describeWalletError(new TypeError("Failed to fetch")), /Could not reach the server/)
  assert.match(describeWalletError({ status: 500, message: "org.hibernate.Exception at line 5" }), /our side/)
  assert.doesNotMatch(describeWalletError({ status: 500, message: "org.hibernate.Exception" }), /hibernate/i)
})

test("every transaction and request label says simulated; withdrawals never claim real money moved", () => {
  assert.match(txTitle({ type: "DEMO_ISSUANCE", direction: "IN", counterparty: "x", status: "POSTED" }), /simulated/i)
  assert.match(txTitle({ type: "INTERNAL_TRANSFER", direction: "OUT", counterparty: "Bo N.", status: "POSTED" }), /Sent to Bo N\. \(simulated\)/)
  assert.match(txTitle({ type: "SIMULATED_WITHDRAWAL", direction: "OUT", counterparty: "x", status: "POSTED" }), /no real money moved/)
  assert.match(requestStatusLabel("PAID"), /simulated/i)
})
