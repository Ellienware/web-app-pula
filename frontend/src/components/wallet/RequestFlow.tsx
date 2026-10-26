import { useCallback, useEffect, useState } from "react"
import QRCode from "qrcode"
import { Copy } from "lucide-react"
import { walletApi, type WalletScope } from "../../lib/walletApi"
import type { PaymentRequest, WalletOverview, WalletPage } from "../../lib/walletTypes"
import { describeWalletError, parseAmountInput, requestStatusLabel, requestStatusTone } from "../../lib/walletLogic"
import { formatDateTime, formatMoney } from "../../lib/format"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { Badge } from "../ui/Badge"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../ui/StatePanel"
import { Skeleton } from "../ui/Skeleton"
import { useToast } from "../../context/ToastContext"
import { SimulatedBanner } from "./SimulatedBanner"
import { useGuard } from "./useGuard"

const EXPIRY: { label: string; minutes: number }[] = [
  { label: "15 minutes", minutes: 15 }, { label: "1 hour", minutes: 60 }, { label: "24 hours", minutes: 1440 }, { label: "7 days", minutes: 10080 },
]

/** Create a payment request and share it as a QR code or text code. A request is NOT a transfer: nothing moves until the payer confirms. */
export function RequestFlow({ overview, onOpenCode, scope = walletApi }: { overview: WalletOverview; onOpenCode?: (code: string) => void; scope?: WalletScope }) {
  const { showToast } = useToast()
  const [amountText, setAmountText] = useState("")
  const [purpose, setPurpose] = useState("")
  const [payer, setPayer] = useState("")
  const [minutes, setMinutes] = useState(60)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<PaymentRequest | null>(null)
  const [qr, setQr] = useState("")
  const [box, setBox] = useState<"outgoing" | "incoming">("outgoing")
  const [list, setList] = useState<WalletPage<PaymentRequest> | null>(null)
  const [listState, setListState] = useState<"loading" | "ok" | "error">("loading")
  const [cancelling, setCancelling] = useState<PaymentRequest | null>(null)
  const create = useGuard()
  const cancel = useGuard()

  const load = useCallback(() => {
    setListState("loading")
    scope.requests(box).then((p) => { setList(p); setListState("ok") }).catch(() => setListState("error"))
  }, [box, scope])
  useEffect(load, [load])

  useEffect(() => {
    if (created?.code) QRCode.toDataURL(created.code, { margin: 1, width: 240 }).then(setQr).catch(() => setQr(""))
    else setQr("")
  }, [created?.code])

  async function submit() {
    const p = parseAmountInput(amountText, overview.limits.transferMax)
    if (!p.ok) { setError(p.error); return }
    if (!purpose.trim()) { setError("Say what the payment is for"); return }
    setError(null)
    await create.run(async () => {
      try {
        const r = await scope.createRequest(p.value, purpose.trim(), payer.trim() || undefined, minutes)
        setCreated(r); setAmountText(""); setPurpose(""); setPayer(""); load()
      } catch (e) { setError(describeWalletError(e)) }
    })
  }

  async function doCancel() {
    if (!cancelling) return
    await cancel.run(async () => {
      try {
        const r = await scope.cancelRequest(cancelling.requestId)
        if (created?.requestId === r.requestId) setCreated(r)
        showToast("Request cancelled", "success")
        load()
      } catch (e) { showToast(describeWalletError(e), "error") }
      setCancelling(null)
    })
  }

  async function copy(text: string) {
    try { await navigator.clipboard.writeText(text); showToast("Code copied") } catch { showToast("Copy failed. Select the code and copy it manually.", "error") }
  }

  return (
    <div className="wallet-stack">
      <Card>
        <SimulatedBanner compact />
        <h3>Request a simulated payment</h3>
        <form onSubmit={(e) => { e.preventDefault(); submit() }} noValidate>
          <div className="field">
            <label htmlFor="req-amount">Amount ({overview.currency})</label>
            <input id="req-amount" inputMode="decimal" autoComplete="off" value={amountText} onChange={(e) => setAmountText(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="req-purpose">What is it for?</label>
            <input id="req-purpose" maxLength={140} value={purpose} onChange={(e) => setPurpose(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="req-payer">Only this person's wallet may pay (optional)</label>
            <input id="req-payer" placeholder="PW-XXXXXXXXXX" autoComplete="off" value={payer} onChange={(e) => setPayer(e.target.value)} />
            <span className="field-hint">Leave empty to let anyone who has the code pay it.</span>
          </div>
          <div className="field">
            <label htmlFor="req-expiry">Valid for</label>
            <select id="req-expiry" value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
              {EXPIRY.map((o) => <option key={o.minutes} value={o.minutes}>{o.label}</option>)}
            </select>
          </div>
          {error && <p role="alert" className="field-error">{error}</p>}
          <Button type="submit" loading={create.loading}>Create request</Button>
        </form>
      </Card>

      {created && (
        <Card>
          <div role="status" aria-live="polite">
            <h3>Share this request <Badge tone={requestStatusTone(created.status)}>{requestStatusLabel(created.status)}</Badge></h3>
            <p>{formatMoney(Number(created.amount), created.currency)} for “{created.purpose}” (simulated). Expires {formatDateTime(created.expiresAt)}.</p>
          </div>
          {created.code && created.status === "OPEN" && (
            <>
              {qr && <img className="wallet-qr" src={qr} width={240} height={240}
                alt="QR code for this payment request. Scanning it opens the request for review; it does not pay." />}
              <p className="field-hint">Scanning or pasting this code only opens the request for review. The payer must confirm before anything moves.</p>
              <p><code className="wallet-code">{created.code}</code>{" "}
                <button type="button" className="icon-btn" onClick={() => copy(created.code!)} aria-label="Copy payment code"><Copy size={14} aria-hidden="true" /></button></p>
            </>
          )}
          {created.canCancel && <Button variant="danger" size="sm" onClick={() => setCancelling(created)}>Cancel request</Button>}
          {created.status === "PAID" && <p className="wallet-result success">Paid (simulated). Reference <code>{created.transactionReference}</code></p>}
        </Card>
      )}

      <Card>
        <div className="wallet-tabs" role="tablist" aria-label="Payment requests">
          {(scope.kind === "business" ? (["outgoing"] as const) : (["outgoing", "incoming"] as const)).map((b) => (
            <button key={b} role="tab" aria-selected={box === b} className={`wallet-tab ${box === b ? "is-active" : ""}`} onClick={() => setBox(b)}>
              {b === "outgoing" ? (scope.kind === "business" ? "Business requests" : "Requests I made") : "Requests for me"}
            </button>
          ))}
        </div>
        {listState === "loading" && <Skeleton />}
        {listState === "error" && <ErrorState title="Could not load requests" onRetry={load} />}
        {listState === "ok" && list && list.items.length === 0 && (
          <EmptyState title={box === "outgoing" ? "No requests yet" : "Nothing is waiting for you"} description={box === "outgoing" ? "Create one above." : "Requests addressed to your wallet id appear here."} />
        )}
        {listState === "ok" && list && list.items.length > 0 && (
          <ul className="wallet-list">
            {list.items.map((r) => (
              <li key={r.requestId} className="wallet-row">
                <div>
                  <strong>{formatMoney(Number(r.amount), r.currency)}</strong> — {r.purpose}
                  <div className="field-hint">{r.direction === "INCOMING" ? `From ${r.requesterName}` : r.targetPayerName ? `To ${r.targetPayerName}` : "Open request"} · {formatDateTime(r.createdAt)} · simulated</div>
                </div>
                <div className="wallet-row-end">
                  <Badge tone={requestStatusTone(r.status)}>{requestStatusLabel(r.status)}</Badge>
                  {onOpenCode && r.direction === "INCOMING" && r.canPay && r.code && <Button size="sm" onClick={() => onOpenCode(r.code!)}>Review</Button>}
                  {r.canCancel && <Button size="sm" variant="ghost" onClick={() => setCancelling(r)}>Cancel</Button>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {cancelling && (
        <ConfirmDialog title="Cancel this request?" danger
          rows={[{ label: "Amount", value: formatMoney(Number(cancelling.amount), cancelling.currency) }, { label: "For", value: cancelling.purpose }]}
          confirmLabel="Cancel request" cancelLabel="Keep it" loading={cancel.loading} onConfirm={doCancel} onCancel={() => setCancelling(null)} />
      )}
    </div>
  )
}
