const BASE = "/api"

let token: string | null = localStorage.getItem("momo_token")

export function setToken(t: string | null) {
  token = t
  if (t) localStorage.setItem("momo_token", t)
  else localStorage.removeItem("momo_token")
}

export function getToken() {
  return token
}

type UnauthorizedListener = () => void
const unauthorizedListeners = new Set<UnauthorizedListener>()

/** Subscribe to "session expired" events (a 401 on an authenticated call). Returns an unsubscribe function. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

// Wrong-credentials 401s from these endpoints are not "session expired".
const AUTH_ENTRY_PATHS = ["/auth/login", "/auth/register"]

export class ApiError extends Error {
  status: number
  code?: string
  /** Present on unexpected 500s — reference this when reporting a bug (see GlobalExceptionHandler). */
  requestId?: string
  constructor(status: number, message: string, code?: string, requestId?: string) {
    super(requestId ? `${message} (ref: ${requestId})` : message)
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

let requestTimeoutMs = 30_000

/** Test hook: lets the suite use a short timeout. Not used by the app. */
export function setRequestTimeout(ms: number) {
  requestTimeoutMs = ms
}

/** True when the request never got an answer (offline, DNS, proxy down) or timed out - as opposed to the server refusing it. */
export function isNetworkError(e: unknown): boolean {
  return e instanceof ApiError && (e.code === "NETWORK" || e.code === "TIMEOUT")
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  }
  if (token) headers["Authorization"] = `Bearer ${token}`

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), requestTimeoutMs)
  const isWrite = (options.method ?? "GET").toUpperCase() !== "GET"
  let res: Response
  let text: string
  try {
    res = await fetch(`${BASE}${path}`, { ...options, headers, signal: controller.signal })
    if (res.status === 204) return undefined as T
    text = await res.text()
  } catch (e) {
    // A write that timed out may still have succeeded on the server, so never tell the user to simply retry it.
    if (e instanceof Error && e.name === "AbortError") {
      throw new ApiError(
        0,
        isWrite
          ? "This is taking too long and we couldn't confirm it went through. Check your orders or activity before trying again."
          : "This is taking too long. Check your connection and try again.",
        "TIMEOUT",
      )
    }
    throw new ApiError(
      0,
      isWrite
        ? "We couldn't reach PULA, so we can't confirm this went through. Check your connection, then check your activity before trying again."
        : "We couldn't reach PULA. Check your connection and try again.",
      "NETWORK",
    )
  } finally {
    clearTimeout(timer)
  }
  // Error bodies are not always JSON (e.g. an HTML 502 from a proxy); never let a parse error mask the real failure.
  let body: any = null
  let parsed = true
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      parsed = false
    }
  }

  if (!res.ok) {
    if (res.status === 401 && token && !AUTH_ENTRY_PATHS.some((p) => path.startsWith(p))) {
      setToken(null)
      unauthorizedListeners.forEach((l) => l())
    }
    const message = body?.message || res.statusText || "Request failed"
    throw new ApiError(res.status, message, body?.code, body?.requestId)
  }
  if (!parsed) throw new ApiError(res.status, "Unexpected response from server")
  return body as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown, headers?: Record<string, string>) =>
    request<T>(path, { method: "POST", body: data ? JSON.stringify(data) : undefined, headers }),
  put: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: "PUT", body: data ? JSON.stringify(data) : undefined }),
  patch: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: "PATCH", body: data ? JSON.stringify(data) : undefined }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
}
