package com.semanticcache.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CacheRequest(
        @NotBlank(message = "Prompt cannot be blank")
        @Size(max = 4000, message = "Prompt cannot exceed 4000 characters")
        String prompt
) {
}
