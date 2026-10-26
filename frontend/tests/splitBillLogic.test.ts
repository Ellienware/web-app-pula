import { test } from "node:test"
import assert from "node:assert/strict"
import {
  checkCustomAllocation, checkItems, checkPercentAllocation, describeSplitError, minorToDecimal, shareStatusLabel, shareStatusTone,
  summariseObligations, toBasisPoints, toMinor,
} from "../src/lib/splitBillLogic.ts"
import type { ExpenseObligation } from "../src/lib/types.ts"

test("toMinor is exact and never rounds", () => {
  assert.equal(toMinor("20"), 2000)
  assert.equal(toMinor("20.5"), 2050)
  assert.equal(toMinor("0.07"), 7)
  assert.equal(toMinor("19.99"), 1999)
  assert.equal(toMinor("1,50"), 150)
  assert.equal(toMinor("10.005"), null)
  assert.equal(toMinor("-1"), null)
  assert.equal(toMinor(""), null)
  assert.equal(toMinor("abc"), null)
  assert.equal(toMinor("0.1") === 10 && 0.1 + 0.2 !== 0.3, true, "float trap does not apply")
})

test("minorToDecimal formats exact hundredths", () => {
  assert.equal(minorToDecimal(2000), "20.00")
  assert.equal(minorToDecimal(5), "0.05")
  assert.equal(minorToDecimal(123456), "1234.56")
})

test("basis points", () => {
  assert.equal(toBasisPoints("33.34"), 3334)
  assert.equal(toBasisPoints("100"), 10000)
  assert.equal(toBasisPoints("100.01"), null)
  assert.equal(toBasisPoints("33.333"), null)
})

test("custom allocation must add up to the total in integer arithmetic", () => {
  assert.equal(checkCustomAllocation(10000, [3334, 3333, 3333]).ok, true)
  assert.equal(checkCustomAllocation(10000, [3334, 3333]).ok, false)
  assert.match(checkCustomAllocation(10000, [3334, 3333]).message, /33\.33 still to allocate/)
  assert.match(checkCustomAllocation(10000, [10000, 1]).message, /0\.01 too much/)
  assert.equal(checkCustomAllocation(10000, [10000, null]).ok, false)
})

test("percentages must total exactly 100.00", () => {
  assert.equal(checkPercentAllocation([3334, 3333, 3333]).ok, true)
  assert.equal(checkPercentAllocation([5000, 4999]).ok, false)
  assert.match(checkPercentAllocation([5000, 5001]).message, /0\.01% over 100/)
})

test("items must add up and be assigned", () => {
  assert.equal(checkItems(10000, [{ amountMinor: 6000, assignees: ["a"] }, { amountMinor: 4000, assignees: ["b"] }]).ok, true)
  assert.equal(checkItems(10000, [{ amountMinor: 6000, assignees: ["a"] }]).ok, false)
  assert.equal(checkItems(10000, [{ amountMinor: 10000, assignees: [] }]).ok, false)
  assert.equal(checkItems(10000, []).ok, false)
})

test("labels never imply real-world settlement", () => {
  assert.equal(shareStatusLabel("PAID", "LEDGER"), "Paid (demo wallet)")
  assert.equal(shareStatusLabel("PAID", "EXTERNAL"), "Repaid outside PULA")
  assert.equal(shareStatusLabel("REFUNDED"), "Refunded (demo wallet)")
  assert.equal(shareStatusTone("PENDING", true), "failed", "overdue overrides")
  assert.equal(shareStatusTone("PAID"), "success")
})

test("errors are plain language and say nothing was taken", () => {
  assert.match(describeSplitError({ status: 409, code: "INSUFFICIENT_FUNDS", message: "x" }), /Nothing was taken/)
  assert.match(describeSplitError({ status: 500, message: "" }), /Nothing was taken/)
  assert.equal(describeSplitError({ status: 409, message: "The amount changed" }), "The amount changed")
})

function ob(amountMinor: number, status: any, overdue = false, expenseStatus: any = "ACTIVE"): ExpenseObligation {
  return { expenseId: "e", circleId: "c", circleName: "C", title: "t", role: "OWE", counterpartyName: "x", currency: "ZAR", dueDate: null,
    expenseStatus, createdAt: "", share: { amountMinor, status, overdue } as any }
}

test("obligation totals exclude settled and cancelled rows", () => {
  const r = summariseObligations([ob(2000, "PENDING"), ob(1000, "PAID"), ob(500, "ACCEPTED", true), ob(700, "PENDING", false, "CANCELLED"), ob(300, "REFUNDED")])
  assert.deepEqual(r, { outstandingMinor: 2800, overdueMinor: 500, count: 3 })
})
