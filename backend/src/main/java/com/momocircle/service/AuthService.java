package com.momocircle.service;

import com.momocircle.domain.User;
import com.momocircle.dto.AuthDtos.AuthResponse;
import com.momocircle.dto.AuthDtos.LoginRequest;
import com.momocircle.dto.AuthDtos.RegisterRequest;
import com.momocircle.dto.AuthDtos.UserResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.UserRepository;
import com.momocircle.security.JwtService;
import com.momocircle.security.LoginRateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(req.fullName().trim());
        user.setPhoneNumber(req.phoneNumber().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        loginRateLimiter.checkAllowed(email);

        User user = userRepository.findByEmail(email).orElse(null);
        // Deliberately the same failure path (message + rate-limit bookkeeping) whether the
        // email doesn't exist or the password is wrong — same as the unchanged "Invalid email
        // or password" message already did, this also means an attacker probing many emails
        // gets rate-limited per-email just like repeated guesses against one real account do.
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            loginRateLimiter.recordFailure(email);
            throw ApiException.unauthorized("Invalid email or password");
        }
        if (!user.isActive()) {
            throw ApiException.unauthorized("This account has been suspended");
        }
        loginRateLimiter.recordSuccess(email);
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, toUserResponse(user));
    }

    public UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhoneNumber(), user.getRole());
    }
}
