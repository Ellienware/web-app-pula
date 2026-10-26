import { DemoPaymentNotice } from "../components/ui/DemoPaymentNotice"
import { useCallback, useEffect, useState } from "react"
import { settlementApi } from "../lib/settlementApi"
import { SOURCE_LABELS } from "../lib/settlementTypes"
import type { SellerSummary, Settlement, SettlementInsights } from "../lib/settlementTypes"
import { formatMoney } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"

const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")

/** A seller's own settlements across retail, food, stock and services. Read-only: nothing here changes a settlement. */
export function SellerSettlementsPage() {
  const [rows, setRows] = useState<Settlement[]>([])
  const [summary, setSummary] = useState<SellerSummary | null>(null)
  const [explained, setExplained] = useState<Record<string, SettlementInsights>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    try { setRows(await settlementApi.mine()); setSummary(await settlementApi.mineSummary()) } catch (e) { setNotice(errMsg(e)) }
  }, [])
  useEffect(() => { void load() }, [load])

  return (
    <div className="page stack">
      <PageHeader title="My settlements" />
      <DemoPaymentNotice kind="payout" />
      {notice && <div className="state-panel" role="alert">{notice}</div>}
      {summary && (
        <section className="card">
          <p>Paid out: <strong>{formatMoney(summary.paid, "ZAR")}</strong> · on its way: {formatMoney(summary.owed, "ZAR")} · on hold: {formatMoney(summary.onHold, "ZAR")}</p>
          <ul>{summary.bySource.map((t) => <li key={t.sourceType}>{SOURCE_LABELS[t.sourceType]}: {t.count} sales · gross {formatMoney(t.gross, "ZAR")} · commission {formatMoney(t.commission, "ZAR")} · you receive {formatMoney(t.sellerNet, "ZAR")}</li>)}</ul>
        </section>
      )}
      {rows.length === 0 && <p className="muted">No settlements yet. A settlement appears when a customer pays, and is paid out after the sale is complete.</p>}
      {rows.map((s) => (
        <article key={s.id} className="card">
          <strong>{SOURCE_LABELS[s.sourceType]}</strong> · <span className="badge">{s.status}</span> · you receive {formatMoney(s.net, s.currency)}
          <p className="muted">gross {formatMoney(s.gross, s.currency)} · commission {formatMoney(s.commission, s.currency)} · fees {formatMoney(s.fixedFee + s.paymentFee, s.currency)} · refunded {formatMoney(s.refunded, s.currency)}</p>
          <button onClick={async () => { try { setExplained({ ...explained, [s.id]: await settlementApi.explainMine(s.id) }) } catch (e) { setNotice(errMsg(e)) } }}>How was this calculated?</button>
          {explained[s.id] && <ul>{explained[s.id].points.map((p) => <li key={p}>{p}</li>)}</ul>}
        </article>
      ))}
    </div>
  )
}
