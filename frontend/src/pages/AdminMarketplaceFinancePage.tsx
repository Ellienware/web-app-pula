import { DemoPaymentNotice } from "../components/ui/DemoPaymentNotice"
import { useCallback, useEffect, useState } from "react"
import { settlementApi } from "../lib/settlementApi"
import { SOURCE_LABELS } from "../lib/settlementTypes"
import type {
  MarketplaceSummary, ReconciliationReport, Settlement, SettlementInsights, SettlementStatus, SourceType,
} from "../lib/settlementTypes"
import { formatMoney } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"

type Tab = "summary" | "settlements" | "reconciliation"
const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Something went wrong")
const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random())
const STATUSES: SettlementStatus[] = ["NOT_ELIGIBLE", "ELIGIBLE", "PROCESSING", "PAID", "FAILED", "ON_HOLD", "CANCELLED"]

/**
 * Admin marketplace finance: volume, PULA commission, fees, refunds, seller and driver money, PULA loss, every settlement
 * with its full breakdown, and reconciliation. Admins can hold, release and add an immutable reasoned adjustment. There is
 * deliberately no control to mark a settlement paid, change an amount, issue a refund or override a reconciliation result.
 * The server requires an ADMIN for every call.
 */
export function AdminMarketplaceFinancePage() {
  const [tab, setTab] = useState<Tab>("summary")
  const [summary, setSummary] = useState<MarketplaceSummary | null>(null)
  const [rows, setRows] = useState<Settlement[]>([])
  const [recon, setRecon] = useState<ReconciliationReport | null>(null)
  const [status, setStatus] = useState<SettlementStatus | "">("")
  const [source, setSource] = useState<SourceType | "">("")
  const [explained, setExplained] = useState<Record<string, SettlementInsights>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setSummary(await settlementApi.summary())
      setRows(await settlementApi.list({ status: status || undefined, source: source || undefined }))
    } catch (e) { setNotice(errMsg(e)) }
  }, [status, source])
  useEffect(() => { void load() }, [load])

  async function act(fn: () => Promise<unknown>) { setNotice(null); try { await fn(); await load() } catch (e) { setNotice(errMsg(e)) } }

  return (
    <div className="page stack">
      <PageHeader title="Marketplace finance" />
      <DemoPaymentNotice kind="payout" />
      {notice && <div className="state-panel" role="alert">{notice}</div>}
      <div className="tabs" role="tablist">
        {(["summary", "settlements", "reconciliation"] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "tab active" : "tab"} onClick={() => setTab(t)}>
            {t === "summary" ? "Summary" : t === "settlements" ? "Settlements" : "Reconciliation"}
          </button>
        ))}
      </div>

      {tab === "summary" && summary && (
        <section className="card">
          <p>Gross marketplace volume: <strong>{formatMoney(summary.grossMarketplaceVolume, "ZAR")}</strong></p>
          <p>PULA commission revenue: {formatMoney(summary.pulaCommissionRevenue, "ZAR")} · payment fees: {formatMoney(summary.paymentFees, "ZAR")} · refunds: {formatMoney(summary.refunds, "ZAR")}</p>
          <p>Seller net (all settlements): {formatMoney(summary.sellerNetTotal, "ZAR")} · PULA operational loss: {formatMoney(summary.pulaOperationalLoss, "ZAR")}</p>
          <p>Failed settlements: {summary.failedSettlements} · on hold: {summary.onHoldSettlements}</p>
          <table>
            <thead><tr><th>Domain</th><th>Count</th><th>Gross</th><th>Commission</th><th>Seller net</th></tr></thead>
            <tbody>{summary.bySource.map((t) => (
              <tr key={t.sourceType}><td>{SOURCE_LABELS[t.sourceType]}</td><td>{t.count}</td><td>{formatMoney(t.gross, "ZAR")}</td><td>{formatMoney(t.commission, "ZAR")}</td><td>{formatMoney(t.sellerNet, "ZAR")}</td></tr>
            ))}</tbody>
          </table>
          <h3>Driver payouts (existing driver payout system)</h3>
          <ul>{summary.driverPayouts.map((d) => <li key={d.status}>{d.status}: {d.count} · {formatMoney(d.amount, "ZAR")}</li>)}</ul>
          <p className="muted">Figures are read from settlements and payouts. PULA holds no wallet or pooled balance.</p>
        </section>
      )}

      {tab === "settlements" && (
        <>
          <section className="card">
            <select value={source} onChange={(e) => setSource(e.target.value as SourceType | "")}>
              <option value="">All domains</option>
              {(Object.keys(SOURCE_LABELS) as SourceType[]).map((s) => <option key={s} value={s}>{SOURCE_LABELS[s]}</option>)}
            </select>
            <select value={status} onChange={(e) => setStatus(e.target.value as SettlementStatus | "")}>
              <option value="">Any status</option>
              {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </section>
          {rows.length === 0 && <p className="muted">No settlements.</p>}
          {rows.map((s) => (
            <article key={s.id} className="card">
              <strong>{SOURCE_LABELS[s.sourceType]}</strong> · {s.businessName} · <span className="badge">{s.status}</span> · net {formatMoney(s.net, s.currency)}
              <p className="muted">
                gross {formatMoney(s.gross, s.currency)} · refunded {formatMoney(s.refunded, s.currency)} · commission {formatMoney(s.commission, s.currency)}
                · fees {formatMoney(s.fixedFee + s.paymentFee, s.currency)} · adjustments {formatMoney(s.adjustments, s.currency)}
                {s.compensation > 0 ? ` · compensation ${formatMoney(s.compensation, s.currency)} (PULA loss ${formatMoney(s.pulaOperationalLoss, s.currency)})` : ""}
              </p>
              {s.failureReason && <p className="muted">Failure: {s.failureReason} (attempts {s.payoutAttempts})</p>}
              {s.holdReason && <p className="muted">Hold: {s.holdReason}</p>}
              {s.payoutReference && <p className="muted">Payout reference: {s.payoutReference}</p>}
              {s.adjustmentHistory.map((a) => <p key={a.id} className="muted">{a.kind} {formatMoney(Math.abs(a.amount), s.currency)}: {a.reason} ({a.createdBy})</p>)}
              {["NOT_ELIGIBLE", "ELIGIBLE", "FAILED"].includes(s.status) && (
                <button onClick={() => { const r = window.prompt("Reason for holding?"); if (r) void act(() => settlementApi.hold(s.id, r)) }}>Hold</button>
              )}
              {s.status === "ON_HOLD" && <button onClick={() => act(() => settlementApi.release(s.id))}>Release</button>}
              {["NOT_ELIGIBLE", "ELIGIBLE", "ON_HOLD", "FAILED"].includes(s.status) && (
                <button onClick={() => {
                  const kind = window.prompt("CREDIT or DEBIT?")?.toUpperCase()
                  const amount = Number(window.prompt("Amount?"))
                  const reason = window.prompt("Reason (kept permanently)?")
                  if ((kind === "CREDIT" || kind === "DEBIT") && amount > 0 && reason) void act(() => settlementApi.adjust(s.id, kind, amount, reason, newKey()))
                }}>Add adjustment</button>
              )}
              <button onClick={async () => { try { setExplained({ ...explained, [s.id]: await settlementApi.explain(s.id) }) } catch (e) { setNotice(errMsg(e)) } }}>Explain</button>
              {explained[s.id] && <div className="muted"><ul>{explained[s.id].points.map((p) => <li key={p}>{p}</li>)}</ul><p>{explained[s.id].notice}</p></div>}
            </article>
          ))}
        </>
      )}

      {tab === "reconciliation" && (
        <>
          <button onClick={async () => { try { setRecon(await settlementApi.reconciliation()) } catch (e) { setNotice(errMsg(e)) } }}>Run reconciliation</button>
          {recon && (
            <section className="card">
              <p>Checked {recon.checked} · matched {recon.matched} · problems {recon.problems}</p>
              {recon.rows.map((r) => (
                <article key={r.sourceType + r.sourceId} className="card">
                  <strong>{SOURCE_LABELS[r.sourceType]}</strong> {r.sourceId} · <span className="badge">{r.state}</span>
                  <p>{r.detail}</p>
                  <p className="muted">
                    sale {r.sourceTotal ?? "-"} · payment {r.paymentAmount ?? "-"} ({r.paymentReference ?? "no ref"}) · settlement gross {r.settlementGross ?? "-"} · payout ref {r.payoutReference ?? "-"}
                  </p>
                </article>
              ))}
            </section>
          )}
        </>
      )}
    </div>
  )
}
