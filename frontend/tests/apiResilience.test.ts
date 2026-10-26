import { test } from "node:test"
import assert from "node:assert/strict"

const store = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
}

type Mode = "network" | "hang" | "ok"
let mode: Mode = "ok"
;(globalThis as any).fetch = (_url: string, init: { signal?: AbortSignal }) => {
  if (mode === "network") return Promise.reject(new TypeError("Failed to fetch"))
  if (mode === "hang") {
    return new Promise((_res, rej) => {
      init.signal?.addEventListener("abort", () => {
        const e = new Error("aborted")
        e.name = "AbortError"
        rej(e)
      })
    })
  }
  return Promise.resolve({ status: 200, ok: true, statusText: "", text: async () => '{"ok":true}' })
}

const { api, ApiError, isNetworkError, setRequestTimeout } = await import("../src/lib/api.ts")

test("a failed connection becomes a friendly NETWORK error, not a raw TypeError", async () => {
  mode = "network"
  await assert.rejects(api.get("/x"), (e: any) => e instanceof ApiError && e.code === "NETWORK" && /couldn't reach PULA/i.test(e.message) && isNetworkError(e))
})

test("a hung request times out with a TIMEOUT error", async () => {
  mode = "hang"
  setRequestTimeout(30)
  await assert.rejects(api.get("/x"), (e: any) => e.code === "TIMEOUT" && isNetworkError(e))
})

test("a timed-out WRITE never tells the user to simply retry it (it may have succeeded)", async () => {
  mode = "hang"
  setRequestTimeout(30)
  await assert.rejects(api.post("/orders/checkout", { a: 1 }), (e: any) => /couldn't confirm/i.test(e.message) && /before trying again/i.test(e.message))
  mode = "network"
  await assert.rejects(api.post("/orders/checkout", { a: 1 }), (e: any) => /can't confirm this went through/i.test(e.message))
})

test("server errors are not mistaken for network errors", async () => {
  assert.equal(isNetworkError(new ApiError(500, "boom")), false)
  assert.equal(isNetworkError(new Error("x")), false)
})

test("normal responses still work after the timeout change", async () => {
  mode = "ok"
  setRequestTimeout(1000)
  assert.deepEqual(await api.get("/x"), { ok: true })
})
