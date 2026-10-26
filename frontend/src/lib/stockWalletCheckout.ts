import { api } from "./api"
import type { StockPaymentSession } from "./types"

/** What the backend reports after a wholesale wallet payment. `paid` is true only when the backend verified the ledger debit. */
export interface StockWalletPaymentResult {
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
 * Pay a wholesale stock order from the buying BUSINESS's SIMULATED demo wallet. Step 1 (start) charges nothing and returns the
 * server-computed amount; step 2 (pay) debits only after that exact amount is confirmed. The UI never decides the outcome.
 */
export const stockWalletCheckoutApi = {
  start: (orderId: string, acknowledged = false) =>
    api.post<StockPaymentSession>(`/stock/orders/mine/${encodeURIComponent(orderId)}/pay/wallet/start?acknowledged=${acknowledged}`),
  pay: (orderId: string, expectedAmount: number) =>
    api.post<StockWalletPaymentResult>(`/stock/orders/mine/${encodeURIComponent(orderId)}/pay/wallet`, { confirm: true, expectedAmount }),
}
