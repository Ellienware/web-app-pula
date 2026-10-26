import type { SaveToBuyVertical } from "./saveToBuyTypes"

/**
 * The Save-to-Buy request the user chose to shop for. It only travels with the existing checkout request as
 * `saveToBuyRequestId`; the server validates it and the order merely references it. Session-scoped on purpose.
 */
const KEY = "pula.saveToBuy.active"
export interface ActiveSaveToBuy { id: string; vertical: SaveToBuyVertical; purpose: string; requestedAmount: number; currency: string }

export function setActiveSaveToBuy(a: ActiveSaveToBuy): void {
  try { sessionStorage.setItem(KEY, JSON.stringify(a)) } catch { /* storage unavailable: checkout simply proceeds without it */ }
}
export function getActiveSaveToBuy(...verticals: SaveToBuyVertical[]): ActiveSaveToBuy | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const a = JSON.parse(raw) as ActiveSaveToBuy
    return verticals.length === 0 || verticals.includes(a.vertical) ? a : null
  } catch { return null }
}
export function clearActiveSaveToBuy(): void {
  try { sessionStorage.removeItem(KEY) } catch { /* ignore */ }
}
