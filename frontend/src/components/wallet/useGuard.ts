import { useRef, useState } from "react"
import { createGuard } from "../../lib/walletLogic"

/**
 * React wrapper around {@link createGuard}: ignores a second call while the first is still running, and exposes `loading`
 * for the UI. Combined with a server-side idempotency key, a double click can never post a second transfer.
 */
export function useGuard() {
  const guard = useRef(createGuard())
  const [loading, setLoading] = useState(false)
  async function run<T>(fn: () => Promise<T>): Promise<T | undefined> {
    return guard.current.run(async () => {
      setLoading(true)
      try {
        return await fn()
      } finally {
        setLoading(false)
      }
    })
  }
  return { loading, run }
}
