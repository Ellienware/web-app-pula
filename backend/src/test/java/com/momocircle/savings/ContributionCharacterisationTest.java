package com.momocircle.savings;

import static com.momocircle.savings.SavingsFixture.d;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.momocircle.domain.PaymentRequest;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.PaymentRequestStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.ContributionDtos.*;
import com.momocircle.exception.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE 2A: current contribution and payment-request behaviour. Payments go through the REAL sandbox payment
 * provider: payer phone ending 0000 fails, 0001 stays processing, anything else succeeds.
 */
class ContributionCharacterisationTest {

    private SavingsFixture f;
    private User owner, admin, member, broke, slow, outsider;
    private String circleId;

    @BeforeEach
    void setUp() {
        f = new SavingsFixture();
        owner = f.user("Owner"); admin = f.user("Admin"); member = f.user("Member");
        broke = f.user("Broke", "0000"); slow = f.user("Slow", "0001"); outsider = f.user("Outsider");
        circleId = f.newCircle(owner, "Savers", CircleType.SAVINGS_GOAL, d("1000.00")).id();
        f.join(circleId, admin, CircleRole.ADMIN);
        f.join(circleId, member, CircleRole.MEMBER);
        f.join(circleId, broke, CircleRole.MEMBER);
        f.join(circleId, slow, CircleRole.MEMBER);
    }

    private ContributionResponse pay(User who, String amount) {
        return f.contributionService.contribute(circleId, who, new ContributeRequest(d(amount), null, null, null));
    }

    private ContributionResponse pay(User who, String amount, String paymentRequestId, String goalId, String key) {
        return f.contributionService.contribute(circleId, who, new ContributeRequest(d(amount), paymentRequestId, goalId, key));
    }

    private int status(Runnable r) { return assertThrows(ApiException.class, r::run).getStatus().value(); }

    // ------------------------------------------------------------------ a valid contribution

    @Test
    void aValidContributionIsPersistedWithASuccessfulPaymentActivityAndReceipt() {
        ContributionResponse r = pay(member, "250.00");
        assertEquals(PaymentStatus.SUCCESSFUL, r.status());
        assertEquals(d("250.00"), r.amount());
        assertEquals("ZAR", r.currency());
        assertEquals(member.getId(), r.contributorId());
        assertTrue(r.providerReference().startsWith("sbx_"));
        assertEquals(1, f.contributions.size());
        assertEquals(1, f.payments.size());
        assertEquals(1, f.activityCount(ActivityType.CONTRIBUTION_MADE));
        verify(f.receipts).issue(any(), any(), anyString(), anyString());
        assertEquals(25, f.circleService.get(circleId, owner).progressPercent(), "250 of the 1000 circle target");
    }

    @Test
    void anyMemberRoleMayContributeIncludingTheOwner() {
        pay(owner, "10.00"); pay(admin, "10.00"); pay(member, "10.00");
        assertEquals(3, f.contributions.size());
    }

    // ------------------------------------------------------------------ validation and authorisation

    @Test
    void invalidAmountsAreRefusedBeforeAnyPaymentIsCreated() {
        for (String bad : new String[] {"0", "-5", "10.005", "10000001"}) {
            assertEquals(400, status(() -> pay(member, bad)), bad);
        }
        assertTrue(f.payments.isEmpty(), "validation happens before the payment reservation");
        assertTrue(f.contributions.isEmpty());
    }

    @Test
    void nonMembersCannotContributeAndNothingIsCreated() {
        assertEquals(403, status(() -> pay(outsider, "10.00")));
        assertEquals(403, status(() -> f.contributionService.contribute("no-such-circle", member, new ContributeRequest(d("10"), null, null, null))));
        assertTrue(f.payments.isEmpty());
    }

    // ------------------------------------------------------------------ payment statuses

    @Test
    void aFailedPaymentIsStillRecordedAsAContributionWithStatusFailedAndCountsForNothing() {
        ContributionResponse r = pay(broke, "100.00");
        assertEquals(PaymentStatus.FAILED, r.status());
        assertEquals(1, f.contributions.size());
        assertEquals(0, f.activityCount(ActivityType.CONTRIBUTION_MADE));
        verify(f.receipts, never()).issue(any(), any(), anyString(), anyString());
        assertEquals(0, f.circleService.get(circleId, owner).progressPercent());
    }

    @Test
    void aProcessingPaymentIsRecordedPendingAndNotCountedUntilResolved() {
        ContributionResponse r = pay(slow, "100.00");
        assertEquals(PaymentStatus.PROCESSING, r.status());
        assertEquals(0, f.circleService.get(circleId, owner).progressPercent());
        assertEquals(0, f.activityCount(ActivityType.CONTRIBUTION_MADE));
        // Later resolution (the existing callback path) records the activity and issues the receipt.
        var contribution = f.contributions.get(0);
        contribution.getPayment().transitionTo(PaymentStatus.SUCCESSFUL);
        f.contributionService.applyResolvedSuccess(contribution);
        assertEquals(1, f.activityCount(ActivityType.CONTRIBUTION_MADE));
        assertEquals(10, f.circleService.get(circleId, owner).progressPercent());
    }

    // ------------------------------------------------------------------ duplicates and idempotency

    @Test
    void theSameIdempotencyKeyTwiceChargesAndRecordsOnce() {
        ContributionResponse a = pay(member, "100.00", null, null, "key-1");
        ContributionResponse b = pay(member, "100.00", null, null, "key-1");
        assertEquals(a.id(), b.id());
        assertEquals(1, f.contributions.size());
        assertEquals(1, f.payments.size());
    }

    @Test
    void theOriginalAmountWinsWhenARetryUsesTheSameKeyWithADifferentAmount() {
        pay(member, "100.00", null, null, "key-1");
        ContributionResponse again = pay(member, "999.00", null, null, "key-1");
        assertEquals(d("100.00"), again.amount());
        assertEquals(1, f.contributions.size());
    }

    @Test
    void withoutAKeyEveryCallIsASeparateContributionThereIsNoDuplicateDetection() {
        pay(member, "100.00"); pay(member, "100.00");
        assertEquals(2, f.contributions.size());
        assertEquals(2, f.payments.size());
    }

    @Test
    void anotherMemberReusingAKeyCannotSeeTheFirstMembersContribution() {
        pay(member, "100.00", null, null, "shared-key");

        assertEquals(404,
                status(() -> pay(admin, "55.00", null, null, "shared-key")));

        assertEquals(1, f.contributions.size(),
                "reusing the key must not create a second contribution");
        assertEquals(1, f.payments.size(),
                "reusing the key must not create a second payment");
    }

    @Test
    void aKeyUsedInOneCircleCannotBeReplayedInAnotherAndIsRefusedAsConflict() {
        pay(member, "100.00", null, null, "key-x");
        String other = f.newCircle(member, "Other", CircleType.EXPENSE_POOL, null).id();
        assertEquals(409, status(() -> f.contributionService.contribute(other, member, new ContributeRequest(d("100.00"), null, null, "key-x"))));
    }

    @Test
    void FINDING_theKeyIsReservedBeforeTheGoalIsValidatedSoAWrongGoalLeavesAnOrphanedReservation() {
        assertEquals(404, status(() -> pay(member, "100.00", null, "no-such-goal", "key-g")));
        assertEquals(1, f.payments.size(), "FINDING: a PROCESSING payment was reserved before the goal check failed");
        assertEquals(0, f.contributions.size());
        // The retry with a valid request now meets the leftover reservation and is refused. (In production the
        // reservation commits in its own transaction; NOT VERIFIED here because the fixture has no transactions.)
        assertEquals(409, status(() -> pay(member, "100.00", null, null, "key-g")));
    }

    // ------------------------------------------------------------------ history

    @Test
    void historyIsNewestFirstIncludesFailuresAndIsVisibleToEveryMember() {
        pay(member, "10.00"); pay(broke, "20.00"); pay(admin, "30.00");
        var list = f.contributionService.list(circleId, member);
        assertEquals(3, list.size());
        assertEquals(d("30.00"), list.get(0).amount());
        assertEquals(PaymentStatus.FAILED, list.get(1).status());
        assertTrue(list.stream().anyMatch(c -> c.contributorId().equals(admin.getId())),
                "a plain member can see everyone's contributions (no own-only filter exists)");
        assertEquals(403, status(() -> f.contributionService.list(circleId, outsider)));
    }

    // ------------------------------------------------------------------ payment requests

    private PaymentRequestResponse request(User actor, String amount) {
        return f.contributionService.createPaymentRequest(circleId, actor, new CreatePaymentRequestRequest(d(amount), "rent", LocalDate.now().plusDays(7)));
    }

    @Test
    void adminsCreateOpenPaymentRequestsMembersCannot() {
        PaymentRequestResponse r = request(admin, "500.00");
        assertEquals(PaymentRequestStatus.OPEN, r.status());
        assertEquals(d("500.00"), r.amountPerMember());
        assertEquals(1, f.activityCount(ActivityType.PAYMENT_REQUEST_CREATED));
        assertEquals(403, status(() -> request(member, "500.00")));
        assertEquals(403, status(() -> request(outsider, "500.00")));
        assertEquals(400, status(() -> request(admin, "0")));
        assertEquals(400, status(() -> request(admin, "-1")));
    }

    @Test
    void identicalPaymentRequestsAreNotDeduplicatedAndAPastDueDateIsAccepted() {
        request(admin, "100.00"); request(admin, "100.00");
        assertEquals(2, f.paymentRequests.size());
        var past = f.contributionService.createPaymentRequest(circleId, admin, new CreatePaymentRequestRequest(d("5.00"), null, LocalDate.now().minusDays(30)));
        assertEquals(PaymentRequestStatus.OPEN, past.status());
    }

    @Test
    void aSuccessfulContributionAgainstARequestFulfilsItButAFailedOneDoesNot() {
        String id = request(admin, "100.00").id();
        pay(broke, "100.00", id, null, null);
        assertEquals(PaymentRequestStatus.OPEN, f.paymentRequests.get(id).getStatus());
        pay(member, "100.00", id, null, null);
        assertEquals(PaymentRequestStatus.FULFILLED, f.paymentRequests.get(id).getStatus());
    }

    @Test
    void FINDING_oneMembersPaymentFulfilsTheRequestForEveryoneAndTheAmountIsNotChecked() {
        String id = request(admin, "100.00").id();
        pay(member, "1.00", id, null, null);
        assertEquals(PaymentRequestStatus.FULFILLED, f.paymentRequests.get(id).getStatus(),
                "FINDING: R1 from one member marks a R100-per-member request fulfilled for the whole group");
    }

    @Test
    void FINDING_aCancelledRequestBecomesFulfilledIfSomeoneStillPaysAgainstIt() {
        String id = request(admin, "100.00").id();
        f.contributionService.cancelPaymentRequest(circleId, id, admin);
        assertEquals(PaymentRequestStatus.CANCELLED, f.paymentRequests.get(id).getStatus());
        pay(member, "100.00", id, null, null);
        assertEquals(PaymentRequestStatus.FULFILLED, f.paymentRequests.get(id).getStatus(), "FINDING: terminal status is overwritten");
    }

    @Test
    void cancelAndRemindRulesAndTheirAuthorisation() {
        String id = request(admin, "100.00").id();
        assertEquals(403, status(() -> f.contributionService.cancelPaymentRequest(circleId, id, member)));
        assertEquals(403, status(() -> f.contributionService.remindPaymentRequest(circleId, id, member)));
        f.contributionService.remindPaymentRequest(circleId, id, admin);
        assertEquals(1, f.activityCount(ActivityType.PAYMENT_REQUEST_REMINDED));
        assertEquals(PaymentRequestStatus.CANCELLED, f.contributionService.cancelPaymentRequest(circleId, id, owner).status());
        assertEquals(400, status(() -> f.contributionService.cancelPaymentRequest(circleId, id, admin)), "only open requests");
        assertEquals(400, status(() -> f.contributionService.remindPaymentRequest(circleId, id, admin)), "only open requests");
        assertEquals(404, status(() -> f.contributionService.cancelPaymentRequest(circleId, "nope", admin)));
    }

    @Test
    void openRequestsPastTheirDueDateExpireOthersAreUntouched() {
        String overdue = f.contributionService.createPaymentRequest(circleId, admin,
                new CreatePaymentRequestRequest(d("10.00"), null, LocalDate.now().minusDays(1))).id();
        String future = request(admin, "10.00").id();
        String undated = f.contributionService.createPaymentRequest(circleId, admin, new CreatePaymentRequestRequest(d("10.00"), null, null)).id();
        f.contributionService.expireOverdueRequests();
        assertEquals(PaymentRequestStatus.EXPIRED, f.paymentRequests.get(overdue).getStatus());
        assertEquals(PaymentRequestStatus.OPEN, f.paymentRequests.get(future).getStatus());
        assertEquals(PaymentRequestStatus.OPEN, f.paymentRequests.get(undated).getStatus());
        assertEquals(1, f.activityCount(ActivityType.PAYMENT_REQUEST_EXPIRED));
    }

    @Test
    void paymentRequestsAreListedForMembersOnly() {
        request(admin, "10.00");
        assertEquals(1, f.contributionService.listPaymentRequests(circleId, member).size());
        assertEquals(403, status(() -> f.contributionService.listPaymentRequests(circleId, outsider)));
    }
}
