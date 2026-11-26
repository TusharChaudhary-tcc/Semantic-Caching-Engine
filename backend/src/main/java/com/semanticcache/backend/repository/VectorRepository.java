package com.semanticcache.backend.repository;

import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;
import com.semanticcache.backend.dto.VectorRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VectorRepository {

    void upsert(VectorRecord record);

    Optional<VectorRecord> findById(UUID id);

    /**
     * Unrestricted low-level vector search. Retained solely for administrative and test use.
     * Must never be invoked by application cache retrieval paths.
     */
    List<SearchResult> search(List<Float> queryVector, int limit);

    /**
     * Privacy-filtered similarity search evaluated inside Qdrant before candidate selection.
     *
     * @param queryVector the raw embedding vector
     * @param limit maximum number of matches to return
     * @param filter trusted privacy and visibility constraints
     * @return non-increasing ranked list of matching SearchResults
     */
    List<SearchResult> search(List<Float> queryVector, int limit, SearchFilter filter);

    /**
     * Removes an evicted vector entry by its point ID.
     */
    default void deleteById(UUID id) {
    }
}
