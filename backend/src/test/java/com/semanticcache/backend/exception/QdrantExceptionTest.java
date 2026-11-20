package com.semanticcache.backend.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantExceptionTest {

    @Test
    @DisplayName("QdrantException constructors and inheritance")
    void qdrantExceptionBasics() {
        QdrantException exWithMessage = new QdrantException("Base error");
        assertEquals("Base error", exWithMessage.getMessage());
        assertTrue(exWithMessage instanceof RuntimeException);

        Throwable cause = new RuntimeException("Root cause");
        QdrantException exWithCause = new QdrantException("Base error with cause", cause);
        assertEquals("Base error with cause", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("QdrantInitializationException constructors and inheritance")
    void qdrantInitializationExceptionBasics() {
        QdrantInitializationException ex = new QdrantInitializationException("Init failure");
        assertEquals("Init failure", ex.getMessage());
        assertTrue(ex instanceof QdrantException);

        Throwable cause = new RuntimeException("gRPC unavailable");
        QdrantInitializationException exWithCause = new QdrantInitializationException("Init failure with cause", cause);
        assertEquals("Init failure with cause", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("QdrantCommunicationException constructors and inheritance")
    void qdrantCommunicationExceptionBasics() {
        QdrantCommunicationException ex = new QdrantCommunicationException("Connection refused");
        assertEquals("Connection refused", ex.getMessage());
        assertTrue(ex instanceof QdrantInitializationException);
        assertTrue(ex instanceof QdrantException);

        Throwable cause = new RuntimeException("Transport failure");
        QdrantCommunicationException exWithCause = new QdrantCommunicationException("Connection refused with cause", cause);
        assertEquals("Connection refused with cause", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("QdrantIncompatibleCollectionException constructors and inheritance")
    void qdrantIncompatibleCollectionExceptionBasics() {
        QdrantIncompatibleCollectionException ex = new QdrantIncompatibleCollectionException("Incompatible dimension");
        assertEquals("Incompatible dimension", ex.getMessage());
        assertTrue(ex instanceof QdrantInitializationException);

        Throwable cause = new RuntimeException("Schema mismatch");
        QdrantIncompatibleCollectionException exWithCause = new QdrantIncompatibleCollectionException("Incompatible with cause", cause);
        assertEquals("Incompatible with cause", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("QdrantOperationException constructors and inheritance")
    void qdrantOperationExceptionBasics() {
        QdrantOperationException ex = new QdrantOperationException("Upsert failure");
        assertEquals("Upsert failure", ex.getMessage());
        assertTrue(ex instanceof QdrantException);

        Throwable cause = new RuntimeException("Timeout");
        QdrantOperationException exWithCause = new QdrantOperationException("Upsert failure with cause", cause);
        assertEquals("Upsert failure with cause", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }
}
