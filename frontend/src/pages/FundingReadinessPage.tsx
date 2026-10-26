import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { Check, Circle } from "lucide-react"
import { api } from "../lib/api"
import type { FundingReadiness, FundingReadinessReport } from "../lib/types"
import { formatMoney } from "../lib/format"
import { PageHeader } from "../components/ui/PageHeader"
import { Button } from "../components/ui/Button"
import { ErrorState } from "../components/ui/StatePanel"

/**
 * PULA Funding Readiness — a preparation checklist built from the business's own PULA records.
 * It is NOT a credit score, does not approve/reject anything and never predicts funding.
 */
export default function FundingReadinessPage() {
  const [data, setData] = useState<FundingReadiness | null>(null)
  const [report, setReport] = useState<FundingReadinessReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  function load() {
    setLoading(true)
    api.get<FundingReadiness>("/funding-readiness")
      .then((r) => { setData(r); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(load, [])

  async function generate() {
    try { setReport(await api.get<FundingReadinessReport>("/funding-readiness/report")) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not generate the report") }
  }

  return (
    <div className="page stack">
      <PageHeader title="Funding Readiness"
        subtitle="See what records you already have and what to prepare before applying for business funding." />
      <p className="muted" style={{ margin: 0 }}>
        <Link to="/cashflow">← Cash Flow</Link>
      </p>

      {error && <ErrorState description={error} onRetry={load} />}
      {loading && <p className="muted">Loading…</p>}

      {data && (
        <>
          <div className="card stack">
            <p style={{ margin: 0, fontWeight: 700 }}>{data.businessName}</p>
            <p className="muted" style={{ margin: 0, fontSize: "0.85rem" }}>
              {data.metCount} of {data.totalCount} records in place · last 6 months ({data.periodFrom} to {data.periodTo})
            </p>
            <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
              {data.items.map((i) => (
                <li key={i.key} className="row" style={{ gap: 10, padding: "8px 0", borderBottom: "1px solid var(--line)", alignItems: "flex-start" }}>
                  {i.met
                    ? <Check size={18} color="var(--success)" aria-label="In place" />
                    : <Circle size={18} color="var(--muted-2)" aria-label="Not yet" />}
                  <div>
                    <div style={{ fontWeight: 600 }}>{i.label}</div>
                    <div className="muted" style={{ fontSize: "0.82rem" }}>{i.detail}</div>
                  </div>
                </li>
              ))}
            </ul>
          </div>

          {data.missingInformation.length > 0 && (
            <div className="card stack">
              <h3 style={{ margin: 0 }}>Missing information</h3>
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {data.missingInformation.map((m) => <li key={m}>{m}</li>)}
              </ul>
            </div>
          )}
          {data.recommendedPreparation.length > 0 && (
            <div className="card stack">
              <h3 style={{ margin: 0 }}>Recommended preparation</h3>
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {data.recommendedPreparation.map((m) => <li key={m}>{m}</li>)}
              </ul>
            </div>
          )}

          <p className="muted" style={{ fontSize: "0.82rem", margin: 0 }}>{data.disclaimer}</p>

          <div className="row" style={{ gap: 8 }}>
            <Button onClick={generate}>Generate funding-readiness summary</Button>
            {report && <Button variant="ghost" onClick={() => window.print()}>Print / save as PDF</Button>}
          </div>
        </>
      )}

      {report && (
        <div className="card stack" id="funding-report">
          <h3 style={{ margin: 0 }}>Funding-readiness summary</h3>
          <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>
            Generated {new Date(report.generatedAt).toLocaleString()} · {report.readiness.periodFrom} to {report.readiness.periodTo}
          </p>
          <p style={{ margin: 0 }}>
            <strong>{report.readiness.businessName}</strong> — {report.businessType.replace(/_/g, " ").toLowerCase()}
            {report.location ? `, ${report.location}` : ""}. Registration: {report.registrationStatus.replace(/_/g, " ").toLowerCase()}
            {report.registrationNumberProvided ? " (number on file)" : ""}. Documents on file: {report.documentsOnFile}.
          </p>
          <table style={{ width: "100%", fontSize: "0.9rem" }}>
            <tbody>
              <tr><td>Recorded sales ({report.completedSales})</td><td style={{ textAlign: "right" }}>{formatMoney(report.recordedSales, report.currency)}</td></tr>
              <tr><td>Other recorded income</td><td style={{ textAlign: "right" }}>{formatMoney(report.otherRecordedIncome, report.currency)}</td></tr>
              <tr><td>Stock purchases ({report.stockOrders} orders)</td><td style={{ textAlign: "right" }}>−{formatMoney(report.stockPurchases, report.currency)}</td></tr>
              <tr><td>Delivery costs (recorded)</td><td style={{ textAlign: "right" }}>−{formatMoney(report.deliveryCosts, report.currency)}</td></tr>
              <tr><td>Operating &amp; other expenses</td><td style={{ textAlign: "right" }}>−{formatMoney(report.operatingAndOtherExpenses, report.currency)}</td></tr>
              <tr style={{ fontWeight: 700 }}><td>Net cash movement</td><td style={{ textAlign: "right" }}>{formatMoney(report.netCashMovement, report.currency)}</td></tr>
            </tbody>
          </table>
          <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>Months with records: {report.monthsWithRecords}</p>
          <h4 style={{ margin: 0 }}>Preparation checklist</h4>
          <ul style={{ margin: 0, paddingLeft: 18 }}>{report.preparationChecklist.map((c) => <li key={c}>{c}</li>)}</ul>
          <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>{report.dataNote}</p>
          <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>{report.readiness.disclaimer}</p>
        </div>
      )}
    </div>
  )
}
