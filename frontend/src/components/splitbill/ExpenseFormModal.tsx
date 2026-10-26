import { useMemo, useRef, useState, type FormEvent } from "react"
import { splitBillApi, type ExpenseBody } from "../../lib/splitBillApi"
import type { Circle, Expense, ExpensePreview, Member, SplitType } from "../../lib/types"
import { formatMoney, initials } from "../../lib/format"
import {
  SIMULATED_NOTE, checkCustomAllocation, checkItems, checkPercentAllocation, describeSplitError, splitTypeLabel, toBasisPoints, toMinor,
} from "../../lib/splitBillLogic"
import { Modal } from "../Modal"

interface Row { userId: string; included: boolean; value: string }
interface ItemRow { name: string; amount: string; assignees: string[] }

const TYPES: SplitType[] = ["EQUAL", "CUSTOM", "PERCENTAGE", "ITEM_BASED"]

/**
 * Create or edit a split bill. Two steps: fill in, then REVIEW the shares the server calculated, then save.
 * The browser never splits or rounds money: it only checks that what you typed adds up, and shows the server's numbers.
 * Nobody is debited by saving: each person is asked and must confirm for themselves.
 */
export function ExpenseFormModal({
  circle, members, currentUserId, existing, onClose, onDone,
}: {
  circle: Circle
  members: Member[]
  currentUserId: string
  existing?: Expense
  onClose: () => void
  onDone: () => void
}) {
  const editing = !!existing
  const [description, setDescription] = useState(existing?.description ?? "")
  const [note, setNote] = useState(existing?.note ?? "")
  const [total, setTotal] = useState(existing ? existing.amount.toFixed(2) : "")
  const [expenseDate, setExpenseDate] = useState(existing?.expenseDate ?? new Date().toISOString().slice(0, 10))
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? "")
  const [paidBy, setPaidBy] = useState(existing?.paidById ?? currentUserId)
  const [splitType, setSplitType] = useState<SplitType>(existing?.splitType ?? "EQUAL")
  const [rows, setRows] = useState<Row[]>(() => {
    const byUser = new Map(existing?.shares.filter((s) => s.status !== "CANCELLED").map((s) => [s.debtorId, s]) ?? [])
    return members.map((m) => {
      const s = byUser.get(m.userId)
      return {
        userId: m.userId,
        included: existing ? !!s : true,
        value: s ? (existing?.splitType === "PERCENTAGE" && s.percentage != null ? s.percentage.toFixed(2) : s.amount.toFixed(2)) : "",
      }
    })
  })
  const [items, setItems] = useState<ItemRow[]>(
    existing?.items.map((i) => ({ name: i.name, amount: i.amount.toFixed(2), assignees: i.assigneeUserIds })) ?? [{ name: "", amount: "", assignees: [] }],
  )
  const [preview, setPreview] = useState<ExpensePreview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const attemptKey = useRef<string>(typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `k-${Date.now()}-${Math.random()}`)

  const nameById = useMemo(() => new Map(members.map((m) => [m.userId, m.fullName])), [members])
  const included = rows.filter((r) => r.included)
  const totalMinor = toMinor(total)

  function invalidate() { setPreview(null); setError(null) }
  function patchRow(userId: string, patch: Partial<Row>) { setRows((p) => p.map((r) => (r.userId === userId ? { ...r, ...patch } : r))); invalidate() }
  function patchItem(i: number, patch: Partial<ItemRow>) { setItems((p) => p.map((x, k) => (k === i ? { ...x, ...patch } : x))); invalidate() }

  /** Local checks only (does it add up?). The server remains the authority and re-checks everything. */
  function localProblem(): string | null {
    if (!description.trim()) return "Give the expense a title"
    if (totalMinor === null || totalMinor <= 0) return "Enter a total with at most two decimals"
    if (included.length === 0) return "Select at least one participant"
    if (dueDate && dueDate < expenseDate) return "The due date cannot be before the expense date"
    if (splitType === "CUSTOM") {
      const c = checkCustomAllocation(totalMinor, included.map((r) => toMinor(r.value)))
      if (!c.ok) return c.message
    }
    if (splitType === "PERCENTAGE") {
      const c = checkPercentAllocation(included.map((r) => toBasisPoints(r.value)))
      if (!c.ok) return c.message
    }
    if (splitType === "ITEM_BASED") {
      const c = checkItems(totalMinor, items.map((i) => ({ amountMinor: toMinor(i.amount), assignees: i.assignees.filter((a) => included.some((r) => r.userId === a)) })))
      if (!c.ok) return c.message
      if (items.some((i) => !i.name.trim())) return "Every item needs a name"
    }
    return null
  }

  function body(): ExpenseBody {
    const b: ExpenseBody = {
      description: description.trim(),
      note: note.trim() || undefined,
      amount: total.trim().replace(",", "."),
      splitType,
      paidByUserId: paidBy,
      expenseDate,
      dueDate: dueDate || undefined,
      participants: included.map((r) => ({
        userId: r.userId,
        amount: splitType === "CUSTOM" ? r.value.trim().replace(",", ".") : undefined,
        percentage: splitType === "PERCENTAGE" ? r.value.trim().replace(",", ".") : undefined,
      })),
    }
    if (splitType === "ITEM_BASED") {
      b.items = items.map((i) => ({ name: i.name.trim(), amount: i.amount.trim().replace(",", "."), assigneeUserIds: i.assignees.filter((a) => included.some((r) => r.userId === a)) }))
    }
    return b
  }

  async function review(e: FormEvent) {
    e.preventDefault()
    const problem = localProblem()
    if (problem) { setError(problem); return }
    setBusy(true); setError(null)
    try {
      setPreview(await splitBillApi.preview(circle.id, body()))
    } catch (err) {
      setError(describeSplitError(err))
    } finally { setBusy(false) }
  }

  async function save() {
    if (!preview) return
    setBusy(true); setError(null)
    try {
      if (existing) await splitBillApi.update(circle.id, existing.id, existing.revision, body())
      else await splitBillApi.create(circle.id, body(), attemptKey.current)
      onDone()
    } catch (err) {
      setError(describeSplitError(err))
    } finally { setBusy(false) }
  }

  return (
    <Modal title={editing ? "Edit shared expense" : "Split a bill"} onClose={onClose}>
      <form className="modal-form" onSubmit={review}>
        <p className="split-notice" role="note">
          Saving does not take any money. Each person is asked first and must confirm for themselves. {SIMULATED_NOTE}
        </p>
        <label className="field">
          <span>Title</span>
          <input value={description} maxLength={120} onChange={(e) => { setDescription(e.target.value); invalidate() }} required placeholder="Dinner at Mama's" autoFocus />
        </label>
        <label className="field">
          <span>Note (optional)</span>
          <input value={note} maxLength={1000} onChange={(e) => { setNote(e.target.value); invalidate() }} />
        </label>
        <div className="field-row">
          <label className="field">
            <span>Total ({circle.currency})</span>
            <input inputMode="decimal" value={total} onChange={(e) => { setTotal(e.target.value); invalidate() }} required placeholder="0.00" />
          </label>
          <label className="field">
            <span>Paid by</span>
            <select value={paidBy} onChange={(e) => { setPaidBy(e.target.value); invalidate() }}>
              {members.map((m) => <option key={m.userId} value={m.userId}>{m.fullName}</option>)}
            </select>
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>Expense date</span>
            <input type="date" value={expenseDate} max={new Date().toISOString().slice(0, 10)} onChange={(e) => { setExpenseDate(e.target.value); invalidate() }} />
          </label>
          <label className="field">
            <span>Due date (optional)</span>
            <input type="date" value={dueDate} min={expenseDate} onChange={(e) => { setDueDate(e.target.value); invalidate() }} />
          </label>
        </div>

        <div className="field">
          <span className="field-label">Split</span>
          <div className="segmented" role="tablist" aria-label="Split method">
            {TYPES.map((t) => (
              <button key={t} type="button" role="tab" aria-selected={splitType === t} className={splitType === t ? "active" : ""}
                onClick={() => { setSplitType(t); invalidate() }}>{splitTypeLabel(t)}</button>
            ))}
          </div>
        </div>

        <ul className="split-list">
          {rows.map((r) => (
            <li key={r.userId} className={`split-row ${r.included ? "" : "excluded"}`}>
              <label className="split-check">
                <input type="checkbox" checked={r.included} onChange={() => patchRow(r.userId, { included: !r.included })} />
                <span className="avatar small" aria-hidden="true">{initials(nameById.get(r.userId) ?? "?")}</span>
                <span className="split-name">{nameById.get(r.userId)}</span>
              </label>
              {r.included && (splitType === "CUSTOM" || splitType === "PERCENTAGE") && (
                <input className="split-amount" inputMode="decimal" value={r.value} onChange={(e) => patchRow(r.userId, { value: e.target.value })}
                  placeholder={splitType === "PERCENTAGE" ? "0.00 %" : "0.00"} aria-label={`${splitType === "PERCENTAGE" ? "Percentage" : "Amount"} for ${nameById.get(r.userId)}`} />
              )}
            </li>
          ))}
        </ul>

        {splitType === "ITEM_BASED" && (
          <div className="split-items">
            {items.map((it, i) => (
              <div key={i} className="split-item">
                <div className="field-row">
                  <input value={it.name} placeholder="Item" aria-label={`Item ${i + 1} name`} maxLength={120} onChange={(e) => patchItem(i, { name: e.target.value })} />
                  <input inputMode="decimal" value={it.amount} placeholder="0.00" aria-label={`Item ${i + 1} amount`} onChange={(e) => patchItem(i, { amount: e.target.value })} />
                  {items.length > 1 && <button type="button" className="btn btn-ghost btn-xs" onClick={() => { setItems((p) => p.filter((_, k) => k !== i)); invalidate() }}>Remove</button>}
                </div>
                <div className="split-assignees">
                  {included.map((r) => (
                    <label key={r.userId} className="chip">
                      <input type="checkbox" checked={it.assignees.includes(r.userId)}
                        onChange={() => patchItem(i, { assignees: it.assignees.includes(r.userId) ? it.assignees.filter((a) => a !== r.userId) : [...it.assignees, r.userId] })} />
                      {nameById.get(r.userId)}
                    </label>
                  ))}
                </div>
              </div>
            ))}
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setItems((p) => [...p, { name: "", amount: "", assignees: [] }]); invalidate() }}>+ Add item</button>
          </div>
        )}

        {error && <p className="form-error" role="alert">{error}</p>}

        {preview && (
          <section className="split-preview-panel" aria-label="Calculated shares">
            <h3>Review the shares</h3>
            <ul className="share-list">
              {preview.shares.map((s) => (
                <li key={s.userId} className="share-row">
                  <span className="share-name">{s.userName}</span>
                  {s.percentage != null && <span className="muted">{s.percentage.toFixed(2)}%</span>}
                  <span className="share-amount">{formatMoney(s.amount, preview.currency)}</span>
                </li>
              ))}
            </ul>
            <p className="muted small">{preview.roundingRule}</p>
            <p className="muted small">The shares add up to exactly {formatMoney(preview.amount, preview.currency)}.</p>
          </section>
        )}

        <div className="modal-footer">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          {!preview ? (
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "Calculating…" : "Review shares"}</button>
          ) : (
            <button type="button" className="btn btn-primary" onClick={save} disabled={busy}>
              {busy ? "Saving…" : editing ? "Save changes" : "Create and ask everyone"}
            </button>
          )}
        </div>
      </form>
    </Modal>
  )
}
