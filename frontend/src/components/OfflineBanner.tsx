import { useEffect, useState } from "react"
import { WifiOff } from "lucide-react"

/** Appears when the browser reports no connection. Money actions will fail with a clear message while offline. */
export function OfflineBanner() {
  const [online, setOnline] = useState(() => (typeof navigator === "undefined" ? true : navigator.onLine))
  useEffect(() => {
    const up = () => setOnline(true)
    const down = () => setOnline(false)
    window.addEventListener("online", up)
    window.addEventListener("offline", down)
    return () => {
      window.removeEventListener("online", up)
      window.removeEventListener("offline", down)
    }
  }, [])
  if (online) return null
  return (
    <div className="offline-banner" role="status" aria-live="polite">
      <WifiOff size={16} aria-hidden="true" />
      <span>You're offline. You can read what's already loaded, but payments and changes won't go through until you're back online.</span>
    </div>
  )
}
