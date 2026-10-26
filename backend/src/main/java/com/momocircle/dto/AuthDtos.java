package com.momocircle.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Auth request/response payloads grouped in one file for cohesion. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank String fullName,
            @NotBlank String phoneNumber,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(String token, UserResponse user) {}

    /**
     * The signed-in user. {@code role} lets clients show or hide admin navigation; it is a display hint only - every admin
     * endpoint still enforces ADMIN on the server.
     */
    public record UserResponse(String id, String email, String fullName, String phoneNumber,
                               com.momocircle.domain.enums.PlatformRole role) {
        /** Kept so existing callers/tests that predate the role field still compile; role defaults to USER. */
        public UserResponse(String id, String email, String fullName, String phoneNumber) {
            this(id, email, fullName, phoneNumber, com.momocircle.domain.enums.PlatformRole.USER);
        }
    }
}
