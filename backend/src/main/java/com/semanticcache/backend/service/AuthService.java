package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.AuthSession;
import com.semanticcache.backend.dto.LoginRequest;
import com.semanticcache.backend.dto.RegisterRequest;
import com.semanticcache.backend.dto.RotationResult;
import com.semanticcache.backend.entity.UserEntity;
import com.semanticcache.backend.exception.AccountDisabledException;
import com.semanticcache.backend.exception.InvalidCredentialsException;
import com.semanticcache.backend.exception.InvalidRefreshTokenException;
import com.semanticcache.backend.exception.TokenReuseException;
import com.semanticcache.backend.exception.UserAlreadyExistsException;
import com.semanticcache.backend.repository.UserRepository;
import com.semanticcache.backend.util.RefreshTokenUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Authentication service coordinating user registration, credential verification,
 * and refresh token rotation.
 * <p>
 * IMPORTANT: Refresh token rotation orchestrations ({@link #rotateSession(String)} and
 * {@link #rotateRefreshToken(String)}) are intentionally non-transactional. They call the transactional
 * {@link RefreshTokenWorker} to perform atomic rotation and family revocation. If token reuse
 * is detected, {@link TokenReuseException} is thrown only after the worker transaction has
 * committed its changes to the database, guaranteeing family-wide revocation persistence.
 */
@Service
public class AuthService {

    private final RefreshTokenWorker refreshTokenWorker;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            RefreshTokenWorker refreshTokenWorker,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.refreshTokenWorker = Objects.requireNonNull(refreshTokenWorker, "RefreshTokenWorker must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "PasswordEncoder must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "JwtService must not be null");
    }

    /**
     * Atomically registers a new user, hashes the raw password, persists the user record,
     * creates their initial refresh token, and issues an access token.
     *
     * @param request validated registration request
     * @return populated {@link AuthSession}
     * @throws UserAlreadyExistsException if the username is already taken
     */
    @Transactional
    public AuthSession register(RegisterRequest request) {
        Objects.requireNonNull(request, "RegisterRequest must not be null");
        String username = request.username().trim();

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException("Username is already taken");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity(username, passwordHash);
        user = userRepository.saveAndFlush(user);

        String rawRefreshToken = refreshTokenWorker.createInitialToken(user, null);
        String accessToken = jwtService.generateAccessToken(user.getId());

        return new AuthSession(accessToken, rawRefreshToken, user.getId(), user.getUsername());
    }

    /**
     * Verifies user credentials, checks if the account is active, and issues a new refresh token
     * and access token.
     *
     * @param request validated login request
     * @return populated {@link AuthSession}
     * @throws InvalidCredentialsException if the username or password does not match
     * @throws AccountDisabledException   if the account exists but has been disabled
     */
    public AuthSession login(LoginRequest request) {
        Objects.requireNonNull(request, "LoginRequest must not be null");
        String username = request.username().trim();

        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        if (!user.isEnabled()) {
            throw new AccountDisabledException("Account is disabled");
        }

        String rawRefreshToken = refreshTokenWorker.createInitialToken(user, null);
        String accessToken = jwtService.generateAccessToken(user.getId());

        return new AuthSession(accessToken, rawRefreshToken, user.getId(), user.getUsername());
    }

    /**
     * Orchestrates refresh token rotation using a presented raw refresh token.
     * Intentionally non-transactional to ensure worker commit on reuse persists before throwing.
     *
     * @param rawRefreshToken the raw refresh token string provided by the client
     * @return populated {@link AuthSession}
     * @throws TokenReuseException           if the token was already revoked (family revoked)
     * @throws InvalidRefreshTokenException if the token is null, malformed, expired, unknown, or user is disabled
     */
    public AuthSession rotateSession(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }

        String tokenHash = RefreshTokenUtil.hashToken(rawRefreshToken);
        RotationResult result = refreshTokenWorker.rotate(tokenHash);

        if (result.isReuseDetected()) {
            throw new TokenReuseException("Refresh token reuse detected; token family has been revoked");
        }

        if (result.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token is expired");
        }

        if (result.isInvalid() || !result.isSuccess()) {
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }

        UserEntity user = userRepository.findById(result.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User not found"));

        if (!user.isEnabled()) {
            throw new InvalidRefreshTokenException("User account is disabled");
        }

        String accessToken = jwtService.generateAccessToken(user.getId());
        return new AuthSession(accessToken, result.getNewRawToken(), user.getId(), user.getUsername());
    }

    /**
     * Backward-compatible token rotation method returning raw refresh token string.
     *
     * @param rawRefreshToken client presented token
     * @return newly generated raw refresh token
     */
    public String rotateRefreshToken(String rawRefreshToken) {
        return rotateSession(rawRefreshToken).rawRefreshToken();
    }

    /**
     * Idempotently revokes the presented refresh token during logout.
     *
     * @param rawRefreshToken raw refresh token string from cookie
     */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = RefreshTokenUtil.hashToken(rawRefreshToken);
            refreshTokenWorker.revokeToken(tokenHash);
        }
    }
}
