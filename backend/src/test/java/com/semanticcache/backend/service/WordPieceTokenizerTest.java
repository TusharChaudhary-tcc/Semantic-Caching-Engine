package com.semanticcache.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordPieceTokenizerTest {

    private WordPieceTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        Map<String, Integer> vocab = Map.ofEntries(
                Map.entry("[PAD]", 0),
                Map.entry("[UNK]", 100),
                Map.entry("[CLS]", 101),
                Map.entry("[SEP]", 102),
                Map.entry("how", 2129),
                Map.entry("to", 2000),
                Map.entry("reset", 15740),
                Map.entry("password", 10945),
                Map.entry("?", 1029),
                Map.entry("forgot", 9471),
                Map.entry("my", 2026),
                Map.entry("un", 4895),
                Map.entry("##known", 12345)
        );
        tokenizer = new WordPieceTokenizer(vocab);
    }

    @Test
    @DisplayName("Encodes sentence pair with [CLS], [SEP], and token_type_ids")
    void encodesSentencePairCorrectly() {
        WordPieceTokenizer.EncodingResult result = tokenizer.encodePair(
                "How to reset password?",
                "Forgot my password",
                32
        );

        // [CLS] how to reset password ? [SEP] forgot my password [SEP]
        assertArrayEquals(new long[]{101, 2129, 2000, 15740, 10945, 1029, 102, 9471, 2026, 10945, 102}, result.inputIds());
        assertArrayEquals(new long[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, result.attentionMask());
        assertArrayEquals(new long[]{0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1}, result.tokenTypeIds());
    }

    @Test
    @DisplayName("Splits subwords with WordPiece ## prefix")
    void splitsSubwords() {
        var tokens = tokenizer.tokenize("unknown");
        assertEquals(2, tokens.size());
        assertEquals(4895, tokens.get(0));
        assertEquals(12345, tokens.get(1));
    }

    @Test
    @DisplayName("Truncates longest sequence first when exceeding maxSequenceLength")
    void truncatesToMaxLength() {
        WordPieceTokenizer.EncodingResult result = tokenizer.encodePair(
                "How to reset password?",
                "Forgot my password",
                8
        );
        assertTrue(result.inputIds().length <= 8);
        assertEquals(101, result.inputIds()[0]);
        assertEquals(102, result.inputIds()[result.inputIds().length - 1]);
    }
}
