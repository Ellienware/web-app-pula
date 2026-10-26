import { useCallback, useEffect, useState } from "react"
import { Trash2 } from "lucide-react"
import { Link } from "react-router-dom"
import { api } from "../lib/api"
import type {
  AffordabilityAssessment,
  AssessAffordabilityRequest,
  AssessScamRequest,
  Budget,
  CalculateLoanRequest,
  CreateOrUpdateBudgetRequest,
  FinancialProfile,
  IncomeRecord,
  IncomeSummary,
  IncomeType,
  LoanCalculation,
  ScamAssessment,
} from "../lib/types"
import { AFFORDABILITY_LABELS, INCOME_TYPE_LABELS, SCAM_RISK_LABELS } from "../lib/types"
import { PageHeader } from "../components/ui/PageHeader"
import { Button } from "../components/ui/Button"
import { MoneyDisplay } from "../components/ui/MoneyDisplay"

const INCOME_TYPES: IncomeType[] = ["FIXED_INCOME", "VARIABLE_INCOME", "IRREGULAR_INCOME"]

export function MoneyPage() {
  const [profile, setProfile] = useState<FinancialProfile | null>(null)
  const [incomeRecords, setIncomeRecords] = useState<IncomeRecord[]>([])
  const [summary, setSummary] = useState<IncomeSummary | null>(null)
  const [budget, setBudget] = useState<Budget | null>(null)
  const [budgetNotSetUp, setBudgetNotSetUp] = useState(false)

  const [incomeAmount, setIncomeAmount] = useState("")
  const [incomeSource, setIncomeSource] = useState("")
  const [incomeDate, setIncomeDate] = useState(() => new Date().toISOString().slice(0, 10))

  const [budgetLabel, setBudgetLabel] = useState("Monthly budget")
  const [categories, setCategories] = useState<{ name: string; allocatedAmount: string }[]>([
    { name: "Essentials", allocatedAmount: "" },
    { name: "Savings", allocatedAmount: "" },
  ])

  const [checkAmount, setCheckAmount] = useState("")
  const [checkCategory, setCheckCategory] = useState("")
  const [checkContext, setCheckContext] = useState("")
  const [checkResult, setCheckResult] = useState<AffordabilityAssessment | null>(null)
  const [checking, setChecking] = useState(false)

  const [loanPrincipal, setLoanPrincipal] = useState("")
  const [loanRate, setLoanRate] = useState("")
  const [loanTerm, setLoanTerm] = useState("12")
  const [loanFees, setLoanFees] = useState("")
  const [loanResult, setLoanResult] = useState<LoanCalculation | null>(null)
  const [calculating, setCalculating] = useState(false)

  const [scamText, setScamText] = useState("")
  const [scamResult, setScamResult] = useState<ScamAssessment | null>(null)
  const [scanning, setScanning] = useState(false)

  const load = useCallback(() => {
    api.get<FinancialProfile>("/money/profile").then(setProfile)
    api.get<IncomeRecord[]>("/money/income").then(setIncomeRecords)
    api.get<IncomeSummary>("/money/income/summary").then(setSummary)
    api
      .get<Budget>("/money/budget")
      .then((b) => {
        setBudget(b)
        setBudgetNotSetUp(false)
      })
      .catch(() => setBudgetNotSetUp(true))
  }, [])

  useEffect(load, [load])

  async function updateIncomeType(incomeType: IncomeType) {
    const updated = await api.put<FinancialProfile>("/money/profile", { incomeType })
    setProfile(updated)
  }

  async function addIncome() {
    if (!incomeAmount) return
    await api.post("/money/income", {
      amount: Number(incomeAmount),
      receivedDate: incomeDate,
      source: incomeSource || null,
    })
    setIncomeAmount("")
    setIncomeSource("")
    load()
  }

  async function removeIncome(id: string) {
    await api.del(`/money/income/${id}`)
    load()
  }

  function updateCategory(index: number, field: "name" | "allocatedAmount", value: string) {
    setCategories((current) => current.map((c, i) => (i === index ? { ...c, [field]: value } : c)))
  }

  function addCategory() {
    setCategories((current) => [...current, { name: "", allocatedAmount: "" }])
  }

  function removeCategory(index: number) {
    setCategories((current) => current.filter((_, i) => i !== index))
  }

  async function saveBudget() {
    const req: CreateOrUpdateBudgetRequest = {
      label: budgetLabel,
      categories: categories
        .filter((c) => c.name.trim() && c.allocatedAmount)
        .map((c) => ({ name: c.name.trim(), allocatedAmount: Number(c.allocatedAmount) })),
    }
    if (req.categories.length === 0) return
    const saved = await api.put<Budget>("/money/budget", req)
    setBudget(saved)
    setBudgetNotSetUp(false)
  }

  async function checkAffordability() {
    if (!checkAmount) return
    setChecking(true)
    try {
      const req: AssessAffordabilityRequest = {
        amount: Number(checkAmount),
        context: checkContext || null,
        category: checkCategory || null,
      }
      const result = await api.post<AffordabilityAssessment>("/money/affordability", req)
      setCheckResult(result)
    } finally {
      setChecking(false)
    }
  }

  async function calculateLoan() {
    if (!loanPrincipal || !loanRate || !loanTerm) return
    setCalculating(true)
    try {
      const req: CalculateLoanRequest = {
        principal: Number(loanPrincipal),
        annualInterestRatePercent: Number(loanRate),
        termMonths: Number(loanTerm),
        fees: loanFees ? Number(loanFees) : null,
      }
      const result = await api.post<LoanCalculation>("/money/credit", req)
      setLoanResult(result)
    } finally {
      setCalculating(false)
    }
  }

  async function scanMessage() {
    if (!scamText.trim()) return
    setScanning(true)
    try {
      const req: AssessScamRequest = { text: scamText }
      const result = await api.post<ScamAssessment>("/money/scams", req)
      setScamResult(result)
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className="page stack">
      <PageHeader
        title="Money Confidence"
        subtitle="Plan your income, track a simple budget, and see your recent pattern — not financial advice, just a clearer picture of your own numbers."
      />

      <div className="card stack">
        <h2>PULA demo wallet</h2>
        <p>Try sending, requesting and paying with simulated demo funds. Not real money.</p>
        <Link className="btn btn-secondary btn-sm" to="/money/wallet">Open demo wallet</Link>
      </div>

      <div className="card stack">
        <h2>How does your income usually arrive?</h2>
        <div className="tab-bar" role="tablist">
          {INCOME_TYPES.map((t) => (
            <button
              key={t}
              role="tab"
              aria-selected={profile?.incomeType === t}
              className={`tab ${profile?.incomeType === t ? "active" : ""}`}
              onClick={() => updateIncomeType(t)}
            >
              {INCOME_TYPE_LABELS[t]}
            </button>
          ))}
        </div>
      </div>

      <div className="card stack">
        <h2>Log income</h2>
        <div className="row wrap">
          <input
            type="number"
            placeholder="Amount"
            value={incomeAmount}
            onChange={(e) => setIncomeAmount(e.target.value)}
            style={{ maxWidth: 140 }}
          />
          <input type="date" value={incomeDate} onChange={(e) => setIncomeDate(e.target.value)} />
          <input
            placeholder="Source (optional)"
            value={incomeSource}
            onChange={(e) => setIncomeSource(e.target.value)}
            style={{ maxWidth: 200 }}
          />
          <Button onClick={addIncome} disabled={!incomeAmount}>
            Add
          </Button>
        </div>

        {summary && summary.buckets.some((b) => b.recordCount > 0) && (
          <div className="stack">
            <p className="muted">
              Last 8 weeks — average{" "}
              {summary.averagePerBucket !== null ? (
                <MoneyDisplay amount={summary.averagePerBucket} currency={summary.currency} />
              ) : (
                "—"
              )}{" "}
              per week, lowest observed{" "}
              {summary.minimumObservedPerBucket !== null ? (
                <MoneyDisplay amount={summary.minimumObservedPerBucket} currency={summary.currency} />
              ) : (
                "—"
              )}
              . These are estimates based on what you've logged, not a guarantee.
            </p>
          </div>
        )}

        {incomeRecords.length > 0 && (
          <table className="comparison-table">
            <tbody>
              {incomeRecords.slice(0, 10).map((r) => (
                <tr key={r.id}>
                  <td>{r.receivedDate}</td>
                  <td>{r.source || "—"}</td>
                  <td className="comparison-price">
                    <MoneyDisplay amount={r.amount} currency={r.currency} />
                  </td>
                  <td>
                    <button type="button" className="btn btn-ghost" onClick={() => removeIncome(r.id)} aria-label="Delete">
                      <Trash2 size={14} aria-hidden="true" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card stack">
        <h2>Budget</h2>
        {budget && !budgetNotSetUp && (
          <div className="stack">
            <p className="muted">{budget.label}</p>
            <table className="comparison-table">
              <tbody>
                {budget.categories.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name}</td>
                    <td className="comparison-price">
                      <MoneyDisplay amount={c.allocatedAmount} currency={budget.currency} />
                    </td>
                  </tr>
                ))}
                <tr>
                  <td>
                    <strong>Total</strong>
                  </td>
                  <td className="comparison-price">
                    <strong>
                      <MoneyDisplay amount={budget.totalAllocated} currency={budget.currency} />
                    </strong>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        )}

        <details>
          <summary>{budget ? "Edit budget" : "Set up a budget"}</summary>
          <div className="stack" style={{ marginTop: 12 }}>
            <input value={budgetLabel} onChange={(e) => setBudgetLabel(e.target.value)} placeholder="Budget label" />
            {categories.map((c, i) => (
              <div key={i} className="row wrap">
                <input
                  placeholder="Category, e.g. Rent"
                  value={c.name}
                  onChange={(e) => updateCategory(i, "name", e.target.value)}
                  style={{ maxWidth: 200 }}
                />
                <input
                  type="number"
                  placeholder="Amount"
                  value={c.allocatedAmount}
                  onChange={(e) => updateCategory(i, "allocatedAmount", e.target.value)}
                  style={{ maxWidth: 140 }}
                />
                <button type="button" className="btn btn-ghost" onClick={() => removeCategory(i)} aria-label="Remove category">
                  <Trash2 size={14} aria-hidden="true" />
                </button>
              </div>
            ))}
            <div className="row wrap">
              <Button variant="ghost" onClick={addCategory}>
                Add category
              </Button>
              <Button onClick={saveBudget}>Save budget</Button>
            </div>
          </div>
        </details>
      </div>

      <div className="card stack">
        <h2>Can I afford this?</h2>
        <p className="muted">
          Checked against your budget category (if it matches one) or your recent average income. This is an
          estimate, not financial advice.
        </p>
        <div className="row wrap">
          <input
            type="number"
            placeholder="Amount"
            value={checkAmount}
            onChange={(e) => setCheckAmount(e.target.value)}
            style={{ maxWidth: 140 }}
          />
          <input
            placeholder="What's it for? (optional)"
            value={checkContext}
            onChange={(e) => setCheckContext(e.target.value)}
            style={{ maxWidth: 200 }}
          />
          <input
            placeholder="Budget category (optional)"
            value={checkCategory}
            onChange={(e) => setCheckCategory(e.target.value)}
            style={{ maxWidth: 180 }}
          />
          <Button onClick={checkAffordability} disabled={!checkAmount || checking}>
            {checking ? "Checking…" : "Check"}
          </Button>
        </div>
        {checkResult && (
          <div className={`card affordability-result affordability-${checkResult.result.toLowerCase()}`}>
            <strong>{AFFORDABILITY_LABELS[checkResult.result]}</strong>
            <p className="muted">{checkResult.reasoning}</p>
          </div>
        )}
      </div>

      <div className="card stack">
        <h2>What would this loan really cost?</h2>
        <p className="muted">
          An educational calculator — PULA doesn't lend money or arrange credit. This just shows the real
          cost of a loan with the terms you enter, so you can decide for yourself.
        </p>
        <div className="row wrap">
          <input
            type="number"
            placeholder="Loan amount"
            value={loanPrincipal}
            onChange={(e) => setLoanPrincipal(e.target.value)}
            style={{ maxWidth: 140 }}
          />
          <input
            type="number"
            placeholder="Interest rate % / year"
            value={loanRate}
            onChange={(e) => setLoanRate(e.target.value)}
            style={{ maxWidth: 170 }}
          />
          <input
            type="number"
            placeholder="Term (months)"
            value={loanTerm}
            onChange={(e) => setLoanTerm(e.target.value)}
            style={{ maxWidth: 130 }}
          />
          <input
            type="number"
            placeholder="Fees (optional)"
            value={loanFees}
            onChange={(e) => setLoanFees(e.target.value)}
            style={{ maxWidth: 140 }}
          />
          <Button onClick={calculateLoan} disabled={!loanPrincipal || !loanRate || !loanTerm || calculating}>
            {calculating ? "Calculating…" : "Calculate"}
          </Button>
        </div>

        {loanResult && (
          <div className="stack">
            <table className="comparison-table">
              <tbody>
                <tr>
                  <td>Monthly repayment</td>
                  <td className="comparison-price">
                    <MoneyDisplay amount={loanResult.monthlyPayment} currency={loanResult.currency} />
                  </td>
                </tr>
                <tr>
                  <td>Total interest</td>
                  <td className="comparison-price">
                    <MoneyDisplay amount={loanResult.totalInterest} currency={loanResult.currency} />
                  </td>
                </tr>
                <tr>
                  <td>Total repayment (incl. fees)</td>
                  <td className="comparison-price">
                    <MoneyDisplay amount={loanResult.totalRepayment} currency={loanResult.currency} />
                  </td>
                </tr>
                <tr>
                  <td>Total cost above what you borrowed</td>
                  <td className="comparison-price">{loanResult.totalCostPercent}%</td>
                </tr>
              </tbody>
            </table>
            {loanResult.warnings.length > 0 && (
              <div className="affordability-result affordability-not_recommended">
                {loanResult.warnings.map((w, i) => (
                  <p key={i} className="muted">
                    {w}
                  </p>
                ))}
              </div>
            )}
            <p className="muted">{loanResult.disclaimer}</p>
          </div>
        )}
      </div>

      <div className="card stack">
        <h2>ScamShield</h2>
        <p className="muted">
          Paste a suspicious SMS, WhatsApp message, or payment request to check it for common warning signs.
          Never paste your actual PIN, password, or one-time code here or anywhere else — we'll remove anything
          that looks like one before saving your check, but the safest move is never sending it in the first place.
        </p>
        <textarea
          value={scamText}
          onChange={(e) => setScamText(e.target.value)}
          rows={4}
          placeholder="Paste the message here…"
        />
        <Button onClick={scanMessage} disabled={!scamText.trim() || scanning}>
          {scanning ? "Checking…" : "Check this message"}
        </Button>

        {scamResult && (
          <div className={`card affordability-result affordability-${scamResult.riskLevel === "HIGH" ? "not_recommended" : scamResult.riskLevel === "MEDIUM" ? "caution" : "affordable"}`}>
            <strong>{SCAM_RISK_LABELS[scamResult.riskLevel]}</strong>
            <p className="muted">{scamResult.guidance}</p>
            {scamResult.indicators.length > 0 && (
              <ul>
                {scamResult.indicators.map((indicator, i) => (
                  <li key={i}>{indicator}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
