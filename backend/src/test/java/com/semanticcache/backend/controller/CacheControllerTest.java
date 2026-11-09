package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import com.semanticcache.backend.service.CacheService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheControllerTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("Valid prompt delegates to CacheService and returns CacheResponse")
    void handleCacheRequestWithValidPrompt() {
        CacheService mockCacheService = mock(CacheService.class);
        CacheController controller = new CacheController(mockCacheService);
        CacheRequest request = new CacheRequest("What is semantic caching?");

        when(mockCacheService.processPrompt(request))
                .thenReturn(new CacheResponse("Semantic caching uses embeddings to find similar queries.", false));

        CacheResponse response = controller.handleCacheRequest(request);

        assertNotNull(response);
        assertEquals("Semantic caching uses embeddings to find similar queries.", response.answer());
        assertFalse(response.cacheHit());
        verify(mockCacheService).processPrompt(request);
    }

    @Test
    @DisplayName("Blank prompt triggers validation constraint violation")
    void cacheRequestValidationFailsOnBlankPrompt() {
        CacheRequest blankRequest = new CacheRequest("   ");
        Set<ConstraintViolation<CacheRequest>> violations = validator.validate(blankRequest);

        assertFalse(violations.isEmpty());
        assertEquals("Prompt cannot be blank", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("Null prompt triggers validation constraint violation")
    void cacheRequestValidationFailsOnNullPrompt() {
        CacheRequest nullRequest = new CacheRequest(null);
        Set<ConstraintViolation<CacheRequest>> violations = validator.validate(nullRequest);

        assertFalse(violations.isEmpty());
        assertEquals("Prompt cannot be blank", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("Valid prompt passes validation with no violations")
    void cacheRequestValidationPassesOnValidPrompt() {
        CacheRequest validRequest = new CacheRequest("Explain quantum computing");
        Set<ConstraintViolation<CacheRequest>> violations = validator.validate(validRequest);

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("Prompt with exactly 4000 characters passes validation with no violations")
    void cacheRequestValidationPassesOnBoundaryLengthPrompt() {
        String boundaryPrompt = "a".repeat(4000);
        CacheRequest validRequest = new CacheRequest(boundaryPrompt);
        Set<ConstraintViolation<CacheRequest>> violations = validator.validate(validRequest);

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("Prompt exceeding 4000 characters triggers validation constraint violation")
    void cacheRequestValidationFailsOnOverLimitPrompt() {
        String overLimitPrompt = "a".repeat(4001);
        CacheRequest invalidRequest = new CacheRequest(overLimitPrompt);
        Set<ConstraintViolation<CacheRequest>> violations = validator.validate(invalidRequest);

        assertFalse(violations.isEmpty());
        assertEquals("Prompt cannot exceed 4000 characters", violations.iterator().next().getMessage());
    }
}
