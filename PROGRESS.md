# 🚀 SemanticCache-LLM — Project Progress Tracker (`PROGRESS.md`)

> **Repository:** `Semantic-Caching-Engine`  
> **Team Members:**  
> - **Vadanta Kumar Chauhaan** (Algorithm Design & Research Lead)  
> - **Uttam Upadhyay** (GenAI Embeddings & Vector Database)  
> - **Tushar Chaudhary** (Java Spring Boot Backend & API Routing)  
> - **Vansh Gupta** (Cloud Infrastructure, Networking & Testing)  
>
> ⚠️ **Team Rule / Protocol:**  
> All team members **must** read this file before starting new work or declaring a new phase. Every phase documents:
> 1. The **Core Idea** of the phase.
> 2. The **Components Built & Verified**.
> 3. The **Changes Made & Current Verification State**.
> 4. The **Exact Transition Criteria** to graduate to the next phase.

---

## 📌 High-Level Phase Roadmap

| Phase | Title | Status | Core Objective |
|---|---|---|---|
| **Phase 1** | Enterprise Backend Core & Baseline Vector Cache | **COMPLETE (PASS)** | Production Java 21 / Spring Boot gateway, dual-token security, Qdrant integration, Gemini bi-encoder baseline, 658 automated tests. |
| **Phase 2** | Two-Stage Cross-Encoder & Novel Eviction Engine | **IN PROGRESS** | In-process ONNX Stage-2 intent verifier, custom Semantic Density & Cost-Weighted eviction policy ($Score = w_1 R + w_2 F + w_3 D + C$), LMSYS benchmark pipeline. |
| **Phase 3** | Research Benchmarking & Empirical Evaluation | **PENDING** | Offline simulation of 50K+ queries on Chatbot Arena dataset, LRU vs. LFU vs. Semantic Eviction comparison curves, latency delta and token cost charts for the paper. |
| **Phase 4** | Frontend Dashboard Integration & Stampede Defense | **PENDING** | React UI connection, live visualizer for Stage 1 vs Stage 2 scores, request coalescing (anti-stampede), and AWS deployment packaging. |

---

## 🟢 PHASE 1: Enterprise Backend Core & Baseline Vector Cache (COMPLETED)

### 1.1 Core Idea
Build a secure, enterprise-grade, high-throughput API gateway in **Java 21 / Spring Boot 4** that acts as the entry point for semantic caching. It abstracts LLM access, stores query embeddings in **Qdrant Vector DB**, verifies authorization with secure dual-token JWTs, and falls back to Google Gemini on cache misses.

### 1.2 Accomplishments & Built Components
1. **Spring Boot Gateway Engine:**
   - Java 21 LTS with Spring Boot `4.1.2`.
   - High-throughput asynchronous controllers and clean separation of concerns (`controller`, `service`, `repository`, `filter`, `dto`, `telemetry`).
2. **Security & Cryptography Architecture:**
   - **Dual-Token System:** 15-minute stateless HMAC-SHA256 JWT access tokens + 7-day cryptographically secure refresh tokens stored as SHA-256 hashes in database.
   - **Strict Token Family Rotation:** Detects token reuse attacks and revokes compromised token families instantly.
   - **Origin & CSRF Guard (`OriginValidationFilter`):** Custom filter with strict origin canonicalization, port normalization, and custom header enforcement (`X-Requested-With: XMLHttpRequest`).
   - **DoS Guard (`MaxRequestBodySizeFilter`):** Limits JSON payloads to 64KB to prevent memory exhaustion.
3. **Stage-1 Vector Caching Pipeline:**
   - `GeminiEmbeddingService`: Generates 768-dimensional float embeddings using Google GenAI SDK.
   - `DefaultSimilaritySearchService`: Communicates with Qdrant Vector Engine via gRPC (`localhost:6334`) searching the `semantic_cache` collection with cosine distance.
   - `DefaultCacheDecisionService`: Checks cosine similarity thresholds and privacy visibility (public vs. private tenant scopes).
   - `GeminiService`: Dynamic LLM invocation (`gemini-3.8-flash`) with server-wide demo key or user-supplied dynamic keys.
4. **Telemetry & Health Monitoring:**
   - `CacheMetrics`: Tracks total requests, cache hits, misses, hit rate percentage, latency histograms, and saved token counts via Micrometer.
   - Health endpoints (`/api/health`) monitoring database, Qdrant, and LLM provider liveness.

### 1.3 Test Verification
- **Total Tests Passing:** **658 Tests**
- **Failures:** 0 | **Errors:** 0 | **Skipped:** 0
- **Test Categories:** Unit tests (Mockito), Filter/Security security tests, Controller integration tests (`MockMvc`), Token rotation and concurrency tests.

---

## 🟡 PHASE 2: Two-Stage Cross-Encoder & Novel Eviction Engine (CURRENT PHASE)

### 2.1 Core Idea
Solve the fundamental weakness of single-stage bi-encoder caches (false positives caused by syntactic similarity without intent alignment, e.g., *"How to reset password"* vs. *"What is password strength"*). 

Implement a **Two-Stage Verification Pipeline**:
- **Stage 1 (Bi-Encoder Retrieval):** Qdrant vector retrieval retrieves top-3 candidates with cosine threshold $\ge 0.80$.
- **Stage 2 (In-Process Cross-Encoder Verification):** Run a quantized cross-encoder (`ms-marco-MiniLM-L-6-v2`) in-process in Java via **ONNX Runtime** to score query pairs with intent threshold $\ge 0.85$.

In addition, replace conventional LRU cache eviction with the project's signature research contribution:
$$\text{Score} = w_1 \cdot \text{Recency} + w_2 \cdot \text{Frequency} + w_3 \cdot \text{Semantic Cluster Density} + w_4 \cdot \text{Cost Weight}$$
coupled with the **Dusty Corner (K-Tail)** eviction selector.

### 2.2 Tasks & Deliverables
- [x] **Task 2.1 — In-Process Cross-Encoder (ONNX Java):** *(Completed)*
  - Added `com.microsoft.onnxruntime:onnxruntime:1.20.0` to `backend/pom.xml`.
  - Implemented pure-Java BERT `WordPieceTokenizer.java` (`[CLS] query [SEP] candidate [SEP]` pair encoding).
  - Implemented `CrossEncoderConfig.java`, `CrossEncoderService.java`, and `OnnxCrossEncoderService.java` with sigmoid probability scoring and graceful fallback when local `.onnx` model artifacts are absent.
  - Upgraded `CacheRetrievalService` / `DefaultCacheRetrievalService` for Top-K candidate retrieval (`findTopCandidatesWithVector`).
  - Upgraded `CacheDecisionService` / `DefaultCacheDecisionService` for Stage-1 threshold filtering + Stage-2 Cross-Encoder re-ranking & intent verification.
  - Upgraded `CacheResponse` & `CacheLookupResult` DTOs to expose `stage1Similarity`, `stage2CrossEncoderScore`, `verificationStage`, and `latencyMs`.
  - Verified with **664 passing tests (0 failures, 0 errors)** including `WordPieceTokenizerTest` and `Stage2CrossEncoderIntegrationTest`.
- [ ] **Task 2.2 — Novel Semantic Eviction Engine:**
  - Implement `SemanticDensityEvictionPolicy.java` computing recency, hit frequency, neighborhood vector density, and generation token cost.
  - Implement the K-Tail selection strategy (scan cold subset rather than whole DB).
- [ ] **Task 2.3 — Multi-LLM Provider Support (Groq & Gemini):**
  - Abstract LLM generation via `LlmProvider` interface.
  - Retain Gemini for high-quality embeddings + support both Gemini and Groq (Llama-3.3-70B / Llama-3.1-8B) for ultra-fast generation.
- [ ] **Task 2.4 — Verification & Test Suite Expansion:**
  - Add unit tests for eviction ranking and multi-provider routing. Ensure all tests pass.

---

## ⚪ PHASE 3: Research Benchmarking & Empirical Evaluation (UPCOMING)

### 3.1 Core Idea
Execute systematic research benchmarks using the **2026 LMSYS Chatbot Arena Dataset** (`Knowledge/chatbot_arena_dataset.csv`) to prove the novelty and efficiency of the system for your research paper.

### 3.2 Deliverables
- Python benchmark evaluation harness (`benchmarks/evaluate_cache.py`).
- Comparative simulations:
  - Cache Hit Ratio: LRU vs. LFU vs. Semantic Density Eviction.
  - Accuracy & False Positive Rate: Stage 1 (Bi-encoder only) vs. Two-Stage (Bi-encoder + Cross-encoder).
  - Latency Delta Distribution: Sub-35ms cache hit vs. 1500ms+ LLM calls.
  - Cost Savings Curve across various cache capacities (1K, 5K, 25K queries).
- Export automated matplotlib/seaborn graphs and LaTeX result tables directly for the paper.

---

## ⚪ PHASE 4: Frontend Integration & Production Hardening (UPCOMING)

### 4.1 Core Idea
Connect the React UI to the live backend, expose interactive cache diagnostics, and deploy with Docker Compose on cloud infrastructure (AWS).

### 4.2 Deliverables
- Connect React dashboard (`frontend/src/App.jsx`) to backend REST API.
- Implement Stage 1 vs. Stage 2 score visualizer badge (showing vector similarity % alongside cross-encoder confidence %).
- Implement Cache Stampede prevention (Request Coalescing via Java `CompletableFuture`).
- Root `docker-compose.yml` for unified setup: PostgreSQL + Qdrant + Spring Boot + React Vite frontend.

---

## 🧠 Architectural Insights & Technical Decisions

### 1. LLM API Choice: Gemini vs. Groq
- **Gemini (Google AI Studio):**
  - **Pros:** Offers both state-of-the-art embeddings (`text-embedding-004` / `gemini-embedding-001`, 768 dimensions) AND generation (`gemini-1.5-flash` / `gemini-2.0-flash`). Generous free tier (up to 15 RPM, 1M TPM).
  - **Cons:** Generation latency is good (~800ms–1500ms) but not instant.
- **Groq:**
  - **Pros:** Industry-leading token generation speed (500–800 tokens/sec on LPUs with Llama-3.3-70B and Llama-3.1-8B). Has a free tier.
  - **Cons:** **Groq does NOT provide native text embedding endpoints.** You cannot do vector caching with Groq alone.
- **Winning Strategy:**
  - Use **Gemini** for text embeddings (Stage 1 vector indexing).
  - Use **Gemini OR Groq** interchangeably for upstream LLM response generation on cache misses through our pluggable provider interface. This gives maximum speed and flexibility!

### 2. Dataset Suitability: LMSYS Chatbot Arena (`chatbot_arena_dataset.csv`)
- **Suitability:** **Outstanding (10/10)**.
- **Why it fits perfectly:**
  - Contains **100K+ real human conversations** spanning coding, logic, science, and conversational queries across 100+ LLMs.
  - Real users frequently ask variations of identical underlying concepts (e.g., debugging specific errors, syntax questions, definitions) — exactly what semantic caching targets.
  - Includes multi-turn dialogue, timestamps (ideal for temporal recency eviction testing), and token volume variations.
- **Supplement (Optional for Paper):** If you need a secondary benchmark for pure semantic textual similarity (STS), standard datasets like **Quora Question Pairs (QQP)** or **Banking77** can be run as baseline validations.
