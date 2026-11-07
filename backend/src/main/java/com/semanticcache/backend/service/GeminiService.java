package com.semanticcache.backend.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import com.semanticcache.backend.exception.GeminiApiException;
import com.semanticcache.backend.exception.GeminiEmptyResponseException;
import com.semanticcache.backend.exception.GeminiMissingCredentialsException;
import com.semanticcache.backend.provider.GeminiClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GeminiService implements GeminiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final GeminiClientProvider clientProvider;
    private final String model;

    public GeminiService(
            GeminiClientProvider clientProvider,
            @Value("${gemini.model:gemini-3.8-flash}") String model
    ) {
        this.clientProvider = clientProvider;
        this.model = model;
    }

    @Override
    public String generateAnswer(String prompt) {
        log.info("Sending request to Gemini API using model: [{}]", model);

        try {
            Client client = clientProvider.getClient();
            GenerateContentResponse response = client.models.generateContent(model, prompt, null);

            if (response == null) {
                throw new GeminiEmptyResponseException("Received null response from Gemini API.");
            }

            String text = response.text();
            if (text == null || text.trim().isEmpty()) {
                throw new GeminiEmptyResponseException("Gemini API returned an empty or blank response.");
            }

            return text.trim();
        } catch (GeminiMissingCredentialsException | GeminiEmptyResponseException e) {
            throw e;
        } catch (Exception e) {
            // Log safe metadata only: model and exception class; never log prompt or raw exception details
            log.error("Gemini API call failed for model [{}]: exception class [{}]", model, e.getClass().getName());
            // Do not expose raw SDK message in the exception; preserve cause for internal debugging
            throw new GeminiApiException("Failed to generate response from Gemini API", e);
        }
    }

    public String getModel() {
        return model;
    }
}
