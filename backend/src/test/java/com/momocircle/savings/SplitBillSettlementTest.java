package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.domain.ExpenseShare;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.dto.ExpenseDtos.*;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.exception.ApiException;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.TxStatus;
import com.momocircle.service.ExpenseSettlementService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.service.wallet.WalletIdentityService;
import com.momocircle.service.wallet.WalletService;
import com.momocircle.wallet.WalletException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 9C settlement logic (consent, claim, idempotency, refund, recovery) over the real ExpenseService and
 * ExpenseSettlementService. The Phase 9B WalletService is replaced by a small SIMULATED transfer service that behaves like the
 * ledger where it matters here: balances in minor units, no overdraft (INSUFFICIENT_FUNDS) and idempotency per (initiator, key).
 * The real ledger's own locking and reconciliation are covered by the Phase 9A LedgerEngineTest; JPQL and the Flyway migration
 * need the database run (NOT RUN in the authoring environment).
 */
class SplitBillSettlementTest {

    private SavingsFixture f;
    private User owner, member, other, outsider;
    private String circleId;
    private ExpenseSettlementService settlement;
    private WalletService wallet;
    private LedgerOperationsService ledgerOps;

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, WalletService.Posted> idempotency = new HashMap<>();
    private final Map<String, LedgerTransactionEntity> txByRef = new HashMap<>();
    private final AtomicInteger postings = new AtomicInteger();

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); member = f.user("Member"); other = f.user("Other"); outsider = f.user("Outsider");
        circleId = f.newCircle(owner, "Flatmates", CircleType.EXPENSE_POOL, null).id();
        f.join(circleId, member, CircleRole.MEMBER);
        f.join(circleId, other, CircleRole.MEMBER);

        wallet = mock(WalletService.class);
        WalletIdentityService identity = mock(WalletIdentityService.class);
        ledgerOps = mock(LedgerOperationsService.class);
        when(identity.publicIdFor(anyString())).thenAnswer(i -> "PW-" + i.getArgument(0));
        when(wallet.transfer(any(User.class), anyString(), any(BigDecimal.class), anyString(), anyString())).thenAnswer(i -> transfer(
                i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(4)));
        when(f.ledgerTxRepo.findByProviderReference(anyString())).thenAnswer(i -> Optional.ofNullable(txByRef.get(i.<String>getArgument(0))));
        when(f.ledgerTxRepo.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            List<LedgerTransactionEntity> found = new ArrayList<>();

            for (String id : ids) {
                txByRef.values().stream()
                        .filter(tx -> id.equals(tx.getId()))
                        .findFirst()
                        .ifPresent(found::add);
            }

            return found;
        });
        settlement = new ExpenseSettlementService(f.expenseService, f.shareRepo, wallet, identity, f.ledgerTxRepo, ledgerOps, 60);
    }

    /** The simulated transfer: atomic, no overdraft, idempotent per (initiator, key). */
    private synchronized WalletService.Posted transfer(User from, String toPublicId, BigDecimal amount, String key) {
        String ik = from.getId() + "|" + key;
        WalletService.Posted prior = idempotency.get(ik);
        if (prior != null) return new WalletService.Posted(prior.view(), true);
        long minor = amount.movePointRight(2).longValueExact();
        long have = balances.getOrDefault(from.getId(), 0L);
        if (have < minor) throw new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS, "Insufficient funds");
        String toUser = toPublicId.substring(3);
        balances.put(from.getId(), have - minor);
        balances.merge(toUser, minor, Long::sum);
        String ref = "SIM-" + postings.incrementAndGet();
        LedgerTransactionEntity tx = mock(LedgerTransactionEntity.class);
        when(tx.getId()).thenReturn("tx-" + ref);
        when(tx.getProviderReference()).thenReturn(ref);
        when(tx.getStatus()).thenReturn(TxStatus.POSTED);
        txByRef.put(ref, tx);
        WalletService.Posted p = new WalletService.Posted(new TransactionView(ref, "INTERNAL_TRANSFER", "POSTED", "OUT",
                amount.toPlainString(), "ZAR", "x", null, null, Instant.now(), true, "simulated"), false);
        idempotency.put(ik, p);
        return p;
    }

    private void fund(User u, long minor) { balances.merge(u.getId(), minor, Long::sum); }
    private long bal(User u) { return balances.getOrDefault(u.getId(), 0L); }
    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    private ExpenseResponse bill() {
        return f.expenseService.create(circleId, owner, new CreateExpenseRequest("Groceries", d("60.00"), SplitType.EQUAL, null,
                List.of(new ShareInput(owner.getId(), null), new ShareInput(member.getId(), null), new ShareInput(other.getId(), null))));
    }

    private ExpenseShareResponse shareOf(ExpenseResponse e, User u) {
        return e.shares().stream().filter(s -> s.debtorId().equals(u.getId())).findFirst().orElseThrow();
    }

    private ConfirmRequest confirm(String amount) { return new ConfirmRequest(true, d(amount), "ZAR", null); }

    // ------------------------------------------------------------ explicit confirmation

    @Test
    void nothingIsDebitedWithoutExplicitConfirmationOrWithAWrongAmount() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        assertEquals(400, status(() -> settlement.pay(member, s, new ConfirmRequest(false, d("20.00"), "ZAR", null))));
        assertEquals(400, status(() -> settlement.pay(member, s, new ConfirmRequest(null, d("20.00"), "ZAR", null))));
        assertEquals(409, status(() -> settlement.pay(member, s, confirm("19.99"))), "the amount must match what was reviewed");
        assertEquals(10_000, bal(member));
        assertEquals(0, postings.get());
        assertEquals(ShareStatus.PENDING, f.share(s).getStatus());
        verify(wallet, times(0)).transfer(any(User.class), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void onlyTheParticipantCanPayTheirOwnShare() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(owner, 10_000); fund(other, 10_000); fund(member, 10_000);
        assertEquals(403, status(() -> settlement.pay(other, s, confirm("20.00"))), "another participant cannot pay it");
        assertEquals(403, status(() -> settlement.pay(owner, s, confirm("20.00"))), "nor can the person who is owed");
        assertEquals(404, status(() -> settlement.pay(outsider, s, confirm("20.00"))));
        assertEquals(0, postings.get());
        assertEquals(400, status(() -> settlement.pay(owner, shareOf(e, owner).id(), confirm("20.00"))), "the payer has nothing to pay");
    }

    // ------------------------------------------------------------ settlement through the ledger

    @Test
    void payingMovesDemoFundsAndNamesTheLedgerTransactionOnTheShare() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        SettlementResponse r = settlement.pay(member, s, confirm("20.00"));
        assertFalse(r.replayed());
        assertTrue(r.simulated());
        assertEquals(8_000, bal(member));
        assertEquals(2_000, bal(owner));
        ExpenseShare stored = f.share(s);
        assertEquals(ShareStatus.PAID, stored.getStatus());
        assertEquals(SettlementMethod.LEDGER, stored.getSettlementMethod());
        assertNotNull(stored.getLedgerTransactionId(), "the share names the ledger transaction that settled it");
        assertTrue(stored.isSettled());
        assertEquals(d("20.00"), r.expense().transferredAmount());
        assertEquals(d("60.00"), r.expense().recordedAmount(), "the recorded cost and the funds moved are different things");
        assertEquals("SIM-1", shareOf(r.expense(), member).ledgerReference());
        assertTrue(f.events.stream().anyMatch(ev -> "SHARE_PAID_LEDGER".equals(ev.getEventType()) && "SIM-1".equals(ev.getLedgerReference())));
    }

    @Test
    void retryingAPaymentReplaysTheSameLedgerTransaction() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        settlement.pay(member, s, confirm("20.00"));
        SettlementResponse again = settlement.pay(member, s, confirm("20.00"));
        assertTrue(again.replayed());
        assertEquals(8_000, bal(member), "debited once");
        assertEquals(1, postings.get());
        assertEquals(1, f.events.stream().filter(ev -> "SHARE_PAID_LEDGER".equals(ev.getEventType())).count(), "history written once");
    }

    @Test
    void simultaneousAttemptsToPayTheSameShareSettleItExactlyOnce() throws Exception {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SettlementResponse>> out = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<SettlementResponse> c = () -> { go.await(); return settlement.pay(member, s, confirm("20.00")); };
            out.add(pool.submit(c));
        }
        go.countDown();
        for (Future<SettlementResponse> fu : out) assertNotNull(fu.get());
        pool.shutdown();
        assertEquals(1, postings.get(), "exactly one ledger posting");
        assertEquals(8_000, bal(member));
        assertEquals(ShareStatus.PAID, f.share(s).getStatus());
    }

    @Test
    void insufficientFundsLeavesTheShareUnpaidWithNoCreditOrOverdraft() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 500);
        LedgerException ex = assertThrows(LedgerException.class, () -> settlement.pay(member, s, confirm("20.00")));
        assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, ex.code());
        assertEquals(500, bal(member), "nothing debited, no negative balance");
        assertEquals(0, bal(owner));
        assertEquals(ShareStatus.ACCEPTED, f.share(s).getStatus(), "claim released; still owed");
        assertNull(f.share(s).getLedgerTransactionId());
        assertTrue(f.events.stream().anyMatch(ev -> "SHARE_PAYMENT_REFUSED".equals(ev.getEventType())));
        fund(member, 10_000); // adds demo funds through the wallet's own demo-funding flow, then pays
        assertEquals(ShareStatus.PAID, settlement.pay(member, s, confirm("20.00")).share().status());
    }

    @Test
    void aDisputedOrDeclinedShareCannotBePaidUntilAcceptedAgain() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        f.expenseService.dispute(s, member, "wrong amount");
        assertEquals(409, status(() -> settlement.pay(member, s, confirm("20.00"))));
        f.expenseService.accept(s, member, null);
        assertEquals(ShareStatus.PAID, settlement.pay(member, s, confirm("20.00")).share().status());
    }

    @Test
    void aCurrencyTheDemoWalletDoesNotSupportCannotBeSettledHere() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        doThrow(WalletException.badRequest("CURRENCY_MISMATCH", "Only ZAR demo funds are supported")).when(wallet).currency(anyString());
        assertEquals(409, status(() -> settlement.pay(member, s, confirm("20.00"))));
        assertEquals(ShareStatus.PENDING, f.share(s).getStatus());
        assertEquals(10_000, bal(member));
    }

    // ------------------------------------------------------------ refunds and reversals

    @Test
    void aValidCorrectionIsAnExplicitCompensatingTransferByThePayer() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        settlement.pay(member, s, confirm("20.00"));
        assertEquals(403, status(() -> settlement.refund(member, s, confirm("20.00"))), "the debtor cannot refund themselves");
        assertEquals(400, status(() -> settlement.refund(owner, s, new ConfirmRequest(false, d("20.00"), "ZAR", null))));
        SettlementResponse r = settlement.refund(owner, s, confirm("20.00"));
        assertEquals(10_000, bal(member));
        assertEquals(0, bal(owner));
        assertEquals(ShareStatus.REFUNDED, f.share(s).getStatus());
        assertNotNull(f.share(s).getRefundTransactionId());
        assertEquals(d("0.00"), r.expense().transferredAmount());
        assertEquals(d("20.00"), r.expense().refundedAmount());
        assertTrue(settlement.refund(owner, s, confirm("20.00")).replayed(), "a repeated refund does not post again");
        assertEquals(2, postings.get());
        // the debtor can be asked again and pays again with a fresh ledger key (a new settle round)
        f.expenseService.reopen(s, owner, "Please pay the corrected amount");
        assertEquals(ShareStatus.PAID, settlement.pay(member, s, confirm("20.00")).share().status());
        assertEquals(3, postings.get());
    }

    @Test
    void fundsAlreadySpentAreNeverRecreatedByARefund() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        settlement.pay(member, s, confirm("20.00"));
        balances.put(owner.getId(), 0L); // the payer spent what they received
        LedgerException ex = assertThrows(LedgerException.class, () -> settlement.refund(owner, s, confirm("20.00")));
        assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, ex.code());
        assertEquals(ShareStatus.PAID, f.share(s).getStatus(), "still settled; nothing was invented");
        assertEquals(8_000, bal(member));
    }

    @Test
    void anExpensePaidThroughTheWalletCannotBeCancelledUntilEachPaymentIsRefunded() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        settlement.pay(member, s, confirm("20.00"));
        assertEquals(409, status(() -> f.expenseService.cancel(circleId, e.id(), owner, "mistake")));
        assertEquals(409, status(() -> f.expenseService.update(circleId, e.id(), owner, new UpdateExpenseRequest(e.revision(), "Groceries", null,
                d("90.00"), null, SplitType.EQUAL, null, null, null,
                List.of(new ShareInput(owner.getId(), null), new ShareInput(member.getId(), null), new ShareInput(other.getId(), null)), null))),
                "an edit may not rewrite a settled amount");
        settlement.refund(owner, s, confirm("20.00"));
        assertEquals("CANCELLED", f.expenseService.cancel(circleId, e.id(), owner, "mistake").settlementState());
        assertEquals(10_000, bal(member), "the money went back before the cancellation");
    }

    @Test
    void anOperatorCanReverseASettlementThroughTheLedger() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        fund(member, 10_000);
        settlement.pay(member, s, confirm("20.00"));
        User ops = f.user("Ops");
        String txId = f.share(s).getLedgerTransactionId();
        LedgerTransactionEntity reversal = mock(LedgerTransactionEntity.class);
        when(reversal.getId()).thenReturn("tx-rev");
        when(reversal.getProviderReference()).thenReturn("SIM-REV");
        when(f.ledgerTxRepo.findByInitiatorAndKey(ops.getId(), "reversal:" + txId)).thenReturn(Optional.of(reversal));
        assertEquals(400, status(() -> settlement.operatorReverse(ops, s, " ")));
        settlement.operatorReverse(ops, s, "Duplicate payment");
        assertEquals(ShareStatus.REVERSED, f.share(s).getStatus());
        assertFalse(f.share(s).isSettled());
        verify(ledgerOps).reverse(any(), anyString(), anyString());
        assertEquals(409, status(() -> settlement.operatorReverse(ops, s, "again")), "only a settled share can be reversed");
    }

    // ------------------------------------------------------------ recovery

    @Test
    void aClaimLeftBehindAfterTheLedgerCommittedIsFinishedBySweep() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        ExpenseShare share = f.share(s);
        share.setStatus(ShareStatus.PROCESSING);
        share.setClaimUserId(member.getId());
        LedgerTransactionEntity posted = mock(LedgerTransactionEntity.class);
        when(posted.getId()).thenReturn("tx-found");
        when(posted.getStatus()).thenReturn(TxStatus.POSTED);
        when(f.ledgerTxRepo.findByInitiatorAndKey(member.getId(), "xfer:expshare:" + s + ":r0")).thenReturn(Optional.of(posted));
        when(f.shareRepo.findStale(any(), any(), any())).thenReturn(List.of(share));
        assertEquals(1, settlement.sweep());
        assertEquals(ShareStatus.PAID, share.getStatus());
        assertEquals("tx-found", share.getLedgerTransactionId());
    }

    @Test
    void aClaimWithNoLedgerTransactionIsReleasedNotSettled() {
        ExpenseResponse e = bill();
        String s = shareOf(e, member).id();
        ExpenseShare share = f.share(s);
        share.setStatus(ShareStatus.PROCESSING);
        share.setClaimUserId(member.getId());
        when(f.ledgerTxRepo.findByInitiatorAndKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.shareRepo.findStale(any(), any(), any())).thenReturn(List.of(share));
        assertEquals(1, settlement.sweep());
        assertEquals(ShareStatus.ACCEPTED, share.getStatus());
        assertNull(share.getLedgerTransactionId());
    }
}
