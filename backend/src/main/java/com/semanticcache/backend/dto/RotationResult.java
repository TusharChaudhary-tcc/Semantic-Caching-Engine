package com.semanticcache.backend.dto;

import java.util.Objects;

/**
 * Structured result returned by {@link com.semanticcache.backend.service.RefreshTokenWorker}
 * to communicate the outcome of a transactional rotation attempt without throwing rollback-inducing exceptions.
 */
public class RotationResult {

    public enum Status {
        SUCCESS,
        REUSE_DETECTED,
        EXPIRED,
        INVALID
    }

    private final Status status;
    private final String newRawToken;
    private final String familyId;
    private final String userId;

    private RotationResult(Status status, String newRawToken, String familyId, String userId) {
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.newRawToken = newRawToken;
        this.familyId = familyId;
        this.userId = userId;
    }

    public static RotationResult success(String newRawToken, String familyId, String userId) {
        Objects.requireNonNull(newRawToken, "newRawToken must not be null for successful rotation");
        Objects.requireNonNull(familyId, "familyId must not be null for successful rotation");
        return new RotationResult(Status.SUCCESS, newRawToken, familyId, userId);
    }

    public static RotationResult reuseDetected(String familyId) {
        Objects.requireNonNull(familyId, "familyId must not be null when reuse is detected");
        return new RotationResult(Status.REUSE_DETECTED, null, familyId, null);
    }

    public static RotationResult expired() {
        return new RotationResult(Status.EXPIRED, null, null, null);
    }

    public static RotationResult invalid() {
        return new RotationResult(Status.INVALID, null, null, null);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isReuseDetected() {
        return status == Status.REUSE_DETECTED;
    }

    public boolean isExpired() {
        return status == Status.EXPIRED;
    }

    public boolean isInvalid() {
        return status == Status.INVALID;
    }

    public Status getStatus() {
        return status;
    }

    public String getNewRawToken() {
        return newRawToken;
    }

    public String getFamilyId() {
        return familyId;
    }

    public String getUserId() {
        return userId;
    }

    @Override
    public String toString() {
        return "RotationResult[status=" + status + ", familyId=" + familyId + "]";
    }
}
