import { useState } from "react"

export interface FoodLocation { lat: number; lng: number }

/**
 * Asks the browser for the customer's position ONLY when they press a button, keeps it in memory
 * for this page view, and never writes it to storage. It is sent as lat/lng query parameters so the
 * server can work out distances; the server does not store it or return it.
 */
export function useFoodLocation() {
  const [location, setLocation] = useState<FoodLocation | null>(null)
  const [locating, setLocating] = useState(false)
  const [locationError, setLocationError] = useState<string | null>(null)

  function requestLocation() {
    if (!navigator.geolocation) {
      setLocationError("Your browser can't share a location. Type an area such as Soweto instead.")
      return
    }
    setLocating(true)
    setLocationError(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude })
        setLocating(false)
      },
      () => {
        setLocationError("We couldn't get your location. You can type an area such as Soweto instead.")
        setLocating(false)
      },
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 },
    )
  }

  return { location, locating, locationError, requestLocation, clearLocation: () => setLocation(null) }
}

/** Query-string helper that drops empty values. */
export function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === "" || v === false) continue
    p.set(k, String(v))
  }
  const s = p.toString()
  return s ? `?${s}` : ""
}
