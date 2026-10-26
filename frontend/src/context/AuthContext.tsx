import { createContext, useContext, useEffect, useState, type ReactNode } from "react"
import { api, setToken, getToken, onUnauthorized } from "../lib/api"
import type { AuthResponse, User } from "../lib/types"

interface AuthState {
  user: User | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  register: (fullName: string, email: string, phoneNumber: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!getToken()) {
      setLoading(false)
      return
    }
    api
      .get<User>("/auth/me")
      .then(setUser)
      // A 401 already clears the token in api.ts; keep it on transient failures (offline, 5xx) so a refresh can recover.
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  // Session expired mid-use: drop the stale user so routes redirect to login instead of failing on every call.
  useEffect(() => onUnauthorized(() => setUser(null)), [])

  async function login(email: string, password: string) {
    const res = await api.post<AuthResponse>("/auth/login", { email, password })
    setToken(res.token)
    setUser(res.user)
  }

  async function register(fullName: string, email: string, phoneNumber: string, password: string) {
    const res = await api.post<AuthResponse>("/auth/register", { fullName, email, phoneNumber, password })
    setToken(res.token)
    setUser(res.user)
  }

  function logout() {
    setToken(null)
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout }}>{children}</AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error("useAuth must be used within AuthProvider")
  return ctx
}
