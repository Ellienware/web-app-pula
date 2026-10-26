/**
 * Builds the customer's refund view from data the app already loads (orders, stock orders, collective-purchase allocations).
 * There is no dedicated refund-list endpoint, so this module only NORMALISES what each source says; it never computes amounts.
 * All refunds shown are SIMULATED records - nothing is sent to a real account.
 *
 * Pure module: no imports, identical on web and mobile.
 */

export type RefundSource = "ORDER" | "STOCK_ORDER" | "BULK"
/** REQUESTED = asked for, not yet recorded. RECORDED = a (demo) refund was recorded. DUE = owed from a collective purchase. */
export type RefundStage = "REQUESTED" | "RECORDED" | "DUE"

export interface RefundItem {
  key: string
  source: RefundSource
  sourceLabel: string
  /** The id of the order / stock order / allocation group, for building a link. */
  refId: string
  title: string
  amount: number | null
  currency: string
  stage: RefundStage
  /** A key into the refund/order status tables in statusLabels.ts. */
  statusKey: "PENDING" | "REFUNDED" | "DUE"
  date: string | null
}

export interface OrderLike { id: string; businessName: string; total: number; currency: string; status: string; createdAt: string }
export interface StockOrderLike { id: string; supplierBusinessName: string; total: number; currency: string; status: string; createdAt: string; updatedAt?: string }
export interface AllocationLike { id: string; groupId: string; groupName: string; status: string; refundDueAmount: number; currency: string }

export const SOURCE_LABELS: Record<RefundSource, string> = { ORDER: "Market order", STOCK_ORDER: "Stock order", BULK: "Collective purchase" }

export function refundItemsFromOrders(orders: readonly OrderLike[] | null | undefined): RefundItem[] {
  const out: RefundItem[] = []
  for (const o of orders ?? []) {
    if (o.status !== "REFUND_PENDING" && o.status !== "REFUNDED") continue
    out.push({
      key: `ORDER:${o.id}`, source: "ORDER", sourceLabel: SOURCE_LABELS.ORDER, refId: o.id, title: o.businessName,
      amount: o.total, currency: o.currency, stage: o.status === "REFUNDED" ? "RECORDED" : "REQUESTED",
      statusKey: o.status === "REFUNDED" ? "REFUNDED" : "PENDING", date: o.createdAt,
    })
  }
  return out
}

export function refundItemsFromStockOrders(orders: readonly StockOrderLike[] | null | undefined): RefundItem[] {
  const out: RefundItem[] = []
  for (const o of orders ?? []) {
    if (o.status !== "REFUND_PENDING" && o.status !== "REFUNDED") continue
    out.push({
      key: `STOCK_ORDER:${o.id}`, source: "STOCK_ORDER", sourceLabel: SOURCE_LABELS.STOCK_ORDER, refId: o.id, title: o.supplierBusinessName,
      amount: o.total, currency: o.currency, stage: o.status === "REFUNDED" ? "RECORDED" : "REQUESTED",
      statusKey: o.status === "REFUNDED" ? "REFUNDED" : "PENDING", date: o.updatedAt ?? o.createdAt,
    })
  }
  return out
}

export function refundItemsFromAllocations(allocs: readonly AllocationLike[] | null | undefined): RefundItem[] {
  const out: RefundItem[] = []
  for (const a of allocs ?? []) {
    if (a.status !== "REFUND_DUE" && !(a.refundDueAmount > 0)) continue
    out.push({
      key: `BULK:${a.id}`, source: "BULK", sourceLabel: SOURCE_LABELS.BULK, refId: a.groupId, title: a.groupName,
      amount: a.refundDueAmount > 0 ? a.refundDueAmount : null, currency: a.currency, stage: "DUE", statusKey: "DUE", date: null,
    })
  }
  return out
}

/** Newest first; undated items (collective purchases) go last in a stable order. */
export function sortRefunds(items: readonly RefundItem[]): RefundItem[] {
  return [...items].sort((a, b) => {
    if (a.date && b.date) return a.date < b.date ? 1 : a.date > b.date ? -1 : 0
    if (a.date) return -1
    if (b.date) return 1
    return 0
  })
}

export interface RefundSummary { requested: number; recorded: number; due: number }

export function summariseRefunds(items: readonly RefundItem[]): RefundSummary {
  return {
    requested: items.filter((i) => i.stage === "REQUESTED").length,
    recorded: items.filter((i) => i.stage === "RECORDED").length,
    due: items.filter((i) => i.stage === "DUE").length,
  }
}

export function buildRefundList(sources: { orders?: readonly OrderLike[]; stockOrders?: readonly StockOrderLike[]; allocations?: readonly AllocationLike[] }): RefundItem[] {
  return sortRefunds([
    ...refundItemsFromOrders(sources.orders),
    ...refundItemsFromStockOrders(sources.stockOrders),
    ...refundItemsFromAllocations(sources.allocations),
  ])
}
