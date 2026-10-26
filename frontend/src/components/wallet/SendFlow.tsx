import { useRef, useState } from "react"
import { walletApi, type WalletScope } from "../../lib/walletApi"
import type { Recipient, WalletOverview, WalletTransaction } from "../../lib/walletTypes"
import { describeWalletError, newIdempotencyKey, parseAmountInput } from "../../lib/walletLogic"
import { AttemptKey } from "../../lib/attemptKey"
import { formatMoney } from "../../lib/format"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { SimulatedBanner } from "./SimulatedBanner"
import { useGuard } from "./useGuard"

type Step = "form" | "review" | "done"

/** Internal simulated transfer: find a recipient, enter an amount, REVIEW, then explicitly confirm. */
export function SendFlow({ overview, onChanged, scope = walletApi }: { overview: WalletOverview; onChanged: () => void; scope?: WalletScope }) {
  const [identifier, setIdentifier] = useState("")
  const [recipient, setRecipient] = useState<Recipient | null>(null)
  const [amountText, setAmountText] = useState("")
  const [amount, setAmount] = useState("")
  const [step, setStep] = useState<Step>("form")
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<WalletTransaction | null>(null)
  const attempt = useRef(new AttemptKey(newIdempotencyKey))
  const lookup = useGuard()
  const send = useGuard()

  async function find() {
    setError(null); setRecipient(null)
    if (!identifier.trim()) { setError("Enter a wallet id, email or phone number"); return }
    await lookup.run(async () => {
      try { setRecipient(await scope.lookupRecipient(identifier.trim())) } catch (e) { setError(describeWalletError(e)) }
    })
  }

  function review() {
    if (!recipient) { setError("Find the recipient first"); return }
    const p = parseAmountInput(amountText, overview.limits.transferMax)
    if (!p.ok) { setError(p.error); return }
    setError(null); setAmount(p.value); setStep("review")
  }

  async function confirm() {
    if (!recipient) return
    await send.run(async () => {
      try {
        const tx = await scope.transfer(recipient.recipientId, amount, attempt.current.keyFor(`${recipient.recipientId}|${amount}|${overview.currency}`))
        attempt.current.settle()
        setResult(tx); setStep("done"); onChanged()
      } catch (e) {
        attempt.current.onError(e)
        setStep("form")
        setError(describeWalletError(e))
      }
    })
  }

  function reset() {
    setIdentifier(""); setRecipient(null); setAmountText(""); setAmount(""); setResult(null); setError(null); setStep("form")
  }

  if (step === "done" && result) {
    return (
      <Card>
        <SimulatedBanner compact />
        <div role="status" className="wallet-result success">
          <h3>Simulated transfer posted</h3>
          <p>{formatMoney(Number(result.amount), result.currency)} sent to {result.counterparty} as demo funds. This is not a real-world payment.</p>
          <p>Reference <code>{result.reference}</code></p>
        </div>
        <Button onClick={reset}>Send another</Button>
      </Card>
    )
  }

  return (
    <Card>
      <SimulatedBanner compact />
      <form onSubmit={(e) => { e.preventDefault(); review() }} noValidate>
        <div className="field">
          <label htmlFor="send-recipient">Recipient (wallet id PW-/BW-, email or phone)</label>
          <input id="send-recipient" value={identifier} autoComplete="off"
            onChange={(e) => { setIdentifier(e.target.value); setRecipient(null) }} />
          <span className="field-hint">Exact match only. We show the recipient's first name and initial so you can check it is the right person.</span>
        </div>
        <Button type="button" variant="secondary" size="sm" loading={lookup.loading} onClick={find}>Find recipient</Button>
        {recipient && <p role="status" className="wallet-recipient">Sending to <strong>{recipient.displayName}</strong> (<code>{recipient.recipientId}</code>)</p>}

        <div className="field">
          <label htmlFor="send-amount">Amount ({overview.currency})</label>
          <input id="send-amount" inputMode="decimal" autoComplete="off" value={amountText} onChange={(e) => setAmountText(e.target.value)} />
          <span className="field-hint">Available demo balance {formatMoney(Number(overview.balance), overview.currency)}. Limit per transfer {formatMoney(Number(overview.limits.transferMax), overview.currency)}.</span>
        </div>
        {error && <p role="alert" className="field-error">{error}</p>}
        <Button type="submit" disabled={!recipient}>Review transfer</Button>
      </form>

      {step === "review" && recipient && (
        <ConfirmDialog
          title="Confirm simulated transfer"
          summary="Demo funds will move between two PULA Money wallets. Nothing real is paid or settled."
          rows={[
            { label: "To", value: `${recipient.displayName} (${recipient.recipientId})` },
            { label: "Amount", value: formatMoney(Number(amount), overview.currency) },
            { label: "Type", value: "Simulated internal transfer" },
          ]}
          confirmLabel="Send demo funds"
          loading={send.loading}
          onConfirm={confirm}
          onCancel={() => setStep("form")}
        />
      )}
    </Card>
  )
}
