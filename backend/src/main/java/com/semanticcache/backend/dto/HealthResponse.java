package com.semanticcache.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data transfer object representing system health status.
 *
 * @param status     overall health status ("UP" or "DOWN")
 * @param message    human-readable status summary
 * @param components optional map of individual component statuses (omitted when null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(
        String status,
        String message,
        Map<String, String> components
) {
    public HealthResponse {
        if (components != null) {
            components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        }
    }

    public static HealthResponse of(String status, String message) {
        return new HealthResponse(status, message, null);
    }

    public static HealthResponse of(String status, String message, Map<String, String> components) {
        return new HealthResponse(status, message, components);
    }

    public boolean isUp() {
        return "UP".equalsIgnoreCase(status);
    }
}
