# PULA Food — restaurant settlement, refunds and merchant protection

## Money model
Customer pays R120 = R100 food + R20 delivery. Three separate things follow:
- **Customer refund** (PULA -> customer): `payment_refunds`, via the payment provider.
- **Restaurant settlement** (PULA -> restaurant): `restaurant_settlements`, via the payout provider. Based on the **food subtotal only**.
- **Driver earnings** (PULA -> driver): `driver_payouts`, unchanged. The delivery fee is never restaurant revenue.

## Why a cause is recorded
`orders.cancellation_cause` is set explicitly by the code path that ended the order; it is never inferred.
| Cause | Set by | Refund | Settlement |
|---|---|---|---|
| RESTAURANT_REJECTED | restaurant declines | full | cancelled, no compensation |
| RESTAURANT_EXPIRED | accept deadline passed | full | cancelled, no compensation |
| CUSTOMER_CANCELLED | customer cancels before the restaurant decides | full | cancelled |
| PULA_DELIVERY_FAILURE | customer cancels READY order after the no-driver timeout (the system verifies the timeout first) | full | **merchant-protection compensation** (policy-gated) |
| PAYMENT_FAILURE | payment never succeeded | none (nothing was paid) | none |
| ADMIN_CANCELLED | admin refund | full or partial | cancelled unless policy says compensate |

Compensation needs: a cause that may owe it, food actually prepared (`orders.ready_at` set), and the policy flag on. Restaurant-fault causes never earn it.

## Settlement lifecycle
`NOT_ELIGIBLE` (created when payment succeeds) -> `ELIGIBLE` (order COMPLETED; after `delay-minutes`) -> `PROCESSING` (atomic claim) -> `PAID` (**only** when the payout provider confirms success). Also `FAILED` (retryable under an attempt ceiling), `ON_HOLD` (admin dispute/fraud hold), `CANCELLED`. One row per order (unique), deterministic keys (`settlement:{orderId}`, provider key `payout:settlement:{id}`), so an order cannot be settled twice. A payout the provider has not confirmed is never PAID; a provider timeout leaves it PROCESSING and the stuck pass repeats the same idempotent call.

## Refund lifecycle
Each refund operation is a `payment_refunds` row with a unique idempotency key (`refund:{orderId}` for the full refund). States: `PENDING` -> `PROCESSING` (Paystack accepted: pending/processing) -> `REFUNDED` (Paystack **processed**); `FAILED` (Paystack failed), `NEEDS_ATTENTION` (Paystack needs-attention, amount/currency mismatch, or retries exhausted).
- The **order** stays `REFUND_PENDING` until a refund is `REFUNDED` and the cumulative refunded amount covers the payment; only then does the order become `REFUNDED` and the payment `REFUNDED`. A partial refund does not refund the order.
- `0 < amount <= payment` and cumulative non-failed refunds `<= payment`, enforced in `RefundMath`.
- A network timeout after Paystack accepted the request is recovered by listing the transaction's refunds and adopting the matching one; a second create call is never made blind.
- Webhooks (`refund.*`) are signature-verified and de-duplicated, but the payload is only a hint: state comes from a fresh fetch from Paystack with amount and currency re-checked.
- Legacy-provider (non-Paystack) payments keep their synchronous refund and original key `refund_{orderId}`; partial refunds are refused for them.

## Configuration (`app.food.settlement.*`, all placeholders for PULA to confirm)
`commission-rate` (0.00), `fixed-fee` (0.00), `delay-minutes` (0), `compensation-commission-rate` (0.00), `compensate-pula-delivery-failure` (true), `compensate-customer-cancelled-after-prep` (false), `compensate-admin-cancelled-after-prep` (false), `stuck-processing-minutes` (10), `max-payout-attempts` (5); and `app.food.refund-max-attempts` (10). **No commission percentage is hardcoded**; the default 0 means PULA takes nothing until it decides.

## Admin API (`/api/admin/food-finance`, admin only, read-only plus safe actions)
`GET /summary`, `GET /settlements?status=`, `GET /merchant-protection`, `GET /refunds?status=`, `GET /orders/{id}/refunds`, `POST /settlements/{id}/hold|release`, `POST /refunds/{id}/retry` (FAILED only; new operation, history kept), `POST /orders/{id}/refunds` (admin partial refund with an operation key). **There is no endpoint that sets PAID or REFUNDED**; those come only from the providers. There is no admin web/mobile UI yet, only the API.

## Cash flow (restaurant view)
Commission is an expense; merchant-protection compensation is income (those orders never completed, so they are not already in sales); refunds, payouts paid and amounts owed are informational and never added to totals, so nothing is double-counted. PULA-side totals (commission earned, fulfilment loss, pending/paid payouts) are in `/summary`.

## Known limitations
- A refund after the restaurant has been PAID is refused (409): a clawback/dispute needs a human; no automatic clawback exists.
- The no-driver cancellation is still customer-triggered after the timeout (existing behaviour); there is no automatic cancel.
- A partial refund reduces the restaurant's eligible revenue first (conservative); a delivery-fee-only refund therefore also reduces the restaurant's settlement unless PULA decides otherwise.
- The sandbox payout provider records payouts but moves no real money; no real payout rail is integrated.
- Paystack webhook field names for `refund.*` events are parsed defensively; they have not been checked against a live payload.
