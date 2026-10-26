import { useCallback, useEffect, useState } from "react"
import { walletApi } from "../lib/walletApi"
import type { WalletPage, WalletReconciliation, WalletTransaction } from "../lib/walletTypes"
import { describeWalletError } from "../lib/walletLogic"
import { formatDateTime, formatMoney } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"
import { Card } from "../components/ui/Card"
import { Button } from "../components/ui/Button"
import { ConfirmDialog } from "../components/ui/ConfirmDialog"
import { EmptyState, ErrorState } from "../components/ui/StatePanel"
import { SkeletonText } from "../components/ui/Skeleton"
import { SimulatedBanner } from "../components/wallet/SimulatedBanner"
import { useGuard } from "../components/wallet/useGuard"
import "../components/wallet/wallet.css"

type Pending = { tx: WalletTransaction; outcome: "complete" | "fail" }

/**
 * Operator screen for SIMULATED demo withdrawals (route /admin/wallet). Completing or failing only finalises the simulated
 * ledger state; no bank, cash or provider is involved. The server requires the ADMIN role for every call.
 */
export function AdminWalletPage() {
  const [data, setData] = useState<WalletPage<WalletTransaction> | null>(null)
  const [state, setState] = useState<"loading" | "ok" | "error">("loading")
  const [page, setPage] = useState(0)
  const [pending, setPending] = useState<Pending | null>(null)
  const [reason, setReason] = useState("")
  const [failDraft, setFailDraft] = useState<WalletTransaction | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [walletId, setWalletId] = useState("")
  const [recon, setRecon] = useState<WalletReconciliation | null>(null)
  const act = useGuard()
  const check = useGuard()

  const load = useCallback(() => {
    setState("loading")
    walletApi.adminPendingWithdrawals(page).then((d) => { setData(d); setState("ok") }).catch((e) => { setNotice(describeWalletError(e)); setState("error") })
  }, [page])
  useEffect(load, [load])

  async function confirm() {
    if (!pending) return
    await act.run(async () => {
      try {
        if (pending.outcome === "complete") await walletApi.adminCompleteWithdrawal(pending.tx.reference)
        else await walletApi.adminFailWithdrawal(pending.tx.reference, reason.trim() || "Simulated failure")
        setNotice(`Withdrawal ${pending.tx.reference} ${pending.outcome === "complete" ? "completed" : "failed; demo funds returned"} (simulated).`)
        setPending(null); setReason(""); load()
      } catch (e) {
        setPending(null)
        setNotice(describeWalletError(e))
      }
    })
  }

  async function reconcile() {
    setRecon(null)
    if (!walletId.trim()) return
    await check.run(async () => {
      try { setRecon(await walletApi.adminReconcile(walletId.trim())) } catch (e) { setNotice(describeWalletError(e)) }
    })
  }

  return (
    <div>
      <PageHeader eyebrow="Admin" title="Demo wallet operations" subtitle="Finalise simulated withdrawals and check a wallet against the ledger." />
      <SimulatedBanner />
      {notice && <p role="status" className="wallet-result success">{notice}</p>}

      <Card>
        <h3>Pending simulated withdrawals</h3>
        {state === "loading" && <SkeletonText />}
        {state === "error" && <ErrorState title="Could not load the queue" description="You may not have the admin role." onRetry={load} />}
        {state === "ok" && data && data.items.length === 0 && <EmptyState title="Nothing pending" description="New simulated withdrawals appear here." />}
        {state === "ok" && data && data.items.length > 0 && (
          <ul className="wallet-list" aria-label="Pending simulated withdrawals">
            {data.items.map((t) => (
              <li key={t.reference} className="wallet-row">
                <div>
                  <strong>{formatMoney(Number(t.amount), t.currency)}</strong> <code>{t.reference}</code>
                  <div className="field-hint">{formatDateTime(t.createdAt)} · simulated</div>
                </div>
                <div className="wallet-row-end">
                  <Button size="sm" onClick={() => setPending({ tx: t, outcome: "complete" })}>Complete</Button>
                  <Button size="sm" variant="ghost" onClick={() => { setReason(""); setFailDraft(t) }}>Fail</Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {state === "ok" && data && data.totalPages > 1 && (
          <div className="wallet-pager">
            <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
            <span aria-live="polite">Page {data.page + 1} of {data.totalPages}</span>
            <Button size="sm" variant="ghost" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
          </div>
        )}
      </Card>

      <Card>
        <h3>Reconcile a wallet</h3>
        <form onSubmit={(e) => { e.preventDefault(); reconcile() }}>
          <div className="field">
            <label htmlFor="recon-wallet">Wallet id</label>
            <input id="recon-wallet" placeholder="PW-XXXXXXXXXX" value={walletId} onChange={(e) => setWalletId(e.target.value)} autoComplete="off" />
          </div>
          <Button type="submit" size="sm" loading={check.loading}>Check against ledger</Button>
        </form>
        {recon && (
          <p role="status" className={recon.consistent ? "wallet-result success" : "field-error"}>
            {recon.consistent ? "Consistent: the balance equals the sum of the wallet's ledger postings." : "MISMATCH between the balance and the ledger postings. Escalate."}
          </p>
        )}
      </Card>

      {failDraft && !pending && (
        <Card>
          <h3>Fail {failDraft.reference}</h3>
          <form onSubmit={(e) => { e.preventDefault(); setPending({ tx: failDraft, outcome: "fail" }); setFailDraft(null) }}>
            <div className="field">
              <label htmlFor="fail-reason">Reason (shown on the transaction)</label>
              <input id="fail-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <Button type="submit" size="sm">Review</Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setFailDraft(null)}>Cancel</Button>
          </form>
        </Card>
      )}

      {pending && (
        <ConfirmDialog
          title={pending.outcome === "complete" ? "Complete this simulated withdrawal?" : "Fail this simulated withdrawal?"}
          summary={pending.outcome === "complete" ? "Finalises the simulated withdrawal. No real money moves." : "The held demo funds return to the user's wallet."}
          rows={[{ label: "Reference", value: pending.tx.reference }, { label: "Amount", value: formatMoney(Number(pending.tx.amount), pending.tx.currency) }]}
          confirmLabel={pending.outcome === "complete" ? "Complete" : "Fail and return funds"}
          loading={act.loading}
          onConfirm={confirm}
          onCancel={() => setPending(null)}
        />
      )}
    </div>
  )
}
