package com.semanticcache.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorRecordTest {

    private static final UUID VALID_ID = UUID.randomUUID();
    private static final String VALID_PROMPT = "What is semantic caching?";
    private static final String VALID_ANSWER = "Semantic caching stores embeddings and responses.";
    private static final String VALID_MODEL = "gemini-3.8-flash";
    private static final Instant VALID_CREATED_AT = Instant.now();
    private static final String VALID_EMBEDDING_MODEL = "gemini-embedding-001";

    private List<Float> createValidVector(int size) {
        List<Float> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add((float) i / 100.0f);
        }
        return list;
    }

    @Nested
    @DisplayName("Valid Construction and Immutability Tests")
    class ValidConstructionTests {

        @Test
        @DisplayName("Constructs VectorRecord with valid values and trims strings")
        void constructsValidRecord() {
            List<Float> vector = createValidVector(768);
            VectorRecord record = new VectorRecord(
                    VALID_ID,
                    vector,
                    "  " + VALID_PROMPT + "  ",
                    "  " + VALID_ANSWER + "  ",
                    "  " + VALID_MODEL + "  ",
                    VALID_CREATED_AT,
                    "  " + VALID_EMBEDDING_MODEL + "  "
            );

            assertEquals(VALID_ID, record.id());
            assertEquals(768, record.vector().size());
            assertEquals(VALID_PROMPT, record.prompt());
            assertEquals(VALID_ANSWER, record.answer());
            assertEquals(VALID_MODEL, record.model());
            assertEquals(VALID_CREATED_AT, record.createdAt());
            assertEquals(VALID_EMBEDDING_MODEL, record.embeddingModel());
        }

        @Test
        @DisplayName("Enforces defensive immutability of the vector")
        void defensiveImmutability() {
            List<Float> sourceList = createValidVector(768);
            VectorRecord record = new VectorRecord(
                    VALID_ID, sourceList, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            );

            // Modifying source list must not affect record
            sourceList.set(0, 999.0f);
            assertEquals(0.0f, record.vector().get(0));

            // Record vector must be unmodifiable
            assertThrows(UnsupportedOperationException.class, () -> record.vector().add(1.0f));
        }

        @Test
        @DisplayName("Overloaded constructor and of() factory validate expected dimension")
        void dimensionValidationSuccess() {
            List<Float> vector = createValidVector(768);

            assertDoesNotThrow(() -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, 768
            ));

            VectorRecord factoryRecord = VectorRecord.of(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, 768
            );
            assertNotNull(factoryRecord);
            assertEquals(768, factoryRecord.vector().size());
        }
    }

    @Nested
    @DisplayName("Vector Validation Tests")
    class VectorValidationTests {

        @Test
        @DisplayName("Rejects null vector")
        void rejectsNullVector() {
            assertThrows(NullPointerException.class, () -> new VectorRecord(
                    VALID_ID, null, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects empty vector")
        void rejectsEmptyVector() {
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, Collections.emptyList(), VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects vector with null elements")
        void rejectsVectorWithNullElements() {
            List<Float> vector = createValidVector(768);
            vector.set(10, null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
            assertTrue(ex.getMessage().contains("must not be null"));
        }

        @Test
        @DisplayName("Rejects vector with NaN values")
        void rejectsVectorWithNaN() {
            List<Float> vector = createValidVector(768);
            vector.set(5, Float.NaN);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
            assertTrue(ex.getMessage().contains("finite numerical values"));
        }

        @Test
        @DisplayName("Rejects vector with positive/negative infinity values")
        void rejectsVectorWithInfinity() {
            List<Float> vectorPos = createValidVector(768);
            vectorPos.set(0, Float.POSITIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vectorPos, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));

            List<Float> vectorNeg = createValidVector(768);
            vectorNeg.set(0, Float.NEGATIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vectorNeg, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects vector dimension mismatch when expectedDimension is specified")
        void rejectsDimensionMismatch() {
            List<Float> vector = createValidVector(512); // Expected 768

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VectorRecord.of(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, 768
            ));
            assertTrue(ex.getMessage().contains("does not match expected dimension"));
        }

        @Test
        @DisplayName("Rejects non-positive expectedDimension")
        void rejectsNonPositiveExpectedDimension() {
            List<Float> vector = createValidVector(768);

            assertThrows(IllegalArgumentException.class, () -> VectorRecord.of(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, 0
            ));
            assertThrows(IllegalArgumentException.class, () -> VectorRecord.of(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL, -1
            ));
        }
    }

    @Nested
    @DisplayName("Metadata Validation Tests")
    class MetadataValidationTests {

        @Test
        @DisplayName("Rejects null ID")
        void rejectsNullId() {
            List<Float> vector = createValidVector(768);
            assertThrows(NullPointerException.class, () -> new VectorRecord(
                    null, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects null or blank prompt")
        void rejectsNullOrBlankPrompt() {
            List<Float> vector = createValidVector(768);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, null, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, "   ", VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects null or blank answer")
        void rejectsNullOrBlankAnswer() {
            List<Float> vector = createValidVector(768);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, null, VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, "   ", VALID_MODEL, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects null or blank model")
        void rejectsNullOrBlankModel() {
            List<Float> vector = createValidVector(768);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, null, VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, "   ", VALID_CREATED_AT, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects null createdAt")
        void rejectsNullCreatedAt() {
            List<Float> vector = createValidVector(768);
            assertThrows(NullPointerException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, null, VALID_EMBEDDING_MODEL
            ));
        }

        @Test
        @DisplayName("Rejects null or blank embeddingModel")
        void rejectsNullOrBlankEmbeddingModel() {
            List<Float> vector = createValidVector(768);
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, null
            ));
            assertThrows(IllegalArgumentException.class, () -> new VectorRecord(
                    VALID_ID, vector, VALID_PROMPT, VALID_ANSWER, VALID_MODEL, VALID_CREATED_AT, "   "
            ));
        }
    }
}
