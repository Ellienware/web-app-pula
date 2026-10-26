import { test, beforeEach } from "node:test"
import assert from "node:assert/strict"

const store = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
}
let next: { status: number; statusText?: string; text: string }
;(globalThis as any).fetch = async () => ({
  status: next.status,
  ok: next.status >= 200 && next.status < 300,
  statusText: next.statusText ?? "",
  text: async () => next.text,
})

const { api, setToken, getToken, onUnauthorized, ApiError } = await import("../src/lib/api.ts")

beforeEach(() => setToken("tok"))

test("non-JSON error body (HTML 502) yields ApiError with the real status, not a SyntaxError", async () => {
  next = { status: 502, statusText: "Bad Gateway", text: "<html>bad gateway</html>" }
  await assert.rejects(api.get("/x"), (e: any) => e instanceof ApiError && e.status === 502 && e.message === "Bad Gateway")
})

test("non-JSON body on a 200 is reported as an unexpected response", async () => {
  next = { status: 200, text: "<html>captive portal</html>" }
  await assert.rejects(api.get("/x"), (e: any) => e instanceof ApiError && /Unexpected response/.test(e.message))
})

test("valid JSON still parses", async () => {
  next = { status: 200, text: '{"a":1}' }
  assert.deepEqual(await api.get("/x"), { a: 1 })
})

test("401 on an authenticated call clears the token and notifies listeners", async () => {
  let fired = 0
  const off = onUnauthorized(() => fired++)
  next = { status: 401, text: '{"message":"Not authenticated"}' }
  await assert.rejects(api.get("/orders"), (e: any) => e.status === 401)
  assert.equal(getToken(), null)
  assert.equal(fired, 1)
  off()
})

test("401 from login (wrong password) is NOT treated as session expiry", async () => {
  let fired = 0
  const off = onUnauthorized(() => fired++)
  next = { status: 401, text: '{"message":"Invalid credentials"}' }
  await assert.rejects(api.post("/auth/login", { email: "a", password: "b" }), (e: any) => e.message === "Invalid credentials")
  assert.equal(getToken(), "tok")
  assert.equal(fired, 0)
  off()
})

test("403 does not log the user out", async () => {
  next = { status: 403, text: '{"message":"Forbidden"}' }
  await assert.rejects(api.get("/admin/x"), (e: any) => e.status === 403)
  assert.equal(getToken(), "tok")
})
