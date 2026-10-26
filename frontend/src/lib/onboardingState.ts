/** First-launch flag for the welcome screens. Storage is injected so it works on web (localStorage) and mobile (async storage). */

export const ONBOARDING_KEY = "pula.onboarding.v1"

export interface SyncStore { getItem(k: string): string | null; setItem(k: string, v: string): void }

export function hasSeenOnboarding(store: SyncStore | null | undefined): boolean {
  try {
    return store?.getItem(ONBOARDING_KEY) === "done"
  } catch {
    // Storage can be blocked (private mode). Treat as "not seen" so a first-time user still sees it, but never crash.
    return false
  }
}

export function markOnboardingSeen(store: SyncStore | null | undefined): void {
  try {
    store?.setItem(ONBOARDING_KEY, "done")
  } catch {
    /* non-fatal: the intro may show again next visit */
  }
}

/** Clamp a step index into the valid range. */
export function clampStep(index: number, total: number): number {
  if (!Number.isFinite(index)) return 0
  return Math.max(0, Math.min(total - 1, Math.trunc(index)))
}
