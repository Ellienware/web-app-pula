package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.stokvel.ApprovalRules;
import com.momocircle.stokvel.StokvelPermissions;
import com.momocircle.stokvel.StokvelPermissions.Action;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stokvel requests and approvals. Records WHO decided WHAT; it never moves money. An approved purchase is paid by the
 * named buyer through normal PULA checkout and then linked here. Amounts, votes and history are never edited.
 */
@Service
public class StokvelRequestService {

    private static final Set<StokvelRequestStatus> OPEN = EnumSet.of(StokvelRequestStatus.PENDING, StokvelRequestStatus.APPROVED);

    private final StokvelSupport support;
    private final StokvelRequestRepository requests;
    private final StokvelRequestVoteRepository votes;
    private final OrderRepository orders;
    private final StockOrderRepository stockOrders;
    private final GoalService goalService;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final FinancialValidationService validation;

    public StokvelRequestService(StokvelSupport support, StokvelRequestRepository requests,
                                 StokvelRequestVoteRepository votes, OrderRepository orders,
                                 StockOrderRepository stockOrders, GoalService goalService,
                                 ActivityService activityService, NotificationService notificationService,
                                 FinancialValidationService validation) {
        this.support = support;
        this.requests = requests;
        this.votes = votes;
        this.orders = orders;
        this.stockOrders = stockOrders;
        this.goalService = goalService;
        this.activityService = activityService;
        this.notificationService = notificationService;
        this.validation = validation;
    }

    @Transactional
    public RequestView create(String circleId, User actor, CreateStokvelRequestRequest req) {
        var ctx = support.require(circleId, actor);
        support.require(ctx, Action.CREATE_REQUEST);
        StokvelProfile p = ctx.profile();

        String key = req.idempotencyKey() == null || req.idempotencyKey().isBlank() ? null : req.idempotencyKey().trim();
        if (key != null) {
            var existing = requests.findByCircleIdAndRequesterIdAndIdempotencyKey(circleId, actor.getId(), key);
            if (existing.isPresent()) {
                return toView(existing.get());
            }
        }
        validation.requireValidAmount(req.amount(), "amount");
        String purpose = req.purpose().trim();
        if (purpose.isEmpty()) {
            throw ApiException.badRequest("Purpose is required");
        }

        List<CircleMembership> members = support.members(circleId);
        User buyer = null;
        if (req.buyerUserId() != null && !req.buyerUserId().isBlank()) {
            buyer = members.stream().map(CircleMembership::getUser)
                    .filter(u -> u.getId().equals(req.buyerUserId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("The named buyer must be a member of this stokvel"));
        }
        if (req.type().isMarketplacePurchase() && buyer == null) {
            throw ApiException.badRequest("A purchase request must name the buyer");
        }
        CircleGoal goal = null;
        if (req.goalId() != null && !req.goalId().isBlank()) {
            goal = goalService.requireGoal(circleId, req.goalId());
        }
        if (requests.existsByCircleIdAndRequesterIdAndRequestTypeAndAmountAndPurposeAndStatus(
                circleId, actor.getId(), req.type(), req.amount(), purpose, StokvelRequestStatus.PENDING)) {
            throw ApiException.conflict("An identical request is already pending");
        }
        BigDecimal available = support.confirmedContributions(circleId).subtract(support.committed(circleId));
        if (req.amount().compareTo(available) > 0) {
            throw ApiException.badRequest("The amount is more than the tracked contributions not yet committed");
        }

        int voters = eligibleVoters(p, members, actor.getId());
        int required;
        try {
            required = ApprovalRules.requiredApprovals(p.getApprovalRule(), p.getApprovalThreshold(), voters);
        } catch (IllegalArgumentException e) {
            throw ApiException.conflict(e.getMessage());
        }

        StokvelRequest r = new StokvelRequest();
        r.setCircle(ctx.circle());
        r.setGoal(goal);
        r.setRequestType(req.type());
        r.setRequester(actor);
        r.setBuyer(buyer);
        r.setAmount(req.amount());
        r.setCurrency(ctx.circle().getCurrency());
        r.setPurpose(purpose);
        r.setStatus(StokvelRequestStatus.PENDING);
        r.setApprovalRule(p.getApprovalRule());
        r.setRequiredApprovals(required);
        r.setEligibleVoters(voters);
        r.setIdempotencyKey(key);
        r.setExpiresAt(Instant.now().plus(Duration.ofDays(p.getRequestValidityDays())));
        r = requests.save(r);

        activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_CREATED,
                actor.getFullName() + " requested " + r.getCurrency() + " " + r.getAmount() + " for " + purpose);
        for (CircleMembership m : members) {
            if (!m.getUser().getId().equals(actor.getId()) && canVote(p, m.getRole())) {
                notificationService.notify(m.getUser(), NotificationType.STOKVEL_APPROVAL_REQUESTED,
                        actor.getFullName() + " needs your approval in " + ctx.circle().getName(), r.getId());
            }
        }
        return toView(r);
    }

    @Transactional(readOnly = true)
    public List<RequestView> list(String circleId, User viewer) {
        support.require(circleId, viewer);
        return requests.findByCircleIdOrderByCreatedAtDesc(circleId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public RequestView get(String circleId, String requestId, User viewer) {
        support.require(circleId, viewer);
        // Looked up by (id AND circle): an id from another circle is simply "not found".
        return toView(requests.findByIdAndCircleId(requestId, circleId)
                .orElseThrow(() -> ApiException.notFound("Request not found")));
    }

    @Transactional
    public RequestView vote(String circleId, String requestId, User actor, VoteDecision decision, String comment) {
        var ctx = support.require(circleId, actor);
        support.require(ctx, Action.VOTE_ON_REQUEST);
        StokvelRequest r = lock(circleId, requestId);
        if (r.getRequester().getId().equals(actor.getId())) {
            throw ApiException.forbidden("You cannot decide your own request");
        }
        var prior = votes.findByRequestIdAndVoterId(r.getId(), actor.getId());
        if (prior.isPresent()) {
            if (prior.get().getDecision() == decision) {
                return toView(r); // idempotent replay
            }
            throw ApiException.conflict("You have already voted on this request");
        }
        requireOpenPending(r);

        StokvelRequestVote v = new StokvelRequestVote();
        v.setRequest(r);
        v.setVoter(actor);
        v.setDecision(decision);
        v.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        votes.save(v);
        votes.flush();

        int approvals = (int) votes.countByRequestIdAndDecision(r.getId(), VoteDecision.APPROVE);
        int rejections = (int) votes.countByRequestIdAndDecision(r.getId(), VoteDecision.REJECT);
        var outcome = ApprovalRules.evaluate(r.getRequiredApprovals(), r.getEligibleVoters(), approvals, rejections);
        Instant now = Instant.now();
        if (outcome == ApprovalRules.Outcome.APPROVED) {
            BigDecimal available = support.confirmedContributions(circleId).subtract(support.committed(circleId));
            if (r.getAmount().compareTo(available) > 0) {
                throw ApiException.conflict("Tracked contributions no longer cover this request");
            }
            r.setStatus(StokvelRequestStatus.APPROVED);
            r.setDecidedAt(now);
            r.setExpiresAt(now.plus(Duration.ofDays(ctx.profile().getRequestValidityDays())));
            requests.save(r);
            activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_APPROVED,
                    "Request for " + r.getCurrency() + " " + r.getAmount() + " was approved");
            notifyOutcome(r, "approved");
        } else if (outcome == ApprovalRules.Outcome.REJECTED) {
            r.setStatus(StokvelRequestStatus.REJECTED);
            r.setDecidedAt(now);
            requests.save(r);
            activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_REJECTED,
                    "Request for " + r.getCurrency() + " " + r.getAmount() + " was rejected");
            notifyOutcome(r, "rejected");
        }
        return toView(r);
    }

    @Transactional
    public RequestView cancel(String circleId, String requestId, User actor) {
        var ctx = support.require(circleId, actor);
        StokvelRequest r = lock(circleId, requestId);
        boolean own = r.getRequester().getId().equals(actor.getId());
        if (!own && !StokvelPermissions.allows(ctx.role(), Action.CANCEL_ANY_REQUEST, false)) {
            throw ApiException.forbidden("Only the requester, the owner/admin or the chairperson can cancel a request");
        }
        if (!OPEN.contains(r.getStatus())) {
            throw ApiException.conflict("This request is already " + r.getStatus().name().toLowerCase());
        }
        r.setStatus(StokvelRequestStatus.CANCELLED);
        r.setDecidedAt(Instant.now());
        requests.save(r);
        activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_CANCELLED,
                actor.getFullName() + " cancelled a request for " + r.getCurrency() + " " + r.getAmount());
        return toView(r);
    }

    /** Optional so existing construction is unchanged; set by Spring. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SaveToBuyRequestRepository saveToBuyRequests;

    /**
     * System hook for Save-to-Buy: the wrapped order was paid through normal checkout, so the group's approved
     * request is now executed. No actor: the buyer, amount and order were all validated when checkout started.
     */
    @Transactional
    public void markExecutedByPurchase(String stokvelRequestId, RelatedOrderKind kind, String orderId) {
        StokvelRequest r = requests.findById(stokvelRequestId).orElse(null);
        if (r == null || r.getStatus() != StokvelRequestStatus.APPROVED) {
            return;
        }
        r.setStatus(StokvelRequestStatus.EXECUTED);
        r.setExecutedAt(Instant.now());
        r.setRelatedOrderKind(kind);
        r.setRelatedOrderId(orderId);
        requests.save(r);
        activityService.record(r.getCircle(), r.getBuyer() != null ? r.getBuyer() : r.getRequester(),
                ActivityType.STOKVEL_REQUEST_EXECUTED,
                "Approved purchase of " + r.getCurrency() + " " + r.getAmount() + " was paid at checkout");
    }

    /** The named buyer links the order they created through normal checkout. */
    @Transactional
    public RequestView linkOrder(String circleId, String requestId, User actor, LinkOrderRequest req) {
        var ctx = support.require(circleId, actor);
        StokvelRequest r = lock(circleId, requestId);
        if (saveToBuyRequests != null && saveToBuyRequests.existsByStokvelRequestId(r.getId())) {
            throw ApiException.conflict("This request is fulfilled through Save-to-Buy checkout, not by linking an order");
        }
        requireApprovedAndLive(r);
        if (!r.getRequestType().isMarketplacePurchase()) {
            throw ApiException.badRequest("This request type is not fulfilled through marketplace checkout");
        }
        if (r.getBuyer() == null || !r.getBuyer().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the named buyer can link an order to this request");
        }
        boolean stock = r.getRequestType() == StokvelRequestType.STOCK_PURCHASE;
        if (stock ? req.kind() != RelatedOrderKind.STOCK_ORDER : req.kind() != RelatedOrderKind.ORDER) {
            throw ApiException.badRequest("Order kind does not match the request type");
        }
        BigDecimal total;
        String currency;
        if (stock) {
            StockOrder o = stockOrders.findById(req.orderId())
                    .orElseThrow(() -> ApiException.notFound("Order not found"));
            if (!o.getBuyerBusiness().getOwner().getId().equals(actor.getId())) {
                throw ApiException.notFound("Order not found"); // do not reveal other people's orders
            }
            if (o.getStokvelRequestId() != null) {
                throw ApiException.conflict("That order is already linked to a stokvel request");
            }
            total = o.getTotal();
            currency = o.getCurrency();
            o.setStokvelRequestId(r.getId());
            stockOrders.save(o);
        } else {
            Order o = orders.findById(req.orderId()).orElseThrow(() -> ApiException.notFound("Order not found"));
            if (!o.getBuyer().getId().equals(actor.getId())) {
                throw ApiException.notFound("Order not found");
            }
            if (o.getStokvelRequestId() != null) {
                throw ApiException.conflict("That order is already linked to a stokvel request");
            }
            total = o.getTotal();
            currency = o.getCurrency();
            o.setStokvelRequestId(r.getId());
            orders.save(o);
        }
        // The order total is the server's number; it may not exceed what the group approved.
        if (total.compareTo(r.getAmount()) > 0 || !r.getCurrency().equals(currency)) {
            throw ApiException.badRequest("The order total exceeds the approved amount");
        }
        r.setStatus(StokvelRequestStatus.EXECUTED);
        r.setExecutedAt(Instant.now());
        r.setRelatedOrderKind(req.kind());
        r.setRelatedOrderId(req.orderId());
        requests.save(r);
        activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_EXECUTED,
                actor.getFullName() + " placed the approved order for " + r.getCurrency() + " " + total);
        return toView(r);
    }

    /** For withdrawals/expenses paid outside PULA: an authorised recorder notes the external reference. */
    @Transactional
    public RequestView recordExecution(String circleId, String requestId, User actor, RecordExecutionRequest req) {
        var ctx = support.require(circleId, actor);
        support.require(ctx, Action.RECORD_EXTERNAL_EXECUTION);
        StokvelRequest r = lock(circleId, requestId);
        requireApprovedAndLive(r);
        if (r.getRequestType().isMarketplacePurchase()) {
            throw ApiException.badRequest("Marketplace purchases are executed by linking the buyer's order");
        }
        r.setStatus(StokvelRequestStatus.EXECUTED);
        r.setExecutedAt(Instant.now());
        r.setRelatedOrderKind(RelatedOrderKind.EXTERNAL);
        r.setExternalReference(req.reference().trim());
        requests.save(r);
        activityService.record(ctx.circle(), actor, ActivityType.STOKVEL_REQUEST_EXECUTED,
                actor.getFullName() + " recorded the approved request as paid");
        return toView(r);
    }

    /** Scheduled: persists EXPIRED for open requests past their deadline. Returns how many were expired. */
    @Transactional
    public int expireDue() {
        int n = 0;
        for (String id : requests.findIdsPastExpiry(OPEN, Instant.now())) {
            StokvelRequest r = requests.findById(id).orElse(null);
            if (r == null || !OPEN.contains(r.getStatus())) {
                continue;
            }
            r.setStatus(StokvelRequestStatus.EXPIRED);
            r.setDecidedAt(Instant.now());
            requests.save(r);
            activityService.record(r.getCircle(), r.getRequester(), ActivityType.STOKVEL_REQUEST_EXPIRED,
                    "Request for " + r.getCurrency() + " " + r.getAmount() + " expired");
            n++;
        }
        return n;
    }

    // ---- helpers ----

    private StokvelRequest lock(String circleId, String requestId) {
        return requests.findForUpdate(requestId, circleId)
                .orElseThrow(() -> ApiException.notFound("Request not found"));
    }

    private void requireOpenPending(StokvelRequest r) {
        if (r.getStatus() != StokvelRequestStatus.PENDING) {
            throw ApiException.conflict("This request is already " + r.getStatus().name().toLowerCase());
        }
        if (r.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("This request has expired");
        }
    }

    private void requireApprovedAndLive(StokvelRequest r) {
        if (r.getStatus() != StokvelRequestStatus.APPROVED) {
            throw ApiException.conflict("Only an approved request can be executed (status is "
                    + r.getStatus().name().toLowerCase() + ")");
        }
        if (r.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("This approval has expired");
        }
    }

    private boolean canVote(StokvelProfile p, CircleRole role) {
        return StokvelPermissions.allows(role, Action.VOTE_ON_REQUEST, p.isMembersCanVote());
    }

    private int eligibleVoters(StokvelProfile p, List<CircleMembership> members, String requesterId) {
        return (int) members.stream()
                .filter(m -> !m.getUser().getId().equals(requesterId) && canVote(p, m.getRole())).count();
    }

    private void notifyOutcome(StokvelRequest r, String word) {
        String msg = "Your stokvel request for " + r.getCurrency() + " " + r.getAmount() + " was " + word;
        notificationService.notify(r.getRequester(), NotificationType.STOKVEL_APPROVAL_COMPLETED, msg, r.getId());
        if (r.getBuyer() != null && !r.getBuyer().getId().equals(r.getRequester().getId())) {
            notificationService.notify(r.getBuyer(), NotificationType.STOKVEL_APPROVAL_COMPLETED,
                    "A stokvel request naming you as buyer was " + word, r.getId());
        }
    }

    private RequestView toView(StokvelRequest r) {
        List<StokvelRequestVote> vs = votes.findByRequestIdOrderByCreatedAtAsc(r.getId());
        int approvals = (int) vs.stream().filter(v -> v.getDecision() == VoteDecision.APPROVE).count();
        StokvelRequestStatus status = r.getStatus();
        if (OPEN.contains(status) && r.getExpiresAt().isBefore(Instant.now())) {
            status = StokvelRequestStatus.EXPIRED; // shown as expired immediately; persisted by expireDue()
        }
        return new RequestView(r.getId(), r.getCircle().getId(), r.getRequestType(), status,
                r.getRequester().getId(), r.getRequester().getFullName(),
                r.getBuyer() == null ? null : r.getBuyer().getId(),
                r.getBuyer() == null ? null : r.getBuyer().getFullName(),
                r.getAmount(), r.getCurrency(), r.getPurpose(), r.getGoal() == null ? null : r.getGoal().getId(),
                r.getApprovalRule(), r.getRequiredApprovals(), r.getEligibleVoters(), approvals, vs.size() - approvals,
                vs.stream().map(v -> new VoteView(v.getVoter().getId(), v.getVoter().getFullName(), v.getDecision(),
                        v.getComment(), v.getCreatedAt())).toList(),
                r.getExpiresAt(), r.getDecidedAt(), r.getExecutedAt(), r.getRelatedOrderKind(),
                r.getRelatedOrderId(), r.getExternalReference(), r.getCreatedAt());
    }
}
