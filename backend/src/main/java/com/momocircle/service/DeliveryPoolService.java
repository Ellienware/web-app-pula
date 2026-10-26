package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.DeliveryPoolMember;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryPoolMemberRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.DriverRepository;
import com.momocircle.repository.momoride.TaxiRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Organisation driver pools. A wholesaler manages WHO is in its pool; it never picks a
 * driver per order — PULA Ride dispatches to the pool and a pool driver accepts.
 * Requires CAN_MANAGE_DELIVERY_FLEET. One PULA identity per person: pool membership is
 * a link to the existing driver profile, not a separate account.
 */
@Service
public class DeliveryPoolService {

    public record PoolMemberResponse(String id, String driverId, String driverName, String phoneNumber, boolean active, String status) {}

    public record PoolInviteResponse(String id, String businessId, String businessName) {}

    public enum DriverCapability { INDEPENDENT_DRIVER, WHOLESALER_DRIVER, TAXI_DRIVER }

    public record AddPoolMemberRequest(@jakarta.validation.constraints.NotBlank String phoneNumber) {}

    private final BusinessService businessService;
    private final DeliveryPoolMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final DriverRepository driverRepository;
    private final NotificationService notificationService;
    private final TaxiRepository taxiRepository;

    public DeliveryPoolService(BusinessService businessService, DeliveryPoolMemberRepository memberRepository,
                               UserRepository userRepository, DriverRepository driverRepository,
                               NotificationService notificationService, TaxiRepository taxiRepository) {
        this.businessService = businessService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.driverRepository = driverRepository;
        this.notificationService = notificationService;
        this.taxiRepository = taxiRepository;
    }

    @Transactional(readOnly = true)
    public List<PoolMemberResponse> members(User owner) {
        Business b = requireFleetBusiness(owner);
        return memberRepository.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PoolMemberResponse add(User owner, String phoneNumber) {
        Business b = requireFleetBusiness(owner);
        // Same message whether the number is unknown or not a driver — no account enumeration.
        Driver driver = userRepository.findByPhoneNumber(phoneNumber.trim())
                .flatMap(u -> driverRepository.findByUserId(u.getId()))
                .orElseThrow(() -> ApiException.badRequest("No PULA driver was found with that phone number."));
        DeliveryPoolMember m = memberRepository.findByBusinessIdAndDriverId(b.getId(), driver.getId())
                .orElseGet(() -> {
                    DeliveryPoolMember n = new DeliveryPoolMember();
                    n.setBusiness(b);
                    n.setDriver(driver);
                    n.setStatus("PENDING"); // the entity defaults to ACTIVE; a new member must be invited and accept
                    return n;
                });
        // Consent: an already-active member stays active; anyone else is invited and must accept.
        if (!"ACTIVE".equals(m.getStatus())) {
            m.setStatus("PENDING");
            notificationService.notify(driver.getUser(), com.momocircle.domain.enums.NotificationType.SYSTEM,
                    b.getName() + " invited you to join their delivery driver pool. Open PULA Ride to accept or decline.",
                    b.getId());
        }
        return toResponse(memberRepository.save(m));
    }

    @Transactional
    public PoolMemberResponse deactivate(User owner, String memberId) {
        Business b = requireFleetBusiness(owner);
        DeliveryPoolMember m = memberRepository.findByIdAndBusinessId(memberId, b.getId())
                .orElseThrow(() -> ApiException.notFound("Pool member not found"));
        m.setStatus("REMOVED");
        return toResponse(memberRepository.save(m));
    }

    /** Derived from real data (driver profile + active pool memberships) — no duplicate driver accounts. */
    @Transactional(readOnly = true)
    public List<DriverCapability> capabilitiesFor(User user) {
        List<DriverCapability> caps = new ArrayList<>();
        driverRepository.findByUserId(user.getId()).ifPresent(d -> {
            caps.add(DriverCapability.INDEPENDENT_DRIVER);
            if (!memberRepository.findByDriverIdAndActiveTrue(d.getId()).isEmpty()) {
                caps.add(DriverCapability.WHOLESALER_DRIVER);
            }
            // Same PULA identity: a driver who has registered a minibus taxi also holds TAXI_DRIVER.
            if (!taxiRepository.findByDriverIdOrderByCreatedAtDesc(d.getId()).isEmpty()) {
                caps.add(DriverCapability.TAXI_DRIVER);
            }
        });
        return caps;
    }

    // ---- Driver side: invitations ----

    @Transactional(readOnly = true)
    public List<PoolInviteResponse> myInvites(User user) {
        Driver d = driverRepository.findByUserId(user.getId()).orElse(null);
        if (d == null) return List.of();
        return memberRepository.findByDriverIdAndStatus(d.getId(), "PENDING").stream()
                .map(m -> new PoolInviteResponse(m.getId(), m.getBusiness().getId(), m.getBusiness().getName())).toList();
    }

    @Transactional
    public void respondToInvite(User user, String memberId, boolean accept) {
        Driver d = driverRepository.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.forbidden("You need a driver profile"));
        DeliveryPoolMember m = memberRepository.findByIdAndDriverId(memberId, d.getId())
                .orElseThrow(() -> ApiException.notFound("Invitation not found"));
        if (!"PENDING".equals(m.getStatus())) throw ApiException.badRequest("This invitation is no longer pending");
        m.setStatus(accept ? "ACTIVE" : "REMOVED");
        memberRepository.save(m);
    }

    public long activeMemberCount(String businessId) {
        return memberRepository.countByBusinessIdAndActiveTrue(businessId);
    }

    private Business requireFleetBusiness(User owner) {
        Business b = businessService.requireOwn(owner);
        if (!b.hasCapability(BusinessCapability.CAN_MANAGE_DELIVERY_FLEET)) {
            throw ApiException.forbidden("Your business needs the 'manage a delivery driver pool' capability");
        }
        return b;
    }

    private PoolMemberResponse toResponse(DeliveryPoolMember m) {
        return new PoolMemberResponse(m.getId(), m.getDriver().getId(), m.getDriver().getUser().getFullName(),
                m.getDriver().getUser().getPhoneNumber(), m.isActive(), m.getStatus());
    }
}
