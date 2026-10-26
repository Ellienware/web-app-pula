import { useEffect, useState } from "react"
import { api } from "../../lib/api"
import { useCurrencyConfig } from "../../lib/currencyConfig"
import type { DriverResponse } from "../../lib/types"
import { RideHub } from "../../components/momoride/RideHub"
import { PayTaxi } from "../../components/momoride/PayTaxi"
import { DriverMode } from "../../components/momoride/DriverMode"

type View = "home" | "pay" | "driver"

export function MoMoRidePage() {
  const { defaultCurrency } = useCurrencyConfig()
  const [view, setView] = useState<View>("home")
  const [driver, setDriver] = useState<DriverResponse | null>(null)

  useEffect(() => {
    api
      .get<DriverResponse>("/momoride/drivers/me")
      .then(setDriver)
      .catch(() => setDriver(null))
  }, [])

  if (view === "pay") {
    return <PayTaxi defaultCurrency={defaultCurrency} onBack={() => setView("home")} />
  }
  if (view === "driver") {
    return (
      <DriverMode
        driver={driver}
        onDriverChange={setDriver}
        defaultCurrency={defaultCurrency}
        onBack={() => setView("home")}
      />
    )
  }
  return <RideHub driver={driver} onNavigate={setView} />
}
