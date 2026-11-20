package com.semanticcache.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SearchConfig.class);

    @Nested
    @DisplayName("Constructor Property and Accessor Tests")
    class PropertyTests {

        @Test
        @DisplayName("Instantiates with custom valid configuration")
        void customValidConfiguration() {
            SearchConfig config = new SearchConfig(10, 100);

            assertEquals(10, config.getDefaultLimit());
            assertEquals(100, config.getMaxLimit());
        }

        @Test
        @DisplayName("Instantiates when defaultLimit equals maxLimit")
        void defaultLimitEqualsMaxLimit() {
            SearchConfig config = new SearchConfig(25, 25);

            assertEquals(25, config.getDefaultLimit());
            assertEquals(25, config.getMaxLimit());
        }
    }

    @Nested
    @DisplayName("Configuration Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects zero default limit")
        void rejectsZeroDefaultLimit() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchConfig(0, 50)
            );
            assertTrue(ex.getMessage().contains("search.default-limit must be greater than zero"));
        }

        @Test
        @DisplayName("Rejects negative default limit")
        void rejectsNegativeDefaultLimit() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchConfig(-5, 50)
            );
            assertTrue(ex.getMessage().contains("search.default-limit must be greater than zero"));
        }

        @Test
        @DisplayName("Rejects zero maximum limit")
        void rejectsZeroMaxLimit() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchConfig(5, 0)
            );
            assertTrue(ex.getMessage().contains("search.max-limit must be greater than zero"));
        }

        @Test
        @DisplayName("Rejects negative maximum limit")
        void rejectsNegativeMaxLimit() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchConfig(5, -10)
            );
            assertTrue(ex.getMessage().contains("search.max-limit must be greater than zero"));
        }

        @Test
        @DisplayName("Rejects maximum limit smaller than default limit")
        void rejectsMaxLimitSmallerThanDefaultLimit() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchConfig(10, 5)
            );
            assertTrue(ex.getMessage().contains("must be greater than or equal to search.default-limit"));
        }
    }

    @Nested
    @DisplayName("Spring ApplicationContext Loading Tests")
    class ContextLoadingTests {

        @Test
        @DisplayName("Loads default values (defaultLimit=5, maxLimit=50) when no properties are configured")
        void loadsDefaultValues() {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SearchConfig.class);
                SearchConfig config = context.getBean(SearchConfig.class);
                assertEquals(5, config.getDefaultLimit());
                assertEquals(50, config.getMaxLimit());
            });
        }

        @Test
        @DisplayName("Binds custom properties from environment")
        void bindsCustomProperties() {
            contextRunner
                    .withPropertyValues("search.default-limit=8", "search.max-limit=64")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        SearchConfig config = context.getBean(SearchConfig.class);
                        assertEquals(8, config.getDefaultLimit());
                        assertEquals(64, config.getMaxLimit());
                    });
        }

        @Test
        @DisplayName("Context fails to start when invalid search.default-limit is configured")
        void failsStartupWithInvalidDefaultLimit() {
            contextRunner
                    .withPropertyValues("search.default-limit=0", "search.max-limit=50")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                                .hasRootCauseMessage("search.default-limit must be greater than zero. Configured value: 0");
                    });
        }

        @Test
        @DisplayName("Context fails to start when search.max-limit is smaller than search.default-limit")
        void failsStartupWhenMaxLimitSmallerThanDefaultLimit() {
            contextRunner
                    .withPropertyValues("search.default-limit=20", "search.max-limit=10")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalArgumentException.class);
                    });
        }
    }
}
