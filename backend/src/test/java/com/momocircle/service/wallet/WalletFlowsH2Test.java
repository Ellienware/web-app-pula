package com.momocircle.service.wallet;

import com.momocircle.domain.User;
import com.momocircle.domain.wallet.PaymentRequestStatus;
import com.momocircle.dto.WalletDtos.PageView;
import com.momocircle.dto.WalletDtos.PaymentRequestView;
import com.momocircle.dto.WalletDtos.PaymentResult;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.Reconciler;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.wallet.WalletPaymentRequestRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.ledger.JpaLedgerStore;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerConfig;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.service.ledger.LedgerReconciliationService;
import com.momocircle.wallet.QrTokenService;
import com.momocircle.wallet.WalletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wallet and payment-request behaviour against a REAL database (H2 in PostgreSQL mode, Flyway migrations V1..V42), with no
 * surrounding test transaction so every operation commits for real. NOT RUN in the sandbox that produced it (no Maven);
 * run with {@code mvn -Dtest=WalletFlowsH2Test test}. Each test uses brand-new users; shared system accounts are asserted
 * only through wallet balances and the global reconciliation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaLedgerStore.class, LedgerAccountService.class, LedgerReconciliationService.class, LedgerConfig.class,
        LedgerOperationsService.class, AuditLogService.class, WalletService.class, WalletIdentityService.class,
        WalletHistoryQuery.class, PaymentRequestService.class, QrTokenService.class, BusinessWalletAccessService.class,
        BusinessWalletMemberService.class, com.momocircle.config.CurrencyProperties.class})
@TestPropertySource(properties = "app.wallet.qr-secret=unit-test-qr-secret-0123456789-abcdefghij")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletFlowsH2Test {

    @Autowired WalletService wallet;
    @Autowired PaymentRequestService requests;
    @Autowired WalletIdentityService identity;
    @Autowired QrTokenService qr;
    @Autowired LedgerEngine engine;
    @Autowired LedgerReconciliationService reconciliation;
    @Autowired WalletPaymentRequestRepository requestRepo;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ fixtures

    User newUser(String fullName) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, now, now, id + "@test.local", fullName, "+2771" + (long) (Math.random() * 1e7), "x", "USER", true);
        return users.findById(id).orElseThrow();
    }

    User funded(String name, String amount) {
        User u = newUser(name);
        wallet.fund(u, new BigDecimal(amount), null, key());
        return u;
    }

    static String key() { return "k-" + UUID.randomUUID(); }

    String walletId(User u) { return wallet.overview(u).walletId(); }

    BigDecimal bal(User u) { return new BigDecimal(wallet.overview(u).balance()); }

    static String codeOf(Runnable r) {
        RuntimeException e = assertThrows(RuntimeException.class, r::run);
        if (e instanceof WalletException w) return w.code();
        if (e instanceof LedgerException l) return l.code().name();
        throw e;
    }

    void assertReconciled() {
        List<Reconciler.Discrepancy> d = reconciliation.reconcile();
        assertTrue(d.isEmpty(), "ledger discrepancies: " + d);
    }

    <T> List<Object> concurrently(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<T> job : jobs) {
            fs.add(pool.submit(() -> {
                go.await();
                try { return job.call(); }
                catch (WalletException e) { return e.code(); }
                catch (LedgerException e) { return e.code().name(); }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    // ------------------------------------------------------------------ overview, funding, limits

    @Test
    void newWalletStartsAtZeroAndIsLabelledSimulated() {
        User u = newUser("Ann Lee");
        var o = wallet.overview(u);
        assertEquals(0, new BigDecimal(o.balance()).compareTo(BigDecimal.ZERO));
        assertTrue(o.simulated());
        assertTrue(o.notice().toLowerCase().contains("not cash"));
        assertTrue(o.walletId().startsWith("PW-"));
        assertEquals(o.walletId(), wallet.overview(u).walletId(), "wallet id is stable");
    }

    @Test
    void demoFundingIncreasesLedgerBalanceAndIsIdempotent() {
        User u = newUser("Ann Lee");
        String k = key();
        var first = wallet.fund(u, new BigDecimal("100.00"), "ZAR", k);
        var replay = wallet.fund(u, new BigDecimal("100.00"), "ZAR", k);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.view().reference(), replay.view().reference());
        assertEquals(0, bal(u).compareTo(new BigDecimal("100.00")));
        assertTrue(wallet.reconcileWallet(walletId(u)).consistent());
        assertReconciled();
    }

    @Test
    void fundingBeyondPerRequestLimitIsRefused() {
        User u = newUser("Ann Lee");
        assertEquals("LIMIT_EXCEEDED", codeOf(() -> wallet.fund(u, new BigDecimal("5000.01"), "ZAR", key())));
        assertEquals(0, bal(u).signum());
    }

    @Test
    void invalidAmountsAndCurrenciesAreRejected() {
        User u = newUser("Ann Lee");
        assertEquals("INVALID_AMOUNT", codeOf(() -> wallet.fund(u, new BigDecimal("10.005"), "ZAR", key())));
        assertEquals("INVALID_AMOUNT", codeOf(() -> wallet.fund(u, BigDecimal.ZERO, "ZAR", key())));
        assertEquals("INVALID_AMOUNT", codeOf(() -> wallet.fund(u, new BigDecimal("-5.00"), "ZAR", key())));
        assertEquals("CURRENCY_MISMATCH", codeOf(() -> wallet.fund(u, new BigDecimal("5.00"), "USD", key())));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", codeOf(() -> wallet.fund(u, new BigDecimal("5.00"), "ZAR", null)));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", codeOf(() -> wallet.fund(u, new BigDecimal("5.00"), "ZAR", "short")));
        assertEquals(0, bal(u).signum());
    }

    // ------------------------------------------------------------------ transfers

    @Test
    void transferMovesValueOnceAndRetriesReplay() {
        User a = funded("Ann Lee", "100.00");
        User b = newUser("Bo Nkosi");
        String k = key();
        var t1 = wallet.transfer(a, walletId(b), new BigDecimal("30.00"), "ZAR", k);
        var t2 = wallet.transfer(a, walletId(b), new BigDecimal("30.00"), "ZAR", k);
        assertFalse(t1.replayed());
        assertTrue(t2.replayed());
        assertEquals(t1.view().reference(), t2.view().reference());
        assertEquals("OUT", t1.view().direction());
        assertEquals(0, bal(a).compareTo(new BigDecimal("70.00")));
        assertEquals(0, bal(b).compareTo(new BigDecimal("30.00")));
        assertEquals("IN", wallet.detail(b, t1.view().reference()).direction());
        assertEquals("Ann L.", wallet.detail(b, t1.view().reference()).counterparty(), "counterparty is masked");
        assertReconciled();
    }

    @Test
    void reusingAKeyWithDifferentParametersIsAConflictAndMovesNothing() {
        User a = funded("Ann Lee", "100.00");
        User b = newUser("Bo Nkosi");
        String k = key();
        wallet.transfer(a, walletId(b), new BigDecimal("10.00"), "ZAR", k);
        assertEquals("IDEMPOTENCY_CONFLICT", codeOf(() -> wallet.transfer(a, walletId(b), new BigDecimal("11.00"), "ZAR", k)));
        assertEquals(0, bal(b).compareTo(new BigDecimal("10.00")));
    }

    @Test
    void cannotSendToSelfUnknownOrSuspendedRecipients() {
        User a = funded("Ann Lee", "50.00");
        User c = newUser("Cy Dube");
        assertEquals("SELF_TRANSFER", codeOf(() -> wallet.transfer(a, walletId(a), new BigDecimal("1.00"), "ZAR", key())));
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> wallet.transfer(a, "PW-NOPE000000", new BigDecimal("1.00"), "ZAR", key())));
        String cid = walletId(c);
        jdbc.update("update users set active = false where id = ?", c.getId());
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> wallet.transfer(a, cid, new BigDecimal("1.00"), "ZAR", key())));
        assertEquals(0, bal(a).compareTo(new BigDecimal("50.00")));
    }

    @Test
    void transferBeyondBalanceOrLimitIsRefused() {
        User a = funded("Ann Lee", "20.00");
        User b = newUser("Bo Nkosi");
        assertEquals("INSUFFICIENT_FUNDS", codeOf(() -> wallet.transfer(a, walletId(b), new BigDecimal("20.01"), "ZAR", key())));
        assertEquals("LIMIT_EXCEEDED", codeOf(() -> wallet.transfer(a, walletId(b), new BigDecimal("10000.01"), "ZAR", key())));
        assertEquals(0, bal(a).compareTo(new BigDecimal("20.00")));
    }

    @Test
    void simultaneousTransfersCannotOverdraw() throws Exception {
        User a = funded("Ann Lee", "100.00");
        User b = newUser("Bo Nkosi");
        String bid = walletId(b);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(() -> wallet.transfer(a, bid, new BigDecimal("60.00"), "ZAR", key()));
        List<Object> results = concurrently(jobs);
        long ok = results.stream().filter(r -> r instanceof WalletService.Posted).count();
        long refused = results.stream().filter("INSUFFICIENT_FUNDS"::equals).count();
        assertEquals(1, ok, "exactly one 60.00 transfer fits in 100.00: " + results);
        assertEquals(7, refused);
        assertEquals(0, bal(a).compareTo(new BigDecimal("40.00")));
        assertEquals(0, bal(b).compareTo(new BigDecimal("60.00")));
        assertReconciled();
    }

    @Test
    void simultaneousRetriesOfTheSameKeyPostOnce() throws Exception {
        User a = funded("Ann Lee", "100.00");
        User b = newUser("Bo Nkosi");
        String bid = walletId(b), k = key();
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(() -> wallet.transfer(a, bid, new BigDecimal("10.00"), "ZAR", k));
        List<Object> results = concurrently(jobs);
        assertTrue(results.stream().allMatch(r -> r instanceof WalletService.Posted), "all retries get a result: " + results);
        assertEquals(1, results.stream().map(r -> ((WalletService.Posted) r).view().reference()).distinct().count());
        assertEquals(0, bal(b).compareTo(new BigDecimal("10.00")));
    }

    // ------------------------------------------------------------------ ownership & history

    @Test
    void usersCannotSeeEachOthersTransactions() {
        User a = funded("Ann Lee", "100.00");
        User b = newUser("Bo Nkosi");
        User stranger = newUser("Eve Mallory");
        String ref = wallet.transfer(a, walletId(b), new BigDecimal("5.00"), "ZAR", key()).view().reference();
        assertEquals("NOT_FOUND", codeOf(() -> wallet.detail(stranger, ref)));
        assertEquals(0, wallet.history(stranger, WalletHistoryQuery.Filter.none(), 0, 20).totalItems());
        assertEquals("NOT_FOUND", codeOf(() -> wallet.cancelWithdrawal(stranger, ref)));
    }

    @Test
    void historyIsPagedFilteredAndMatchesTheLedger() {
        User a = funded("Ann Lee", "500.00");
        User b = newUser("Bo Nkosi");
        for (int i = 0; i < 24; i++) wallet.transfer(a, walletId(b), new BigDecimal("1.00"), "ZAR", key());
        PageView<TransactionView> p0 = wallet.history(a, WalletHistoryQuery.Filter.none(), 0, 10);
        assertEquals(25, p0.totalItems()); // 1 funding + 24 transfers
        assertEquals(3, p0.totalPages());
        assertEquals(10, p0.items().size());
        PageView<TransactionView> outs = wallet.history(a, new WalletHistoryQuery.Filter(null, null, Posting.Direction.DEBIT, null, null), 0, 50);
        assertEquals(24, outs.totalItems());
        assertTrue(outs.items().stream().allMatch(t -> "OUT".equals(t.direction())));
        BigDecimal viewNet = BigDecimal.ZERO;
        for (TransactionView t : wallet.history(a, WalletHistoryQuery.Filter.none(), 0, 100).items()) {
            viewNet = "IN".equals(t.direction()) ? viewNet.add(new BigDecimal(t.amount())) : viewNet.subtract(new BigDecimal(t.amount()));
        }
        assertEquals(0, viewNet.compareTo(bal(a)), "history sums to the ledger balance");
        assertTrue(wallet.reconcileWallet(walletId(a)).consistent());
    }

    @Test
    void recipientLookupIsExactAndMasked() {
        User a = newUser("Ann Lee");
        User b = newUser("Bo Nkosi");
        var r = wallet.lookupRecipient(a, b.getEmail());
        assertEquals("Bo N.", r.displayName());
        assertEquals(walletId(b), r.recipientId());
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> wallet.lookupRecipient(a, "nobody@test.local")));
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> wallet.lookupRecipient(a, b.getEmail().substring(0, 8))), "no partial search");
        assertEquals("SELF_TRANSFER", codeOf(() -> wallet.lookupRecipient(a, a.getEmail())));
    }

    // ------------------------------------------------------------------ withdrawals

    @Test
    void withdrawalHoldsFundsAndCanBeCancelledOrFailedBackToTheWallet() {
        User a = funded("Ann Lee", "100.00");
        User admin = newUser("Ops Admin");
        var w1 = wallet.requestWithdrawal(a, new BigDecimal("40.00"), "ZAR", key());
        assertEquals("PENDING", w1.view().status());
        assertEquals(0, bal(a).compareTo(new BigDecimal("60.00")));
        assertEquals("CANCELLED", wallet.cancelWithdrawal(a, w1.view().reference()).status());
        assertEquals(0, bal(a).compareTo(new BigDecimal("100.00")));

        var w2 = wallet.requestWithdrawal(a, new BigDecimal("25.00"), "ZAR", key());
        assertEquals("FAILED", wallet.failWithdrawal(admin, w2.view().reference(), "test").status());
        assertEquals(0, bal(a).compareTo(new BigDecimal("100.00")));

        var w3 = wallet.requestWithdrawal(a, new BigDecimal("10.00"), "ZAR", key());
        assertEquals("POSTED", wallet.completeWithdrawal(admin, w3.view().reference()).status());
        assertEquals(0, bal(a).compareTo(new BigDecimal("90.00")));
        assertEquals("ILLEGAL_STATE", codeOf(() -> wallet.cancelWithdrawal(a, w3.view().reference())));
        assertReconciled();
    }

    // ------------------------------------------------------------------ payment requests & QR

    PaymentRequestView request(User requester, String amount, String payerWalletId) {
        return requests.create(requester, new BigDecimal(amount), "ZAR", "Lunch", payerWalletId, 30);
    }

    @Test
    void scanningOrResolvingACodeNeverMovesFunds() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        PaymentRequestView seen = requests.resolve(payer, r.code());
        for (int i = 0; i < 3; i++) requests.resolve(payer, r.code());
        assertEquals("OPEN", seen.status());
        assertEquals("25.00", seen.amount());
        assertTrue(seen.canPay());
        assertNull(seen.code(), "only the requester receives the share code");
        assertEquals(0, bal(payer).compareTo(new BigDecimal("100.00")));
        assertEquals(0, bal(payee).signum());
    }

    @Test
    void explicitConfirmationPaysOnceAndReturnsTheTransactionReference() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        PaymentResult res = requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR");
        assertEquals("PAID", res.request().status());
        assertNotNull(res.transaction().reference());
        assertTrue(res.transaction().reference().startsWith("SIM-"));
        assertTrue(res.simulated());
        assertEquals(0, bal(payer).compareTo(new BigDecimal("75.00")));
        assertEquals(0, bal(payee).compareTo(new BigDecimal("25.00")));
        // a duplicate submission by the same payer is a harmless replay
        PaymentResult again = requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR");
        assertTrue(again.replayed());
        assertEquals(res.transaction().reference(), again.transaction().reference());
        assertEquals(0, bal(payer).compareTo(new BigDecimal("75.00")));
        // anyone else is told it is paid
        User other = funded("Rae Smit", "100.00");
        assertEquals("ALREADY_PAID", codeOf(() -> requests.pay(other, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals(0, bal(other).compareTo(new BigDecimal("100.00")));
        assertReconciled();
    }

    @Test
    void manyPayersRacingForOneRequestPayExactlyOnce() throws Exception {
        User payee = newUser("Pat Dlamini");
        PaymentRequestView r = request(payee, "10.00", null);
        List<User> payers = new ArrayList<>();
        for (int i = 0; i < 8; i++) payers.add(funded("Payer " + i, "50.00"));
        List<Callable<Object>> jobs = new ArrayList<>();
        for (User p : payers) jobs.add(() -> requests.pay(p, r.code(), new BigDecimal("10.00"), "ZAR"));
        List<Object> results = concurrently(jobs);
        long paid = results.stream().filter(x -> x instanceof PaymentResult).count();
        assertEquals(1, paid, "exactly one payer succeeds: " + results);
        assertTrue(results.stream().filter(x -> !(x instanceof PaymentResult)).allMatch(x -> "ALREADY_PAID".equals(x) || "IN_PROGRESS".equals(x)), results.toString());
        assertEquals(0, bal(payee).compareTo(new BigDecimal("10.00")));
        BigDecimal total = payers.stream().map(this::bal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total.compareTo(new BigDecimal("390.00")), "8 x 50.00 minus one 10.00 payment");
        assertReconciled();
    }

    @Test
    void alteredOrForgedCodesAreRejectedAndMoveNothing() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        String code = request(payee, "25.00", null).code();
        String[] parts = code.split("\\.");
        String flippedTag = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        String otherRef = parts[0] + "." + qr.newReference() + "." + parts[2];
        String[] bad = {flippedTag, otherRef, parts[1], parts[0] + "." + parts[1], "", "PULAPAY1.x.y", code + "x", code.replace("PULAPAY1", "PULAPAY2"),
                "'; drop table users; --"};
        for (String b : bad) {
            assertEquals("INVALID_CODE", codeOf(() -> requests.pay(payer, b, new BigDecimal("25.00"), "ZAR")), "code: " + b);
            assertEquals("INVALID_CODE", codeOf(() -> requests.resolve(payer, b)), "code: " + b);
        }
        // a validly signed code for a reference that does not exist is also just invalid
        String ghost = qr.tokenFor(qr.newReference());
        assertEquals("INVALID_CODE", codeOf(() -> requests.resolve(payer, ghost)));
        assertEquals(0, bal(payer).compareTo(new BigDecimal("100.00")));
        assertEquals(0, bal(payee).signum());
    }

    @Test
    void theCodeCarriesNoAmountOrIdentity() {
        User payee = newUser("Pat Dlamini");
        String code = request(payee, "25.00", null).code();
        assertTrue(code.matches("PULAPAY1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{16}"));
        assertFalse(code.contains("25"), "no amount in code: " + code);
        assertFalse(code.contains(payee.getId()));
    }

    @Test
    void amountMustMatchWhatThePayerConfirmed() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        String code = request(payee, "25.00", null).code();
        assertEquals("REQUEST_CHANGED", codeOf(() -> requests.pay(payer, code, new BigDecimal("24.00"), "ZAR")));
        assertEquals("REQUEST_CHANGED", codeOf(() -> requests.pay(payer, code, new BigDecimal("25.00"), "USD")));
        assertEquals(0, bal(payer).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void expiredRequestsCannotBePaidAndTheSweeperExpiresThem() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        jdbc.update("update wallet_payment_requests set expires_at = ? where public_ref = ?", Timestamp.from(Instant.now().minusSeconds(60)), r.requestId());
        assertEquals("REQUEST_EXPIRED", codeOf(() -> requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals("EXPIRED", requests.resolve(payer, r.code()).status());
        assertEquals(0, bal(payer).compareTo(new BigDecimal("100.00")));

        PaymentRequestView r2 = request(payee, "5.00", null);
        jdbc.update("update wallet_payment_requests set expires_at = ? where public_ref = ?", Timestamp.from(Instant.now().minusSeconds(60)), r2.requestId());
        assertTrue(requests.sweep() >= 1);
        assertEquals(PaymentRequestStatus.EXPIRED.name(), requests.get(payee, r2.requestId()).status());
    }

    @Test
    void cancellationIsRequesterOnlyAndBlocksPayment() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        assertEquals("NOT_FOUND", codeOf(() -> requests.cancel(payer, r.requestId())));
        assertEquals("CANCELLED", requests.cancel(payee, r.requestId()).status());
        assertEquals("CANCELLED", requests.cancel(payee, r.requestId()).status(), "repeat is harmless");
        assertEquals("REQUEST_CANCELLED", codeOf(() -> requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals(0, bal(payer).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void cannotCancelAPaidRequest() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR");
        assertEquals("NOT_CANCELLABLE", codeOf(() -> requests.cancel(payee, r.requestId())));
    }

    @Test
    void targetedRequestsAreInvisibleToOthersAndCanBeRejectedByTheTarget() {
        User payee = newUser("Pat Dlamini");
        User target = funded("Quinn Zulu", "100.00");
        User other = funded("Rae Smit", "100.00");
        PaymentRequestView r = request(payee, "25.00", walletId(target));
        assertEquals("INVALID_CODE", codeOf(() -> requests.resolve(other, r.code())));
        assertEquals("INVALID_CODE", codeOf(() -> requests.pay(other, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals("NOT_FOUND", codeOf(() -> requests.get(other, r.requestId())));
        assertEquals(1, requests.list(target, true, 0, 10).totalItems());
        assertTrue(requests.resolve(target, r.code()).canReject());
        assertEquals("REJECTED", requests.reject(target, r.code()).status());
        assertEquals("REQUEST_REJECTED", codeOf(() -> requests.pay(target, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals(0, bal(target).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void openRequestsCannotBeRejectedAndNobodyCanPayThemselves() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView open = request(payee, "25.00", null);
        assertEquals("NOT_REJECTABLE", codeOf(() -> requests.reject(payer, open.code())));
        assertEquals("SELF_PAYMENT", codeOf(() -> requests.pay(payee, open.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals("SELF_PAYMENT", codeOf(() -> requests.create(payee, new BigDecimal("1.00"), "ZAR", "x", walletId(payee), null)));
    }

    @Test
    void insufficientFundsLeavesTheRequestOpenForALaterPayment() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "10.00");
        PaymentRequestView r = request(payee, "25.00", null);
        assertEquals("INSUFFICIENT_FUNDS", codeOf(() -> requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR")));
        assertEquals("OPEN", requests.resolve(payer, r.code()).status());
        wallet.fund(payer, new BigDecimal("50.00"), "ZAR", key());
        assertEquals("PAID", requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR").request().status());
        assertEquals(0, bal(payee).compareTo(new BigDecimal("25.00")));
    }

    @Test
    void requestCreationIsValidated() {
        User payee = newUser("Pat Dlamini");
        assertEquals("INVALID_AMOUNT", codeOf(() -> requests.create(payee, new BigDecimal("1.234"), "ZAR", "x", null, null)));
        assertEquals("LIMIT_EXCEEDED", codeOf(() -> requests.create(payee, new BigDecimal("10000.01"), "ZAR", "x", null, null)));
        assertEquals("INVALID_PURPOSE", codeOf(() -> requests.create(payee, new BigDecimal("1.00"), "ZAR", "   \n\t ", null, null)));
        assertEquals("CURRENCY_MISMATCH", codeOf(() -> requests.create(payee, new BigDecimal("1.00"), "EUR", "x", null, null)));
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> requests.create(payee, new BigDecimal("1.00"), "ZAR", "x", "PW-NOPE000000", null)));
    }

    @Test
    void staleClaimWithNoLedgerPostingIsReleasedByTheSweeper() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        var e = requestRepo.findByPublicRef(r.requestId()).orElseThrow();
        assertEquals(1, requestRepo.claim(e.getId(), payer.getId(), Instant.now(), PaymentRequestStatus.OPEN, PaymentRequestStatus.PROCESSING));
        assertEquals("IN_PROGRESS", codeOf(() -> requests.pay(funded("Rae Smit", "50.00"), r.code(), new BigDecimal("25.00"), "ZAR")));
        jdbc.update("update wallet_payment_requests set updated_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(7200)), e.getId());
        requests.sweep();
        assertEquals("OPEN", requests.get(payee, r.requestId()).status());
    }

    @Test
    void stalePostedClaimIsFinalisedAsPaidByTheSweeper() {
        User payee = newUser("Pat Dlamini");
        User payer = funded("Quinn Zulu", "100.00");
        PaymentRequestView r = request(payee, "25.00", null);
        var e = requestRepo.findByPublicRef(r.requestId()).orElseThrow();
        requestRepo.claim(e.getId(), payer.getId(), Instant.now(), PaymentRequestStatus.OPEN, PaymentRequestStatus.PROCESSING);
        // the ledger posted but the process died before marking the request paid
        engine.transfer(Actor.ofUser(payer.getId()), accountOf(payer), accountOf(payee), new Money(2500, "ZAR"), "payreq:" + e.getId());
        jdbc.update("update wallet_payment_requests set updated_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(7200)), e.getId());
        requests.sweep();
        PaymentRequestView after = requests.get(payee, r.requestId());
        assertEquals("PAID", after.status());
        assertNotNull(after.transactionReference());
        // the payer's own retry is a replay, not a second payment
        assertTrue(requests.pay(payer, r.code(), new BigDecimal("25.00"), "ZAR").replayed());
        assertEquals(0, bal(payer).compareTo(new BigDecimal("75.00")));
    }

    String accountOf(User u) {
        return jdbc.queryForObject("select id from ledger_accounts where owner_user_id = ? and currency = 'ZAR'", String.class, u.getId());
    }

    @Test
    void publicIdsAreStableUniqueAndOpaque() {
        User a = newUser("Ann Lee"), b = newUser("Bo Nkosi");
        assertNotEquals(walletId(a), walletId(b));
        assertFalse(walletId(a).contains(a.getId()));
        assertEquals("Ann L.", WalletIdentityService.maskName("Ann Lee"));
        assertEquals("Cher", WalletIdentityService.maskName("Cher"));
        assertEquals("PULA user", WalletIdentityService.maskName("  "));
    }
}
