package com.semanticcache.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request payload containing user authentication credentials.
 * <p>
 * Does not impose registration-only minimum length constraints on passwords,
 * while enforcing the native BCrypt 72-byte limit to prevent processing
 * passwords that could not have been validly registered.
 */
public record LoginRequest(
        @NotBlank(message = "Username cannot be blank")
        @Size(max = 50, message = "Username cannot exceed 50 characters")
        String username,

        @NotBlank(message = "Password cannot be blank")
        @Size(max = 100, message = "Password cannot exceed 100 characters")
        @RegisterRequest.MaxBytes(72)
        String password
) {
}
