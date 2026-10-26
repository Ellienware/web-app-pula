import { test } from "node:test"
import assert from "node:assert/strict"
import {
  paymentStatusLabel, refundStatusLabel, orderStatusLabel, settlementStatusLabel, payoutStatusLabel, KNOWN_STATUSES, isSimulated, humanise,
} from "../src/lib/statusLabels.ts"
import { buildRefundList, summariseRefunds, refundItemsFromAllocations, refundItemsFromOrders } from "../src/lib/refundLogic.ts"

// Backend enum values (see backend domain/enums). If a backend enum gains a value, the matching test below fails until the label exists.
const BACKEND = {
  payment: ["PENDING", "PROCESSING", "SUCCESSFUL", "FAILED", "REFUNDED"],
  refund: ["PENDING", "PROCESSING", "REFUNDED", "FAILED", "NEEDS_ATTENTION"],
  order: ["PENDING_PAYMENT", "PAYMENT_PROCESSING", "PAID", "PROCESSING", "READY", "COMPLETED", "CANCELLED", "REFUND_PENDING", "REFUNDED"],
  settlement: ["NOT_ELIGIBLE", "ELIGIBLE", "PROCESSING", "PAID", "FAILED", "ON_HOLD", "CANCELLED"],
  payout: ["PENDING", "PROCESSING", "SUCCESSFUL", "FAILED", "REVERSED"],
}

test("every backend status has an explicit label (mapping is exhaustive)", () => {
  for (const [kind, values] of Object.entries(BACKEND)) {
    for (const v of values) assert.ok((KNOWN_STATUSES as Record<string, string[]>)[kind].includes(v), `${kind}:${v} has no label`)
  }
})

test("simulated money never reads as real settlement", () => {
  const simulated = [
    paymentStatusLabel("SUCCESSFUL"), paymentStatusLabel("REFUNDED"), orderStatusLabel("PAID"), orderStatusLabel("REFUNDED"),
    refundStatusLabel("REFUNDED"), settlementStatusLabel("PAID"), payoutStatusLabel("SUCCESSFUL"),
  ]
  for (const l of simulated) {
    assert.ok(isSimulated(l), l.label)
    assert.match(l.label, /demo/i, `"${l.label}" must say demo`)
    assert.doesNotMatch(l.label, /^(paid|refunded|settled|successful)$/i)
  }
})

test("every label has text, a symbol and a description (never colour-only)", () => {
  for (const [kind, fn] of [["payment", paymentStatusLabel], ["refund", refundStatusLabel], ["order", orderStatusLabel], ["settlement", settlementStatusLabel], ["payout", payoutStatusLabel]] as const) {
    for (const key of KNOWN_STATUSES[kind]) {
      const l = fn(key)
      assert.ok(l.label.length > 0 && l.symbol.length > 0 && l.description.length > 0, `${kind}:${key}`)
    }
  }
})

test("unknown values fall back safely instead of throwing", () => {
  assert.equal(paymentStatusLabel("SOMETHING_NEW").label, "Something new")
  assert.equal(orderStatusLabel(null).label, "Unknown")
  assert.equal(humanise("A_B"), "A b")
})

test("refund list picks only refund states, newest first, and never invents amounts", () => {
  const list = buildRefundList({
    orders: [
      { id: "o1", businessName: "Shop A", total: 100, currency: "ZAR", status: "REFUNDED", createdAt: "2026-10-01T10:00:00Z" },
      { id: "o2", businessName: "Shop B", total: 50, currency: "ZAR", status: "COMPLETED", createdAt: "2026-10-02T10:00:00Z" },
      { id: "o3", businessName: "Shop C", total: 75, currency: "ZAR", status: "REFUND_PENDING", createdAt: "2026-10-03T10:00:00Z" },
    ],
    stockOrders: [{ id: "s1", supplierBusinessName: "Wholesaler", total: 900, currency: "ZAR", status: "REFUND_PENDING", createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-10-05T00:00:00Z" }],
    allocations: [
      { id: "a1", groupId: "g1", groupName: "Maize meal", status: "REFUND_DUE", refundDueAmount: 40, currency: "ZAR" },
      { id: "a2", groupId: "g2", groupName: "Sugar", status: "PAID", refundDueAmount: 0, currency: "ZAR" },
    ],
  })
  assert.deepEqual(list.map((i) => i.key), ["STOCK_ORDER:s1", "ORDER:o3", "ORDER:o1", "BULK:a1"])
  assert.equal(list[0].stage, "REQUESTED")
  assert.equal(list[2].stage, "RECORDED")
  assert.equal(list[3].stage, "DUE")
  assert.equal(list[3].amount, 40)
  assert.deepEqual(summariseRefunds(list), { requested: 2, recorded: 1, due: 1 })
})

test("an allocation with a refund amount but another status still shows; one with neither does not", () => {
  const items = refundItemsFromAllocations([
    { id: "x", groupId: "g", groupName: "G", status: "ORDERED", refundDueAmount: 12.5, currency: "ZAR" },
    { id: "y", groupId: "g", groupName: "G", status: "PAID", refundDueAmount: 0, currency: "ZAR" },
  ])
  assert.deepEqual(items.map((i) => i.key), ["BULK:x"])
})

test("empty or missing sources give an empty list", () => {
  assert.deepEqual(buildRefundList({}), [])
  assert.deepEqual(refundItemsFromOrders(null), [])
})
