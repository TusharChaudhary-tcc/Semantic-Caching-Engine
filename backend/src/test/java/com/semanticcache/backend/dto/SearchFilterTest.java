package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchFilterTest {

    @Test
    @DisplayName("SearchFilter.publicOnly() creates filter with publicOnly=true and null ownerId")
    void publicOnlyFactory() {
        SearchFilter filter = SearchFilter.publicOnly();

        assertTrue(filter.isPublicOnly());
        assertFalse(filter.hasOwner());
        assertNull(filter.ownerId());
    }

    @Test
    @DisplayName("SearchFilter.forOwner() creates filter with publicOnly=false and trimmed ownerId")
    void forOwnerFactory() {
        SearchFilter filter = SearchFilter.forOwner("  user-123  ");

        assertFalse(filter.isPublicOnly());
        assertTrue(filter.hasOwner());
        assertEquals("user-123", filter.ownerId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("SearchFilter.forOwner() rejects null, empty, or blank ownerId")
    void rejectsNullOrBlankOwnerId(String invalidOwnerId) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SearchFilter.forOwner(invalidOwnerId)
        );
        assertTrue(ex.getMessage().contains("verifiedOwnerId must not be null or blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "Anonymous", "ANONYMOUS", "  anonymous  "})
    @DisplayName("SearchFilter.forOwner() rejects 'anonymous' as owner identity")
    void rejectsAnonymousAsOwnerId(String anonymousId) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SearchFilter.forOwner(anonymousId)
        );
        assertTrue(ex.getMessage().contains("cannot be used as an authenticated owner identity"));
    }

    @Test
    @DisplayName("SearchFilter implements value-based equality and matching hash code")
    void valueBasedEquality() {
        SearchFilter public1 = SearchFilter.publicOnly();
        SearchFilter public2 = SearchFilter.publicOnly();
        assertEquals(public1, public2);
        assertEquals(public1.hashCode(), public2.hashCode());

        SearchFilter owner1 = SearchFilter.forOwner("user-123");
        SearchFilter owner2 = SearchFilter.forOwner("user-123");
        assertEquals(owner1, owner2);
        assertEquals(owner1.hashCode(), owner2.hashCode());

        SearchFilter ownerDiff = SearchFilter.forOwner("user-456");
        assertNotEquals(owner1, ownerDiff);
        assertNotEquals(public1, owner1);
    }
}
