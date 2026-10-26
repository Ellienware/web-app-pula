package com.momocircle.service.wallet;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.wallet.BusinessWalletProfileEntity;
import com.momocircle.domain.wallet.WalletProfileEntity;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.wallet.BusinessWalletProfileRepository;
import com.momocircle.repository.wallet.WalletProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps between internal users and the opaque wallet ids shown in the UI. Reuses the existing {@code users} table (no second
 * identity store). Wallet ids are random, carry no personal data, and are the only way another user is addressed.
 *
 * <p>Not {@code @Transactional}: each repository call is its own short transaction and a lost creation race is resolved by
 * the unique constraint, then re-read.
 */
@Service
public class WalletIdentityService {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
    private static final int ID_LEN = 10;
    private final SecureRandom random = new SecureRandom();

    private final WalletProfileRepository profiles;
    private final UserRepository users;
    private final BusinessWalletProfileRepository businessProfiles;
    private final BusinessRepository businesses;

    public WalletIdentityService(WalletProfileRepository profiles, UserRepository users,
                                 BusinessWalletProfileRepository businessProfiles, BusinessRepository businesses) {
        this.profiles = profiles;
        this.users = users;
        this.businessProfiles = businessProfiles;
        this.businesses = businesses;
    }

    /** The business wallet's public id ({@code BW-...}), created on first use. */
    public String businessPublicIdFor(String businessId) {
        return businessProfiles.findById(businessId).map(BusinessWalletProfileEntity::getPublicId).orElseGet(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    businessProfiles.saveAndFlush(new BusinessWalletProfileEntity(businessId, "BW-" + randomId(), Instant.now()));
                    break;
                } catch (DataIntegrityViolationException lostRace) {
                    if (businessProfiles.findById(businessId).isPresent()) break;
                }
            }
            return businessProfiles.findById(businessId).map(BusinessWalletProfileEntity::getPublicId)
                    .orElseThrow(() -> new IllegalStateException("Business wallet id could not be created"));
        });
    }

    /** The ACTIVE business behind a business wallet id, or empty. */
    public Optional<Business> businessForPublicId(String publicId) {
        if (publicId == null) return Optional.empty();
        return businessProfiles.findByPublicId(publicId.trim().toUpperCase(Locale.ROOT))
                .flatMap(p -> businesses.findById(p.getBusinessId()))
                .filter(Business::isActive);
    }

    /** The user's public wallet id, created on first use. */
    public String publicIdFor(String userId) {
        return profiles.findById(userId).map(WalletProfileEntity::getPublicId).orElseGet(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    profiles.saveAndFlush(new WalletProfileEntity(userId, "PW-" + randomId(), Instant.now()));
                    break;
                } catch (DataIntegrityViolationException lostRace) {
                    if (profiles.findById(userId).isPresent()) break; // a concurrent request created it
                    // otherwise a public-id collision: retry with a new random id
                }
            }
            return profiles.findById(userId).map(WalletProfileEntity::getPublicId)
                    .orElseThrow(() -> new IllegalStateException("Wallet id could not be created"));
        });
    }

    /** The active user behind a wallet id, or empty. Suspended users are indistinguishable from unknown ones. */
    public Optional<User> userForPublicId(String publicId) {
        if (publicId == null) return Optional.empty();
        return profiles.findByPublicId(publicId.trim().toUpperCase(Locale.ROOT))
                .flatMap(p -> users.findById(p.getUserId()))
                .filter(User::isActive);
    }

    /**
     * Finds an eligible recipient from something the sender already knows: a wallet id, an exact email or an exact phone
     * number. There is deliberately no partial or fuzzy search, so this cannot be used to browse users. Callers rate-limit it.
     */
    public Optional<User> lookup(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String id = identifier.trim();
        Optional<User> found;
        if (id.toUpperCase(Locale.ROOT).startsWith("PW-")) {
            found = userForPublicId(id);
        } else if (id.contains("@")) {
            found = users.findByEmail(id.toLowerCase(Locale.ROOT));
            if (found.isEmpty()) found = users.findByEmail(id);
        } else {
            String compact = id.replaceAll("[\\s-]", "");
            found = users.findByPhoneNumber(compact);
            if (found.isEmpty() && !compact.startsWith("+")) found = users.findByPhoneNumber("+" + compact);
        }
        return found.filter(User::isActive);
    }

    /** "Thandi Mokoena" -> "Thandi M."; never the email, phone or full surname. */
    public static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "PULA user";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + parts[parts.length - 1].substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }

    private String randomId() {
        StringBuilder sb = new StringBuilder(ID_LEN);
        for (int i = 0; i < ID_LEN; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
