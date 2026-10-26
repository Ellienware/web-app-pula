package com.momocircle.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SANDBOX / MOCK gateway — makes NO external call and moves NO real money.
 * Used when no Paystack credentials are configured so the full order flow can
 * be demonstrated. A payment only becomes "paid" here when the buyer explicitly
 * completes it through the guarded sandbox endpoint; the confirm path is still
 * the same server-side verify used for real Paystack. State is in memory, so a
 * restart forgets pending mock checkouts (mock only).
 */
@Component
@ConditionalOnProperty(name = "app.payments.paystack.mode", havingValue = "mock", matchIfMissing = true)
public class MockPaystackGateway implements PaystackGateway {

    private record Init(long amountMinor, String currency) {}

    private final Map<String, Init> initialised = new ConcurrentHashMap<>();
    private final Set<String> paid = ConcurrentHashMap.newKeySet();
    private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong refundSeq = new java.util.concurrent.atomic.AtomicLong(1000);
    /** Mock only: when true a refund moves PROCESSING -> PROCESSED on its first status check (default), else it waits for {@link #simulateRefundProcessed}. */
    private volatile boolean autoProcessRefunds = true;

    @Override public String mode() { return "SANDBOX_MOCK"; }

    @Override
    public CheckoutSession initialize(InitRequest r) {
        initialised.put(r.reference(), new Init(PaystackGateway.toMinor(r.amount()), r.currency()));
        return new CheckoutSession(r.reference(), null, mode());
    }

    @Override
    public VerifyResult verify(String reference) {
        Init init = initialised.get(reference);
        if (init == null) return new VerifyResult(RemoteStatus.NOT_FOUND, 0, null);
        RemoteStatus st = paid.contains(reference) ? RemoteStatus.SUCCESS : RemoteStatus.PENDING;
        return new VerifyResult(st, init.amountMinor(), init.currency());
    }

    /** Sandbox-only: simulate the customer completing checkout. */
    public void simulateCustomerPaid(String reference) {
        if (initialised.containsKey(reference)) paid.add(reference);
    }

    /** Sandbox refund: no money moves. Accepted refunds are PROCESSING (asynchronous, like Paystack) until checked. */
    @Override
    public RefundResult createRefund(RefundRequest r) {
        Init init = initialised.get(r.transactionReference());
        if (init == null || !paid.contains(r.transactionReference())) {
            throw new RefundRejectedException("Transaction not found or not successful");
        }
        long already = refunds.values().stream()
                .filter(x -> r.transactionReference().equals(x.transactionReference()) && x.status() != RefundRemoteStatus.FAILED)
                .mapToLong(RefundResult::amountMinor).sum();
        if (r.amountMinor() <= 0 || already + r.amountMinor() > init.amountMinor()) {
            throw new RefundRejectedException("Refund amount exceeds the transaction amount");
        }
        String id = String.valueOf(refundSeq.incrementAndGet());
        RefundResult res = new RefundResult(RefundRemoteStatus.PROCESSING, id, r.transactionReference(),
                r.amountMinor(), init.currency(), "processing");
        refunds.put(id, res);
        return res;
    }

    @Override
    public RefundResult fetchRefund(String providerRefundId) {
        RefundResult cur = refunds.get(providerRefundId);
        if (cur == null) return new RefundResult(RefundRemoteStatus.NOT_FOUND, providerRefundId, null, 0, null, "not-found");
        if (autoProcessRefunds && cur.status() == RefundRemoteStatus.PROCESSING) {
            cur = new RefundResult(RefundRemoteStatus.PROCESSED, cur.providerRefundId(), cur.transactionReference(),
                    cur.amountMinor(), cur.currency(), "processed");
            refunds.put(providerRefundId, cur);
        }
        return cur;
    }

    @Override
    public java.util.List<RefundResult> listRefunds(String transactionReference) {
        return refunds.values().stream().filter(x -> transactionReference.equals(x.transactionReference())).toList();
    }

    public void setAutoProcessRefunds(boolean v) { this.autoProcessRefunds = v; }

    /** Sandbox-only: complete / fail a refund by hand. */
    public void simulateRefundProcessed(String id) { setRefundStatus(id, RefundRemoteStatus.PROCESSED, "processed"); }
    public void simulateRefundFailed(String id) { setRefundStatus(id, RefundRemoteStatus.FAILED, "failed"); }

    private void setRefundStatus(String id, RefundRemoteStatus st, String raw) {
        refunds.computeIfPresent(id, (k, c) -> new RefundResult(st, c.providerRefundId(), c.transactionReference(),
                c.amountMinor(), c.currency(), raw));
    }
}
