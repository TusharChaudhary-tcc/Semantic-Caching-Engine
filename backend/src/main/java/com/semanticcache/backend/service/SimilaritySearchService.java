package com.semanticcache.backend.service;

import com.semanticcache.backend.dto.SearchFilter;
import com.semanticcache.backend.dto.SearchResult;

import java.util.List;

public interface SimilaritySearchService {

    List<SearchResult> search(String query);

    List<SearchResult> search(String query, int limit);

    List<SearchResult> search(String query, int limit, SearchFilter filter);
}
