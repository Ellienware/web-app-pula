import { Car, CreditCard } from "lucide-react"
import type { DriverResponse } from "../../lib/types"
import { PageHeader } from "../ui/PageHeader"

type View = "home" | "pay" | "driver"

export function RideHub({ driver, onNavigate }: { driver: DriverResponse | null; onNavigate: (view: View) => void }) {
  return (
    <div className="page">
      <PageHeader title="PULA Ride" subtitle="Pay minibus taxi fares and manage your taxi services." />

      <div className="ride-hub-tiles">
        <button className="ride-hub-tile ride-hub-tile-pay" onClick={() => onNavigate("pay")}>
          <span className="ride-hub-tile-icon">
            <CreditCard size={22} aria-hidden="true" />
          </span>
          <div>
            <h3>Pay Taxi</h3>
            <p>Enter a payment code or scan a QR to pay your fare.</p>
          </div>
        </button>
        <button
          className={`ride-hub-tile ride-hub-tile-driver ${driver ? "is-active" : ""}`}
          onClick={() => onNavigate("driver")}
        >
          <span className="ride-hub-tile-icon">
            <Car size={22} aria-hidden="true" />
          </span>
          <div>
            <h3>Driver Mode</h3>
            <p>{driver ? "Manage your taxi and payment requests." : "Register your taxi to start receiving taxi payments."}</p>
          </div>
        </button>
      </div>

      <div className="section-head">
        <h2>Payment history</h2>
        <a href="/momoride/history" className="btn btn-ghost btn-sm">History and receipts</a>
      </div>
    </div>
  )
}
