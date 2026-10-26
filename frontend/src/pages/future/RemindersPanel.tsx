import { useCallback, useEffect, useState } from "react"
import { futureApi, type ReminderSettings } from "../../lib/futureApi"
import { describeFutureError, reminderLabel } from "../../lib/futureLogic"

/** Opt-in. Everything is off until the user turns it on, and reminders are in-app notifications only. */
export function RemindersPanel() {
  const [data, setData] = useState<ReminderSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const load = useCallback(() => { futureApi.reminders().then(setData).catch((e) => setError(describeFutureError(e))) }, [])
  useEffect(load, [load])

  async function toggle(kind: string, enabled: boolean) {
    setBusy(true); setError(null)
    try { setData(await futureApi.setReminder(kind, enabled)) } catch (e) { setError(describeFutureError(e)) } finally { setBusy(false) }
  }
  if (!data) return error ? <div className="form-error" role="alert">{error}</div> : null
  return (
    <div className="card stack">
      <h3>Reminders</h3>
      {data.settings.map((s) => (
        <label key={s.kind} className="future-row">
          <input type="checkbox" checked={s.enabled} disabled={busy} onChange={(e) => toggle(s.kind, e.target.checked)} />
          <span><strong>{reminderLabel(s.kind)}</strong> — {s.description}</span>
        </label>
      ))}
      {data.notes.map((n) => <div className="field-hint" key={n}>{n}</div>)}
      {error && <div className="form-error" role="alert">{error}</div>}
    </div>
  )
}
