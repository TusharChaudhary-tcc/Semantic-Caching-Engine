package com.semanticcache.backend.config;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantConfigTest {

    @Nested
    @DisplayName("Configuration Properties and Accessor Tests")
    class PropertyTests {

        @Test
        @DisplayName("Instantiates with valid properties without API key")
        void validPropertiesWithoutApiKey() {
            QdrantConfig config = new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 768);

            assertEquals("localhost", config.getHost());
            assertEquals(6334, config.getPort());
            assertFalse(config.isUseTls());
            assertFalse(config.isApiKeyConfigured());
            assertEquals("semantic_cache", config.getCollectionName());
            assertEquals(768, config.getVectorSize());
            assertEquals(10, config.getTimeoutSeconds());
        }

        @Test
        @DisplayName("Instantiates with custom positive timeout")
        void acceptsCustomPositiveTimeout() {
            QdrantConfig config = new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 768, 25);

            assertEquals(25, config.getTimeoutSeconds());
        }

        @Test
        @DisplayName("Instantiates with valid properties with API key")
        void validPropertiesWithApiKey() {
            QdrantConfig config = new QdrantConfig("qdrant.internal", 6334, true, "secret-test-key", "custom_cache", 768, 15);

            assertEquals("qdrant.internal", config.getHost());
            assertEquals(6334, config.getPort());
            assertTrue(config.isUseTls());
            assertTrue(config.isApiKeyConfigured());
            assertEquals("custom_cache", config.getCollectionName());
            assertEquals(768, config.getVectorSize());
            assertEquals(15, config.getTimeoutSeconds());
        }

        @Test
        @DisplayName("Null API key is normalized to empty string")
        void nullApiKeyNormalized() {
            QdrantConfig config = new QdrantConfig("localhost", 6334, false, null, "semantic_cache", 768);
            assertFalse(config.isApiKeyConfigured());
            assertEquals(10, config.getTimeoutSeconds());
        }
    }

    @Nested
    @DisplayName("Configuration Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null or blank host")
        void rejectsNullOrBlankHost() {
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig(null, 6334, false, "", "semantic_cache", 768));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("   ", 6334, false, "", "semantic_cache", 768));
        }

        @Test
        @DisplayName("Rejects invalid port numbers")
        void rejectsInvalidPort() {
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 0, false, "", "semantic_cache", 768));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", -1, false, "", "semantic_cache", 768));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 65536, false, "", "semantic_cache", 768));
        }

        @Test
        @DisplayName("Rejects null or blank collection name")
        void rejectsNullOrBlankCollectionName() {
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", null, 768));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", "   ", 768));
        }

        @Test
        @DisplayName("Rejects non-positive vector size")
        void rejectsNonPositiveVectorSize() {
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 0));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", "semantic_cache", -1));
        }

        @Test
        @DisplayName("Rejects non-positive timeout")
        void rejectsNonPositiveTimeout() {
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 768, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 768, -1));
        }
    }

    @Nested
    @DisplayName("QdrantClient Bean Creation and Lifecycle Tests")
    class ClientCreationTests {

        @Test
        @DisplayName("Creates QdrantClient bean without API key and closes cleanly")
        void createsClientWithoutApiKey() {
            QdrantConfig config = new QdrantConfig("localhost", 6334, false, "", "semantic_cache", 768);

            QdrantClient client = config.qdrantClient();
            assertNotNull(client);
            assertNotNull(client.grpcClient());

            assertDoesNotThrow(client::close);
        }

        @Test
        @DisplayName("Creates QdrantClient bean with API key and closes cleanly")
        void createsClientWithApiKey() {
            QdrantConfig config = new QdrantConfig("localhost", 6334, false, "test-api-key", "semantic_cache", 768);

            QdrantClient client = config.qdrantClient();
            assertNotNull(client);
            assertNotNull(client.grpcClient());

            assertDoesNotThrow(client::close);
        }
    }
}
