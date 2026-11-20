package com.semanticcache.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchConfig {

    private final int defaultLimit;
    private final int maxLimit;

    public SearchConfig(
            @Value("${search.default-limit:5}") int defaultLimit,
            @Value("${search.max-limit:50}") int maxLimit
    ) {
        if (defaultLimit <= 0) {
            throw new IllegalArgumentException("search.default-limit must be greater than zero. Configured value: " + defaultLimit);
        }
        if (maxLimit <= 0) {
            throw new IllegalArgumentException("search.max-limit must be greater than zero. Configured value: " + maxLimit);
        }
        if (maxLimit < defaultLimit) {
            throw new IllegalArgumentException(
                    "search.max-limit [" + maxLimit + "] must be greater than or equal to search.default-limit [" + defaultLimit + "]"
            );
        }

        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }
}
