package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
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

class CacheControllerTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("Valid prompt produces expected CacheResponse")
    void handleCacheRequestWithValidPrompt() {
        CacheController controller = new CacheController();
        CacheRequest request = new CacheRequest("What is semantic caching?");

        CacheResponse response = controller.handleCacheRequest(request);

        assertNotNull(response);
        assertEquals(
                "This is a temporary mocked backend response for your prompt: 'What is semantic caching?'",
                response.answer()
        );
        assertFalse(response.cacheHit());
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
}
