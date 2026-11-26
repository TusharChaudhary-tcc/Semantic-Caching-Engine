package com.semanticcache.backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe pure-Java BERT WordPiece tokenizer for HuggingFace MiniLM / BERT cross-encoder models.
 * Produces {@code input_ids}, {@code attention_mask}, and {@code token_type_ids} for sentence pairs:
 * {@code [CLS] query [SEP] candidate [SEP]}.
 */
public class WordPieceTokenizer {

    private static final String TOKEN_UNK = "[UNK]";
    private static final String TOKEN_CLS = "[CLS]";
    private static final String TOKEN_SEP = "[SEP]";
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    public WordPieceTokenizer(Map<String, Integer> vocab) {
        this.vocab = Map.copyOf(Objects.requireNonNull(vocab, "vocab must not be null"));
        this.unkId = vocab.getOrDefault(TOKEN_UNK, 100);
        this.clsId = vocab.getOrDefault(TOKEN_CLS, 101);
        this.sepId = vocab.getOrDefault(TOKEN_SEP, 102);
    }

    public static WordPieceTokenizer fromVocabFile(Path vocabPath) throws IOException {
        Objects.requireNonNull(vocabPath, "vocabPath must not be null");
        Map<String, Integer> vocabMap = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(vocabPath, StandardCharsets.UTF_8)) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                vocabMap.put(line, index++);
            }
        }
        return new WordPieceTokenizer(vocabMap);
    }

    public record EncodingResult(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
    }

    /**
     * Tokenizes a (query, candidate) pair into BERT sequence inputs, truncating the longer sequence
     * if combined length exceeds {@code maxSequenceLength}.
     */
    public EncodingResult encodePair(String query, String candidate, int maxSequenceLength) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (maxSequenceLength < 4) {
            throw new IllegalArgumentException("maxSequenceLength must be at least 4");
        }

        List<Integer> tokensA = new ArrayList<>(tokenize(query));
        List<Integer> tokensB = new ArrayList<>(tokenize(candidate));

        // Account for [CLS], [SEP], [SEP]
        int maxTokens = maxSequenceLength - 3;
        while (tokensA.size() + tokensB.size() > maxTokens) {
            if (tokensA.size() > tokensB.size()) {
                tokensA.remove(tokensA.size() - 1);
            } else {
                tokensB.remove(tokensB.size() - 1);
            }
        }

        int totalLen = tokensA.size() + tokensB.size() + 3;
        long[] inputIds = new long[totalLen];
        long[] attentionMask = new long[totalLen];
        long[] tokenTypeIds = new long[totalLen];

        int pos = 0;
        inputIds[pos] = clsId;
        attentionMask[pos] = 1L;
        tokenTypeIds[pos] = 0L;
        pos++;

        for (int id : tokensA) {
            inputIds[pos] = id;
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 0L;
            pos++;
        }

        inputIds[pos] = sepId;
        attentionMask[pos] = 1L;
        tokenTypeIds[pos] = 0L;
        pos++;

        for (int id : tokensB) {
            inputIds[pos] = id;
            attentionMask[pos] = 1L;
            tokenTypeIds[pos] = 1L;
            pos++;
        }

        inputIds[pos] = sepId;
        attentionMask[pos] = 1L;
        tokenTypeIds[pos] = 1L;

        return new EncodingResult(inputIds, attentionMask, tokenTypeIds);
    }

    public List<Integer> tokenize(String text) {
        List<Integer> outputIds = new ArrayList<>();
        for (String basicToken : basicTokenize(text)) {
            if (basicToken.length() > MAX_CHARS_PER_WORD) {
                outputIds.add(unkId);
                continue;
            }
            outputIds.addAll(wordPieceTokenize(basicToken));
        }
        return outputIds;
    }

    private List<String> basicTokenize(String text) {
        String cleaned = cleanAndStripAccents(text.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else if (isPunctuation(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private List<Integer> wordPieceTokenize(String token) {
        List<Integer> subTokens = new ArrayList<>();
        int start = 0;
        int len = token.length();
        boolean isBad = false;

        while (start < len) {
            int end = len;
            Integer matchedId = null;
            while (start < end) {
                String substr = token.substring(start, end);
                if (start > 0) {
                    substr = "##" + substr;
                }
                Integer id = vocab.get(substr);
                if (id != null) {
                    matchedId = id;
                    break;
                }
                end--;
            }
            if (matchedId == null) {
                isBad = true;
                break;
            }
            subTokens.add(matchedId);
            start = end;
        }

        if (isBad) {
            return List.of(unkId);
        }
        return subTokens;
    }

    private static String cleanAndStripAccents(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.CONTROL || type == Character.FORMAT) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isPunctuation(char c) {
        int cp = c;
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
}
