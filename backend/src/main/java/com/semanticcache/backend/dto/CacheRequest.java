package com.semanticcache.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record CacheRequest(
        @NotBlank(message = "Prompt cannot be blank")
        String prompt
) {
}
