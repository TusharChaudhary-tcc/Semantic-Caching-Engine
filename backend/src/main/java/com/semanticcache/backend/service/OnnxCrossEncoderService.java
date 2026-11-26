package com.semanticcache.backend.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.semanticcache.backend.config.CrossEncoderConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-process Stage-2 Cross-Encoder powered by ONNX Runtime Java.
 * Loads a quantized MiniLM/BERT sequence-classification ONNX model and evaluates
 * (query, candidatePrompt) pairs with zero network serialization overhead.
 */
@Service
public class OnnxCrossEncoderService implements CrossEncoderService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderService.class);

    private final CrossEncoderConfig config;
    private OrtEnvironment environment;
    private OrtSession session;
    private WordPieceTokenizer tokenizer;
    private volatile boolean available = false;

    public OnnxCrossEncoderService(CrossEncoderConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.info("Stage-2 Cross-Encoder is disabled via configuration.");
            return;
        }

        Path modelPath = Path.of(config.getModelPath());
        Path vocabPath = Path.of(config.getVocabPath());

        if (!Files.exists(modelPath) || !Files.exists(vocabPath)) {
            String message = String.format(
                    "Stage-2 Cross-Encoder artifacts not found (model=%s, vocab=%s). Stage-2 verification will be bypassed.",
                    modelPath.toAbsolutePath(), vocabPath.toAbsolutePath()
            );
            if (config.isFailOnMissingModel()) {
                throw new IllegalStateException(message);
            }
            log.warn(message);
            return;
        }

        try {
            this.tokenizer = WordPieceTokenizer.fromVocabFile(vocabPath);
            this.environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = environment.createSession(modelPath.toString(), options);
            this.available = true;
            log.info("Stage-2 Cross-Encoder ONNX model loaded successfully from [{}]", modelPath);
        } catch (IOException | OrtException e) {
            this.available = false;
            if (config.isFailOnMissingModel()) {
                throw new IllegalStateException("Failed to initialize ONNX Cross-Encoder", e);
            }
            log.error("Failed to initialize ONNX Cross-Encoder; falling back to Stage-1 only.", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return available && session != null && tokenizer != null;
    }

    @Override
    public float score(String query, String candidatePrompt) {
        if (query == null || query.isBlank() || candidatePrompt == null || candidatePrompt.isBlank()) {
            throw new IllegalArgumentException("query and candidatePrompt must not be null or blank");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("CrossEncoderService is not available (model not loaded)");
        }

        WordPieceTokenizer.EncodingResult encoding =
                tokenizer.encodePair(query.trim(), candidatePrompt.trim(), config.getMaxSequenceLength());

        long seqLen = encoding.inputIds().length;
        long[] shape = new long[]{1L, seqLen};

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoding.inputIds()), shape);
             OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoding.attentionMask()), shape);
             OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(encoding.tokenTypeIds()), shape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", tokenTypeIdsTensor);
            }

            try (OrtSession.Result result = session.run(inputs)) {
                Object outputValue = result.get(0).getValue();
                float logit = extractLogit(outputValue);
                return sigmoid(logit);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX Cross-Encoder inference failed", e);
        }
    }

    private static float extractLogit(Object outputValue) {
        if (outputValue instanceof float[][] matrix && matrix.length > 0 && matrix[0].length > 0) {
            return matrix[0][0];
        }
        if (outputValue instanceof float[] array && array.length > 0) {
            return array[0];
        }
        throw new IllegalStateException("Unexpected ONNX output tensor type: "
                + (outputValue != null ? outputValue.getClass().getName() : "null"));
    }

    static float sigmoid(float logit) {
        return (float) (1.0 / (1.0 + Math.exp(-logit)));
    }

    @PreDestroy
    @Override
    public void close() {
        available = false;
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("Error closing ONNX session", e);
            }
        }
    }
}
