package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultTest {

    private static final UUID VALID_ID = UUID.randomUUID();
    private static final String VALID_PROMPT = "What is semantic caching?";
    private static final String VALID_ANSWER = "Semantic caching accelerates LLM queries.";
    private static final String VALID_MODEL = "gemini-3.8-flash";
    private static final Instant VALID_CREATED_AT = Instant.parse("2026-11-07T20:00:00Z");
    private static final String VALID_EMBEDDING_MODEL = "gemini-embedding-001";
    private static final float VALID_SCORE = 0.88f;

    @Nested
    @DisplayName("Valid Construction and Trimming Tests")
    class ValidConstructionTests {

        @Test
        @DisplayName("Constructs SearchResult successfully with valid fields")
        void constructsValidSearchResult() {
            SearchResult result = assertDoesNotThrow(() -> new SearchResult(
                    VALID_ID,
                    VALID_PROMPT,
                    VALID_ANSWER,
                    VALID_MODEL,
                    VALID_CREATED_AT,
                    VALID_EMBEDDING_MODEL,
                    VALID_SCORE
            ));

            assertEquals(VALID_ID, result.id());
            assertEquals(VALID_PROMPT, result.prompt());
            assertEquals(VALID_ANSWER, result.answer());
            assertEquals(VALID_MODEL, result.model());
            assertEquals(VALID_CREATED_AT, result.createdAt());
            assertEquals(VALID_EMBEDDING_MODEL, result.embeddingModel());
            assertEquals(VALID_SCORE, result.score(), 1e-6);
            assertNull(result.ownerId());
            assertEquals("PRIVATE", result.visibility());
        }

        @Test
        @DisplayName("Trims whitespace from string fields consistently")
        void trimsWhitespace() {
            SearchResult result = new SearchResult(
                    VALID_ID,
                    "   " + VALID_PROMPT + "   ",
                    " \t" + VALID_ANSWER + " \n",
                    "  " + VALID_MODEL + "  ",
                    VALID_CREATED_AT,
                    "  " + VALID_EMBEDDING_MODEL + "  ",
                    VALID_SCORE
            );

            assertEquals(VALID_PROMPT, result.prompt());
            assertEquals(VALID_ANSWER, result.answer());
            assertEquals(VALID_MODEL, result.model());
            assertEquals(VALID_EMBEDDING_MODEL, result.embeddingModel());
        }

        @Test
        @DisplayName("Accepts zero and negative finite scores (e.g. Cosine metric range)")
        void acceptsZeroAndNegativeScores() {
            SearchResult zeroScore = assertDoesNotThrow(() -> new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, 0.0f
            ));
            assertEquals(0.0f, zeroScore.score(), 1e-6);

            SearchResult negativeScore = assertDoesNotThrow(() -> new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, -0.42f
            ));
            assertEquals(-0.42f, negativeScore.score(), 1e-6);
        }
    }

    @Nested
    @DisplayName("Identifier and Timestamp Validation Tests")
    class IdAndTimestampTests {

        @Test
        @DisplayName("Rejects null UUID with NullPointerException")
        void rejectsNullId() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new SearchResult(
                            null,
                            VALID_PROMPT,
                            VALID_ANSWER,
                            VALID_MODEL,
                            VALID_CREATED_AT,
                            VALID_EMBEDDING_MODEL,
                            VALID_SCORE
                    )
            );
            assertTrue(ex.getMessage().contains("id must not be null"));
        }

        @Test
        @DisplayName("Rejects null createdAt with NullPointerException")
        void rejectsNullCreatedAt() {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> new SearchResult(
                            VALID_ID,
                            VALID_PROMPT,
                            VALID_ANSWER,
                            VALID_MODEL,
                            null,
                            VALID_EMBEDDING_MODEL,
                            VALID_SCORE
                    )
            );
            assertTrue(ex.getMessage().contains("createdAt must not be null"));
        }
    }

    @Nested
    @DisplayName("String Fields Validation Tests")
    class StringFieldsValidationTests {

        @Test
        @DisplayName("Rejects null prompt")
        void rejectsNullPrompt() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, null, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("prompt must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects blank prompt")
        void rejectsBlankPrompt() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, "   ", VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("prompt must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects null answer")
        void rejectsNullAnswer() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, null, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("answer must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects blank answer")
        void rejectsBlankAnswer() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, "   ", VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("answer must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects null model")
        void rejectsNullModel() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, null, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("model must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects blank model")
        void rejectsBlankModel() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, "   ", VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("model must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects null embeddingModel")
        void rejectsNullEmbeddingModel() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, null, VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("embeddingModel must not be null or blank"));
        }

        @Test
        @DisplayName("Rejects blank embeddingModel")
        void rejectsBlankEmbeddingModel() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, "   ", VALID_SCORE)
            );
            assertTrue(ex.getMessage().contains("embeddingModel must not be null or blank"));
        }
    }

    @Nested
    @DisplayName("Score Numerical Validation Tests")
    class ScoreValidationTests {

        @Test
        @DisplayName("Rejects NaN score with IllegalArgumentException")
        void rejectsNanScore() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, Float.NaN)
            );
            assertEquals("score must be a finite numerical value", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects Positive Infinity score with IllegalArgumentException")
        void rejectsPositiveInfinityScore() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, Float.POSITIVE_INFINITY)
            );
            assertEquals("score must be a finite numerical value", ex.getMessage());
        }

        @Test
        @DisplayName("Rejects Negative Infinity score with IllegalArgumentException")
        void rejectsNegativeInfinityScore() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, Float.NEGATIVE_INFINITY)
            );
            assertEquals("score must be a finite numerical value", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Privacy and Visibility Metadata Tests")
    class PrivacyAndVisibilityTests {

        @Test
        @DisplayName("Seven-argument constructor defaults ownerId to null and visibility to PRIVATE")
        void sevenArgConstructorDefaults() {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, VALID_SCORE
            );

            assertNull(result.ownerId());
            assertEquals("PRIVATE", result.visibility());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Nine-argument constructor defaults null or blank visibility to PRIVATE")
        void defaultsNullOrBlankVisibilityToPrivate(String blankVisibility) {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                    VALID_SCORE, "owner123", blankVisibility
            );

            assertEquals("owner123", result.ownerId());
            assertEquals("PRIVATE", result.visibility());
        }

        @ParameterizedTest
        @ValueSource(strings = {"public", "PUBLIC", "  public  ", "pUbLiC"})
        @DisplayName("Normalizes valid PUBLIC visibility values to uppercase")
        void normalizesPublicVisibility(String publicVal) {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                    VALID_SCORE, null, publicVal
            );

            assertEquals("PUBLIC", result.visibility());
        }

        @ParameterizedTest
        @ValueSource(strings = {"private", "PRIVATE", "  private  "})
        @DisplayName("Normalizes valid PRIVATE visibility values to uppercase")
        void normalizesPrivateVisibility(String privateVal) {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                    VALID_SCORE, null, privateVal
            );

            assertEquals("PRIVATE", result.visibility());
        }

        @ParameterizedTest
        @ValueSource(strings = {"SHARED", "shared", "RESTRICTED", "internal", "GLOBAL"})
        @DisplayName("Rejects unsupported visibility values with IllegalArgumentException")
        void rejectsUnsupportedVisibility(String unsupported) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchResult(
                            VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                            VALID_SCORE, null, unsupported
                    )
            );
            assertTrue(ex.getMessage().contains("visibility must be either 'PRIVATE' or 'PUBLIC'"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Normalizes blank or empty ownerId to null")
        void normalizesBlankOwnerIdToNull(String blankOwner) {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                    VALID_SCORE, blankOwner, "PUBLIC"
            );

            assertNull(result.ownerId());
            assertEquals("PUBLIC", result.visibility());
        }

        @Test
        @DisplayName("Trims valid ownerId")
        void trimsValidOwnerId() {
            SearchResult result = new SearchResult(
                    VALID_ID, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL,
                    VALID_SCORE, "  user-xyz  ", "PUBLIC"
            );

            assertEquals("user-xyz", result.ownerId());
        }
    }
}
