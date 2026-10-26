import type { StatusLabel } from "../../lib/statusLabels"

/**
 * Status with text AND a symbol, never colour alone. Money statuses that are only simulated use the "simulated" family
 * (dashed border) so they cannot be mistaken for real settlement.
 */
export function StatusPill({ status }: { status: StatusLabel }) {
  return (
    <span className={`status-pill status-${status.family}`} title={status.description}>
      <span className="status-pill-symbol" aria-hidden="true">
        {status.symbol}
      </span>
      {status.label}
    </span>
  )
}
