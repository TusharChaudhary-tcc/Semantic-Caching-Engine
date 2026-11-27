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
| **Phase 2** | Two-Stage Cross-Encoder & Novel Eviction Engine | **COMPLETE (PASS)** | In-process ONNX Stage-2 intent verifier, custom Semantic Density & Cost-Weighted eviction policy ($Score = w_1 R + w_2 F + w_3 D + C$), Multi-LLM routing (Gemini + Groq). |
| **Phase 3** | Research Benchmarking & Empirical Evaluation | **COMPLETE (PASS)** | Offline simulation of 50K LMSYS Chatbot Arena queries, LRU vs. LFU vs. Semantic Density + K-Tail comparison, latency delta (<27ms p95), 4-store DB benchmark, and paper figures/LaTeX tables. |
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

## 🟢 PHASE 2: Two-Stage Cross-Encoder & Novel Eviction Engine (COMPLETED)

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
- [x] **Task 2.2 — Novel Semantic Eviction Engine:** *(Completed)*
  - Implemented `EvictionConfig.java`, `CacheEntryMetadata.java`, `EvictionPolicy.java`, and `SemanticDensityEvictionPolicy.java` ($Score = w_1 \cdot R + w_2 \cdot F + w_3 \cdot D + w_4 \cdot C$).
  - Implemented `SemanticEvictionManager.java` using an access-order `LinkedHashMap` index and the **Dusty Corner (K-Tail)** selector ($O(K)$ bounded eviction).
  - Added `deleteById(UUID)` to `VectorRepository.java` and `QdrantVectorRepository.java`.
  - Wired hit tracking (`recordHit`) and miss insertion/eviction (`recordInsert` with LLM latency, estimated token cost, and cluster density) into `CacheService.java`.
  - Added `SemanticEvictionTest.java` and verified full Maven test suite passes with **0 failures, 0 errors**.
- [x] **Task 2.3 — Multi-LLM Provider Support (Groq & Gemini):** *(Completed)*
  - Abstracted upstream LLM generation via `LlmProvider.java` (with `GeminiProvider` extending `LlmProvider`).
  - Implemented `GroqConfig.java` and `GroqService.java` using Java 21 `HttpClient` against Groq's OpenAI-compatible Chat Completions endpoint (`llama-3.3-70b-versatile`).
  - Implemented `LlmRouterService.java` supporting per-request `provider` selection (`"gemini"` / `"groq"`) with graceful fallback to Gemini if `GROQ_API_KEY` is absent.
  - Updated `CacheRequest`, `CacheResponse` (exposing `provider`), `CacheService`, and `application.properties`.
- [x] **Task 2.4 — Verification & Test Suite Expansion:** *(Completed)*
  - Added `WordPieceTokenizerTest.java`, `Stage2CrossEncoderIntegrationTest.java`, `SemanticEvictionTest.java`, and `MultiLlmRouterTest.java`.
  - Full Maven test suite (`.\mvnw.cmd test -q`) passed with **exit code 0 (0 failures, 0 errors)**.

---

## 🟢 PHASE 3: Research Benchmarking & Empirical Evaluation (COMPLETED)

### 3.1 Core Idea
Execute systematic research benchmarks using **50,000 real conversational queries** from the **LMSYS Chatbot Arena Dataset** (`lmsys/chatbot_arena_conversations` $\rightarrow$ `Knowledge/chatbot_arena_dataset.csv`) and **2,000 balanced paraphrase / hard-negative pairs** from **GLUE QQP** (`benchmarks/data/qqp_intent_eval.csv`) to empirically validate our Two-Stage pipeline, Semantic Density & Cost-Weighted eviction policy, latency delta, and vector store performance.

### 3.2 Deliverables & Verified Results
- [x] **Task 3.1 — Python 3.12 Benchmark Harness (`benchmarks/`):**
  - Managed via `uv` (`benchmarks/pyproject.toml`), `prepare_dataset.py`, `eval_two_stage.py`, `evaluate_cache.py`, `eval_system_perf.py`, and `generate_artifacts.py`.
- [x] **Task 3.2 — Eviction Policy Simulation (50,000 Queries, 23,043 Unique Prompts, 9.23M Total Tokens):**
  - **Capacity 500:** Exact Match `11.66%` (`1,119,153` tokens) | LRU `12.13%` (`1,170,482` tokens) | **Semantic Density + Cost `14.05%` (`1,578,980` tokens — +34.9% more tokens saved than LRU)** | LFU ($O(N)$ full scan) `19.51%`.
  - **Capacity 1,000:** Exact Match `16.65%` | LRU `17.47%` (`1,641,506` tokens) | **Semantic Density + Cost `18.98%` (`1,942,841` tokens, 21.06% token savings — +18.4% more tokens saved than LRU)**.
  - **Capacity 5,000:** Exact `34.28%` | LRU `36.70%` | **Semantic Density + Cost `37.23%` (`3,462,917` tokens)**.
  - **Capacity 10,000:** Exact `44.96%` | LRU `47.85%` | **Semantic Density + Cost `48.03%` (`4,443,145` tokens)**.
  - **K-Tail & Weight Ablation (`capacity=1000`):** Verified `K=100` (`18.98%` hit rate, `12.67s`) as the optimal $O(K)$ balance between `K=25` (`18.30%`, `6.84s`) and `K=250` (`19.29%`, `73.01s`).
- [x] **Task 3.3 — Two-Stage Intent Verification (`eval_two_stage.py`):**
  - Contrastive intent stress tests confirm Stage-2 (`ms-marco-MiniLM-L-6-v2`) rejects different-intent traps (e.g., *"How do I reset my password?"* vs. *"What is a good password strength?"* $\rightarrow$ `0.0001`; *"Explain Docker vs Kubernetes"* vs. *"How do I install Docker on Ubuntu?"* $\rightarrow$ `0.0001`) while accepting true paraphrases (`0.9227` and `0.9750`).
  - Also observed on GLUE QQP hard lexical-overlap negatives that asymmetric MS-MARCO cross-encoders score `0.3866` ROC-AUC vs. `0.5903` for symmetric bi-encoders, motivating symmetric paraphrase cross-encoders (e.g., `quora-distilroberta-base`) as a future calibration option.
- [x] **Task 3.4 — Latency Delta & 4-Store Vector DB Comparison (`eval_system_perf.py`):**
  - **End-to-End Latency:** Stage-1 Vector Hit `2.78 ms` median (`2.86 ms` p95) | **Two-Stage Cache Hit `23.46 ms` median (`26.70 ms` p95)** vs. Groq Llama-3.3-70B miss `425.64 ms` median and Gemini Flash miss `1434.22 ms` median (**61x speedup** over Gemini Flash).
  - **4-Store Comparison (10K vectors, Top-3):** Exact HashMap (`0.0005 ms` p50, `0.0000` recall@3), FAISS HNSW (`0.1590 ms` p50, `4,637.4 QPS`, `0.9953` recall@3), FAISS Flat (`1.4618 ms` p50, `638.4 QPS`, `1.0000` recall@3), Qdrant Vector DB local client (`35.5429 ms` p50, `1.0000` recall@3).
- [x] **Task 3.5 — Publication-Grade Figures & LaTeX Tables (`benchmarks/results/`):**
  - Generated 5 high-DPI (`300 DPI`) PNG charts in `benchmarks/results/figures/` (`fig1_eviction_hit_rate.png` .. `fig5_db_comparison.png`) and LaTeX tables in `benchmarks/results/tables.tex`.

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
