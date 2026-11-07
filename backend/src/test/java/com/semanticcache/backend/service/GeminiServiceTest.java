package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.GenerateContentResponse;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.provider.GeminiClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @Mock
    private GeminiClientProvider clientProvider;

    @Mock
    private Client mockClient;

    @Mock
    private Models mockModels;

    private GeminiService geminiService;

    @BeforeEach
    void setUp() {
        geminiService = new GeminiService(clientProvider, "gemini-3.8-flash");
    }

    @Test
    @DisplayName("Generates answer successfully from Gemini response")
    void generateAnswerSuccess() {
        ReflectionTestUtils.setField(mockClient, "models", mockModels);
        when(clientProvider.getClient()).thenReturn(mockClient);

        GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
        when(mockResponse.text()).thenReturn("Quantum computing uses qubits.");
        when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("What is quantum computing?"), any()))
                .thenReturn(mockResponse);

        String answer = geminiService.generateAnswer("What is quantum computing?");

        assertNotNull(answer);
        assertEquals("Quantum computing uses qubits.", answer);
        verify(mockModels).generateContent("gemini-3.8-flash", "What is quantum computing?", null);
    }

    @Test
    @DisplayName("Throws GeminiEmptyResponseException when response text is null")
    void throwsWhenResponseTextIsNull() {
        ReflectionTestUtils.setField(mockClient, "models", mockModels);
        when(clientProvider.getClient()).thenReturn(mockClient);

        GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
        when(mockResponse.text()).thenReturn(null);
        when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                .thenReturn(mockResponse);

        assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));
    }

    @Test
    @DisplayName("Throws GeminiEmptyResponseException when response text is blank")
    void throwsWhenResponseTextIsBlank() {
        ReflectionTestUtils.setField(mockClient, "models", mockModels);
        when(clientProvider.getClient()).thenReturn(mockClient);

        GenerateContentResponse mockResponse = mock(GenerateContentResponse.class);
        when(mockResponse.text()).thenReturn("   ");
        when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                .thenReturn(mockResponse);

        assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));
    }

    @Test
    @DisplayName("Throws GeminiEmptyResponseException when response is null")
    void throwsWhenResponseIsNull() {
        ReflectionTestUtils.setField(mockClient, "models", mockModels);
        when(clientProvider.getClient()).thenReturn(mockClient);

        when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                .thenReturn(null);

        assertThrows(GeminiEmptyResponseException.class, () -> geminiService.generateAnswer("test prompt"));
    }

    @Test
    @DisplayName("Propagates GeminiMissingCredentialsException directly")
    void propagatesMissingCredentialsException() {
        when(clientProvider.getClient()).thenThrow(new GeminiMissingCredentialsException("Missing API key"));

        assertThrows(GeminiMissingCredentialsException.class, () -> geminiService.generateAnswer("test prompt"));
    }

    @Test
    @DisplayName("Wraps API/SDK runtime failures in GeminiApiException with generic message and preserved cause")
    void wrapsRuntimeFailureInGeminiApiException() {
        ReflectionTestUtils.setField(mockClient, "models", mockModels);
        when(clientProvider.getClient()).thenReturn(mockClient);

        when(mockModels.generateContent(eq("gemini-3.8-flash"), eq("test prompt"), any()))
                .thenThrow(new RuntimeException("Rate limit exceeded"));

        GeminiApiException ex = assertThrows(
                GeminiApiException.class,
                () -> geminiService.generateAnswer("test prompt")
        );

        assertEquals("Failed to generate response from Gemini API", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("Rate limit exceeded", ex.getCause().getMessage());
    }
}
