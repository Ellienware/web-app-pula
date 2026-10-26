import { ShieldAlert } from "lucide-react"

/** Shown on every wallet screen: demo balances must never be mistaken for cash or real settlement. */
export function SimulatedBanner({ compact }: { compact?: boolean }) {
  return (
    <div className="wallet-banner" role="note" aria-label="Simulated funds notice">
      <ShieldAlert size={18} aria-hidden="true" />
      <div>
        <strong>Demo funds only.</strong>{" "}
        {compact
          ? "Simulated PULA Money. Not real money."
          : "This is a simulated PULA Money balance. It is not cash, it is not held in a bank, and it cannot pay real orders, payouts or taxi fares."}
      </div>
    </div>
  )
}
