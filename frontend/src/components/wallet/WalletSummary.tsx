import { useRef, useState } from "react"
import { Copy } from "lucide-react"
import { walletApi, type WalletScope } from "../../lib/walletApi"
import type { WalletOverview, WalletTransaction } from "../../lib/walletTypes"
import { describeWalletError, newIdempotencyKey, parseAmountInput } from "../../lib/walletLogic"
import { AttemptKey } from "../../lib/attemptKey"
import { formatMoney } from "../../lib/format"
import { Card } from "../ui/Card"
import { Button } from "../ui/Button"
import { ConfirmDialog } from "../ui/ConfirmDialog"
import { useToast } from "../../context/ToastContext"
import { useGuard } from "./useGuard"

type Mode = "fund" | "withdraw"

/** Balance (read from the ledger by the server), the wallet id to share, demo funding and simulated withdrawals. */
export function WalletSummary({ overview, onChanged, scope = walletApi }: { overview: WalletOverview; onChanged: () => void; scope?: WalletScope }) {
  const { showToast } = useToast()
  const [mode, setMode] = useState<Mode | null>(null)
  const [amountText, setAmountText] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<string | null>(null)
  const [done, setDone] = useState<WalletTransaction | null>(null)
  const attempt = useRef(new AttemptKey(newIdempotencyKey))
  const guard = useGuard()

  const max = mode === "fund" ? overview.limits.demoFundingPerRequestMax : overview.limits.withdrawalMax

  function begin(m: Mode) {
    setMode(m); setAmountText(""); setError(null); setReview(null); setDone(null)
  }

  function toReview() {
    const p = parseAmountInput(amountText, max)
    if (!p.ok) { setError(p.error); return }
    setError(null)
    setReview(p.value)
  }

  async function confirm() {
    if (!review || !mode) return
    await guard.run(async () => {
      const sig = `${mode}|${review}|${overview.currency}`
      try {
        const tx = mode === "fund"
          ? await scope.demoFunding(review, attempt.current.keyFor(sig))
          : await scope.requestWithdrawal(review, attempt.current.keyFor(sig))
        attempt.current.settle()
        setDone(tx); setReview(null); setMode(null)
        showToast(mode === "fund" ? "Demo funds added (simulated)" : "Simulated withdrawal requested", "success")
        onChanged()
      } catch (e) {
        attempt.current.onError(e)
        setReview(null)
        setError(describeWalletError(e))
      }
    })
  }

  async function copyId() {
    try {
      await navigator.clipboard.writeText(overview.walletId)
      showToast("Wallet id copied")
    } catch {
      showToast("Copy failed. Select the id and copy it manually.", "error")
    }
  }

  const balance = Number(overview.balance)
  return (
    <Card>
      <div className="wallet-balance">
        <span className="wallet-balance-label">{scope.kind === "business" ? "Business simulated balance" : "Simulated balance"}</span>
        <span className="wallet-balance-amount" aria-label={`Simulated balance ${formatMoney(balance, overview.currency)}`}>
          {formatMoney(balance, overview.currency)}
        </span>
        <span className="badge info">Demo funds</span>
      </div>
      <p className="field-hint">
        Your wallet id <code>{overview.walletId}</code>{" "}
        <button type="button" className="icon-btn" onClick={copyId} aria-label="Copy wallet id"><Copy size={14} aria-hidden="true" /></button>
        {" "}— share it so others can send {scope.kind === "business" ? "this business" : "you"} demo funds.
      </p>

      <div className="wallet-actions">
        {scope.permissions.includes("FUND") && <Button variant="secondary" size="sm" onClick={() => begin("fund")}>Add demo funds</Button>}
        {scope.permissions.includes("WITHDRAW") && <Button variant="ghost" size="sm" onClick={() => begin("withdraw")} disabled={balance <= 0}>Simulate a withdrawal</Button>}
      </div>

      {done && (
        <p role="status" className="wallet-result success">
          {done.type === "DEMO_ISSUANCE" ? "Demo funds added (simulated)." : "Simulated withdrawal requested. No real money moves."}{" "}
          Reference <code>{done.reference}</code>.
        </p>
      )}

      {mode && !review && (
        <form className="wallet-inline-form" onSubmit={(e) => { e.preventDefault(); toReview() }} noValidate>
          <div className={`field ${error ? "has-error" : ""}`}>
            <label htmlFor="wallet-amount">{mode === "fund" ? "Demo funds to add" : "Amount to withdraw (simulated)"} ({overview.currency})</label>
            <input id="wallet-amount" inputMode="decimal" autoComplete="off" value={amountText}
              onChange={(e) => setAmountText(e.target.value)} aria-invalid={!!error} aria-describedby="wallet-amount-hint" />
            <span id="wallet-amount-hint" className="field-hint">
              {mode === "fund"
                ? `Up to ${formatMoney(Number(max), overview.currency)} per request. These are not real funds.`
                : `Up to ${formatMoney(Number(max), overview.currency)}. This only simulates a withdrawal: no bank account or cash is involved.`}
            </span>
            {error && <span className="field-error" role="alert">{error}</span>}
          </div>
          <Button type="submit" size="sm">Review</Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => setMode(null)}>Cancel</Button>
        </form>
      )}
      {!mode && error && <p role="alert" className="field-error">{error}</p>}

      {mode && review && (
        <ConfirmDialog
          title={mode === "fund" ? "Add demo funds?" : "Request a simulated withdrawal?"}
          summary={mode === "fund" ? "This creates simulated demo value in your wallet. It is not a deposit." : "Demo funds are held until the simulated outcome. No real money, bank transfer or cash is involved."}
          rows={[
            { label: "Amount", value: formatMoney(Number(review), overview.currency) },
            { label: "Type", value: mode === "fund" ? "Simulated demo funding" : "Simulated withdrawal" },
          ]}
          confirmLabel={mode === "fund" ? "Add demo funds" : "Request simulated withdrawal"}
          loading={guard.loading}
          onConfirm={confirm}
          onCancel={() => setReview(null)}
        />
      )}
    </Card>
  )
}
