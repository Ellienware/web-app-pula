import { useEffect, useState } from "react"
import { Link } from "react-router-dom"
import { api } from "../../lib/api"
import type { BusinessJourney } from "../../lib/types"

/** The PULA business lifecycle: Start → Build → List → Trade → Get paid → Restock → Deliver → Records → Funding-ready. */
export function JourneyCard() {
  const [j, setJ] = useState<BusinessJourney | null>(null)
  useEffect(() => { api.get<BusinessJourney>("/business/journey").then(setJ).catch(() => setJ(null)) }, [])
  if (!j) return null
  return (
    <div className="card stack">
      <h3 style={{ margin: 0 }}>{j.hasBusiness ? `${j.businessName} — your PULA journey` : "Start your PULA journey"}</h3>
      <p className="muted" style={{ margin: 0, fontSize: "0.82rem" }}>{j.doneCount} of {j.stages.length} steps done</p>
      <ol style={{ listStyle: "none", padding: 0, margin: 0 }}>
        {j.stages.map((s) => (
          <li key={s.key} className="row" style={{ gap: 10, padding: "6px 0", alignItems: "flex-start" }}>
            <span aria-label={s.done ? "Done" : "To do"} style={{ color: s.done ? "var(--success)" : "var(--muted-2)", fontWeight: 800 }}>{s.done ? "✓" : "○"}</span>
            <div>
              <Link to={s.route} style={{ fontWeight: s.key === j.nextStageKey ? 700 : 500 }}>{s.title}</Link>
              <div className="muted" style={{ fontSize: "0.8rem" }}>{s.description}</div>
            </div>
          </li>
        ))}
      </ol>
      <p className="muted" style={{ margin: 0, fontSize: "0.8rem" }}>{j.note}</p>
    </div>
  )
}
