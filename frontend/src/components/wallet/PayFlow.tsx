import { useCallback, useEffect, useRef, useState } from "react"
import { ScanLine } from "lucide-react"
import { walletApi } from "../../lib/walletApi"
import type { PaymentRequest, PaymentResult } from "../../lib/walletTypes"
import { describeWalletError, extractPaymentCode, requestStatusLabel, requestStatusTone, reviewView } from "../../lib/walletLogic"
import { formatDateTime, formatMoney } from "../../lib/format"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { Badge } from "../ui/Badge"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { SimulatedBanner } from "./SimulatedBanner"
import { QrScanner } from "./QrScanner"
import { useGuard } from "./useGuard"

/**
 * Pay a request: scan or paste a code, REVIEW who/how much/what for, then explicitly confirm.
 * Opening a code (resolve) never moves funds; only the confirm button does, and the server re-checks status and amount.
 */
export function PayFlow({ initialCode, onChanged }: { initialCode?: string | null; onChanged: () => void }) {
  const [text, setText] = useState("")
  const [code, setCode] = useState<string | null>(null)
  const [request, setRequest] = useState<PaymentRequest | null>(null)
  const [result, setResult] = useState<PaymentResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [scanning, setScanning] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const open = useGuard()
  const pay = useGuard()
  const reject = useGuard()
  const lastInitial = useRef<string | null>(null)

  const resolve = useCallback(async (raw: string) => {
    const c = extractPaymentCode(raw)
    setError(null); setResult(null); setRequest(null); setCode(null)
    if (!c) { setError("That is not a PULA payment code. Scan or paste the code you were given."); return }
    await open.run(async () => {
      try { setRequest(await walletApi.resolveCode(c)); setCode(c) } catch (e) { setError(describeWalletError(e)) }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (initialCode && initialCode !== lastInitial.current) { lastInitial.current = initialCode; setText(initialCode); resolve(initialCode) }
  }, [initialCode, resolve])

  const onScanned = useCallback((c: string) => { setScanning(false); setText(c); resolve(c) }, [resolve])

  async function confirm() {
    if (!request || !code) return
    await pay.run(async () => {
      try {
        const r = await walletApi.payRequest(code, request.amount, request.currency)
        setResult(r); setRequest(r.request); setConfirming(false); onChanged()
      } catch (e) {
        setConfirming(false)
        setError(describeWalletError(e))
        try { setRequest(await walletApi.resolveCode(code)) } catch { /* keep the previous review */ }
      }
    })
  }

  async function decline() {
    if (!code) return
    await reject.run(async () => {
      try { setRequest(await walletApi.rejectRequest(code)) } catch (e) { setError(describeWalletError(e)) }
    })
  }

  function reset() { setText(""); setCode(null); setRequest(null); setResult(null); setError(null) }

  const view = request ? reviewView(request, result !== null) : null

  return (
    <Card>
      <SimulatedBanner compact />
      <h3>Pay a request</h3>
      <form onSubmit={(e) => { e.preventDefault(); resolve(text) }} noValidate>
        <div className="field">
          <label htmlFor="pay-code">Payment code</label>
          <input id="pay-code" value={text} autoComplete="off" spellCheck={false} placeholder="PULAPAY1.…" onChange={(e) => setText(e.target.value)} />
          <span className="field-hint">Scanning or pasting a code only opens it so you can review it. Nothing is paid yet.</span>
        </div>
        <div className="wallet-actions">
          <Button type="submit" size="sm" loading={open.loading}>Open request</Button>
          <Button type="button" variant="secondary" size="sm" onClick={() => setScanning((s) => !s)}><ScanLine size={14} aria-hidden="true" /> Scan QR</Button>
        </div>
      </form>
      {scanning && <QrScanner onCode={onScanned} onClose={() => setScanning(false)} />}
      {open.loading && <p role="status">Opening request…</p>}
      {error && <p role="alert" className="field-error">{error}</p>}

      {request && view && (
        <section className="wallet-review" aria-label="Payment request review">
          <h4>Review <Badge tone={requestStatusTone(request.status)}>{requestStatusLabel(request.status)}</Badge></h4>
          <dl className="confirm-dialog-rows">
            <div className="confirm-dialog-row"><dt>Pay to</dt><dd>{request.requesterName}</dd></div>
            <div className="confirm-dialog-row"><dt>Amount</dt><dd>{formatMoney(Number(request.amount), request.currency)} (simulated)</dd></div>
            <div className="confirm-dialog-row"><dt>For</dt><dd>{request.purpose}</dd></div>
            <div className="confirm-dialog-row"><dt>Expires</dt><dd>{formatDateTime(request.expiresAt)}</dd></div>
          </dl>
          {view.message && (
            <p role="status" className={view.kind === "paid-now" ? "wallet-result success" : undefined}>
              {view.message}
              {view.kind === "paid-now" && <> Reference <code>{result?.transaction?.reference ?? request.transactionReference}</code>.</>}
            </p>
          )}
          <div className="wallet-actions">
            {view.showPay && <Button onClick={() => setConfirming(true)}>Review & pay</Button>}
            {view.showReject && <Button variant="ghost" loading={reject.loading} onClick={decline}>Decline</Button>}
            {view.showDone && <Button variant="ghost" onClick={reset}>Done</Button>}
          </div>
        </section>
      )}

      {confirming && request && (
        <ConfirmDialog
          title="Confirm simulated payment"
          summary="Demo funds will move from your wallet to the requester's wallet. This is not a real-world payment."
          rows={[
            { label: "To", value: request.requesterName },
            { label: "Amount", value: formatMoney(Number(request.amount), request.currency) },
            { label: "For", value: request.purpose },
          ]}
          confirmLabel="Pay with demo funds"
          loading={pay.loading}
          onConfirm={confirm}
          onCancel={() => setConfirming(false)}
        />
      )}
    </Card>
  )
}
