package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.ExpenseStatus;
import com.momocircle.domain.enums.SettlementMethod;
import com.momocircle.domain.enums.ShareStatus;
import com.momocircle.domain.enums.SplitType;
import com.momocircle.dto.ExpenseDtos.*;
import com.momocircle.exception.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE 9C: the split-bill RECORD (consent, edits, cancellation, history) over the real ExpenseService with in-memory repositories. */
class SplitBillLifecycleTest {

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

    private ShareInput eq(User u) { return new ShareInput(u.getId(), null, null); }
    private ShareInput amt(User u, String a) { return new ShareInput(u.getId(), d(a), null); }
    private ShareInput pct(User u, String p) { return new ShareInput(u.getId(), null, d(p)); }
    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    private CreateExpenseRequest req(String total, SplitType t, List<ShareInput> ps, List<ItemInput> items) {
        return new CreateExpenseRequest("Dinner", "note", d(total), null, t, null, null, null, ps, items);
    }

    private ExpenseResponse create(User actor, String total, SplitType t, ShareInput... ps) {
        return f.expenseService.create(circleId, actor, req(total, t, List.of(ps), null));
    }

    private ExpenseShareResponse shareOf(ExpenseResponse e, User u) {
        return e.shares().stream().filter(s -> s.debtorId().equals(u.getId())).findFirst().orElseThrow();
    }

    private UpdateExpenseRequest update(ExpenseResponse e, String total, SplitType t, ShareInput... ps) {
        return new UpdateExpenseRequest(e.revision(), "Dinner", null, d(total), null, t, null, null, null, List.of(ps), null);
    }

    // ------------------------------------------------------------ consent: nobody is debited by being added

    @Test
    void addedParticipantsStartPendingAndAreNotifiedButNothingIsSettled() {
        ExpenseResponse e = create(owner, "90.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        assertEquals(ShareStatus.PAID, shareOf(e, owner).status(), "the payer's own share needs no payment");
        assertEquals(SettlementMethod.SELF, shareOf(e, owner).settlementMethod());
        assertEquals(ShareStatus.PENDING, shareOf(e, admin).status());
        assertEquals(ShareStatus.PENDING, shareOf(e, member).status());
        assertEquals(d("0.00"), e.transferredAmount(), "no simulated funds moved at creation");
        assertEquals(d("90.00"), e.recordedAmount());
        assertTrue(e.simulated());
        assertTrue(shareOf(e, admin).ledgerReference() == null);
        verify(f.notifications, atLeastOnce()).notify(any(User.class), any(), anyString(), anyString());
    }

    @Test
    void onlyTheParticipantCanRespondForTheirOwnShare() {
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        String adminShare = shareOf(e, admin).id();
        assertEquals(403, status(() -> f.expenseService.accept(adminShare, member, null)), "another member cannot answer for them");
        assertEquals(403, status(() -> f.expenseService.accept(adminShare, owner, null)), "not even the creator");
        assertEquals(404, status(() -> f.expenseService.accept(adminShare, outsider, null)), "outsiders learn nothing");
        ExpenseResponse after = f.expenseService.accept(adminShare, admin, null);
        assertEquals(ShareStatus.ACCEPTED, shareOf(after, admin).status());
        assertEquals(d("0.00"), after.transferredAmount(), "accepting never debits");
        assertEquals(ShareStatus.ACCEPTED, shareOf(f.expenseService.accept(adminShare, admin, null), admin).status(), "repeat is harmless");
    }

    @Test
    void disputeNeedsAReason_declineIsRecorded_andThePayerCanAskAgain() {
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        String s = shareOf(e, member).id();
        assertEquals(400, status(() -> f.expenseService.dispute(s, member, " ")));
        ExpenseResponse disputed = f.expenseService.dispute(s, member, "I was not there");
        assertEquals(ShareStatus.DISPUTED, shareOf(disputed, member).status());
        assertEquals("DISPUTED", disputed.settlementState());
        assertEquals(403, status(() -> f.expenseService.reopen(s, admin, null)), "only the creator or payer can ask again");
        ExpenseResponse reopened = f.expenseService.reopen(s, owner, "It was the team dinner");
        assertEquals(ShareStatus.PENDING, shareOf(reopened, member).status());
        assertEquals(ShareStatus.DECLINED, shareOf(f.expenseService.decline(s, member, null), member).status());
    }

    // ------------------------------------------------------------ split methods and rounding

    @Test
    void percentageAndItemBasedSplitsAddUpExactlyAndMatchThePreview() {
        CreateExpenseRequest p = req("100.00", SplitType.PERCENTAGE, List.of(pct(owner, "33.34"), pct(admin, "33.33"), pct(member, "33.33")), null);
        PreviewResponse pv = f.expenseService.preview(circleId, owner, p);
        assertEquals(List.of(3334L, 3333L, 3333L), pv.shares().stream().map(PreviewShare::amountMinor).toList());
        ExpenseResponse e = f.expenseService.create(circleId, owner, p);
        assertEquals(pv.shares().stream().map(PreviewShare::amountMinor).toList(), e.shares().stream().map(ExpenseShareResponse::amountMinor).toList());
        assertEquals(d("33.34"), e.shares().get(0).percentage());
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, req("100.00", SplitType.PERCENTAGE,
                List.of(pct(owner, "50"), pct(admin, "49.99")), null))), "percentages must total 100");
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, req("100.00", SplitType.PERCENTAGE,
                List.of(pct(owner, "50.005"), pct(admin, "49.995")), null))), "more than two decimals is refused, never rounded");

        List<ItemInput> items = List.of(new ItemInput("Pizza", d("60.00"), List.of(owner.getId(), admin.getId())),
                new ItemInput("Wine", d("40.00"), List.of(admin.getId())));
        ExpenseResponse i = f.expenseService.create(circleId, owner, req("100.00", SplitType.ITEM_BASED, List.of(eq(owner), eq(admin)), items));
        assertEquals(d("30.00"), shareOf(i, owner).amount());
        assertEquals(d("70.00"), shareOf(i, admin).amount());
        assertEquals(2, i.items().size());
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, req("100.00", SplitType.ITEM_BASED,
                List.of(eq(owner), eq(admin)), List.of(new ItemInput("Pizza", d("60.00"), List.of(owner.getId())))))), "items must add up");
    }

    @Test
    void currencyPrecisionIsEnforced() {
        assertEquals(400, status(() -> create(owner, "10.005", SplitType.EQUAL, eq(owner))));
        assertEquals(400, status(() -> create(owner, "100.00", SplitType.CUSTOM, amt(owner, "33.333"), amt(admin, "66.667"))));
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner,
                new CreateExpenseRequest("x", null, d("10"), "USD", SplitType.EQUAL, null, null, null, List.of(eq(owner)), null))), "currency must match the circle");
    }

    @Test
    void aRetriedCreateWithTheSameKeyReturnsTheFirstExpense() {
        CreateExpenseRequest r = req("50.00", SplitType.EQUAL, List.of(eq(owner), eq(admin)), null);
        ExpenseResponse a = f.expenseService.create(circleId, owner, r, "key-12345678");
        ExpenseResponse b = f.expenseService.create(circleId, owner, r, "key-12345678");
        assertEquals(a.id(), b.id());
        assertEquals(1, f.expenses.size());
        assertEquals(409, status(() -> f.expenseService.create(circleId, owner, req("51.00", SplitType.EQUAL, List.of(eq(owner), eq(admin)), null), "key-12345678")));
    }

    @Test
    void datesAreValidated() {
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, new CreateExpenseRequest("x", null, d("10"), null, SplitType.EQUAL,
                null, LocalDate.now().plusDays(1), null, List.of(eq(owner)), null))));
        assertEquals(400, status(() -> f.expenseService.create(circleId, owner, new CreateExpenseRequest("x", null, d("10"), null, SplitType.EQUAL,
                null, LocalDate.now(), LocalDate.now().minusDays(1), List.of(eq(owner)), null))));
    }

    // ------------------------------------------------------------ group membership and permissions

    @Test
    void membershipAndPermissionsAreEnforced() {
        assertEquals(403, status(() -> create(outsider, "10.00", SplitType.EQUAL, eq(outsider))));
        assertEquals(400, status(() -> create(owner, "10.00", SplitType.EQUAL, eq(outsider))), "participants must be members");
        assertEquals(403, status(() -> f.expenseService.list(circleId, outsider)));
        ExpenseResponse e = create(owner, "10.00", SplitType.EQUAL, eq(owner), eq(member));
        assertEquals(404, status(() -> f.expenseService.get(e.id(), outsider)), "an outsider cannot even see that it exists");
        assertEquals(403, status(() -> f.expenseService.update(circleId, e.id(), member, update(e, "10.00", SplitType.EQUAL, eq(owner)))), "only the creator edits");
        assertEquals(403, status(() -> f.expenseService.cancel(circleId, e.id(), member, null)), "a plain member cannot cancel");
        assertEquals(ExpenseStatus.CANCELLED, f.expenseService.cancel(circleId, e.id(), admin, "duplicate").status(), "a circle admin can");
    }

    @Test
    void aMemberWhoLeavesKeepsTheirHistoryAndCanStillSeeAndAnswerTheirShare() {
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, eq(owner), eq(member));
        f.leave(circleId, member);
        List<ObligationResponse> mine = f.expenseService.obligations(member, false, 50);
        assertEquals(1, mine.size());
        assertTrue(mine.get(0).share().debtorFormerMember(), "flagged as a former member, record kept");
        assertEquals(e.id(), f.expenseService.get(e.id(), member).id(), "participants keep access to their own expense");
        assertEquals(ShareStatus.ACCEPTED, shareOf(f.expenseService.accept(mine.get(0).share().id(), member, null), member).status());
        assertEquals(1, f.expenseService.list(circleId, owner).size(), "the circle still lists it");
        assertEquals(1, f.expenseService.obligations(owner, true, 50).size(), "and the payer still sees what is owed");
    }

    // ------------------------------------------------------------ edits

    @Test
    void anEditThatChangesAnAcceptedShareRequiresFreshAcknowledgementAndIsAudited() {
        ExpenseResponse e = create(owner, "90.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        f.expenseService.accept(shareOf(e, admin).id(), admin, null);
        f.expenseService.accept(shareOf(e, member).id(), member, null);
        ExpenseResponse edited = f.expenseService.update(circleId, e.id(), owner,
                update(e, "100.00", SplitType.CUSTOM, amt(owner, "30.00"), amt(admin, "30.00"), amt(member, "40.00")));
        assertEquals(2, edited.revision());
        assertEquals(ShareStatus.ACCEPTED, shareOf(edited, admin).status(), "an unchanged (30.00 -> 30.00) accepted share keeps its answer");
        assertEquals(ShareStatus.PENDING, shareOf(edited, member).status(), "30.00 -> 40.00 needs acknowledgement again");
        assertTrue(f.expenseService.history(e.id(), owner).stream().anyMatch(h -> h.eventType().equals("EXPENSE_EDITED")));
        assertTrue(f.expenseService.history(e.id(), owner).stream().anyMatch(h -> h.eventType().equals("SHARE_CHANGED")));
    }

    @Test
    void aStaleEditIsRefused() {
        ExpenseResponse e = create(owner, "40.00", SplitType.EQUAL, eq(owner), eq(admin));
        f.expenseService.update(circleId, e.id(), owner, update(e, "50.00", SplitType.EQUAL, eq(owner), eq(admin)));
        assertEquals(409, status(() -> f.expenseService.update(circleId, e.id(), owner, update(e, "60.00", SplitType.EQUAL, eq(owner), eq(admin)))));
    }

    @Test
    void aSettledShareIsNeverSilentlyRewrittenByAnEdit() {
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        f.expenseService.markReceivedExternally(shareOf(e, admin).id(), owner);
        // changing the settled share's amount, or removing that person, is refused
        assertEquals(409, status(() -> f.expenseService.update(circleId, e.id(), owner, update(e, "90.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member)))));
        assertEquals(409, status(() -> f.expenseService.update(circleId, e.id(), owner, update(e, "40.00", SplitType.EQUAL, eq(owner), eq(member)))));
        // a change that leaves the settled share at 20.00 is fine
        ExpenseResponse ok = f.expenseService.update(circleId, e.id(), owner,
                update(e, "70.00", SplitType.CUSTOM, amt(owner, "20.00"), amt(admin, "20.00"), amt(member, "30.00")));
        assertEquals(ShareStatus.PAID, shareOf(ok, admin).status());
        assertEquals(d("20.00"), shareOf(ok, admin).amount());
        assertEquals(ShareStatus.PENDING, shareOf(ok, member).status());
    }

    @Test
    void removingAParticipantKeepsTheirRowForHistory() {
        ExpenseResponse e = create(owner, "60.00", SplitType.EQUAL, eq(owner), eq(admin), eq(member));
        ExpenseResponse edited = f.expenseService.update(circleId, e.id(), owner, update(e, "60.00", SplitType.EQUAL, eq(owner), eq(admin)));
        assertEquals(3, edited.shares().size());
        assertEquals(ShareStatus.CANCELLED, shareOf(edited, member).status());
        assertEquals(d("30.00"), edited.outstandingAmount(), "removed shares are not owed");
        assertTrue(f.expenseService.history(e.id(), owner).stream().anyMatch(h -> h.eventType().equals("SHARE_REMOVED")));
    }

    // ------------------------------------------------------------ cancellation

    @Test
    void aSettledExpenseCannotBeCancelledUntilTheSettlementIsRetracted() {
        ExpenseResponse e = create(owner, "40.00", SplitType.EQUAL, eq(owner), eq(admin));
        f.expenseService.markReceivedExternally(shareOf(e, admin).id(), owner);
        assertEquals(409, status(() -> f.expenseService.cancel(circleId, e.id(), owner, "oops")));
        f.expenseService.retractReceivedExternally(shareOf(e, admin).id(), owner);
        ExpenseResponse c = f.expenseService.cancel(circleId, e.id(), owner, "oops");
        assertEquals(ExpenseStatus.CANCELLED, c.status());
        assertEquals("CANCELLED", c.settlementState());
        assertEquals(ShareStatus.CANCELLED, shareOf(c, admin).status());
        assertEquals(409, status(() -> f.expenseService.accept(shareOf(e, admin).id(), admin, null)), "a cancelled expense takes no responses");
        assertEquals(409, status(() -> f.expenseService.update(circleId, e.id(), owner, update(e, "40.00", SplitType.EQUAL, eq(owner), eq(admin)))));
        assertFalse(f.expenseService.history(e.id(), owner).isEmpty(), "cancelling keeps the history");
        assertEquals(c.id(), f.expenseService.cancel(circleId, e.id(), owner, null).id(), "cancelling twice is harmless");
    }

    @Test
    void overdueIsReportedOnceTheDueDatePasses() {
        CreateExpenseRequest r = new CreateExpenseRequest("Rent", null, d("40.00"), null, SplitType.EQUAL, null, LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(1), List.of(eq(owner), eq(admin)), null);
        ExpenseResponse e = f.expenseService.create(circleId, owner, r);
        assertTrue(shareOf(e, admin).overdue());
        assertEquals("OVERDUE", e.settlementState());
        assertFalse(shareOf(e, owner).overdue(), "the payer's own share is never overdue");
    }

    @Test
    void historyHidesLedgerReferencesFromPeopleWhoAreNotParties() {
        ExpenseResponse e = create(owner, "40.00", SplitType.EQUAL, eq(owner), eq(admin));
        f.events.add(com.momocircle.domain.ExpenseEvent.of(e.id(), null, admin.getId(), "SHARE_PAID_LEDGER", 1, "x", "SIM-ABC"));
        assertEquals("SIM-ABC", f.expenseService.history(e.id(), owner).stream().filter(h -> h.eventType().equals("SHARE_PAID_LEDGER")).findFirst().orElseThrow().ledgerReference());
        assertNull(f.expenseService.history(e.id(), member).stream().filter(h -> h.eventType().equals("SHARE_PAID_LEDGER")).findFirst().orElseThrow().ledgerReference());
    }
}
