package com.momocircle.security;

import com.momocircle.domain.User;
import com.momocircle.domain.enums.PlatformRole;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves the authenticated User from the JWT-populated security context. */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw ApiException.unauthorized("Not authenticated");
        }
        String userId = auth.getPrincipal().toString();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        if (!user.isActive()) {
            throw ApiException.unauthorized("This account has been suspended");
        }
        return user;
    }

    /**
     * The signed-in, active user if the request carries a valid identity, otherwise empty. For endpoints that are public but show
     * more to the owner or an admin; never throws for anonymous callers.
     */
    public java.util.Optional<User> optional() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || auth.getPrincipal() == null) {
            return java.util.Optional.empty();
        }
        return userRepository.findById(auth.getPrincipal().toString()).filter(User::isActive);
    }

    /** Same as {@link #require()} but also asserts the caller holds the platform ADMIN role. */
    public User requireAdmin() {
        User user = require();
        if (user.getRole() != PlatformRole.ADMIN) {
            throw ApiException.forbidden("Admin access required");
        }
        return user;
    }
}
