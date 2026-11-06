package com.semanticcache.backend.controller;

import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CacheController {

    @PostMapping("/cache")
    public CacheResponse handleCacheRequest(@Valid @RequestBody CacheRequest request) {
        // For now, we are returning a mock response just to test the connection.
        // Later, this will call our CacheService to check Redis/Qdrant or Gemini.
        String mockAnswer = "This is a temporary mocked backend response for your prompt: '" + request.prompt() + "'";
        
        return new CacheResponse(mockAnswer, false);
    }
}
