package com.semanticcache.backend.dto;

import java.util.Objects;

/**
 * Immutable search filter representing privacy and visibility constraints for vector queries.
 * Instances can only be constructed by trusted backend code via static factory methods.
 */
public final class SearchFilter {

    private final String ownerId;
    private final boolean publicOnly;

    private SearchFilter(String ownerId, boolean publicOnly) {
        this.ownerId = ownerId;
        this.publicOnly = publicOnly;
    }

    /**
     * Creates a public-only filter for unauthenticated callers.
     * Matches only records explicitly designated with PUBLIC visibility.
     *
     * @return a public-only SearchFilter instance
     */
    public static SearchFilter publicOnly() {
        return new SearchFilter(null, true);
    }

    /**
     * Creates an ownership filter for an authenticated caller.
     * Matches records owned by the verified owner or records designated with PUBLIC visibility.
     *
     * @param verifiedOwnerId the trusted, verified identity of the caller
     * @return a caller-specific SearchFilter instance
     * @throws IllegalArgumentException if verifiedOwnerId is null, blank, or equals "anonymous"
     */
    public static SearchFilter forOwner(String verifiedOwnerId) {
        if (verifiedOwnerId == null || verifiedOwnerId.trim().isEmpty()) {
            throw new IllegalArgumentException("verifiedOwnerId must not be null or blank");
        }
        if ("anonymous".equalsIgnoreCase(verifiedOwnerId.trim())) {
            throw new IllegalArgumentException(
                    "The identifier 'anonymous' cannot be used as an authenticated owner identity"
            );
        }
        return new SearchFilter(verifiedOwnerId.trim(), false);
    }

    public String ownerId() {
        return ownerId;
    }

    public boolean isPublicOnly() {
        return publicOnly;
    }

    public boolean hasOwner() {
        return ownerId != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SearchFilter that)) return false;
        return publicOnly == that.publicOnly && Objects.equals(ownerId, that.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, publicOnly);
    }

    @Override
    public String toString() {
        return publicOnly ? "SearchFilter[publicOnly]" : "SearchFilter[ownerId=" + ownerId + "]";
    }
}
