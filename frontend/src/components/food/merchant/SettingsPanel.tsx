import { useEffect, useState } from "react"
import { api } from "../../../lib/api"
import type { FoodSettings } from "../../../lib/foodTypes"
import { useToast } from "../../../context/ToastContext"
import { Button } from "../../ui/Button"
import { ErrorState } from "../../ui/StatePanel"

export function SettingsPanel() {
  const { showToast } = useToast()
  const [s, setS] = useState<FoodSettings | null>(null)
  const [minutes, setMinutes] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get<FoodSettings>("/market/food/settings").then((v) => { setS(v); setMinutes(v.acceptTimeoutMinutes ? String(v.acceptTimeoutMinutes) : "") })
      .catch((e) => setError(e instanceof Error ? e.message : "Could not load settings"))
  }, [])

  async function save(body: { temporarilyClosed?: boolean; acceptTimeoutMinutes?: number }) {
    setBusy(true)
    try {
      setS(await api.patch<FoodSettings>("/market/food/settings", body))
      showToast("Saved", "success")
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not save", "error")
    } finally {
      setBusy(false)
    }
  }

  if (error) return <ErrorState title="Couldn't load settings" description={error} />
  if (!s) return null
  return (
    <div className="stack">
      <div className="card stack">
        <h2>Taking orders</h2>
        <p className="muted">
          {s.temporarilyClosed
            ? "You're paused. Customers can see your menu but can't order."
            : "You're open. Pause when you're busy or out of stock."}
        </p>
        <div><Button variant={s.temporarilyClosed ? "primary" : "secondary"} loading={busy}
          onClick={() => save({ temporarilyClosed: !s.temporarilyClosed })}>
          {s.temporarilyClosed ? "Start taking orders" : "Pause orders"}
        </Button></div>
      </div>
      <div className="card stack">
        <h2>How long you have to answer</h2>
        <p className="muted">
          After a customer pays you must accept or reject within this time, or the order is cancelled and refunded.
          Currently {s.effectiveAcceptTimeoutMinutes} minutes (up to {s.maxAcceptTimeoutMinutes}).
        </p>
        <div className="row wrap">
          <input type="number" min={1} max={s.maxAcceptTimeoutMinutes} value={minutes} className="market-qty-input"
            onChange={(e) => setMinutes(e.target.value)} aria-label="Minutes to answer an order" />
          <Button loading={busy} disabled={!minutes} onClick={() => save({ acceptTimeoutMinutes: Number(minutes) })}>Save</Button>
        </div>
      </div>
    </div>
  )
}
