package com.momocircle.service.wallet;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletMemberEntity;
import com.momocircle.domain.wallet.BusinessWalletRole;
import com.momocircle.dto.WalletDtos.MemberView;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.wallet.BusinessWalletMemberRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.wallet.WalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Delegated access to a business wallet. Only the business OWNER may change it (MANAGE_MEMBERS, checked by the caller through
 * {@link BusinessWalletAccessService#require}). The owner is never a row here and cannot be added, demoted or removed.
 * Members are addressed by their opaque personal wallet id (PW-...), never an internal user id.
 */
@Service
public class BusinessWalletMemberService {

    private static final Logger log = LoggerFactory.getLogger(BusinessWalletMemberService.class);

    private final BusinessWalletMemberRepository members;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final WalletIdentityService identity;
    private final AuditLogService audit;

    public BusinessWalletMemberService(BusinessWalletMemberRepository members, BusinessRepository businesses, UserRepository users,
                                       WalletIdentityService identity, AuditLogService audit) {
        this.members = members;
        this.businesses = businesses;
        this.users = users;
        this.identity = identity;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list(BusinessWalletAccessService.Access access) {
        List<MemberView> out = new ArrayList<>();
        Business b = businesses.findById(access.businessId()).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Business wallet not found"));
        if (b.getOwner() != null) {
            out.add(new MemberView(identity.publicIdFor(b.getOwner().getId()), WalletIdentityService.maskName(b.getOwner().getFullName()), "OWNER", true));
        }
        for (BusinessWalletMemberEntity m : members.findByBusinessIdOrderByCreatedAtAsc(access.businessId())) {
            String name = users.findById(m.getUserId()).map(u -> WalletIdentityService.maskName(u.getFullName())).orElse("PULA user");
            out.add(new MemberView(identity.publicIdFor(m.getUserId()), name, m.getRole().name(), false));
        }
        return out;
    }

    @Transactional
    public MemberView add(User owner, BusinessWalletAccessService.Access access, String identifier, String roleName) {
        BusinessWalletRole role = delegableRole(roleName);
        User target = identity.lookup(identifier).orElseThrow(() ->
                WalletException.notFound("RECIPIENT_NOT_FOUND", "No eligible PULA user matches that wallet id, email or phone number"));
        if (isOwner(access, target)) {
            throw WalletException.badRequest("ALREADY_OWNER", "The business owner already has full access");
        }
        if (members.findByBusinessIdAndUserId(access.businessId(), target.getId()).isPresent()) {
            throw WalletException.conflict("ALREADY_MEMBER", "That person already has access to this business wallet");
        }
        try {
            members.saveAndFlush(new BusinessWalletMemberEntity(UUID.randomUUID().toString(), access.businessId(), target.getId(), role,
                    owner.getId(), Instant.now()));
        } catch (DataIntegrityViolationException lostRace) {
            throw WalletException.conflict("ALREADY_MEMBER", "That person already has access to this business wallet");
        }
        audit(owner, "BUSINESS_WALLET_MEMBER_ADDED", access, "role=" + role + " member=" + identity.publicIdFor(target.getId()));
        return new MemberView(identity.publicIdFor(target.getId()), WalletIdentityService.maskName(target.getFullName()), role.name(), false);
    }

    @Transactional
    public MemberView changeRole(User owner, BusinessWalletAccessService.Access access, String memberWalletId, String roleName) {
        BusinessWalletRole role = delegableRole(roleName);
        BusinessWalletMemberEntity m = memberOf(access, memberWalletId);
        m.changeRole(role, Instant.now());
        members.saveAndFlush(m);
        audit(owner, "BUSINESS_WALLET_MEMBER_ROLE_CHANGED", access, "role=" + role + " member=" + memberWalletId);
        String name = users.findById(m.getUserId()).map(u -> WalletIdentityService.maskName(u.getFullName())).orElse("PULA user");
        return new MemberView(memberWalletId, name, role.name(), false);
    }

    @Transactional
    public void remove(User owner, BusinessWalletAccessService.Access access, String memberWalletId) {
        BusinessWalletMemberEntity m = memberOf(access, memberWalletId);
        members.delete(m);
        audit(owner, "BUSINESS_WALLET_MEMBER_REMOVED", access, "member=" + memberWalletId);
    }

    // ---------------------------------------------------------------- helpers

    private BusinessWalletMemberEntity memberOf(BusinessWalletAccessService.Access access, String memberWalletId) {
        User u = identity.userForPublicId(memberWalletId).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Member not found"));
        return members.findByBusinessIdAndUserId(access.businessId(), u.getId())
                .orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Member not found"));
    }

    private static BusinessWalletRole delegableRole(String name) {
        try {
            BusinessWalletRole r = BusinessWalletRole.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
            if (r.isDelegable()) return r;
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw WalletException.badRequest("INVALID_ROLE", "Role must be FINANCE or STAFF");
    }

    private boolean isOwner(BusinessWalletAccessService.Access access, User candidate) {
        return businesses.findById(access.businessId()).map(b -> b.getOwner() != null && candidate.getId().equals(b.getOwner().getId())).orElse(false);
    }

    private void audit(User actor, String action, BusinessWalletAccessService.Access access, String details) {
        try {
            audit.record(actor, action, "BUSINESS_WALLET", access.walletPublicId(), details);
        } catch (RuntimeException ex) {
            log.error("Audit entry for {} could not be written ({})", action, ex.getClass().getSimpleName());
        }
    }
}
