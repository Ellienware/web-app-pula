import { ShieldAlert } from "lucide-react"

export type DemoNoticeKind = "checkout" | "taxi" | "payout" | "refund"

// Plain, specific wording per context. Each line says what is NOT happening, so a
// "paid" or "completed" status can never be read as real settlement.
const COPY: Record<DemoNoticeKind, string> = {
  checkout: "No real money moves. Orders here are simulated; no shop, restaurant or supplier is actually paid.",
  taxi: "This is a simulated fare. Nothing is paid to the driver or the taxi association.",
  payout: "These payouts are simulated records. Nothing has been paid into a real bank or mobile-money account.",
  refund: "Refunds here are simulated. Nothing is sent back to a real account.",
}

/** Shown wherever money appears to be paid, refunded or paid out outside the wallet screens. */
export function DemoPaymentNotice({ kind }: { kind: DemoNoticeKind }) {
  return (
    <div className="demo-notice" role="note" aria-label="Demo payment notice">
      <ShieldAlert size={18} aria-hidden="true" />
      <div>
        <strong>Demo payments only.</strong> {COPY[kind]}
      </div>
    </div>
  )
}
