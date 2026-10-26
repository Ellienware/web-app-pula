import type { ReactNode } from "react"
import { Info } from "lucide-react"

/** Persistent honesty banner: planning and simulation are never real money movement. */
export function FutureNotice({ children }: { children?: ReactNode }) {
  return (
    <div className="card future-notice" role="note">
      <Info size={16} aria-hidden="true" />
      <div>
        <strong>Simulated balance — not real money.</strong> Planning estimate — no money has been transferred.
        {children}
      </div>
    </div>
  )
}

export function Bar({ percent }: { percent: number }) {
  return (
    <div className="progress" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
      <span style={{ width: `${percent}%` }} />
    </div>
  )
}
