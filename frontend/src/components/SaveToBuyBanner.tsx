import { useState } from "react"
import { clearActiveSaveToBuy, getActiveSaveToBuy } from "../lib/saveToBuyContext"
import type { SaveToBuyVertical } from "../lib/saveToBuyTypes"
import { formatMoney } from "../lib/format"

/**
 * Shown inside the EXISTING cart/checkout screens when a Save-to-Buy request is active. It changes nothing about
 * pricing or payment: the total charged is whatever checkout computes. Clearing it just removes the reference.
 */
export function SaveToBuyBanner({ verticals }: { verticals: SaveToBuyVertical[] }) {
  const [active, setActive] = useState(() => getActiveSaveToBuy(...verticals))
  if (!active) return null
  return (
    <div className="state-panel" role="note">
      <strong>Purchase plan:</strong> {active.purpose} (planned {formatMoney(active.requestedAmount, active.currency)}).
      The order will be linked to this plan. You still pay the checkout total below; PULA does not hold your savings.
      <button className="btn btn-ghost" onClick={() => { clearActiveSaveToBuy(); setActive(null) }}>Don&apos;t link this order</button>
    </div>
  )
}
