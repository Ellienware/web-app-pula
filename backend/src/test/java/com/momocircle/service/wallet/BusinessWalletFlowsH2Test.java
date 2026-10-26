package com.momocircle.service.wallet;

import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletRole.Permission;
import com.momocircle.dto.WalletDtos.MemberView;
import com.momocircle.dto.WalletDtos.PaymentRequestView;
import com.momocircle.dto.WalletDtos.PaymentResult;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.Reconciler;
import com.momocircle.repository.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUSINESS wallets against a real database (H2 in PostgreSQL mode, Flyway V1..V43): identity and membership kept separate from
 * the owner, role permissions, delegated access, customers paying a business by QR, overdraft safety and reconciliation.
 * NOT RUN in the sandbox that produced it (no Maven); run with {@code mvn -Dtest=BusinessWalletFlowsH2Test test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaLedgerStore.class, LedgerAccountService.class, LedgerReconciliationService.class, LedgerConfig.class,
        LedgerOperationsService.class, AuditLogService.class, WalletService.class, WalletIdentityService.class,
        WalletHistoryQuery.class, PaymentRequestService.class, QrTokenService.class, BusinessWalletAccessService.class,
        BusinessWalletMemberService.class, com.momocircle.config.CurrencyProperties.class})
@TestPropertySource(properties = "app.wallet.qr-secret=unit-test-qr-secret-0123456789-abcdefghij")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessWalletFlowsH2Test {

    @Autowired WalletService wallet;
    @Autowired PaymentRequestService requests;
    @Autowired BusinessWalletAccessService access;
    @Autowired BusinessWalletMemberService memberService;
    @Autowired WalletIdentityService identity;
    @Autowired LedgerReconciliationService reconciliation;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ fixtures

    User user(String fullName) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, now, now, id + "@test.local", fullName, "+2771" + (long) (Math.random() * 1e7), "x", "USER", true);
        return users.findById(id).orElseThrow();
    }

    /** Creates a business owned by {@code owner}; returns its PUBLIC wallet id (BW-...). */
    String business(User owner, String name) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into businesses (id, created_at, updated_at, owner_id, name, category) values (?, ?, ?, ?, ?, ?)",
                id, now, now, owner.getId(), name, "Food");
        return identity.businessPublicIdFor(id);
    }

    void setActive(String bw, boolean active) {
        String businessId = identity.businessForPublicId(bw).orElseThrow().getId();
        jdbc.update("update businesses set active = ? where id = ?", active, businessId);
    }

    String key() { return "k-" + UUID.randomUUID(); }

    WalletService.Ctx ctx(User u, String bw, Permission p) { return wallet.business(u, access.require(u, bw, p)); }

    void fund(User u, String bw, String amount) { wallet.fund(ctx(u, bw, Permission.FUND), new BigDecimal(amount), null, key()); }

    BigDecimal bal(WalletService.Ctx c) { return new BigDecimal(wallet.overview(c).balance()); }
    BigDecimal bal(User u, String bw) { return bal(ctx(u, bw, Permission.VIEW)); }
    BigDecimal personalBal(User u) { return new BigDecimal(wallet.overview(u).balance()); }

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

    // ------------------------------------------------------------------ identity & access

    @Test
    void ownerHasFullAccessAndEveryoneElseGetsNotFound() {
        User owner = user("Olu Bakker"), stranger = user("Eve Mallory");
        String bw = business(owner, "Olu's Kitchen");
        assertTrue(bw.startsWith("BW-"));
        assertEquals("OWNER", access.require(owner, bw, Permission.MANAGE_MEMBERS).role().name());
        assertEquals("NOT_FOUND", codeOf(() -> access.require(stranger, bw, Permission.VIEW)));
        assertEquals("NOT_FOUND", codeOf(() -> access.require(stranger, "BW-NOPE000000", Permission.VIEW)));
        assertEquals(1, access.accessibleTo(owner).size());
        assertEquals(0, access.accessibleTo(stranger).size());
    }

    @Test
    void theBusinessWalletIsADifferentWalletFromTheOwnersPersonalWallet() {
        User owner = user("Olu Bakker");
        String bw = business(owner, "Olu's Kitchen");
        fund(owner, bw, "100.00");
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("100.00")));
        assertEquals(0, personalBal(owner).signum(), "the owner's personal wallet is untouched");
        assertNotEquals(wallet.overview(owner).walletId(), bw);
        assertTrue(wallet.overview(owner).walletId().startsWith("PW-"));
        assertFalse(bw.contains(owner.getId()));
    }

    @Test
    void delegatedRolesEnforcePermissions() {
        User owner = user("Olu Bakker"), finance = user("Fin Ance"), staff = user("Sta Ff");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), finance.getEmail(), "FINANCE");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), staff.getEmail(), "STAFF");

        for (Permission p : new Permission[]{Permission.VIEW, Permission.REQUEST}) {
            access.require(staff, bw, p);
        }
        for (Permission p : new Permission[]{Permission.SEND, Permission.WITHDRAW, Permission.FUND, Permission.MANAGE_MEMBERS}) {
            assertEquals("FORBIDDEN_ROLE", codeOf(() -> access.require(staff, bw, p)), "STAFF must not have " + p);
        }
        for (Permission p : new Permission[]{Permission.VIEW, Permission.REQUEST, Permission.SEND, Permission.WITHDRAW, Permission.FUND}) {
            access.require(finance, bw, p);
        }
        assertEquals("FORBIDDEN_ROLE", codeOf(() -> access.require(finance, bw, Permission.MANAGE_MEMBERS)));
    }

    @Test
    void onlyTheOwnerManagesMembersAndTheOwnerCanNeverBeAddedOrDemoted() {
        User owner = user("Olu Bakker"), finance = user("Fin Ance"), other = user("Oth Er");
        String bw = business(owner, "Olu's Kitchen");
        var ownerAccess = access.require(owner, bw, Permission.MANAGE_MEMBERS);
        MemberView added = memberService.add(owner, ownerAccess, finance.getEmail(), "FINANCE");
        assertEquals("Fin A.", added.displayName());
        assertTrue(added.memberId().startsWith("PW-"));
        assertEquals("ALREADY_MEMBER", codeOf(() -> memberService.add(owner, ownerAccess, finance.getEmail(), "STAFF")));
        assertEquals("ALREADY_OWNER", codeOf(() -> memberService.add(owner, ownerAccess, owner.getEmail(), "FINANCE")));
        assertEquals("INVALID_ROLE", codeOf(() -> memberService.add(owner, ownerAccess, other.getEmail(), "OWNER")));
        assertEquals("INVALID_ROLE", codeOf(() -> memberService.add(owner, ownerAccess, other.getEmail(), "root")));
        assertEquals("RECIPIENT_NOT_FOUND", codeOf(() -> memberService.add(owner, ownerAccess, "nobody@test.local", "STAFF")));

        assertEquals("STAFF", memberService.changeRole(owner, ownerAccess, added.memberId(), "STAFF").role());
        assertEquals("FORBIDDEN_ROLE", codeOf(() -> access.require(finance, bw, Permission.SEND)));
        assertEquals(2, memberService.list(ownerAccess).size());
        assertEquals("OWNER", memberService.list(ownerAccess).get(0).role());

        memberService.remove(owner, ownerAccess, added.memberId());
        assertEquals("NOT_FOUND", codeOf(() -> access.require(finance, bw, Permission.VIEW)), "a removed member loses access entirely");
        assertEquals(0, access.accessibleTo(finance).size());
    }

    @Test
    void aMemberOfOneBusinessCannotAccessAnother() {
        User ownerA = user("Aa Owner"), ownerB = user("Bb Owner"), memberA = user("Mem Ber");
        String a = business(ownerA, "Shop A"), b = business(ownerB, "Shop B");
        memberService.add(ownerA, access.require(ownerA, a, Permission.MANAGE_MEMBERS), memberA.getEmail(), "FINANCE");
        access.require(memberA, a, Permission.SEND);
        assertEquals("NOT_FOUND", codeOf(() -> access.require(memberA, b, Permission.VIEW)));
    }

    @Test
    void inactiveBusinessesAreInvisible() {
        User owner = user("Olu Bakker");
        String bw = business(owner, "Olu's Kitchen");
        access.require(owner, bw, Permission.VIEW);
        setActive(bw, false);
        assertEquals("NOT_FOUND", codeOf(() -> access.require(owner, bw, Permission.VIEW)));
        assertEquals(0, access.accessibleTo(owner).size());
    }

    // ------------------------------------------------------------------ money

    @Test
    void businessTransfersMoveFromTheBusinessWalletAndAreIdempotent() {
        User owner = user("Olu Bakker"), supplier = user("Sup Plier");
        String bw = business(owner, "Olu's Kitchen");
        fund(owner, bw, "500.00");
        var c = ctx(owner, bw, Permission.SEND);
        String k = key();
        var first = wallet.transfer(c, wallet.overview(supplier).walletId(), new BigDecimal("120.00"), "ZAR", k);
        var replay = wallet.transfer(c, wallet.overview(supplier).walletId(), new BigDecimal("120.00"), "ZAR", k);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.view().reference(), replay.view().reference());
        assertEquals(0, bal(c).compareTo(new BigDecimal("380.00")));
        assertEquals(0, personalBal(supplier).compareTo(new BigDecimal("120.00")));
        assertEquals(0, personalBal(owner).signum());
        assertEquals("Olu's Kitchen", wallet.detail(supplier, first.view().reference()).counterparty(), "the payee sees the business name");
        assertEquals("OUT", first.view().direction());
        assertReconciled();
    }

    @Test
    void theSameClientKeyInPersonalAndBusinessScopesDoesNotCollide() {
        User owner = user("Olu Bakker"), other = user("Oth Er");
        String bw = business(owner, "Olu's Kitchen");
        fund(owner, bw, "100.00");
        wallet.fund(owner, new BigDecimal("50.00"), "ZAR", "shared-key-0001");
        wallet.fund(ctx(owner, bw, Permission.FUND), new BigDecimal("70.00"), "ZAR", "shared-key-0001");
        assertEquals(0, personalBal(owner).compareTo(new BigDecimal("50.00")));
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("170.00")));
        assertEquals(0, personalBal(other).signum());
    }

    @Test
    void anyoneCanPayIntoABusinessWalletByItsPublicId() {
        User owner = user("Olu Bakker"), customer = user("Cus Tomer");
        String bw = business(owner, "Olu's Kitchen");
        wallet.fund(customer, new BigDecimal("80.00"), "ZAR", key());
        var rec = wallet.lookupRecipient(customer, bw);
        assertEquals("Olu's Kitchen", rec.displayName());
        wallet.transfer(customer, bw, new BigDecimal("30.00"), "ZAR", key());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("30.00")));
        assertEquals(0, personalBal(customer).compareTo(new BigDecimal("50.00")));
    }

    @Test
    void simultaneousBusinessTransfersByDifferentMembersCannotOverdraw() throws Exception {
        User owner = user("Olu Bakker"), finance = user("Fin Ance"), supplier = user("Sup Plier");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), finance.getEmail(), "FINANCE");
        fund(owner, bw, "100.00");
        String to = wallet.overview(supplier).walletId();
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            User who = i % 2 == 0 ? owner : finance;
            jobs.add(() -> wallet.transfer(ctx(who, bw, Permission.SEND), to, new BigDecimal("60.00"), "ZAR", key()));
        }
        List<Object> results = concurrently(jobs);
        assertEquals(1, results.stream().filter(r -> r instanceof WalletService.Posted).count(), "exactly one 60.00 fits in 100.00: " + results);
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("40.00")));
        assertEquals(0, personalBal(supplier).compareTo(new BigDecimal("60.00")));
        assertReconciled();
    }

    @Test
    void businessWithdrawalsAreSimulatedAndCancellableOnlyByTheRequestingMember() {
        User owner = user("Olu Bakker"), finance = user("Fin Ance"), admin = user("Ops Admin");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), finance.getEmail(), "FINANCE");
        fund(owner, bw, "200.00");
        var w = wallet.requestWithdrawal(ctx(owner, bw, Permission.WITHDRAW), new BigDecimal("50.00"), "ZAR", key());
        assertEquals("PENDING", w.view().status());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("150.00")));
        assertEquals("NOT_OWNER", codeOf(() -> wallet.cancelWithdrawal(ctx(finance, bw, Permission.WITHDRAW), w.view().reference())),
                "another member cannot cancel it");
        assertEquals("CANCELLED", wallet.cancelWithdrawal(ctx(owner, bw, Permission.WITHDRAW), w.view().reference()).status());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("200.00")));
        var w2 = wallet.requestWithdrawal(ctx(owner, bw, Permission.WITHDRAW), new BigDecimal("20.00"), "ZAR", key());
        assertEquals("FAILED", wallet.failWithdrawal(admin, w2.view().reference(), "test").status());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("200.00")));
    }

    // ------------------------------------------------------------------ payment requests for a business

    PaymentRequestView businessRequest(User creator, String bw, String amount) {
        return requests.createForBusiness(creator, access.require(creator, bw, Permission.REQUEST), new BigDecimal(amount), "ZAR", "Lunch order", null, 30);
    }

    @Test
    void aCustomerPaysABusinessRequestByQrAndTheBusinessWalletIsCredited() {
        User owner = user("Olu Bakker"), staff = user("Sta Ff"), customer = user("Cus Tomer");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), staff.getEmail(), "STAFF");
        wallet.fund(customer, new BigDecimal("100.00"), "ZAR", key());

        PaymentRequestView r = businessRequest(staff, bw, "35.00");
        assertEquals("OUTGOING", r.direction());
        assertEquals("Olu's Kitchen", r.requesterName());
        assertTrue(r.canCancel());

        PaymentRequestView seen = requests.resolve(customer, r.code());
        assertEquals("Olu's Kitchen", seen.requesterName());
        assertEquals("OPEN_LINK", seen.direction());
        assertTrue(seen.canPay());
        assertNull(seen.code());
        assertEquals(0, bal(owner, bw).signum(), "resolving moves nothing");

        PaymentResult paid = requests.pay(customer, r.code(), new BigDecimal("35.00"), "ZAR");
        assertEquals("PAID", paid.request().status());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("35.00")));
        assertEquals(0, personalBal(customer).compareTo(new BigDecimal("65.00")));
        assertEquals(0, personalBal(owner).signum(), "the owner's personal wallet gets nothing");
        assertTrue(requests.pay(customer, r.code(), new BigDecimal("35.00"), "ZAR").replayed());
        assertEquals(0, bal(owner, bw).compareTo(new BigDecimal("35.00")));

        var listed = requests.listForBusiness(owner, access.require(owner, bw, Permission.VIEW), 0, 10);
        assertEquals(1, listed.totalItems());
        assertEquals("PAID", listed.items().get(0).status());
        assertTrue(listed.items().get(0).transactionReference().startsWith("SIM-"));
        assertEquals(0, requests.list(staff, false, 0, 10).totalItems(), "a business request is not in the creator's personal list");
        assertReconciled();
    }

    @Test
    void businessMembersCannotPayTheirOwnBusinessRequest() {
        User owner = user("Olu Bakker"), finance = user("Fin Ance");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), finance.getEmail(), "FINANCE");
        wallet.fund(owner, new BigDecimal("100.00"), "ZAR", key());
        wallet.fund(finance, new BigDecimal("100.00"), "ZAR", key());
        PaymentRequestView r = businessRequest(owner, bw, "10.00");
        for (User u : new User[]{owner, finance}) {
            assertEquals("SELF_PAYMENT", codeOf(() -> requests.pay(u, r.code(), new BigDecimal("10.00"), "ZAR")));
            PaymentRequestView v = requests.resolve(u, r.code());
            assertFalse(v.canPay(), "members are shown their own business's request as theirs");
            assertEquals("OUTGOING", v.direction());
        }
        assertEquals(0, personalBal(owner).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void anyMemberWithRequestPermissionCancelsButOnlyThroughTheBusinessPath() {
        User owner = user("Olu Bakker"), staff = user("Sta Ff"), stranger = user("Eve Mallory");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), staff.getEmail(), "STAFF");
        PaymentRequestView r = businessRequest(staff, bw, "10.00");
        assertEquals("NOT_FOUND", codeOf(() -> requests.cancel(staff, r.requestId())), "the personal cancel path ignores business requests");
        assertEquals("NOT_FOUND", codeOf(() -> requests.get(staff, r.requestId())));
        assertEquals("NOT_FOUND", codeOf(() -> access.require(stranger, bw, Permission.REQUEST)));
        assertEquals("CANCELLED", requests.cancelForBusiness(owner, access.require(owner, bw, Permission.REQUEST), r.requestId()).status());
        assertEquals("CANCELLED", requests.getForBusiness(staff, access.require(staff, bw, Permission.VIEW), r.requestId()).status());
    }

    @Test
    void aRemovedMemberCanNoLongerCancelOrSeeBusinessRequests() {
        User owner = user("Olu Bakker"), staff = user("Sta Ff");
        String bw = business(owner, "Olu's Kitchen");
        var ownerAccess = access.require(owner, bw, Permission.MANAGE_MEMBERS);
        MemberView m = memberService.add(owner, ownerAccess, staff.getEmail(), "STAFF");
        PaymentRequestView r = businessRequest(staff, bw, "10.00");
        memberService.remove(owner, ownerAccess, m.memberId());
        assertEquals("NOT_FOUND", codeOf(() -> access.require(staff, bw, Permission.REQUEST)));
        assertEquals("NOT_FOUND", codeOf(() -> requests.cancel(staff, r.requestId())));
        assertEquals("OPEN_LINK", requests.resolve(staff, r.code()).direction(), "an ex-member is just a customer now");
    }

    @Test
    void aBusinessThatBecomesInactiveCannotBePaidAndTheRequestStaysOpen() {
        User owner = user("Olu Bakker"), customer = user("Cus Tomer");
        String bw = business(owner, "Olu's Kitchen");
        wallet.fund(customer, new BigDecimal("100.00"), "ZAR", key());
        PaymentRequestView r = businessRequest(owner, bw, "10.00");
        setActive(bw, false);
        assertEquals("BUSINESS_UNAVAILABLE", codeOf(() -> requests.pay(customer, r.code(), new BigDecimal("10.00"), "ZAR")));
        assertEquals(0, personalBal(customer).compareTo(new BigDecimal("100.00")));
        assertEquals("OPEN", requests.resolve(customer, r.code()).status());
    }

    @Test
    void businessRequestsCanBeTargetedButNeverAtAMemberOfThatBusiness() {
        User owner = user("Olu Bakker"), finance = user("Fin Ance"), customer = user("Cus Tomer");
        String bw = business(owner, "Olu's Kitchen");
        memberService.add(owner, access.require(owner, bw, Permission.MANAGE_MEMBERS), finance.getEmail(), "FINANCE");
        var a = access.require(owner, bw, Permission.REQUEST);
        assertEquals("SELF_PAYMENT", codeOf(() -> requests.createForBusiness(owner, a, new BigDecimal("5.00"), "ZAR", "x", wallet.overview(finance).walletId(), null)));
        PaymentRequestView r = requests.createForBusiness(owner, a, new BigDecimal("5.00"), "ZAR", "x", wallet.overview(customer).walletId(), null);
        assertEquals(1, requests.list(customer, true, 0, 10).totalItems());
        assertEquals("INCOMING", requests.resolve(customer, r.code()).direction());
    }

    @Test
    void businessHistoryMatchesTheLedgerAndReconcilesByPublicId() {
        User owner = user("Olu Bakker"), supplier = user("Sup Plier");
        String bw = business(owner, "Olu's Kitchen");
        fund(owner, bw, "300.00");
        for (int i = 0; i < 5; i++) wallet.transfer(ctx(owner, bw, Permission.SEND), wallet.overview(supplier).walletId(), new BigDecimal("10.00"), "ZAR", key());
        var c = ctx(owner, bw, Permission.VIEW);
        var page = wallet.history(c, WalletHistoryQuery.Filter.none(), 0, 50);
        assertEquals(6, page.totalItems());
        BigDecimal net = BigDecimal.ZERO;
        for (var t : page.items()) net = "IN".equals(t.direction()) ? net.add(new BigDecimal(t.amount())) : net.subtract(new BigDecimal(t.amount()));
        assertEquals(0, net.compareTo(bal(c)));
        assertTrue(wallet.reconcileWallet(bw).consistent());
        assertEquals(0, wallet.history(supplier, WalletHistoryQuery.Filter.none(), 0, 50).items().stream()
                .filter(t -> !"Olu's Kitchen".equals(t.counterparty())).count());
    }
}
