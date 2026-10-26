import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import type { DeliveryRequestInfo, Page } from "../../lib/types"
import { formatMoney, humanize } from "../../lib/format"
import { useToast } from "../../context/ToastContext"
import { EmptyState } from "../ui/StatePanel"

/**
 * Self-contained: manages its own data loading so it can be dropped into
 * DriverDashboard without threading extra props through. Mirrors that
 * component's own load()/action-handler/toast pattern rather than
 * introducing a different style for what's conceptually the same kind of
 * "list of jobs a driver acts on" UI as the rides section right below it.
 */
export function DriverDeliveries() {
  const { showToast } = useToast()
  const [openDeliveries, setOpenDeliveries] = useState<DeliveryRequestInfo[] | null>(null)
  const [myDeliveries, setMyDeliveries] = useState<DeliveryRequestInfo[] | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  function load() {
    api
      .get<Page<DeliveryRequestInfo>>("/deliveries/open")
      .then((p) => setOpenDeliveries(p.content))
      .catch(() => setOpenDeliveries([]))
    api
      .get<Page<DeliveryRequestInfo>>("/deliveries/mine")
      .then((p) => setMyDeliveries(p.content))
      .catch(() => setMyDeliveries([]))
  }

  useEffect(load, [])

  async function act(id: string, action: "accept" | "picked-up" | "delivered" | "cancel", successMessage: string) {
    setBusyId(id)
    try {
      await api.post(`/deliveries/${id}/${action}`, {})
      showToast(successMessage, "success")
      load()
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Could not update delivery", "error")
    } finally {
      setBusyId(null)
    }
  }

  const activeMine = (myDeliveries ?? []).filter((d) => d.status === "ACCEPTED" || d.status === "PICKED_UP")
  const pastMine = (myDeliveries ?? []).filter((d) => d.status === "DELIVERED" || d.status === "CANCELLED")

  return (
    <>
      <h2 className="driver-section-title">Open deliveries</h2>
      {openDeliveries === null ? null : openDeliveries.length === 0 ? (
        <EmptyState title="No open deliveries right now" description="New delivery jobs will show up here." />
      ) : (
        openDeliveries.map((d) => (
          <div className="request-row" key={d.id}>
            <div>
              <strong>{d.businessName}</strong>
              <div className="muted">
                {d.pickupLocation} → {d.dropoffLocation}
              </div>
              {d.estimatedFee !== null && (
                <div className="muted">Est. fee: {formatMoney(d.estimatedFee, d.currency)}</div>
              )}
            </div>
            <button
              className="btn btn-primary btn-sm"
              disabled={busyId === d.id}
              onClick={() => act(d.id, "accept", "Delivery accepted ✓")}
            >
              Accept
            </button>
          </div>
        ))
      )}

      <h2 className="driver-section-title">My deliveries</h2>
      {activeMine.length === 0 && pastMine.length === 0 ? (
        <EmptyState title="No deliveries yet" description="Deliveries you accept will show up here." />
      ) : (
        <>
          {activeMine.map((d) => (
            <div className="request-row" key={d.id}>
              <div>
                <strong>{d.businessName}</strong>
                <span className="badge" style={{ marginLeft: 8 }}>
                  {humanize(d.status)}
                </span>
                <div className="muted">
                  {d.pickupLocation} → {d.dropoffLocation}
                </div>
              </div>
              <div className="row">
                {d.status === "ACCEPTED" && (
                  <button
                    className="btn btn-ghost btn-sm"
                    disabled={busyId === d.id}
                    onClick={() => act(d.id, "picked-up", "Marked as picked up ✓")}
                  >
                    Mark picked up
                  </button>
                )}
                {d.status === "PICKED_UP" && (
                  <button
                    className="btn btn-ghost btn-sm"
                    disabled={busyId === d.id}
                    onClick={() => act(d.id, "delivered", "Marked as delivered ✓")}
                  >
                    Mark delivered
                  </button>
                )}
                <button
                  className="btn btn-ghost btn-sm"
                  disabled={busyId === d.id}
                  onClick={() => act(d.id, "cancel", "Delivery cancelled")}
                >
                  Cancel
                </button>
              </div>
            </div>
          ))}
          {pastMine.length > 0 && (
            <details style={{ marginTop: "var(--space-3)" }}>
              <summary className="muted">Past deliveries ({pastMine.length})</summary>
              {pastMine.map((d) => (
                <div className="request-row" key={d.id}>
                  <div>
                    <strong>{d.businessName}</strong>
                    <span className="badge" style={{ marginLeft: 8 }}>
                      {humanize(d.status)}
                    </span>
                  </div>
                </div>
              ))}
            </details>
          )}
        </>
      )}
    </>
  )
}
