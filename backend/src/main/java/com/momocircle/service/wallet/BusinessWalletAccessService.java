package com.momocircle.service.wallet;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletMemberEntity;
import com.momocircle.domain.wallet.BusinessWalletRole;
import com.momocircle.domain.wallet.BusinessWalletRole.Permission;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.wallet.BusinessWalletMemberRepository;
import com.momocircle.wallet.WalletException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * THE gate for business wallets. Who may act on a business wallet is decided here and only here, from server-side facts:
 * the business's owner (derived from {@code businesses.owner_id}) or a row in {@code business_wallet_members}. A business is a
 * separate identity from its owner: the owner's personal wallet and the business wallet are different ledger accounts, and a
 * user who merely knows a business wallet id gets "not found". Platform ADMIN does not imply business access.
 */
@Service
public class BusinessWalletAccessService {

    /** A user's verified access to one business wallet. */
    public record Access(String businessId, String walletPublicId, String businessName, BusinessWalletRole role) {
        public boolean allows(Permission p) { return role.allows(p); }
    }

    private final BusinessRepository businesses;
    private final BusinessWalletMemberRepository members;
    private final WalletIdentityService identity;

    public BusinessWalletAccessService(BusinessRepository businesses, BusinessWalletMemberRepository members, WalletIdentityService identity) {
        this.businesses = businesses;
        this.members = members;
        this.identity = identity;
    }

    /**
     * The user's access to the business behind {@code walletPublicId} with at least {@code permission}. Unknown wallet,
     * inactive business and "not a member" are indistinguishable (404); a member lacking the permission gets 403.
     */
    public Access require(User user, String walletPublicId, Permission permission) {
        Access a = find(user, walletPublicId).orElseThrow(() -> WalletException.notFound("NOT_FOUND", "Business wallet not found"));
        if (!a.allows(permission)) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "Your role on this business wallet does not allow that");
        }
        return a;
    }

    public Optional<Access> find(User user, String walletPublicId) {
        return identity.businessForPublicId(walletPublicId).flatMap(b -> accessTo(user, b));
    }

    /** Access of {@code user} to the business with internal id {@code businessId} (used when a request already points at it). */
    public Optional<Access> findByBusinessId(User user, String businessId) {
        return businesses.findById(businessId).filter(Business::isActive).flatMap(b -> accessTo(user, b));
    }

    /** Every active business wallet the user can act on (as owner or member), for the "my business wallets" list. */
    public List<Access> accessibleTo(User user) {
        List<Access> out = new ArrayList<>();
        businesses.findByOwnerId(user.getId()).filter(Business::isActive).flatMap(b -> accessTo(user, b)).ifPresent(out::add);
        for (BusinessWalletMemberEntity m : members.findByUserId(user.getId())) {
            businesses.findById(m.getBusinessId()).filter(Business::isActive).flatMap(b -> accessTo(user, b)).ifPresent(a -> {
                if (out.stream().noneMatch(x -> x.businessId().equals(a.businessId()))) out.add(a);
            });
        }
        return out;
    }

    private Optional<Access> accessTo(User user, Business b) {
        if (!b.isActive()) return Optional.empty();
        BusinessWalletRole role = null;
        if (b.getOwner() != null && user.getId().equals(b.getOwner().getId())) {
            role = BusinessWalletRole.OWNER;
        } else {
            Optional<BusinessWalletMemberEntity> m = members.findByBusinessIdAndUserId(b.getId(), user.getId());
            if (m.isPresent()) role = m.get().getRole();
        }
        if (role == null) return Optional.empty();
        return Optional.of(new Access(b.getId(), identity.businessPublicIdFor(b.getId()), b.getName(), role));
    }
}
