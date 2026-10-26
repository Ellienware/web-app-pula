import { useState } from "react"
import { formatMoney } from "../../lib/format"
import { futureApi, type FutureGoal, type Schedule } from "../../lib/futureApi"
import { categoryLabel, describeFutureError, GOAL_CATEGORIES, goalStatusLabel, validateGoalForm } from "../../lib/futureLogic"
import { Button } from "../../components/ui/Button"
import { EmptyState } from "../../components/ui/StatePanel"

export function GoalsTab({ goals, currency, onChanged }: { goals: FutureGoal[]; currency: string; onChanged: () => void }) {
  return (
    <div className="stack">
      <CreateGoal goals={goals} onCreated={onChanged} />
      {goals.length === 0 && <EmptyState title="No goals yet" description="Create your first goal above. It will appear here." />}
      {goals.map((g) => <GoalCard key={g.goalId} goal={g} currency={currency} onChanged={onChanged} />)}
    </div>
  )
}

function CreateGoal({ goals, onCreated }: { goals: FutureGoal[]; onCreated: () => void }) {
  const [title, setTitle] = useState("")
  const [target, setTarget] = useState("")
  const [deadline, setDeadline] = useState("")
  const [category, setCategory] = useState("")
  const [priority, setPriority] = useState("")
  const [circleId, setCircleId] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  // Only circles where the user may add goals are offered; the backend enforces the admin rule.
  const circles = [...new Map(goals.filter((g) => g.scope === "GROUP_TRACKED").map((g) => [g.circleId, g.circleName])).entries()]

  async function submit() {
    setError(null); setDone(false)
    const v = validateGoalForm(title, target, deadline)
    if (!v.ok) return setError(v.error)
    setBusy(true)
    try {
      await futureApi.createGoal({ title: title.trim(), targetAmount: v.target, deadline: deadline || undefined,
        category: category || undefined, priority: priority || undefined, circleId: circleId || undefined })
      setTitle(""); setTarget(""); setDeadline(""); setCategory(""); setPriority(""); setCircleId(""); setDone(true); onCreated()
    } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }

  return (
    <div className="card stack">
      <h3>Create a goal</h3>
      <p className="field-hint">A goal is a target you track. Creating one does not move or reserve any money.</p>
      <label className="field"><span>Goal name</span><input value={title} maxLength={255} onChange={(e) => setTitle(e.target.value)} /></label>
      <div className="grid">
        <label className="field"><span>Target amount</span><input inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} /></label>
        <label className="field"><span>Target date (optional)</span><input type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} /></label>
        <label className="field"><span>Category</span>
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">Uncategorised</option>
            {GOAL_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
        </label>
        <label className="field"><span>Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Not set</option><option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option>
          </select>
        </label>
      </div>
      <label className="field"><span>Where</span>
        <select value={circleId} onChange={(e) => setCircleId(e.target.value)}>
          <option value="">Personal goal (just me)</option>
          {circles.map(([id, name]) => <option key={id} value={id}>Shared: {name}</option>)}
        </select>
        <span className="field-hint">Shared goals can only be added by a Circle admin.</span>
      </label>
      <div><Button loading={busy} onClick={submit}>Create goal</Button></div>
      {done && <div className="field-hint" role="status">Goal created.</div>}
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}

function GoalCard({ goal, currency, onChanged }: { goal: FutureGoal; currency: string; onChanged: () => void }) {
  const [category, setCategory] = useState(goal.category ?? "")
  const [priority, setPriority] = useState(goal.priority ?? "")
  const [freq, setFreq] = useState<"WEEKLY" | "MONTHLY">("MONTHLY")
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  async function saveMeta() {
    setBusy(true); setError(null); setSaved(false)
    try {
      await futureApi.goalMeta(goal.goalId, { category: category || undefined, priority: priority || undefined })
      setSaved(true); onChanged()
    } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function togglePause() {
    setBusy(true); setError(null)
    try { await (goal.status === "PAUSED" ? futureApi.resumeGoal(goal.goalId) : futureApi.pauseGoal(goal.goalId)); onChanged() }
    catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  async function estimate() {
    setBusy(true); setError(null)
    try { setSchedule(await futureApi.schedule(goal.goalId, freq)) }
    catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }

  return (
    <div className="card stack">
      <div className="future-between">
        <strong>{goal.title}</strong>
        <span className="badge badge-muted">{categoryLabel(goal.category)}</span>
      </div>
      <div>Target {formatMoney(goal.targetAmount, currency)} · Recorded {formatMoney(goal.recordedProgress, currency)}</div>
      <div className="field-hint">{goalStatusLabel(goal.status)}</div>
      {(goal.status === "ACTIVE" || goal.status === "PAUSED") && (
        <div className="future-row">
          <Button size="sm" variant="ghost" loading={busy} onClick={togglePause}>{goal.status === "PAUSED" ? "Resume goal" : "Pause goal"}</Button>
          <span className="field-hint">Pausing only stops new contributions to this goal. Existing records are kept. Circle admins only.</span>
        </div>
      )}

      <div className="grid">
        <label className="field"><span>Category</span>
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">Uncategorised</option>
            {GOAL_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
        </label>
        <label className="field"><span>Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Not set</option><option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option><option value="HIGH">High</option>
          </select>
        </label>
      </div>
      <div className="future-row">
        <Button size="sm" variant="secondary" loading={busy} onClick={saveMeta}>Save details</Button>
        {saved && <span className="field-hint" role="status">Saved. Only the label changed — no money was touched.</span>}
      </div>
      <div className="field-hint">Only a Circle admin can change details. Target and progress are managed in the Circle.</div>

      <div className="future-row">
        <label className="field"><span>Estimate a schedule</span>
          <select value={freq} onChange={(e) => setFreq(e.target.value as "WEEKLY" | "MONTHLY")}>
            <option value="MONTHLY">Monthly</option><option value="WEEKLY">Weekly</option>
          </select>
        </label>
        <Button size="sm" variant="ghost" loading={busy} onClick={estimate}>Estimate</Button>
      </div>
      {schedule && (
        <div className="stack" role="status">
          {schedule.suggestedPerPeriod != null
            ? <strong>About {formatMoney(schedule.suggestedPerPeriod, currency)} {schedule.frequency === "WEEKLY" ? "a week" : "a month"} over {schedule.periods} {schedule.frequency === "WEEKLY" ? "weeks" : "months"}</strong>
            : <strong>No schedule to suggest</strong>}
          {schedule.warnings.map((w) => <div className="field-hint" key={w}>{w}</div>)}
          {schedule.purchaseNote && <div className="field-hint">{schedule.purchaseNote}</div>}
        </div>
      )}
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}
