package com.semanticcache.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Stage-2 in-process Cross-Encoder intent verification.
 */
@Configuration
public class CrossEncoderConfig {

    private final boolean enabled;
    private final String modelPath;
    private final String vocabPath;
    private final float threshold;
    private final float stage1Threshold;
    private final int topK;
    private final int maxSequenceLength;
    private final boolean failOnMissingModel;

    public CrossEncoderConfig(
            @Value("${cache.cross-encoder.enabled:true}") boolean enabled,
            @Value("${cache.cross-encoder.model-path:models/ms-marco-MiniLM-L-6-v2.onnx}") String modelPath,
            @Value("${cache.cross-encoder.vocab-path:models/vocab.txt}") String vocabPath,
            @Value("${cache.cross-encoder.threshold:0.85}") float threshold,
            @Value("${cache.cross-encoder.stage1-threshold:0.75}") float stage1Threshold,
            @Value("${cache.cross-encoder.top-k:3}") int topK,
            @Value("${cache.cross-encoder.max-sequence-length:256}") int maxSequenceLength,
            @Value("${cache.cross-encoder.fail-on-missing-model:false}") boolean failOnMissingModel
    ) {
        if (!Float.isFinite(threshold) || threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("cache.cross-encoder.threshold must be between 0.0 and 1.0");
        }
        if (!Float.isFinite(stage1Threshold) || stage1Threshold < 0.0f || stage1Threshold > 1.0f) {
            throw new IllegalArgumentException("cache.cross-encoder.stage1-threshold must be between 0.0 and 1.0");
        }
        if (topK <= 0 || topK > 20) {
            throw new IllegalArgumentException("cache.cross-encoder.top-k must be between 1 and 20");
        }
        if (maxSequenceLength < 16 || maxSequenceLength > 512) {
            throw new IllegalArgumentException("cache.cross-encoder.max-sequence-length must be between 16 and 512");
        }

        this.enabled = enabled;
        this.modelPath = modelPath;
        this.vocabPath = vocabPath;
        this.threshold = threshold;
        this.stage1Threshold = stage1Threshold;
        this.topK = topK;
        this.maxSequenceLength = maxSequenceLength;
        this.failOnMissingModel = failOnMissingModel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModelPath() {
        return modelPath;
    }

    public String getVocabPath() {
        return vocabPath;
    }

    public float getThreshold() {
        return threshold;
    }

    public float getStage1Threshold() {
        return stage1Threshold;
    }

    public int getTopK() {
        return topK;
    }

    public int getMaxSequenceLength() {
        return maxSequenceLength;
    }

    public boolean isFailOnMissingModel() {
        return failOnMissingModel;
    }
}
