import { useCallback, useEffect, useState } from "react"
import { PageHeader } from "../components/ui/PageHeader"
import { ErrorState } from "../components/ui/StatePanel"
import { useCurrencyConfig } from "../lib/currencyConfig"
import { futureApi, type FutureDashboard } from "../lib/futureApi"
import { describeFutureError } from "../lib/futureLogic"
import { AllocationTab } from "./future/AllocationTab"
import { GoalsTab } from "./future/GoalsTab"
import { LearnTab } from "./future/LearnTab"
import { PlanTab } from "./future/PlanTab"
import { OverviewTab } from "./future/OverviewTab"
import { SimulatorTab } from "./future/SimulatorTab"
import { CoachTab } from "./future/CoachTab"
import { StokvelsTab } from "./future/StokvelsTab"
import { FutureNotice } from "./future/shared"

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "goals", label: "Goals" },
  { id: "plan", label: "Plan" },
  { id: "allocation", label: "Smart Allocation" },
  { id: "stokvels", label: "Stokvels" },
  { id: "simulator", label: "Simulator" },
  { id: "coach", label: "Coach" },
  { id: "learn", label: "Learn" },
]

/**
 * PULA Future — planning, simulation and education. Never moves money; every figure is computed by the backend.
 * The dashboard is always fetched fresh (no cache), so recorded progress is never shown stale as if current.
 */
export default function FuturePage() {
  const { defaultCurrency: currency } = useCurrencyConfig()
  const [tab, setTab] = useState("overview")
  const [data, setData] = useState<FutureDashboard | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    futureApi.dashboard().then((d) => { setData(d); setError(null) }).catch((e) => setError(describeFutureError(e)))
  }, [])
  useEffect(load, [load])

  return (
    <div className="stack">
      <PageHeader eyebrow="PULA Future" title="Plan your future" subtitle="Set goals, plan how income could be divided, and learn — without moving any money." />
      <FutureNotice />
      <div className="future-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id}
            className={`btn btn-sm ${tab === t.id ? "btn-primary" : "btn-ghost"}`} onClick={() => setTab(t.id)}>{t.label}</button>
        ))}
      </div>
      {error && ["overview", "goals", "allocation"].includes(tab) && <ErrorState description={error} onRetry={load} />}
      {tab === "overview" && !error && (data ? <OverviewTab data={data} currency={currency} goTo={setTab} /> : <div className="spinner" role="status" aria-label="Loading" />)}
      {tab === "goals" && data && <GoalsTab goals={data.goals} currency={currency} onChanged={load} />}
      {tab === "plan" && <PlanTab currency={currency} goTo={setTab} />}
      {tab === "allocation" && data && <AllocationTab goals={data.goals} currency={currency} />}
      {tab === "stokvels" && <StokvelsTab currency={currency} />}
      {tab === "simulator" && <SimulatorTab currency={currency} />}
      {tab === "coach" && <CoachTab />}
      {tab === "learn" && <LearnTab />}
    </div>
  )
}
