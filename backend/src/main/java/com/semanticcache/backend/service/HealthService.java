package com.semanticcache.backend.service;

import org.springframework.stereotype.Service;

@Service
public class HealthService {

    public String getStatus() {
        return "Semantic Cache backend is running!";
    }
}
