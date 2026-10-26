import { Link } from "react-router-dom"
import { Target } from "lucide-react"
import { formatMoney } from "../../lib/format"
import type { FutureDashboard } from "../../lib/futureApi"
import { categoryLabel, planStatusLabel, progressPercent, SCOPE_LABEL } from "../../lib/futureLogic"
import { EmptyState } from "../../components/ui/StatePanel"
import { Bar } from "./shared"
import { RemindersPanel } from "./RemindersPanel"
import { goalStatusLabel } from "../../lib/futureLogic"

export function OverviewTab({ data, currency, goTo }: { data: FutureDashboard; currency: string; goTo: (t: string) => void }) {
  if (data.goals.length === 0 && data.plans.length === 0) {
    return (
      <EmptyState
        icon={<Target size={22} aria-hidden="true" />}
        title="Plan your first goal"
        description="Goals live inside a Circle — a personal goal is a Circle with only you in it. Create one, then come back to plan how to reach it."
        action={<Link className="btn btn-primary" to="/">Create a goal</Link>}
      />
    )
  }
  const personal = data.goals.filter((g) => g.scope === "PERSONAL")
  const group = data.goals.filter((g) => g.scope === "GROUP_TRACKED")
  return (
    <div className="stack">
      <Section title="Personal goals" goals={personal} currency={currency} />
      <Section title="Community and stokvel goals" goals={group} currency={currency} />
      <div className="card stack">
        <h3>Allocation plans</h3>
        {data.plans.length === 0 ? (
          <p className="muted">No plans yet. A plan only proposes how income could be divided — it never moves money.</p>
        ) : (
          <ul className="future-list">
            {data.plans.map((p) => (
              <li key={p.id}>{p.name} <span className="badge badge-muted">{planStatusLabel(p.status)}</span></li>
            ))}
          </ul>
        )}
        <button className="btn btn-secondary btn-sm" onClick={() => goTo("allocation")}>Open Smart Allocation</button>
      </div>
      <RemindersPanel />
      <ul className="field-hint future-list">
        {data.disclosures.map((d) => <li key={d}>{d}</li>)}
      </ul>
    </div>
  )
}

function Section({ title, goals, currency }: { title: string; goals: FutureDashboard["goals"]; currency: string }) {
  if (goals.length === 0) return null
  return (
    <div className="stack">
      <h3>{title}</h3>
      {goals.map((g) => (
        <div className="card stack" key={g.goalId}>
          <div className="future-between">
            <strong>{g.title}</strong>
            <span className="badge badge-muted">{categoryLabel(g.category)}</span>
          </div>
          <Bar percent={progressPercent(g.recordedProgress, g.targetAmount)} />
          <div>
            Recorded progress {formatMoney(g.recordedProgress, currency)} of {formatMoney(g.targetAmount, currency)}
            {" · "}Remaining {formatMoney(g.remaining, currency)}
          </div>
          {g.scope === "GROUP_TRACKED" && (
            <div>Your own recorded contributions: {formatMoney(g.myRecordedContributions, currency)}</div>
          )}
          <div className="field-hint">{SCOPE_LABEL[g.scope]} — {g.progressNote}</div>
          {g.status !== "ACTIVE" && <div className="field-hint">{goalStatusLabel(g.status)}</div>}
          {g.deadline && <div className="field-hint">Target date {g.deadline}</div>}
          <div className="future-row">
            <Link className="btn btn-ghost btn-sm" to={`/circles/${g.circleId}`}>Open</Link>
            {g.saveToBuy && <Link className="btn btn-ghost btn-sm" to="/save-to-buy">Save-to-Buy</Link>}
          </div>
        </div>
      ))}
    </div>
  )
}
