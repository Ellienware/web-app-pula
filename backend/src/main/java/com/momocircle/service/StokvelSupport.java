package com.momocircle.service;

import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.StokvelRequestStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.StokvelCycleRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.repository.StokvelRequestRepository;
import com.momocircle.stokvel.StokvelPermissions;
import com.momocircle.stokvel.StokvelPermissions.Action;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared access checks and tracked-funds arithmetic for the stokvel services. Holds no state. */
@Component
public class StokvelSupport {

    public static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");
    public static final String FUNDS_NOTICE =
            "These figures are tracked from confirmed contributions. PULA does not hold or pool stokvel money.";

    public record Context(StokvelProfile profile, CircleMembership membership) {
        public Circle circle() { return membership.getCircle(); }
        public String circleId() { return membership.getCircle().getId(); }
        public CircleRole role() { return membership.getRole(); }
    }

    private final CircleService circleService;
    private final StokvelProfileRepository profiles;
    private final ContributionRepository contributions;
    private final StokvelCycleRepository cycles;
    private final StokvelRequestRepository requests;

    public StokvelSupport(CircleService circleService, StokvelProfileRepository profiles,
                          ContributionRepository contributions, StokvelCycleRepository cycles,
                          StokvelRequestRepository requests) {
        this.circleService = circleService;
        this.profiles = profiles;
        this.contributions = contributions;
        this.cycles = cycles;
        this.requests = requests;
    }

    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Membership first (403 for outsiders, same as the rest of Savings), then "is this circle a stokvel" (404). */
    public Context require(String circleId, User user) {
        CircleMembership m = circleService.requireMembership(circleId, user);
        StokvelProfile p = profiles.findByCircleId(circleId)
                .orElseThrow(() -> ApiException.notFound("Stokvel not found"));
        return new Context(p, m);
    }

    /** Same as {@link #require} but takes the row lock used to serialise rotation/payout changes. */
    public Context requireForUpdate(String circleId, User user) {
        CircleMembership m = circleService.requireMembership(circleId, user);
        StokvelProfile p = profiles.findByCircleIdForUpdate(circleId)
                .orElseThrow(() -> ApiException.notFound("Stokvel not found"));
        return new Context(p, m);
    }

    public void require(Context ctx, Action action) {
        if (!StokvelPermissions.allows(ctx.role(), action, ctx.profile().isMembersCanVote())) {
            throw ApiException.forbidden("Your role is not allowed to do this in this stokvel");
        }
    }

    public List<CircleMembership> members(String circleId) {
        return circleService.membershipsOf(circleId);
    }

    public Set<String> memberIds(String circleId) {
        return members(circleId).stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
    }

    public BigDecimal confirmedContributions(String circleId) {
        BigDecimal v = contributions.sumConfirmedAmount(circleId, PaymentStatus.SUCCESSFUL);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Money the group has already earmarked: recorded payouts plus approved/executed requests. */
    public BigDecimal committed(String circleId) {
        BigDecimal paidOut = cycles.sumCollectedPaidOut(circleId);
        BigDecimal reqs = requests.sumAmountByStatuses(circleId,
                List.of(StokvelRequestStatus.APPROVED, StokvelRequestStatus.EXECUTED));
        return (paidOut == null ? BigDecimal.ZERO : paidOut).add(reqs == null ? BigDecimal.ZERO : reqs);
    }
}
