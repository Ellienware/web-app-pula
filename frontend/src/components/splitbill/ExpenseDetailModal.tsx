import { useCallback, useEffect, useState } from "react"
import { splitBillApi } from "../../lib/splitBillApi"
import type { Expense, ExpenseEvent, ExpenseShare } from "../../lib/types"
import { formatDate, formatDateTime, formatMoney } from "../../lib/format"
import {
  SIMULATED_NOTE, describeSplitError, minorToDecimal, shareStatusLabel, shareStatusTone, splitTypeLabel, stateLabel, stateTone,
} from "../../lib/splitBillLogic"
import { Modal } from "../Modal"
import { Badge } from "../ui/Badge"
import { Button } from "../ui/Button"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { useGuard } from "../wallet/useGuard"

type Pending =
  | { kind: "pay" | "refund"; share: ExpenseShare }
  | { kind: "dispute" | "decline"; share: ExpenseShare }
  | { kind: "cancel" }

/**
 * One expense: details, every share with the actions the SERVER says the viewer may take, and the audit history.
 * Responding (accept, dispute, decline) never moves money. Paying and refunding each need an explicit confirmation dialog.
 * Everything that moves value is a SIMULATED demo-wallet transfer and is labelled as such.
 */
export function ExpenseDetailModal({
  expenseId, onClose, onChanged, onEdit,
}: {
  expenseId: string
  onClose: () => void
  onChanged: () => void
  onEdit?: (e: Expense) => void
}) {
  const [expense, setExpense] = useState<Expense | null>(null)
  const [history, setHistory] = useState<ExpenseEvent[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [reason, setReason] = useState("")
  const [showHistory, setShowHistory] = useState(false)
  const action = useGuard()

  const load = useCallback(async () => {
    try {
      const [e, h] = await Promise.all([splitBillApi.get(expenseId), splitBillApi.history(expenseId)])
      setExpense(e); setHistory(h); setLoadError(null)
    } catch (err) {
      setLoadError(describeSplitError(err))
    }
  }, [expenseId])
  useEffect(() => { void load() }, [load])

  async function run(fn: () => Promise<unknown>, done?: string) {
    setError(null); setNotice(null)
    await action.run(async () => {
      try {
        await fn()
        if (done) setNotice(done)
        setPending(null); setReason("")
        await load(); onChanged()
      } catch (err) {
        setError(describeSplitError(err)); setPending(null)
        await load()
      }
    })
  }

  if (loadError) return <Modal title="Expense" onClose={onClose}><p className="form-error" role="alert">{loadError}</p></Modal>
  if (!expense) return <Modal title="Expense" onClose={onClose}><p className="muted" role="status">Loading…</p></Modal>

  const cur = expense.currency
  const money = (n: number) => formatMoney(n, cur)
  const confirmBody = (s: ExpenseShare) => ({ confirm: true as const, expectedAmount: minorToDecimal(s.amountMinor), expectedCurrency: cur })

  return (
    <Modal title={expense.description} onClose={onClose}>
      <div className="split-detail">
        <p className="split-notice" role="note">{expense.simulatedNotice || SIMULATED_NOTE}</p>

        <div className="expense-head">
          <div>
            <span className="muted">
              {`Paid by ${expense.paidByName} · recorded by ${expense.createdByName} · ${splitTypeLabel(expense.splitType)} split`}
            </span>
            {expense.expenseDate && <div className="muted small">{`Expense date ${formatDate(expense.expenseDate)}`}{expense.dueDate ? ` · due ${formatDate(expense.dueDate)}` : ""}</div>}
            {expense.note && <p>{expense.note}</p>}
          </div>
          <Badge tone={stateTone(expense.settlementState)}>{stateLabel(expense.settlementState)}</Badge>
        </div>

        <dl className="split-totals">
          <div><dt>Recorded cost</dt><dd>{money(expense.recordedAmount)}</dd></div>
          <div><dt>Moved by demo wallet</dt><dd>{money(expense.transferredAmount)}</dd></div>
          {expense.externalSettledAmount > 0 && <div><dt>Repaid outside PULA</dt><dd>{money(expense.externalSettledAmount)}</dd></div>}
          {expense.refundedAmount > 0 && <div><dt>Returned by demo wallet</dt><dd>{money(expense.refundedAmount)}</dd></div>}
          <div><dt>Still owed</dt><dd>{money(expense.outstandingAmount)}</dd></div>
        </dl>

        {expense.status === "CANCELLED" && <p className="form-error" role="status">{`Cancelled${expense.cancelReason ? `: ${expense.cancelReason}` : ""}. History is kept.`}</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
        {notice && <p className="form-ok" role="status">{notice}</p>}

        {expense.items.length > 0 && (
          <ul className="share-list" aria-label="Items">
            {expense.items.map((i) => (
              <li key={i.id} className="share-row">
                <span className="share-name">{i.name}</span>
                <span className="muted small">{i.assigneeNames.join(", ")}</span>
                <span className="share-amount">{money(i.amount)}</span>
              </li>
            ))}
          </ul>
        )}

        <ul className="share-list split-shares">
          {expense.shares.map((s) => (
            <li key={s.id} className={`split-share ${s.mine ? "mine" : ""}`}>
              <div className="share-row">
                <span className="share-name">
                  {s.debtorName}{s.mine ? " (you)" : ""}{s.debtorFormerMember ? " · left the circle" : ""}
                </span>
                <span className="share-amount">{money(s.amount)}</span>
                <Badge tone={shareStatusTone(s.status, s.overdue)}>{s.overdue ? "Overdue" : shareStatusLabel(s.status, s.settlementMethod)}</Badge>
              </div>
              {s.responseNote && <div className="muted small">{`"${s.responseNote}"`}</div>}
              {s.ledgerReference && <div className="muted small">{`Demo-wallet transfer ${s.ledgerReference}`}</div>}
              {s.refundReference && <div className="muted small">{`Returned by transfer ${s.refundReference}`}</div>}
              {s.payBlockedReason && <div className="form-error small">{s.payBlockedReason}</div>}
              <div className="split-actions">
                {s.canAccept && <Button size="sm" variant="secondary" loading={action.loading} onClick={() => run(() => splitBillApi.accept(s.id), "Accepted. Nothing was taken from your wallet.")}>Accept</Button>}
                {s.canPay && <Button size="sm" onClick={() => setPending({ kind: "pay", share: s })}>Pay {money(s.amount)} from demo wallet</Button>}
                {s.canDispute && <Button size="sm" variant="ghost" onClick={() => { setReason(""); setPending({ kind: "dispute", share: s }) }}>Dispute</Button>}
                {s.canDecline && <Button size="sm" variant="ghost" onClick={() => { setReason(""); setPending({ kind: "decline", share: s }) }}>Decline</Button>}
                {s.canReopen && <Button size="sm" variant="secondary" loading={action.loading} onClick={() => run(() => splitBillApi.reopen(s.id), "Asked again.")}>Ask again</Button>}
                {s.canMarkReceived && <Button size="sm" variant="ghost" loading={action.loading} onClick={() => run(() => splitBillApi.markReceived(s.id), "Recorded as repaid outside PULA. No demo funds moved.")}>Repaid outside PULA</Button>}
                {s.canRetractReceived && <Button size="sm" variant="ghost" loading={action.loading} onClick={() => run(() => splitBillApi.retractReceived(s.id), "Record retracted.")}>Retract repaid record</Button>}
                {s.canRefund && <Button size="sm" variant="ghost" onClick={() => setPending({ kind: "refund", share: s })}>Refund {s.debtorName}</Button>}
              </div>
            </li>
          ))}
        </ul>

        <div className="split-actions">
          {expense.canEdit && onEdit && <Button variant="secondary" size="sm" onClick={() => onEdit(expense)}>Edit</Button>}
          {expense.canCancel && <Button variant="danger" size="sm" onClick={() => { setReason(""); setPending({ kind: "cancel" }) }}>Cancel expense</Button>}
          {!expense.canCancel && expense.cancelBlockedReason && <p className="muted small">{expense.cancelBlockedReason}</p>}
          <Button variant="ghost" size="sm" onClick={() => setShowHistory((v) => !v)}>{showHistory ? "Hide history" : `History (${history.length})`}</Button>
        </div>

        {showHistory && (
          <ol className="split-history" aria-label="History">
            {history.map((h) => (
              <li key={h.id}>
                <span className="muted small">{formatDateTime(h.createdAt)} · {h.actorName}</span>
                <div>{h.details || h.eventType}{h.ledgerReference ? ` (${h.ledgerReference})` : ""}</div>
              </li>
            ))}
          </ol>
        )}
      </div>

      {pending?.kind === "pay" && (
        <ConfirmDialog
          title="Pay from your demo wallet?"
          summary={SIMULATED_NOTE}
          confirmLabel="Confirm and pay"
          loading={action.loading}
          rows={[
            { label: "For", value: expense.description },
            { label: "You pay", value: money(pending.share.amount) },
            { label: "To", value: expense.paidByName },
            { label: "From", value: "Your PULA Money demo wallet" },
          ]}
          onCancel={() => setPending(null)}
          onConfirm={() => run(async () => {
            const r = await splitBillApi.pay(pending.share.id, confirmBody(pending.share))
            setNotice(r.replayed ? "Already paid; nothing was taken again." : `Paid ${money(pending.share.amount)} (simulated).`)
          })}
        />
      )}
      {pending?.kind === "refund" && (
        <ConfirmDialog
          title="Return this payment?"
          summary={`${SIMULATED_NOTE} This sends the same amount back from your demo wallet. It needs enough demo funds; PULA never creates funds that were already spent.`}
          confirmLabel="Confirm refund"
          loading={action.loading}
          rows={[
            { label: "To", value: pending.share.debtorName },
            { label: "You send", value: money(pending.share.amount) },
          ]}
          onCancel={() => setPending(null)}
          onConfirm={() => run(async () => {
            await splitBillApi.refund(pending.share.id, confirmBody(pending.share))
            setNotice("Refunded (simulated).")
          })}
        />
      )}
      {(pending?.kind === "dispute" || pending?.kind === "decline" || pending?.kind === "cancel") && (
        <Modal title={pending.kind === "dispute" ? "Dispute this share" : pending.kind === "decline" ? "Decline this share" : "Cancel this expense"} onClose={() => setPending(null)}>
          <div className="modal-form">
            <label className="field">
              <span>{pending.kind === "dispute" ? "What looks wrong? (required)" : "Reason (optional)"}</span>
              <textarea value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} rows={3} />
            </label>
            <div className="modal-footer">
              <Button variant="ghost" onClick={() => setPending(null)}>Back</Button>
              <Button
                variant={pending.kind === "cancel" ? "danger" : "primary"}
                loading={action.loading}
                disabled={pending.kind === "dispute" && !reason.trim()}
                onClick={() => {
                  const p = pending
                  if (p.kind === "cancel") return run(() => splitBillApi.cancel(expense.circleId, expense.id, reason.trim() || undefined), "Expense cancelled.")
                  if (p.kind === "dispute") return run(() => splitBillApi.dispute(p.share.id, reason.trim()), "Disputed. The creator has been told.")
                  return run(() => splitBillApi.decline(p.share.id, reason.trim() || undefined), "Declined.")
                }}
              >
                Confirm
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </Modal>
  )
}
