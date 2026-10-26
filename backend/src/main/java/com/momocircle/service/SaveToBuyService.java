package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.SaveToBuyDtos.*;
import com.momocircle.dto.StokvelDtos.CreateStokvelRequestRequest;
import com.momocircle.dto.StokvelDtos.RequestView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.stokvel.StokvelPermissions;
import com.momocircle.stokvel.StokvelPermissions.Action;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Save-to-Buy: a TRACKING link between a savings goal and a purchase made through the EXISTING marketplace checkout.
 *
 * - No wallet, balance, ledger or payment provider. Savings are never "spent": the buyer pays at normal checkout.
 * - Goal reached never triggers a purchase; a person (or, for a stokvel, the group) must confirm/approve first.
 * - Stokvel requests REUSE the stokvel approval engine (StokvelRequestService); this class never counts votes itself.
 * - The marketplace order is created by the existing checkout and only REFERENCES the request.
 */
@Service
public class SaveToBuyService {

    static final Duration PERSONAL_VALIDITY = Duration.ofDays(30);
    private static final Set<SaveToBuyVertical> STOKVEL_VERTICALS =
            EnumSet.of(SaveToBuyVertical.RETAIL, SaveToBuyVertical.FOOD, SaveToBuyVertical.STOCK, SaveToBuyVertical.SERVICE);

    private final SaveToBuyRequestRepository requests;
    private final CircleGoalRepository goals;
    private final CircleMembershipRepository memberships;
    private final StokvelProfileRepository stokvelProfiles;
    private final StokvelRequestRepository stokvelRequests;
    private final StokvelRequestVoteRepository votes;
    private final StokvelRequestService stokvelRequestService;
    private final ContributionRepository contributions;
    private final ActivityService activityService;
    private final FinancialValidationService validation;

    public SaveToBuyService(SaveToBuyRequestRepository requests, CircleGoalRepository goals,
                            CircleMembershipRepository memberships, StokvelProfileRepository stokvelProfiles,
                            StokvelRequestRepository stokvelRequests, StokvelRequestVoteRepository votes,
                            StokvelRequestService stokvelRequestService, ContributionRepository contributions,
                            ActivityService activityService, FinancialValidationService validation) {
        this.requests = requests;
        this.goals = goals;
        this.memberships = memberships;
        this.stokvelProfiles = stokvelProfiles;
        this.stokvelRequests = stokvelRequests;
        this.votes = votes;
        this.stokvelRequestService = stokvelRequestService;
        this.contributions = contributions;
        this.activityService = activityService;
        this.validation = validation;
    }

    // ------------------------------------------------------------------ goals

    @Transactional
    public EligibleGoal designateGoal(String goalId, User actor, DesignateGoalRequest req) {
        CircleGoal goal = visibleGoal(goalId, actor);
        CircleMembership m = membership(goal, actor);
        boolean stokvel = stokvelProfiles.findByCircleId(goal.getCircle().getId()).isPresent();
        if (stokvel) {
            if (!StokvelPermissions.allows(m.getRole(), Action.UPDATE_SETTINGS, false)) {
                throw ApiException.forbidden("Only a stokvel owner or admin can designate a goal for a purchase");
            }
            if (!STOKVEL_VERTICALS.contains(req.vertical())) {
                throw ApiException.badRequest("Stokvel goals can be designated for retail, food, stock or service purchases");
            }
        } else {
            requirePersonalGoal(goal, actor);
        }
        if (goal.getStatus() == GoalStatus.CLOSED) {
            throw ApiException.conflict("This goal is closed");
        }
        goal.setPurchaseVertical(req.vertical());
        goal.setPurchasePurpose(req.purpose() == null || req.purpose().isBlank() ? null : req.purpose().trim());
        goals.save(goal);
        return eligible(goal, actor, m);
    }

    @Transactional(readOnly = true)
    public List<EligibleGoal> eligibleGoals(User user) {
        return eligibleGoals(user, false);
    }

    /** {@code includeUndesignated}: also list goals not yet set up for a purchase, so the UI can offer to set them up. */
    @Transactional(readOnly = true)
    public List<EligibleGoal> eligibleGoals(User user, boolean includeUndesignated) {
        Map<String, CircleMembership> mine = memberships.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(m -> m.getCircle().getId(), m -> m, (a, b) -> a));
        if (mine.isEmpty()) {
            return List.of();
        }
        List<CircleGoal> found = includeUndesignated
                ? goals.findByCircleIdInOrderByCreatedAtDesc(mine.keySet())
                : goals.findByCircleIdInAndPurchaseVerticalIsNotNullOrderByCreatedAtDesc(mine.keySet());
        return found.stream()
                .filter(g -> g.getStatus() != GoalStatus.CLOSED)
                .map(g -> eligible(g, user, mine.get(g.getCircle().getId())))
                .toList();
    }

    // ------------------------------------------------------------------ requests

    @Transactional
    public SaveToBuyView create(User actor, CreateSaveToBuyRequest req) {
        String key = blankToNull(req.idempotencyKey());
        if (key != null) {
            var existing = requests.findByOwnerIdAndIdempotencyKey(actor.getId(), key);
            if (existing.isPresent()) {
                return view(existing.get(), actor);
            }
        }
        CircleGoal goal = visibleGoal(req.goalId(), actor);
        CircleMembership m = membership(goal, actor);
        if (goal.getPurchaseVertical() == null) {
            throw ApiException.badRequest("This goal has not been set up for a purchase yet");
        }
        SaveToBuyVertical vertical = req.vertical() != null ? req.vertical() : goal.getPurchaseVertical();
        if (goal.getPurchaseVertical() != SaveToBuyVertical.GENERAL && goal.getPurchaseVertical() != vertical) {
            throw ApiException.badRequest("This goal is set up for " + goal.getPurchaseVertical().name().toLowerCase()
                    + " purchases");
        }
        if (goal.getStatus() == GoalStatus.CLOSED) {
            throw ApiException.conflict("This goal is closed");
        }
        validation.requireValidAmount(req.requestedAmount(), "requestedAmount");
        String purpose = req.purpose().trim();
        if (purpose.isEmpty()) {
            throw ApiException.badRequest("Purpose is required");
        }

        boolean stokvel = stokvelProfiles.findByCircleId(goal.getCircle().getId()).isPresent();
        User buyer;
        SaveToBuyScope scope;
        if (stokvel) {
            scope = SaveToBuyScope.STOKVEL;
            if (!StokvelPermissions.allows(m.getRole(), Action.CREATE_REQUEST, false)) {
                throw ApiException.forbidden("Your role cannot create purchase requests in this stokvel");
            }
            if (!STOKVEL_VERTICALS.contains(vertical)) {
                throw ApiException.badRequest("Stokvel purchases can be retail, food, stock or service");
            }
            if (blankToNull(req.namedBuyerUserId()) == null) {
                throw ApiException.badRequest("A stokvel purchase must name the buyer");
            }
            CircleMembership bm = memberships.findByCircleIdAndUserId(goal.getCircle().getId(), req.namedBuyerUserId())
                    .orElseThrow(() -> ApiException.badRequest("The named buyer must be a member of this stokvel"));
            buyer = bm.getUser();
        } else {
            scope = SaveToBuyScope.PERSONAL;
            requirePersonalGoal(goal, actor);
            if (blankToNull(req.namedBuyerUserId()) != null && !req.namedBuyerUserId().equals(actor.getId())) {
                throw ApiException.badRequest("A personal purchase is always bought by you");
            }
            buyer = actor;
        }
        if (requests.existsOpenDuplicate(goal.getId(), actor.getId(), vertical, req.requestedAmount(), purpose,
                EnumSet.of(SaveToBuyStatus.DRAFT, SaveToBuyStatus.PENDING_APPROVAL, SaveToBuyStatus.APPROVED,
                        SaveToBuyStatus.CHECKOUT_STARTED))) {
            throw ApiException.conflict("An identical Save-to-Buy request is already open");
        }

        SaveToBuyRequest r = new SaveToBuyRequest();
        r.setOwner(actor);
        r.setGoal(goal);
        r.setCircle(goal.getCircle());
        r.setScope(scope);
        r.setVertical(vertical);
        r.setPurpose(purpose);
        r.setRequestedAmount(req.requestedAmount());
        r.setCurrency(goal.getCircle().getCurrency());
        r.setGoalTargetSnapshot(goal.getTargetAmount());
        r.setNamedBuyer(buyer);
        r.setStatus(SaveToBuyStatus.DRAFT);
        r.setIdempotencyKey(key);
        r = requests.save(r);
        activityService.record(goal.getCircle(), actor, ActivityType.SAVE_TO_BUY_CREATED,
                actor.getFullName() + " started a purchase plan for " + purpose);
        return view(r, actor);
    }

    /** Personal: the owner explicitly confirms. Never automatic, and only once the goal has been reached. */
    @Transactional
    public SaveToBuyView confirm(String id, User actor) {
        SaveToBuyRequest r = lockVisible(id, actor);
        if (r.getScope() != SaveToBuyScope.PERSONAL) {
            throw ApiException.badRequest("A stokvel purchase is submitted for group approval instead");
        }
        if (!r.getOwner().getId().equals(actor.getId())) {
            throw ApiException.notFound("Save-to-Buy request not found");
        }
        refresh(r);
        if (r.getStatus() != SaveToBuyStatus.DRAFT) {
            throw ApiException.conflict("This request is already " + r.getStatus().name().toLowerCase());
        }
        requireGoalReached(r);
        r.setStatus(SaveToBuyStatus.APPROVED);
        r.setExpiresAt(Instant.now().plus(PERSONAL_VALIDITY));
        requests.save(r);
        activityService.record(r.getCircle(), actor, ActivityType.SAVE_TO_BUY_APPROVED,
                actor.getFullName() + " confirmed a purchase for " + r.getPurpose());
        return view(r, actor);
    }

    /** Stokvel: hands the request to the stokvel's own approval engine. */
    @Transactional
    public SaveToBuyView submit(String id, User actor) {
        SaveToBuyRequest r = lockVisible(id, actor);
        if (r.getScope() != SaveToBuyScope.STOKVEL) {
            throw ApiException.badRequest("A personal purchase is confirmed, not submitted for approval");
        }
        if (!r.getOwner().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the member who created this request can submit it");
        }
        if (r.getStatus() == SaveToBuyStatus.PENDING_APPROVAL) {
            return view(r, actor); // idempotent replay
        }
        if (r.getStatus() != SaveToBuyStatus.DRAFT) {
            throw ApiException.conflict("This request is already " + r.getStatus().name().toLowerCase());
        }
        requireGoalReached(r);
        StokvelRequestType type = r.getVertical() == SaveToBuyVertical.STOCK
                ? StokvelRequestType.STOCK_PURCHASE : StokvelRequestType.MARKETPLACE_PURCHASE;
        // Approval rules, eligible voters, no self-approval and funds checks all live in the stokvel engine.
        RequestView sr = stokvelRequestService.create(r.getCircle().getId(), actor, new CreateStokvelRequestRequest(
                type, r.getRequestedAmount(), r.getPurpose(), r.getNamedBuyer().getId(), r.getGoal().getId(),
                "s2b:" + r.getId()));
        r.setStokvelRequest(stokvelRequests.findById(sr.id()).orElseThrow());
        r.setStatus(SaveToBuyStatus.PENDING_APPROVAL);
        r.setExpiresAt(sr.expiresAt());
        requests.save(r);
        return view(r, actor);
    }

    @Transactional
    public SaveToBuyView cancel(String id, User actor) {
        SaveToBuyRequest r = lockVisible(id, actor);
        refresh(r);
        boolean owner = r.getOwner().getId().equals(actor.getId());
        if (!owner) {
            CircleMembership m = memberships.findByCircleIdAndUserId(r.getCircle().getId(), actor.getId()).orElse(null);
            if (r.getScope() != SaveToBuyScope.STOKVEL || m == null
                    || !StokvelPermissions.allows(m.getRole(), Action.CANCEL_ANY_REQUEST, false)) {
                throw ApiException.forbidden("Only the request owner can cancel it");
            }
        }
        if (r.getStatus() == SaveToBuyStatus.CHECKOUT_STARTED) {
            throw ApiException.conflict("Checkout has started; cancel the order instead");
        }
        if (!r.getStatus().isOpen()) {
            throw ApiException.conflict("This request is already " + r.getStatus().name().toLowerCase());
        }
        if (r.getStokvelRequest() != null) {
            StokvelRequest sr = r.getStokvelRequest();
            if (sr.getStatus() == StokvelRequestStatus.PENDING || sr.getStatus() == StokvelRequestStatus.APPROVED) {
                stokvelRequestService.cancel(r.getCircle().getId(), sr.getId(), actor);
            }
        }
        r.setStatus(SaveToBuyStatus.CANCELLED);
        requests.save(r);
        activityService.record(r.getCircle(), actor, ActivityType.SAVE_TO_BUY_CANCELLED,
                actor.getFullName() + " cancelled a purchase plan for " + r.getPurpose());
        return view(r, actor);
    }

    /**
     * Taxi / general purposes continue in an existing flow (e.g. PULA Ride) that Save-to-Buy does not touch. The owner
     * records that they used the confirmed request. This is a self-declaration, not a verified payment.
     */
    @Transactional
    public SaveToBuyView complete(String id, User actor, CompleteRequest req) {
        SaveToBuyRequest r = lockVisible(id, actor);
        refresh(r);
        if (r.getVertical() != SaveToBuyVertical.TAXI && r.getVertical() != SaveToBuyVertical.GENERAL) {
            throw ApiException.badRequest("This purchase is completed through marketplace checkout");
        }
        if (!r.getNamedBuyer().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the named buyer can complete this request");
        }
        if (r.getStatus() != SaveToBuyStatus.APPROVED) {
            throw ApiException.conflict("Only a confirmed request can be completed (status is "
                    + r.getStatus().name().toLowerCase() + ")");
        }
        r.setStatus(SaveToBuyStatus.PURCHASED);
        r.setOrderKind(RelatedOrderKind.EXTERNAL);
        r.setCompletionReference(req.reference().trim());
        r.setPurchasedAt(Instant.now());
        requests.save(r);
        activityService.record(r.getCircle(), actor, ActivityType.SAVE_TO_BUY_PURCHASED,
                actor.getFullName() + " marked a purchase as done: " + r.getPurpose());
        return view(r, actor);
    }

    @Transactional
    public SaveToBuyView get(String id, User viewer) {
        SaveToBuyRequest r = loadVisible(id, viewer);
        refresh(r);
        return view(r, viewer);
    }

    @Transactional
    public List<SaveToBuyView> listMine(User viewer) {
        return requests.findByOwnerIdAndScopeOrderByCreatedAtDesc(viewer.getId(), SaveToBuyScope.PERSONAL).stream()
                .peek(this::refresh).map(r -> view(r, viewer)).toList();
    }

    @Transactional
    public List<SaveToBuyView> listForCircle(String circleId, User viewer) {
        if (!memberships.existsByCircleIdAndUserId(circleId, viewer.getId())) {
            throw ApiException.forbidden("You are not a member of this circle");
        }
        return requests.findByCircleIdOrderByCreatedAtDesc(circleId).stream()
                .filter(r -> r.getScope() == SaveToBuyScope.STOKVEL)
                .peek(this::refresh).map(r -> view(r, viewer)).toList();
    }

    /** "Which goal motivated this purchase?" - visible to the buyer, the owner and stokvel members. */
    @Transactional
    public SaveToBuyView forOrder(RelatedOrderKind kind, String orderId, User viewer) {
        SaveToBuyRequest r = requests.findByOrderKindAndOrderId(kind, orderId)
                .orElseThrow(() -> ApiException.notFound("No Save-to-Buy request for this order"));
        return get(r.getId(), viewer);
    }

    // ------------------------------------------------------------------ checkout integration (called by the existing checkout)

    /**
     * Runs BEFORE the order is created. Returns null when no request id was supplied (ordinary checkout, unchanged).
     * Throws if the request is not approved, belongs to someone else, is for another vertical, or the total is above
     * what a stokvel approved. A personal purchase may cost more than the goal: the checkout charges the real total.
     */
    @Transactional
    public SaveToBuyRequest validateForCheckout(User buyer, String requestId, SaveToBuyVertical vertical,
                                                BigDecimal total, String checkoutKey) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        SaveToBuyRequest r = requests.findForUpdate(requestId.trim())
                .orElseThrow(() -> ApiException.notFound("Save-to-Buy request not found"));
        if (!r.getNamedBuyer().getId().equals(buyer.getId())) {
            boolean member = memberships.existsByCircleIdAndUserId(r.getCircle().getId(), buyer.getId());
            if (!member) {
                throw ApiException.notFound("Save-to-Buy request not found");
            }
            throw ApiException.forbidden("Only the named buyer can check out with this request");
        }
        refresh(r);
        if (r.getStatus() == SaveToBuyStatus.CHECKOUT_STARTED && checkoutKey != null
                && checkoutKey.equals(r.getCheckoutKey())) {
            return r; // replay of the same checkout
        }
        if (r.getStatus() != SaveToBuyStatus.APPROVED) {
            throw ApiException.conflict("This Save-to-Buy request is " + r.getStatus().name().toLowerCase()
                    + " and cannot be used at checkout");
        }
        if (r.getVertical() != vertical) {
            throw ApiException.badRequest("This request is for " + r.getVertical().name().toLowerCase()
                    + " purchases");
        }
        if (total != null) {
            requireWithinApproval(r, total);
        }
        return r;
    }

    /** A stokvel purchase may not exceed the approved amount. Personal purchases are charged the real total. */
    public void requireWithinApproval(SaveToBuyRequest r, BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            throw ApiException.badRequest("Invalid checkout total");
        }
        if (r.getScope() == SaveToBuyScope.STOKVEL && total.compareTo(r.getRequestedAmount()) > 0) {
            throw ApiException.badRequest("The order total is higher than the amount the stokvel approved");
        }
    }

    /** Runs right after the order row exists (still unpaid). Records the reference; moves nothing. */
    @Transactional
    public void attachOrder(SaveToBuyRequest r, RelatedOrderKind kind, String orderId, String checkoutKey) {
        if (r == null || (r.getStatus() == SaveToBuyStatus.CHECKOUT_STARTED && orderId.equals(r.getOrderId()))) {
            return;
        }
        r.setStatus(SaveToBuyStatus.CHECKOUT_STARTED);
        r.setOrderKind(kind);
        r.setOrderId(orderId);
        r.setCheckoutKey(checkoutKey);
        requests.save(r);
    }

    /** The order was paid. Returns the stokvel request id (or null) so the caller can stamp it on the order. */
    @Transactional
    public String onOrderPaid(RelatedOrderKind kind, String orderId, BigDecimal orderTotal) {
        SaveToBuyRequest r = requests.findByOrderKindAndOrderId(kind, orderId).orElse(null);
        if (r == null || r.getStatus() != SaveToBuyStatus.CHECKOUT_STARTED) {
            return null;
        }
        r.setStatus(SaveToBuyStatus.PURCHASED);
        r.setPurchasedAmount(orderTotal);
        r.setPurchasedAt(Instant.now());
        requests.save(r);
        activityService.record(r.getCircle(), r.getNamedBuyer(), ActivityType.SAVE_TO_BUY_PURCHASED,
                r.getNamedBuyer().getFullName() + " bought: " + r.getPurpose());
        if (r.getStokvelRequest() != null) {
            stokvelRequestService.markExecutedByPurchase(r.getStokvelRequest().getId(), kind, orderId);
            return r.getStokvelRequest().getId();
        }
        return null;
    }

    /** The payment failed or the order was cancelled before payment: the request can be used again. */
    @Transactional
    public void onOrderFailed(RelatedOrderKind kind, String orderId) {
        SaveToBuyRequest r = requests.findByOrderKindAndOrderId(kind, orderId).orElse(null);
        if (r == null || r.getStatus() != SaveToBuyStatus.CHECKOUT_STARTED) {
            return;
        }
        r.setStatus(SaveToBuyStatus.APPROVED);
        r.setOrderKind(null);
        r.setOrderId(null);
        r.setCheckoutKey(null);
        requests.save(r);
    }

    // ------------------------------------------------------------------ helpers

    /** Pulls the status in line with the stokvel decision and with expiry. Cheap and idempotent. */
    void refresh(SaveToBuyRequest r) {
        SaveToBuyStatus before = r.getStatus();
        Instant now = Instant.now();
        StokvelRequest sr = r.getStokvelRequest();
        if (sr != null && (before == SaveToBuyStatus.PENDING_APPROVAL || before == SaveToBuyStatus.APPROVED)) {
            switch (sr.getStatus()) {
                case APPROVED -> {
                    if (before == SaveToBuyStatus.PENDING_APPROVAL) {
                        r.setStatus(SaveToBuyStatus.APPROVED);
                        r.setExpiresAt(sr.getExpiresAt());
                    }
                }
                case REJECTED, CANCELLED -> r.setStatus(SaveToBuyStatus.CANCELLED);
                case EXPIRED -> r.setStatus(SaveToBuyStatus.EXPIRED);
                default -> { }
            }
            if (sr.getStatus() == StokvelRequestStatus.APPROVED && sr.getExpiresAt() != null) {
                r.setExpiresAt(sr.getExpiresAt());
            }
        }
        if ((r.getStatus() == SaveToBuyStatus.APPROVED || r.getStatus() == SaveToBuyStatus.PENDING_APPROVAL)
                && r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.setStatus(SaveToBuyStatus.EXPIRED);
        }
        if (r.getStatus() != before) {
            requests.save(r);
            if (before == SaveToBuyStatus.PENDING_APPROVAL && r.getStatus() == SaveToBuyStatus.APPROVED) {
                activityService.record(r.getCircle(), r.getOwner(), ActivityType.SAVE_TO_BUY_APPROVED,
                        "Purchase approved: " + r.getPurpose());
            }
        }
    }

    private void requireGoalReached(SaveToBuyRequest r) {
        BigDecimal saved = saved(r.getGoal());
        if (!goalReached(r.getGoal(), saved)) {
            BigDecimal remaining = r.getGoal().getTargetAmount().subtract(saved).max(BigDecimal.ZERO);
            throw ApiException.conflict("The savings goal has not been reached yet (" + r.getCurrency() + " "
                    + remaining + " to go)");
        }
    }

    private void requirePersonalGoal(CircleGoal goal, User actor) {
        if (goal.getCreatedBy() == null || !goal.getCreatedBy().getId().equals(actor.getId())) {
            throw ApiException.notFound("Goal not found");
        }
        if (memberships.countByCircleId(goal.getCircle().getId()) != 1) {
            throw ApiException.conflict(
                    "This goal is shared with other people. Use a stokvel so the group can approve purchases");
        }
    }

    private CircleGoal visibleGoal(String goalId, User actor) {
        CircleGoal goal = goals.findById(goalId).orElseThrow(() -> ApiException.notFound("Goal not found"));
        // Not a member: the goal "does not exist" for you.
        if (!memberships.existsByCircleIdAndUserId(goal.getCircle().getId(), actor.getId())) {
            throw ApiException.notFound("Goal not found");
        }
        return goal;
    }

    private CircleMembership membership(CircleGoal goal, User actor) {
        return memberships.findByCircleIdAndUserId(goal.getCircle().getId(), actor.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found"));
    }

    private boolean canSee(SaveToBuyRequest r, User viewer) {
        if (r.getScope() == SaveToBuyScope.PERSONAL) {
            return r.getOwner().getId().equals(viewer.getId());
        }
        return memberships.existsByCircleIdAndUserId(r.getCircle().getId(), viewer.getId());
    }

    private SaveToBuyRequest loadVisible(String id, User viewer) {
        SaveToBuyRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Save-to-Buy request not found"));
        if (!canSee(r, viewer)) {
            throw ApiException.notFound("Save-to-Buy request not found");
        }
        return r;
    }

    private SaveToBuyRequest lockVisible(String id, User viewer) {
        SaveToBuyRequest r = requests.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Save-to-Buy request not found"));
        if (!canSee(r, viewer)) {
            throw ApiException.notFound("Save-to-Buy request not found");
        }
        return r;
    }

    BigDecimal saved(CircleGoal goal) {
        BigDecimal v = contributions.sumConfirmedAmountForGoal(goal.getId(), PaymentStatus.SUCCESSFUL);
        return v == null ? BigDecimal.ZERO : v;
    }

    static boolean goalReached(CircleGoal goal, BigDecimal saved) {
        return goal.getStatus() == GoalStatus.REACHED || saved.compareTo(goal.getTargetAmount()) >= 0;
    }

    private EligibleGoal eligible(CircleGoal g, User viewer, CircleMembership m) {
        BigDecimal saved = saved(g);
        BigDecimal target = g.getTargetAmount();
        int percent = target.signum() > 0
                ? saved.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.FLOOR)
                        .min(BigDecimal.valueOf(100)).intValue() : 0;
        boolean stokvel = stokvelProfiles.findByCircleId(g.getCircle().getId()).isPresent();
        boolean personalOwner = g.getCreatedBy() != null && g.getCreatedBy().getId().equals(viewer.getId())
                && memberships.countByCircleId(g.getCircle().getId()) == 1;
        boolean designated = g.getPurchaseVertical() != null;
        boolean can = designated && (stokvel
                ? StokvelPermissions.allows(m.getRole(), Action.CREATE_REQUEST, false)
                : personalOwner);
        boolean canDesignate = stokvel ? StokvelPermissions.allows(m.getRole(), Action.UPDATE_SETTINGS, false) : personalOwner;
        return new EligibleGoal(g.getId(), g.getTitle(), g.getCircle().getId(), g.getCircle().getName(),
                stokvel ? SaveToBuyScope.STOKVEL : SaveToBuyScope.PERSONAL, g.getPurchaseVertical(),
                g.getPurchasePurpose(), g.getCircle().getCurrency(), target, saved,
                target.subtract(saved).max(BigDecimal.ZERO), percent, goalReached(g, saved), g.getDeadline(), can, canDesignate);
    }

    SaveToBuyView view(SaveToBuyRequest r, User viewer) {
        CircleGoal g = r.getGoal();
        BigDecimal saved = saved(g);
        boolean reached = goalReached(g, saved);
        BigDecimal shortfall = r.getRequestedAmount().subtract(saved).max(BigDecimal.ZERO);
        ApprovalProgress approval = null;
        StokvelRequest sr = r.getStokvelRequest();
        if (sr != null) {
            int approvals = (int) votes.countByRequestIdAndDecision(sr.getId(), VoteDecision.APPROVE);
            int rejections = (int) votes.countByRequestIdAndDecision(sr.getId(), VoteDecision.REJECT);
            approval = new ApprovalProgress(sr.getRequiredApprovals(), approvals, rejections, sr.getEligibleVoters(), sr.getId());
        }
        return new SaveToBuyView(r.getId(), r.getScope(), r.getStatus(), r.getVertical(), r.getPurpose(), g.getId(),
                g.getTitle(), r.getCircle().getId(), r.getCircle().getName(), r.getCurrency(), g.getTargetAmount(),
                saved, reached, r.getRequestedAmount(), shortfall,
                r.getRequestedAmount().subtract(r.getGoalTargetSnapshot()),
                reached && shortfall.signum() == 0,
                r.getNamedBuyer().getId(), r.getNamedBuyer().getFullName(), r.getOwner().getId(), r.getOwner().getFullName(),
                approval, r.getExpiresAt(), r.getOrderKind(), r.getOrderId(), r.getPurchasedAmount(), r.getPurchasedAt(),
                r.getCompletionReference(), r.getCreatedAt(), com.momocircle.dto.SaveToBuyDtos.NOTICE);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
