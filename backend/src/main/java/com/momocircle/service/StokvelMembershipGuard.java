package com.momocircle.service;

import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.StokvelCycleRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.repository.StokvelRotationSlotRepository;
import org.springframework.stereotype.Component;

/**
 * Hooks called by CircleService so the generic membership paths respect stokvel rules without CircleService
 * depending on stokvel services. Ordinary circles (no profile) are only affected by the role check.
 */
@Component
public class StokvelMembershipGuard {

    private final StokvelProfileRepository profiles;
    private final StokvelRotationSlotRepository slots;
    private final StokvelCycleRepository cycles;

    public StokvelMembershipGuard(StokvelProfileRepository profiles, StokvelRotationSlotRepository slots,
                                  StokvelCycleRepository cycles) {
        this.profiles = profiles;
        this.slots = slots;
        this.cycles = cycles;
    }

    /** TREASURER/CHAIRPERSON exist only in stokvels; nobody can be handed OWNER through add/invite/role change. */
    public void validateRole(String circleId, CircleRole role) {
        if (role == null) {
            return;
        }
        boolean stokvel = profiles.findByCircleId(circleId).isPresent();
        if ((role == CircleRole.TREASURER || role == CircleRole.CHAIRPERSON) && !stokvel) {
            throw ApiException.badRequest("Treasurer and chairperson roles exist only in stokvels");
        }
        if (stokvel && role == CircleRole.OWNER) {
            throw ApiException.badRequest("Ownership cannot be assigned in a stokvel");
        }
    }

    /** A locked rotation cannot lose a member who is still waiting for their payout. */
    public void beforeMemberLeaves(String circleId, String userId) {
        StokvelProfile p = profiles.findByCircleId(circleId).orElse(null);
        if (p == null || !p.isRotationLocked()) {
            return;
        }
        if (slots.existsByCircleIdAndUserId(circleId, userId)
                && !cycles.existsByCircleIdAndBeneficiaryId(circleId, userId)) {
            throw ApiException.conflict(
                    "The payout rotation is locked and this member has not yet received their payout");
        }
    }
}
