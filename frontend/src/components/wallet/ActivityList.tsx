import { useCallback, useEffect, useState } from "react"
import { walletApi, type WalletScope } from "../../lib/walletApi"
import type { TransactionFilter, TxStatus, TxType, WalletPage, WalletTransaction } from "../../lib/walletTypes"
import { describeWalletError, txBadgeLabel, txStatusTone, txTitle } from "../../lib/walletLogic"
import { formatDateTime, formatMoney } from "../../lib/format"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { Badge } from "../ui/Badge"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../ui/StatePanel"
import { SkeletonText } from "../ui/Skeleton"
import { useToast } from "../../context/ToastContext"
import { SimulatedBanner } from "./SimulatedBanner"
import { useGuard } from "./useGuard"

const TYPES: { v: "" | TxType; l: string }[] = [
  { v: "", l: "All types" }, { v: "INTERNAL_TRANSFER", l: "Transfers" }, { v: "DEMO_ISSUANCE", l: "Demo funding" },
  { v: "SIMULATED_WITHDRAWAL", l: "Simulated withdrawals" }, { v: "REVERSAL", l: "Corrections" },
]
const STATUSES: { v: "" | TxStatus; l: string }[] = [
  { v: "", l: "Any status" }, { v: "PENDING", l: "Pending" }, { v: "POSTED", l: "Completed" }, { v: "FAILED", l: "Failed" },
  { v: "CANCELLED", l: "Cancelled" }, { v: "REVERSED", l: "Reversed" },
]

/** Wallet history read from the ledger: paged, filterable, with loading / empty / error states. */
export function ActivityList({ version, onChanged, scope = walletApi }: { version: number; onChanged: () => void; scope?: WalletScope }) {
  const { showToast } = useToast()
  const [page, setPage] = useState(0)
  const [type, setType] = useState<"" | TxType>("")
  const [status, setStatus] = useState<"" | TxStatus>("")
  const [direction, setDirection] = useState<"" | "IN" | "OUT">("")
  const [data, setData] = useState<WalletPage<WalletTransaction> | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [cancelTx, setCancelTx] = useState<WalletTransaction | null>(null)
  const cancel = useGuard()

  const load = useCallback(() => {
    setState("loading")
    const f: TransactionFilter = {}
    if (type) f.type = type
    if (status) f.status = status
    if (direction) f.direction = direction
    scope.transactions(page, f).then((d) => { setData(d); setState("ok") }).catch(() => setState("error"))
  }, [page, type, status, direction, scope])
  useEffect(load, [load, version])

  function change<T>(set: (v: T) => void) { return (v: T) => { setPage(0); set(v) } }

  async function doCancel() {
    if (!cancelTx) return
    await cancel.run(async () => {
      try { await scope.cancelWithdrawal(cancelTx.reference); showToast("Simulated withdrawal cancelled. Demo funds returned.", "success"); onChanged() }
      catch (e) { showToast(describeWalletError(e), "error") }
      setCancelTx(null)
    })
  }

  return (
    <Card>
      <SimulatedBanner compact />
      <div className="wallet-filters">
        <label>Type <select value={type} onChange={(e) => change(setType)(e.target.value as "" | TxType)}>{TYPES.map((o) => <option key={o.v} value={o.v}>{o.l}</option>)}</select></label>
        <label>Status <select value={status} onChange={(e) => change(setStatus)(e.target.value as "" | TxStatus)}>{STATUSES.map((o) => <option key={o.v} value={o.v}>{o.l}</option>)}</select></label>
        <label>Direction <select value={direction} onChange={(e) => change(setDirection)(e.target.value as "" | "IN" | "OUT")}>
          <option value="">In and out</option><option value="IN">Money in</option><option value="OUT">Money out</option></select></label>
      </div>
      {state === "loading" && <SkeletonText />}
      {state === "error" && <ErrorState title="Could not load your activity" description="Nothing was changed." onRetry={load} />}
      {state === "ok" && data && data.items.length === 0 && <EmptyState title="No simulated transactions yet" description="Add demo funds or send some to see activity here." />}
      {state === "ok" && data && data.items.length > 0 && (
        <>
          <ul className="wallet-list" aria-label="Simulated wallet transactions">
            {data.items.map((t) => (
              <li key={t.reference} className="wallet-row">
                <div>
                  <strong>{txTitle(t)}</strong>
                  <div className="field-hint">{formatDateTime(t.createdAt)} · <code>{t.reference}</code>{t.failureReason ? ` · ${t.failureReason}` : ""}</div>
                </div>
                <div className="wallet-row-end">
                  <span className={t.direction === "IN" ? "money-display-positive" : "money-display-negative"}
                    aria-label={`${t.direction === "IN" ? "Plus" : "Minus"} ${formatMoney(Number(t.amount), t.currency)} simulated`}>
                    {t.direction === "IN" ? "+" : "−"}{formatMoney(Number(t.amount), t.currency)}
                  </span>
                  <Badge tone={txStatusTone(t.status)}>{txBadgeLabel(t.status)}</Badge>
                  {t.type === "SIMULATED_WITHDRAWAL" && t.status === "PENDING" && <Button size="sm" variant="ghost" onClick={() => setCancelTx(t)}>Cancel</Button>}
                </div>
              </li>
            ))}
          </ul>
          <div className="wallet-pager">
            <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
            <span aria-live="polite">Page {data.page + 1} of {Math.max(data.totalPages, 1)}</span>
            <Button size="sm" variant="ghost" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
          </div>
        </>
      )}
      {cancelTx && (
        <ConfirmDialog title="Cancel simulated withdrawal?" summary="The held demo funds go back to your wallet."
          rows={[{ label: "Amount", value: formatMoney(Number(cancelTx.amount), cancelTx.currency) }, { label: "Reference", value: cancelTx.reference }]}
          confirmLabel="Cancel withdrawal" cancelLabel="Keep it" loading={cancel.loading} onConfirm={doCancel} onCancel={() => setCancelTx(null)} />
      )}
    </Card>
  )
}
