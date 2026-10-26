import { api } from "./api"

/** What the backend reports after a wallet payment attempt. `paid` is true only when the backend verified the ledger debit. */
export interface WalletPaymentResult {
  orderId: string
  paymentStatus: string
  orderStatus: string
  paid: boolean
  replayed: boolean
  ledgerReference: string | null
  amount: number
  currency: string
  simulated: true
  notice: string
}

/**
 * Pay a PULA Market / PULA Food order from the SIMULATED demo wallet. Two explicit steps: create the order (the server
 * computes the total and charges nothing), then confirm the amount shown. Nothing is paid until the backend says so.
 */
export const walletCheckoutApi = {
  /** Whether the server offers wallet checkout. Failure means "not offered"; the original checkout is unaffected. */
  enabled: (): Promise<boolean> =>
    api.get<{ walletCheckout?: boolean }>("/config/market-payments").then((c) => c.walletCheckout === true).catch(() => false),
  create: (body: object, idempotencyKey: string) =>
    api.post<{ orderId: string }>("/orders/checkout/wallet", body, { "Idempotency-Key": idempotencyKey }),
  pay: (orderId: string, expectedAmount: number) =>
    api.post<WalletPaymentResult>(`/orders/${encodeURIComponent(orderId)}/pay/wallet`, { confirm: true, expectedAmount }),
}
