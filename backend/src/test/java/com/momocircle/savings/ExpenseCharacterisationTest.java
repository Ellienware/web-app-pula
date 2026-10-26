package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.dto.ExpenseDtos.*;
import com.momocircle.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE 2A: current shared-expense behaviour (a ledger of who owes whom; no money moves through PULA). */
class ExpenseCharacterisationTest {

    private SavingsFixture f;
    private User owner, admin, member, outsider;
    private String circleId;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); admin = f.user("Admin"); member = f.user("Member"); outsider = f.user("Outsider");
        circleId = f.newCircle(owner, "Flatmates", CircleType.EXPENSE_POOL, null).id();
        f.join(circleId, admin, CircleRole.ADMIN);
        f.join(circleId, member, CircleRole.MEMBER);
    }

    private ExpenseResponse create(User actor, String amount, SplitType split, String paidBy, ShareInput... shares) {
        return f.expenseService.create(circleId, actor, new CreateExpenseRequest("Groceries", d(amount), split, paidBy, List.of(shares)));
    }

    private ShareInput in(User u) { return new ShareInput(u.getId(), null); }
    private ShareInput in(User u, String amount) { return new ShareInput(u.getId(), d(amount)); }
    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    @Test
    void anyMemberNotJustAnAdminCanRecordAnExpenseAndPayerSharesAreSettledAutomatically() {
        ExpenseResponse e = create(member, "90.00", SplitType.EQUAL, null, in(owner), in(admin), in(member));
        assertEquals(member.getId(), e.paidById());
        assertEquals("ZAR", e.currency());
        assertEquals(3, e.shares().size());
        assertTrue(e.shares().stream().filter(s -> s.debtorId().equals(member.getId())).allMatch(ExpenseShareResponse::settled));
        assertEquals(d("30.00"), e.settledAmount());
        assertEquals(d("60.00"), e.outstandingAmount());
        assertEquals(1, f.activityCount(ActivityType.EXPENSE_ADDED));
    }

    @Test
    void equalSplitsAssignLeftoverCentsToTheFirstParticipantsDeterministically() {
        ExpenseResponse e = create(owner, "100.00", SplitType.EQUAL, null, in(owner), in(admin), in(member));
        assertEquals(List.of(d("33.34"), d("33.33"), d("33.33")), e.shares().stream().map(ExpenseShareResponse::amount).toList());
        assertEquals(d("100.00"), e.shares().stream().map(ExpenseShareResponse::amount).reduce(d("0"), java.math.BigDecimal::add));
    }

    @Test
    void customSplitsMustAddUpExactlyAndNotBeNegative() {
        ExpenseResponse ok = create(owner, "100.00", SplitType.CUSTOM, null, in(owner, "70.00"), in(admin, "30.00"));
        assertEquals(d("70.00"), ok.shares().get(0).amount());
        assertEquals(400, status(() -> create(owner, "100.00", SplitType.CUSTOM, null, in(owner, "70.00"), in(admin, "20.00"))));
        assertEquals(400, status(() -> create(owner, "100.00", SplitType.CUSTOM, null, in(owner, "110.00"), in(admin, "-10.00"))));
        assertEquals(400, status(() -> create(owner, "100.00", SplitType.CUSTOM, null, in(owner), in(admin, "100.00"))), "an amount is needed for everyone");
    }

    @Test
    void amountsMustBeValidAndParticipantsMustBeMembers() {
        for (String bad : new String[] {"0", "-1", "10.005"}) assertEquals(400, status(() -> create(owner, bad, SplitType.EQUAL, null, in(owner))), bad);
        assertEquals(400, status(() -> create(owner, "10.00", SplitType.EQUAL, null, in(outsider))), "participant is not a member");
        assertEquals(400, status(() -> create(owner, "10.00", SplitType.EQUAL, outsider.getId(), in(owner))), "payer is not a member");
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, new CreateExpenseRequest("x", d("10"), SplitType.EQUAL, null, List.of()))), "at least one participant");
        assertEquals(403, status(() -> create(outsider, "10.00", SplitType.EQUAL, null, in(owner))));
    }

    @Test
    void PHASE_9C_aPlainMemberCanNoLongerRecordThatAnotherMemberPaid_butAnAdminCan() {
        // Phase 2A FINDING (any member could attribute payment, and so debts, to someone else) is closed in Phase 9C.
        assertEquals(403, status(() -> create(member, "50.00", SplitType.EQUAL, admin.getId(), in(member), in(admin))));
        ExpenseResponse e = create(admin, "50.00", SplitType.EQUAL, owner.getId(), in(member), in(owner));
        assertEquals(owner.getId(), e.paidById(), "a circle admin may record on someone's behalf");
        assertEquals(admin.getId(), e.createdById());
    }

    @Test
    void PHASE_9C_onlyThePayerCanRecordRepaymentOutsidePula_andItIsNotAWalletSettlement() {
        // Phase 2A let the debtor or any admin mark a share paid. That let people clear their own debt with no money moving.
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, null, in(owner), in(admin), in(member));
        String adminShare = e.shares().stream().filter(s -> s.debtorId().equals(admin.getId())).findFirst().orElseThrow().id();
        String memberShare = e.shares().stream().filter(s -> s.debtorId().equals(member.getId())).findFirst().orElseThrow().id();
        assertEquals(403, status(() -> f.expenseService.markReceivedExternally(adminShare, admin)), "the debtor cannot clear their own debt");
        assertEquals(403, status(() -> f.expenseService.markReceivedExternally(adminShare, member)), "another member cannot either");
        ExpenseResponse after = f.expenseService.markReceivedExternally(adminShare, owner);
        f.expenseService.markReceivedExternally(adminShare, owner); // repeating is harmless
        assertEquals(1, f.activityCount(ActivityType.EXPENSE_SHARE_SETTLED));
        ExpenseShareResponse row = after.shares().stream().filter(s -> s.id().equals(adminShare)).findFirst().orElseThrow();
        assertEquals(com.momocircle.domain.enums.SettlementMethod.EXTERNAL, row.settlementMethod());
        assertNull(row.ledgerReference(), "no ledger transaction exists for a repayment recorded outside PULA");
        assertEquals(d("0.00"), after.transferredAmount(), "no simulated funds moved");
        assertEquals(d("20.00"), after.externalSettledAmount());
        ExpenseResponse done = f.expenseService.markReceivedExternally(memberShare, owner);
        assertEquals(d("0.00"), done.outstandingAmount());
    }

    @Test
    void expensesAreListedForMembersOnlyAndBelongToTheirCircle() {
        ExpenseResponse e = create(owner, "10.00", SplitType.EQUAL, null, in(owner));
        assertEquals(1, f.expenseService.list(circleId, member).size());
        assertEquals(403, status(() -> f.expenseService.list(circleId, outsider)));
        String other = f.newCircle(owner, "Other", CircleType.EXPENSE_POOL, null).id();
        assertEquals(400, status(() -> f.expenseService.cancel(other, e.id(), owner, null)), "expense id from another circle");
        assertEquals(404, status(() -> f.expenseService.markReceivedExternally("no-share", owner)));
        assertEquals(404, status(() -> f.expenseService.get("no-expense", owner)));
    }
}
