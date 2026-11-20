package com.semanticcache.backend.provider;

import com.google.genai.Client;

public interface GeminiClientProvider {

    /**
     * Obtains an authenticated Google Gen AI Client instance.
     *
     * @return Initialized Client instance
     * @throws com.semanticcache.backend.exception.GeminiMissingCredentialsException if credentials are missing
     */
    Client getClient();
}
