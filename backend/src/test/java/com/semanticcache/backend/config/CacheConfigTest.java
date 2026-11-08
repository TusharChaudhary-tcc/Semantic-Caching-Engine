package com.semanticcache.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class);

    @Nested
    @DisplayName("Direct Instantiation and Boundary Tests")
    class InstantiationTests {

        @ParameterizedTest
        @ValueSource(floats = {0.0f, 0.1f, 0.5f, 0.85f, 1.0f})
        @DisplayName("Accepts valid threshold values in range [0.0, 1.0]")
        void acceptsValidThresholds(float validThreshold) {
            CacheConfig config = new CacheConfig(validThreshold);
            assertEquals(validThreshold, config.getSimilarityThreshold(), 1e-6);
        }

        @ParameterizedTest
        @ValueSource(floats = {-0.01f, -0.5f, -1.0f})
        @DisplayName("Rejects negative threshold values")
        void rejectsNegativeThresholds(float negativeThreshold) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CacheConfig(negativeThreshold)
            );
            assertTrue(ex.getMessage().contains("between 0.0 and 1.0 (inclusive)"));
        }

        @ParameterizedTest
        @ValueSource(floats = {1.01f, 1.5f, 2.0f})
        @DisplayName("Rejects threshold values strictly greater than 1.0")
        void rejectsThresholdsGreaterThanOne(float highThreshold) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CacheConfig(highThreshold)
            );
            assertTrue(ex.getMessage().contains("between 0.0 and 1.0 (inclusive)"));
        }

        @Test
        @DisplayName("Rejects NaN and infinite threshold values")
        void rejectsNonFiniteThresholds() {
            assertThrows(IllegalArgumentException.class, () -> new CacheConfig(Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> new CacheConfig(Float.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> new CacheConfig(Float.NEGATIVE_INFINITY));
        }
    }

    @Nested
    @DisplayName("Spring ApplicationContext Loading Tests")
    class ContextLoadingTests {

        @Test
        @DisplayName("Loads default threshold 0.85 when no property is configured")
        void loadsDefaultThreshold() {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(CacheConfig.class);
                CacheConfig config = context.getBean(CacheConfig.class);
                assertEquals(0.85f, config.getSimilarityThreshold(), 1e-6);
            });
        }

        @Test
        @DisplayName("Binds custom valid threshold from environment")
        void bindsCustomThreshold() {
            contextRunner
                    .withPropertyValues("cache.similarity-threshold=0.92")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        CacheConfig config = context.getBean(CacheConfig.class);
                        assertEquals(0.92f, config.getSimilarityThreshold(), 1e-6);
                    });
        }

        @Test
        @DisplayName("Fails application startup when invalid threshold is configured")
        void failsStartupWithInvalidThreshold() {
            contextRunner
                    .withPropertyValues("cache.similarity-threshold=-0.1")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class);
                    });
        }
    }
}
