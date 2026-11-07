package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.CacheRequest;
import com.semanticcache.backend.dto.CacheResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private GeminiProvider geminiProvider;

    @InjectMocks
    private CacheService cacheService;

    @Test
    @DisplayName("Calls GeminiProvider and returns CacheResponse with cacheHit=false")
    void processesPromptWithGeminiAndSetsCacheHitFalse() {
        CacheRequest request = new CacheRequest("Explain caching in distributed systems");
        when(geminiProvider.generateAnswer("Explain caching in distributed systems"))
                .thenReturn("Caching stores frequently accessed data in faster memory.");

        CacheResponse response = cacheService.processPrompt(request);

        assertNotNull(response);
        assertEquals("Caching stores frequently accessed data in faster memory.", response.answer());
        assertFalse(response.cacheHit());
        verify(geminiProvider).generateAnswer("Explain caching in distributed systems");
    }
}
