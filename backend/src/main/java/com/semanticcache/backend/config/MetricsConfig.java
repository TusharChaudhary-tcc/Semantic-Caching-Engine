package com.semanticcache.backend.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Micrometer telemetry infrastructure.
 * Provides a lightweight SimpleMeterRegistry for local operation,
 * testing, and standalone telemetry without introducing external monitoring overhead.
 */
@Configuration
public class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
