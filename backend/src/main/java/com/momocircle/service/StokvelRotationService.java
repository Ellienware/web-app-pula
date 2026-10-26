package com.momocircle.service;

import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelCycle;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.StokvelRotationSlot;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StokvelCycleStatus;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.StokvelDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.StokvelContributionLinkRepository;
import com.momocircle.repository.StokvelCycleRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.repository.StokvelRotationSlotRepository;
import com.momocircle.stokvel.RotationOrder;
import com.momocircle.stokvel.StokvelPermissions.Action;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rotational payout order. Deterministic: default = member join order; founder may override until the first payout
 * is recorded; recording the first payout locks the order for good. A "payout" here is a RECORD that the group paid
 * the beneficiary - PULA does not hold the pot and moves no money.
 */
@Service
public class StokvelRotationService {

    private final StokvelSupport support;
    private final StokvelProfileRepository profiles;
    private final StokvelRotationSlotRepository slots;
    private final StokvelCycleRepository cycles;
    private final StokvelContributionLinkRepository links;
    private final StokvelContributionService contributionService;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public StokvelRotationService(StokvelSupport support, StokvelProfileRepository profiles,
                                  StokvelRotationSlotRepository slots, StokvelCycleRepository cycles,
                                  StokvelContributionLinkRepository links,
                                  StokvelContributionService contributionService,
                                  ActivityService activityService, NotificationService notificationService) {
        this.support = support;
        this.profiles = profiles;
        this.slots = slots;
        this.cycles = cycles;
        this.links = links;
        this.contributionService = contributionService;
        this.activityService = activityService;
        this.notificationService = notificationService;
    }

    /**
     * Brings slots in line with membership. Unlocked: departed members are dropped and the rest compacted.
     * Locked: nobody is dropped (history stays intact). Newcomers are appended in join order either way.
     */
    @Transactional
    public List<StokvelRotationSlot> syncSlots(StokvelProfile p) {
        String circleId = p.getCircle().getId();
        List<CircleMembership> members = support.members(circleId);
        Set<String> active = members.stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
        List<StokvelRotationSlot> current = new ArrayList<>(slots.findByCircleIdOrderByPositionAsc(circleId));

        if (!p.isRotationLocked()) {
            List<StokvelRotationSlot> gone = current.stream().filter(s -> !active.contains(s.getUser().getId())).toList();
            if (!gone.isEmpty()) {
                slots.deleteAll(gone);
                current.removeAll(gone);
            }
        }
        List<String> joinOrder = RotationOrder.joinOrder(members.stream()
                .map(m -> new RotationOrder.Joiner(m.getUser().getId(), m.getCreatedAt(), m.getId())).toList());
        List<String> existing = current.stream().map(s -> s.getUser().getId()).toList();
        List<String> full = RotationOrder.appendMissing(existing, joinOrder);

        Map<String, CircleMembership> byUser = members.stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m));
        Map<String, StokvelRotationSlot> slotByUser = current.stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s));
        List<StokvelRotationSlot> result = new ArrayList<>();
        boolean changed = false;
        int pos = 1;
        for (String userId : full) {
            StokvelRotationSlot s = slotByUser.get(userId);
            if (s == null) {
                s = new StokvelRotationSlot();
                s.setCircle(p.getCircle());
                s.setUser(byUser.get(userId).getUser());
                changed = true;
            }
            if (s.getPosition() != pos) {
                s.setPosition(pos);
                changed = true;
            }
            result.add(s);
            pos++;
        }
        if (changed) {
            result = slots.saveAll(result);
        }
        return result;
    }

    @Transactional
    public RotationView get(String circleId, User viewer) {
        var ctx = support.require(circleId, viewer);
        requireRotational(ctx.profile());
        return view(ctx.profile());
    }

    @Transactional
    public RotationView setOrder(String circleId, User actor, SetRotationOrderRequest req) {
        var ctx = support.requireForUpdate(circleId, actor);
        support.require(ctx, Action.OVERRIDE_ROTATION);
        StokvelProfile p = ctx.profile();
        requireRotational(p);
        if (p.isRotationLocked() || cycles.countByCircleId(circleId) > 0) {
            throw ApiException.conflict("The payout order is locked because a payout has already been recorded");
        }
        List<StokvelRotationSlot> current = syncSlots(p);
        try {
            RotationOrder.requirePermutation(
                    current.stream().map(s -> s.getUser().getId()).collect(Collectors.toSet()), req.userIds());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        Map<String, StokvelRotationSlot> byUser = current.stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s));
        int pos = 1;
        for (String userId : req.userIds()) {
            byUser.get(userId).setPosition(pos++);
        }
        slots.saveAll(current);
        activityService.record(ctx.circle(), actor, ActivityType.ROTATION_UPDATED,
                actor.getFullName() + " changed the payout order");
        return view(p);
    }

    @Transactional
    public RotationView recordPayout(String circleId, User actor, RecordPayoutRequest req) {
        var ctx = support.requireForUpdate(circleId, actor);
        support.require(ctx, Action.RECORD_PAYOUT);
        StokvelProfile p = ctx.profile();
        requireRotational(p);

        int next = (int) cycles.countByCircleId(circleId) + 1;
        if (req.expectedCycleNumber() != next) {
            throw ApiException.conflict("Cycle " + req.expectedCycleNumber() + " is not the next payout (next is "
                    + next + ")");
        }
        List<StokvelRotationSlot> order = syncSlots(p);
        List<CircleMembership> members = support.members(circleId);
        Set<String> active = members.stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
        List<String> ids = order.stream().map(s -> s.getUser().getId()).toList();
        String beneficiaryId = RotationOrder.beneficiary(ids, active, next);
        if (beneficiaryId == null) {
            throw ApiException.conflict("There is no eligible beneficiary");
        }
        if (beneficiaryId.equals(actor.getId())) {
            throw ApiException.forbidden("The beneficiary cannot record their own payout");
        }
        // Amounts are computed here from confirmed payments; the client supplies none.
        BigDecimal collected = links.confirmedForPeriod(circleId, next - 1, PaymentStatus.SUCCESSFUL);
        collected = collected == null ? BigDecimal.ZERO : collected;
        if (collected.signum() <= 0) {
            throw ApiException.badRequest("No confirmed contributions have been collected for this cycle");
        }
        BigDecimal expected = p.getContributionAmount().multiply(BigDecimal.valueOf(members.size()));

        StokvelRotationSlot slot = order.stream().filter(s -> s.getUser().getId().equals(beneficiaryId))
                .findFirst().orElseThrow();
        StokvelCycle c = new StokvelCycle();
        c.setCircle(ctx.circle());
        c.setCycleNumber(next);
        c.setPayoutPosition(slot.getPosition());
        c.setBeneficiary(slot.getUser());
        c.setRecordedBy(actor);
        c.setExpectedAmount(expected);
        c.setCollectedAmount(collected);
        c.setStatus(StokvelCycleStatus.PAID_OUT);
        c.setReference(req.reference());
        c.setPaidAt(Instant.now());
        cycles.save(c);

        if (!p.isRotationLocked()) {
            p.setRotationLocked(true);
            p.setRotationLockedAt(Instant.now());
            profiles.save(p);
            activityService.record(ctx.circle(), actor, ActivityType.ROTATION_LOCKED,
                    "The payout order is now locked");
        }
        activityService.record(ctx.circle(), actor, ActivityType.PAYOUT_RECORDED,
                actor.getFullName() + " recorded payout " + next + " to " + slot.getUser().getFullName());
        notificationService.notify(slot.getUser(), NotificationType.SYSTEM,
                "Your stokvel payout for cycle " + next + " was recorded in " + ctx.circle().getName(), circleId);
        String upcoming = RotationOrder.beneficiary(ids, active, next + 1);
        if (upcoming != null && !upcoming.equals(beneficiaryId)) {
            members.stream().filter(m -> m.getUser().getId().equals(upcoming)).findFirst().ifPresent(m ->
                    notificationService.notify(m.getUser(), NotificationType.STOKVEL_PAYOUT_UPCOMING,
                            "You are next to receive the payout in " + ctx.circle().getName(), circleId));
        }
        return view(p);
    }

    // ---- view ----

    private RotationView view(StokvelProfile p) {
        String circleId = p.getCircle().getId();
        List<StokvelRotationSlot> order = syncSlots(p);
        List<StokvelCycle> history = cycles.findByCircleIdOrderByCycleNumberAsc(circleId);
        List<CircleMembership> members = support.members(circleId);
        Set<String> active = members.stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
        List<String> ids = order.stream().map(s -> s.getUser().getId()).toList();
        Map<String, String> names = order.stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s.getUser().getFullName()));
        Map<String, Integer> paidIn = new HashMap<>();
        history.forEach(h -> paidIn.putIfAbsent(h.getBeneficiary().getId(), h.getCycleNumber()));

        int current = history.size() + 1;
        String cur = RotationOrder.beneficiary(ids, active, current);
        String up = RotationOrder.beneficiary(ids, active, current + 1);

        BigDecimal expected = p.getContributionAmount().multiply(BigDecimal.valueOf(members.size()));
        BigDecimal collected = links.confirmedForPeriod(circleId, current - 1, PaymentStatus.SUCCESSFUL);
        Map<String, BigDecimal> confirmed = contributionService.confirmedMap(circleId);
        var today = support.today();
        List<MemberPeriodStatus> contribs = members.stream()
                .map(m -> contributionService.memberStatus(p, m, current - 1, confirmed, today)).toList();

        return new RotationView(p.isRotationLocked(), p.getRotationLockedAt(), history.size(), current,
                cur, cur == null ? null : names.get(cur), up, up == null ? null : names.get(up),
                expected, collected == null ? BigDecimal.ZERO : collected,
                order.stream().map(s -> new RotationSlotView(s.getPosition(), s.getUser().getId(),
                        s.getUser().getFullName(), paidIn.containsKey(s.getUser().getId()),
                        paidIn.get(s.getUser().getId()))).toList(),
                history.stream().map(h -> new CycleView(h.getCycleNumber(), h.getPayoutPosition(),
                        h.getBeneficiary().getId(), h.getBeneficiary().getFullName(), h.getExpectedAmount(),
                        h.getCollectedAmount(), h.getRecordedBy().getFullName(), h.getReference(), h.getPaidAt())).toList(),
                contribs);
    }

    private void requireRotational(StokvelProfile p) {
        if (p.getStokvelType() != StokvelType.ROTATIONAL) {
            throw ApiException.badRequest("This stokvel is not rotational");
        }
    }
}
