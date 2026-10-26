package com.momocircle.service.wallet;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.dto.ExpenseDtos.*;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Reconciler;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.service.ActivityService;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.CircleService;
import com.momocircle.service.ExpenseService;
import com.momocircle.service.ExpenseSettlementService;
import com.momocircle.service.FinancialValidationService;
import com.momocircle.service.NotificationService;
import com.momocircle.service.ledger.JpaLedgerStore;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerConfig;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.service.ledger.LedgerReconciliationService;
import com.momocircle.wallet.QrTokenService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split-bill settlement against a REAL database (H2 in PostgreSQL mode, Flyway V1..V46) with the REAL Phase 9B wallet and
 * Phase 9A ledger. Starting this context also proves that the V46 migration matches the JPA mappings (ddl-auto=validate).
 * NOT RUN in the sandbox that produced it (no Maven, no network). Run: {@code mvn -Dtest=SplitBillSettlementH2Test test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaLedgerStore.class, LedgerAccountService.class, LedgerReconciliationService.class, LedgerConfig.class,
        LedgerOperationsService.class, AuditLogService.class, WalletService.class, WalletIdentityService.class,
        WalletHistoryQuery.class, BusinessWalletAccessService.class, QrTokenService.class, CurrencyProperties.class,
        ExpenseService.class, ExpenseSettlementService.class, CircleService.class, ActivityService.class,
        FinancialValidationService.class, NotificationService.class})
@TestPropertySource(properties = {"app.wallet.qr-secret=unit-test-qr-secret-0123456789-abcdefghij", "app.financial.max-amount=10000000"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SplitBillSettlementH2Test {

    @Autowired WalletService wallet;
    @Autowired ExpenseService expenses;
    @Autowired ExpenseSettlementService settlement;
    @Autowired CircleService circles;
    @Autowired CircleMembershipRepository memberships;
    @Autowired LedgerReconciliationService reconciliation;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    User newUser(String name) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, now, now, id + "@test.local", name, "+2771" + (long) (Math.random() * 1e7), "x", "USER", true);
        return users.findById(id).orElseThrow();
    }

    BigDecimal bal(User u) { return new BigDecimal(wallet.overview(u).balance()); }

    ConfirmRequest confirm(String amount) { return new ConfirmRequest(true, new BigDecimal(amount), "ZAR", null); }

    /** payer + two participants in a new circle, a 60.00 equal split, members funded with 100.00. */
    record Setup(User payer, User a, User b, ExpenseResponse expense) {
        ExpenseShareResponse share(User u) { return expense.shares().stream().filter(s -> s.debtorId().equals(u.getId())).findFirst().orElseThrow(); }
    }

    Setup setup() {
        User payer = newUser("Payer"), a = newUser("Alice"), b = newUser("Bob");
        String circleId = circles.create(payer, new CreateCircleRequest("Flat " + UUID.randomUUID(), "d", CircleType.EXPENSE_POOL, "ZAR", null)).id();
        for (User u : List.of(a, b)) {
            com.momocircle.domain.CircleMembership m = new com.momocircle.domain.CircleMembership();
            m.setCircle(memberships.findByCircleIdAndUserId(circleId, payer.getId()).orElseThrow().getCircle());
            m.setUser(u);
            memberships.save(m);
        }
        wallet.fund(a, new BigDecimal("100.00"), null, "fund-" + UUID.randomUUID());
        wallet.fund(b, new BigDecimal("100.00"), null, "fund-" + UUID.randomUUID());
        ExpenseResponse e = expenses.create(circleId, payer, new CreateExpenseRequest("Groceries", new BigDecimal("60.00"), SplitType.EQUAL, null,
                List.of(new ShareInput(payer.getId(), null), new ShareInput(a.getId(), null), new ShareInput(b.getId(), null))));
        return new Setup(payer, a, b, e);
    }

    void assertReconciled() {
        List<Reconciler.Discrepancy> d = reconciliation.reconcile();
        assertTrue(d.isEmpty(), "ledger discrepancies: " + d);
    }

    @Test
    void payingMovesRealLedgerValueOnceAndLinksTheTransaction() {
        Setup s = setup();
        assertEquals(0, bal(s.payer()).compareTo(BigDecimal.ZERO), "creating the expense debited nobody");
        SettlementResponse r = settlement.pay(s.a(), s.share(s.a()).id(), confirm("20.00"));
        assertNotNull(r.transaction().reference());
        assertEquals(0, new BigDecimal("80.00").compareTo(bal(s.a())));
        assertEquals(0, new BigDecimal("20.00").compareTo(bal(s.payer())));
        assertEquals(r.transaction().reference(), r.share().ledgerReference());
        assertTrue(settlement.pay(s.a(), s.share(s.a()).id(), confirm("20.00")).replayed());
        assertEquals(0, new BigDecimal("80.00").compareTo(bal(s.a())), "retry did not debit again");
        assertReconciled();
    }

    @Test
    void simultaneousPaymentsOfOneShareSettleItOnce() throws Exception {
        Setup s = setup();
        String shareId = s.share(s.a()).id();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Callable<Object> job = () -> { go.await(); return settlement.pay(s.a(), shareId, confirm("20.00")); };
            fs.add(pool.submit(job));
        }
        go.countDown();
        for (Future<Object> f : fs) { try { f.get(); } catch (Exception ignored) { /* an in-progress conflict is acceptable; a double debit is not */ } }
        pool.shutdown();
        assertEquals(0, new BigDecimal("80.00").compareTo(bal(s.a())), "debited exactly once");
        assertEquals(ShareStatus.PAID, expenses.get(s.expense().id(), s.payer()).shares().stream()
                .filter(x -> x.id().equals(shareId)).findFirst().orElseThrow().status());
        assertReconciled();
    }

    @Test
    void insufficientFundsLeavesTheShareUnpaid() {
        Setup s = setup();
        User poor = s.b();
        wallet.requestWithdrawal(poor, new BigDecimal("95.00"), null, "wd-" + UUID.randomUUID()); // leaves 5.00
        LedgerException ex = assertThrows(LedgerException.class, () -> settlement.pay(poor, s.share(poor).id(), confirm("20.00")));
        assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, ex.code());
        assertEquals(0, new BigDecimal("5.00").compareTo(bal(poor)));
        assertNotEquals(ShareStatus.PAID, expenses.get(s.expense().id(), poor).shares().stream()
                .filter(x -> x.debtorId().equals(poor.getId())).findFirst().orElseThrow().status());
        assertReconciled();
    }

    @Test
    void refundIsACompensatingTransferAndUnblocksCancellation() {
        Setup s = setup();
        settlement.pay(s.a(), s.share(s.a()).id(), confirm("20.00"));
        assertThrows(com.momocircle.exception.ApiException.class,
                () -> expenses.cancel(s.expense().circleId(), s.expense().id(), s.payer(), "mistake"));
        settlement.refund(s.payer(), s.share(s.a()).id(), confirm("20.00"));
        assertEquals(0, new BigDecimal("100.00").compareTo(bal(s.a())));
        assertEquals("CANCELLED", expenses.cancel(s.expense().circleId(), s.expense().id(), s.payer(), "mistake").settlementState());
        assertReconciled();
    }
}
