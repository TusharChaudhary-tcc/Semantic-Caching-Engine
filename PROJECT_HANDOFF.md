# SemanticCache-LLM: Project Handoff Documentation

> **Status:** Backend Phase 3 Complete | Test Suite: 658 Tests Passing (0 Failures, 0 Errors)  
> **Repository:** `D:/MINI Project 2026/Semantic-Caching-Engine`  
> **Primary Branch:** `feature/backend-development`  
> **Base Commit:** `2e92ea4` (`feat: complete backend authentication and cache pipeline`)  
> **Date:** November 2026  

---

## Table of Contents
1. [Project Overview & Core Value Proposition](#1-project-overview--core-value-proposition)
2. [Technology Stack & System Dependencies](#2-technology-stack--system-dependencies)
3. [Architecture & System Design](#3-architecture--system-design)
4. [Completed Backend Implementation](#4-completed-backend-implementation)
5. [Security & Privacy Architecture](#5-security--privacy-architecture)
6. [Frontend Status & Gap Analysis](#6-frontend-status--gap-analysis)
7. [Environment Setup & Local Run Guide](#7-environment-setup--local-run-guide)
8. [Comprehensive API Reference](#8-comprehensive-api-reference)
9. [Testing Strategy & Test Suite Details](#9-testing-strategy--test-suite-details)
10. [Remaining Work & Backlog (P0–P3)](#10-remaining-work--backlog-p0p3)
11. [Known Limitations, Edge Cases & Technical Debt](#11-known-limitations-edge-cases--technical-debt)
12. [Recommended Development Roadmap](#12-recommended-development-roadmap)
13. [Git Workflow & Collaboration Guidelines](#13-git-workflow--collaboration-guidelines)
14. [Quickstart Guide for Next Developer](#14-quickstart-guide-for-next-developer)

---

## 1. Project Overview & Core Value Proposition

### 1.1 Executive Summary
**SemanticCache-LLM** is an enterprise-grade semantic caching middleware and API gateway for Large Language Models (LLMs). Querying state-of-the-art LLMs (such as Google Gemini, OpenAI GPT, or Anthropic Claude) incurs significant latency (often 800ms to 4000ms+) and recurring API token costs. In applications with repeated, conversational, or semantically equivalent questions, traditional key-value caches (like Redis using exact string keys or hashes) fail because natural language varies widely in syntax and vocabulary:
- *"How do I sort a list in Python?"*
- *"What is the method to order elements in a Python list?"*
- *"Can you show me Python syntax for sorting an array?"*

Exact-match caches treat these as three distinct cache misses, repeating the expensive LLM roundtrip. **SemanticCache-LLM** computes high-dimensional vector embeddings for user prompts, queries a high-performance vector database (**Qdrant**), applies cosine similarity thresholds, evaluates visibility/privacy scopes, and returns cached answers instantly when semantic equivalence is met.

### 1.2 Core Metrics & Targets
- **Cache Hit Latency Target:** $< 50\text{ ms}$ (served from local memory / Qdrant vector index).
- **Cache Miss Latency Target:** Upstream LLM response time + vector indexing ($\approx 800\text{--}2500\text{ ms}$).
- **Similarity Threshold:** Default `0.85` cosine similarity score (configurable via `cache.similarity-threshold`).
- **Cost Reduction:** Up to 60–80% cost savings on repetitive queries across multi-user or enterprise chat environments.

### 1.3 Cache Processing Flow

```
                      +-----------------------------+
                      |   Incoming User Prompt      |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      | Generate Prompt Embedding   |
                      |  (gemini-embedding-001)     |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      | Vector Similarity Search    |
                      | (Qdrant Cosine Similarity)  |
                      +--------------+--------------+
                                     |
                                     v
                           /-------------------\
                          /   Cosine Score >=   \
                         <   0.85 & Visibility   >
                          \      Allowed?       /
                           \-------------------/
                              /               \
                      YES    /                 \   NO
                            v                   v
            +---------------------+   +-----------------------+
            |  CACHE HIT          |   |  CACHE MISS           |
            |  Return cached      |   |  Call Gemini LLM API  |
            |  answer instantly   |   |  (gemini-3.8-flash)   |
            +---------------------+   +-----------+-----------+
                                                  |
                                                  v
                                      +-----------------------+
                                      | Upsert Prompt Vector  |
                                      | & Answer to Qdrant    |
                                      +-----------+-----------+
                                                  |
                                                  v
                                      +-----------------------+
                                      | Return Answer &       |
                                      | Metadata to Client    |
                                      +-----------------------+
```

---

## 2. Technology Stack & System Dependencies

### 2.1 Backend Stack
- **Language / Runtime:** Java 21 LTS (OpenJDK 21.0.6)
- **Framework:** Spring Boot `4.1.2` (Web, Data JPA, Validation)
- **LLM SDK:** Google Gen AI Java SDK (`com.google.genai:google-genai:2.0.0`)
- **Vector Database Client:** Qdrant Java Client (`io.qdrant:client:1.20.0`)
- **Security & Crypto:**
  - `org.springframework.security:spring-security-crypto` (BCrypt password hashing)
  - `org.springframework.security:spring-security-core`
  - Note: `spring-boot-starter-security` is intentionally **NOT** imported to prevent default auto-configuration overhead; custom security filter chains and authentication filters are used instead.
- **JWT (JSON Web Tokens):** JJWT `0.12.6` (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`)
- **Relational Databases:**
  - Development / Test: H2 Database `2.4.240` (embedded file / in-memory)
  - Production: PostgreSQL `42.7.5`
- **Database Migrations:** Flyway `11.20.0` (`flyway-core`, `flyway-database-postgresql`)
- **Metrics & Observability:** Micrometer Core `1.16.2`
- **Build System:** Apache Maven 3.9+ (`pom.xml` in `backend/`)

### 2.2 Frontend Stack
- **Framework:** React `19.2.8`
- **Build Tool / Bundler:** Vite `8.3.0`
- **Runtime:** Node.js v18+ / npm
- **CSS:** Plain CSS (`index.css`, `App.css`)

### 2.3 External Supporting Infrastructure
- **Vector Engine:** Qdrant Vector Search Engine (gRPC port `6334`, HTTP port `6333`)
- **Relational Storage:** PostgreSQL 15+ (port `5432`)
- **LLM / Embedding Provider:** Google Gemini API (`generativelanguage.googleapis.com`)

---

## 3. Architecture & System Design

### 3.1 Architectural Diagram

```
+---------------------------------------------------------------------------------------------------+
|                                       CLIENT TIER (React / SPA)                                   |
|   - Bearer Access Token in Memory                                                                 |
|   - HttpOnly Refresh Token Cookie scoped to /api/auth                                             |
|   - Custom CSRF Header: X-Requested-With: XMLHttpRequest                                         |
+------------------------------------------------+--------------------------------------------------+
                                                 |
                                     HTTP REST / JSON / Cookies
                                                 |
                                                 v
+------------------------------------------------+--------------------------------------------------+
|                                    BACKEND FILTER CHAIN                                           |
|                                                                                                   |
|  1. MaxRequestBodySizeFilter      (Enforces max payload size: 64KB)                               |
|  2. CorsConfig                    (Credentials allowed, Origin allowlist, header exposure)        |
|  3. OriginValidationFilter        (Order: HIGHEST_PRECEDENCE + 5; guards /refresh & /logout)       |
|  4. JwtAuthenticationFilter       (Order: HIGHEST_PRECEDENCE + 10; parses Bearer JWTs)             |
+------------------------------------------------+--------------------------------------------------+
                                                 |
                                                 v
+------------------------------------------------+--------------------------------------------------+
|                                   CONTROLLERS & API GATEWAY                                       |
|                                                                                                   |
|  - AuthController:     /api/auth/register, /api/auth/login, /api/auth/refresh, /api/auth/logout   |
|  - CacheController:    /api/cache (POST - query prompt)                                           |
|  - HealthController:   /api/health (Readiness & Liveness checks)                                  |
|  - MetricsController:  /metrics (Cache hit/miss counters, latency stats)                          |
+-------------------+----------------------------+-----------------------------+--------------------+
                    |                            |                             |
                    v                            v                             v
+-------------------+----+      +----------------+---------+       +-----------+--------------------+
|      AUTH SERVICE      |      |      CACHE SERVICE       |       |       METRICS SERVICE          |
|  - User registration   |      |  - Prompt hashing        |       |  - Cache Hits counter          |
|  - BCrypt auth         |      |  - Embedding generation  |       |  - Cache Misses counter        |
|  - Token rotation      |      |  - Similarity lookup     |       |  - Saved tokens / latency      |
|  - Family revocation   |      |  - Visibility evaluation |       +--------------------------------+
+---------+--------------+      +----------------+---------+
          |                                      |
          v                                      v
+---------+--------------+      +----------------+---------+       +--------------------------------+
|  RELATIONAL STORAGE    |      |  VECTOR REPOSITORY       |       |       GEMINI SDK CLIENT        |
|  (PostgreSQL / H2)     |      |  (Qdrant gRPC 6334)      |       |  - gemini-embedding-001        |
|  - users table         |      |  - Collection:           |       |  - gemini-3.8-flash            |
|  - refresh_tokens      |      |    "semantic_cache"      |       |  - Dynamic API key injection   |
+------------------------+      +--------------------------+       +--------------------------------+
```

### 3.2 Key Architectural Components

#### 1. GeminiClientProvider (`com.semanticcache.backend.client.GeminiClientProvider`)
Provides dynamic instantiations of `com.google.genai.Client`:
- **Demo Mode:** When no client-specific API key is provided, falls back to the server-wide environment variable `GOOGLE_API_KEY`.
- **Personal Mode:** When a request supplies an API key (or user settings), creates an isolated Gemini client without leaking or persisting the key.

#### 2. Cache Retrieval & Decision Pipeline (`com.semanticcache.backend.service`)
- `GeminiEmbeddingService`: Invokes `gemini-embedding-001` to generate 768-dimensional float embeddings.
- `DefaultSimilaritySearchService`: Queries Qdrant collection `semantic_cache` with cosine distance and search filters.
- `DefaultCacheDecisionService`: Evaluates candidates against similarity threshold (`0.85`), expiration timestamp, and access visibility (Public vs. Private user scope).
- `GeminiService`: Invokes `gemini-3.8-flash` on cache misses to generate fresh responses.

#### 3. Database Schema (Flyway Migration: `V1__init_auth_schema.sql`)
- `users`:
  - `id`: `VARCHAR(36)` (UUID primary key)
  - `username`: `VARCHAR(50)` NOT NULL, UNIQUE (`uk_users_username`)
  - `password_hash`: `VARCHAR(255)` NOT NULL (BCrypt)
  - `enabled`: `BOOLEAN` NOT NULL DEFAULT TRUE
  - `created_at`: `TIMESTAMP WITH TIME ZONE` NOT NULL
  - `updated_at`: `TIMESTAMP WITH TIME ZONE` NOT NULL
- `refresh_tokens`:
  - `id`: `VARCHAR(36)` (UUID primary key)
  - `user_id`: `VARCHAR(36)` FOREIGN KEY -> `users(id)` ON DELETE CASCADE
  - `token_hash`: `VARCHAR(64)` NOT NULL, UNIQUE (`uk_refresh_tokens_token_hash`) — SHA-256 hash of raw token
  - `family_id`: `VARCHAR(36)` NOT NULL (Indexed: `idx_refresh_tokens_family_id`)
  - `revoked`: `BOOLEAN` NOT NULL DEFAULT FALSE
  - `expires_at`: `TIMESTAMP WITH TIME ZONE` NOT NULL
  - `created_at`: `TIMESTAMP WITH TIME ZONE` NOT NULL

---

## 4. Completed Backend Implementation

All backend modules are fully implemented, tested, and passing in `backend/`:

### 4.1 Authentication & Session Management
- **Registration (`POST /api/auth/register`):** Validates username (3-50 chars, alphanumeric + underscores) and password (8-100 chars, uppercase, lowercase, digit, special character). Hashes passwords with BCrypt (strength 10). Handles duplicate username violations cleanly with HTTP 409 (`DUPLICATE_USERNAME`).
- **Login (`POST /api/auth/login`):** Authenticates credentials, generates an initial `family_id`, issues a 15-minute access token (`expiresIn: 900`), creates a 7-day refresh token in database (SHA-256 hashed), and sets an `HttpOnly`, `Path=/api/auth` cookie.
- **Refresh Token Rotation (`POST /api/auth/refresh`):** 
  - Validates cookie or request token.
  - Implements **Strict Token Family Rotation**: If a previously revoked token is submitted, the system flags token reuse, revokes all active tokens within that entire `family_id`, and returns HTTP 401 (`INVALID_REFRESH_TOKEN`).
  - Otherwise, marks old token revoked and issues a new token pair in the same family.
- **Logout (`POST /api/auth/logout`):** Revokes token in database and sets expired cookie (`Max-Age=0`).
- **Scheduled Token Cleanup:** `RefreshTokenWorker` executes periodic cleanup of expired tokens.

### 4.2 Semantic Cache Engine
- **Anonymous / Privacy Mode (`CacheService`):**
  - Prompt embeddings are generated via `GeminiEmbeddingService`.
  - Vectors are searched in Qdrant with `SearchFilter.publicOnly()` or matching user ID filter.
  - On **HIT**: Returns answer immediately, flags `cached: true`, records metric hit.
  - On **MISS**: Calls `GeminiService.generateResponse()`, saves vector and prompt/response payload into Qdrant, returns answer, records metric miss.

### 4.3 Error Handling & Request Guarding
- **GlobalExceptionHandler:** Catches and translates exceptions into standard `ErrorResponse`:
  - `MethodArgumentNotValidException` -> 400 `VALIDATION_FAILED` (with field-level validation errors)
  - `DataIntegrityViolationException` -> 409 `DUPLICATE_USERNAME` if username constraint violation; otherwise 500 `INTERNAL_SERVER_ERROR`.
  - `BadCredentialsException` / `InvalidTokenException` -> 401 `INVALID_CREDENTIALS` / `INVALID_TOKEN`.
  - `MaxUploadSizeExceededException` -> 413 `PAYLOAD_TOO_LARGE`.
- **MaxRequestBodySizeFilter:** Enforces strict 64 KB (`65536` bytes) limit on JSON requests to prevent memory exhaustion and DoS attacks.

---

## 5. Security & Privacy Architecture

The backend has undergone multiple rigorous security hardening phases (Phase 3 Tasks 3.1 through 3.5):

### 5.1 Dual-Token Authentication Lifecycle
- **Access Tokens:**
  - Stateless JWT signed with HMAC-SHA256 (`HS256`).
  - Short lifetime: 15 minutes (900 seconds).
  - Payload contains `sub` (username), `userId`, and `roles`.
  - Transmitted by client as `Authorization: Bearer <token>`.
- **Refresh Tokens:**
  - High-entropy cryptographic random strings (32 bytes / 256 bits secure random).
  - Long lifetime: 7 days.
  - Stored in database exclusively as SHA-256 hex hashes. Raw refresh tokens are never persisted or logged.
  - Transmitted exclusively via `HttpOnly`, `SameSite=Lax` (dev) / `SameSite=Strict` (prod), `Path=/api/auth` cookies.
  - Raw refresh token is never trimmed or normalized, preserving strict cryptographic identity.

### 5.2 Origin Validation & CSRF Protection (`OriginValidationFilter`)
Protects state-changing cookie-authenticated endpoints: `POST /api/auth/refresh` and `POST /api/auth/logout`:
1. **Explicit Origin Match:** Canonicalizes `Origin` header (scheme, host, default-port normalization) and verifies against `app.cors.allowed-origins`.
2. **Strict URI Syntax:** Any `Origin` header with a path, query parameter, trailing slash, or fragment is rejected immediately with HTTP 403 `UNTRUSTED_ORIGIN`.
3. **No Fallback on Malformed/Blank Origin:** If an `Origin` header is present but blank or untrusted, it is rejected immediately.
4. **Referer Fallback:** Referer header is only evaluated when `Origin` is completely absent. The origin component of the Referer is extracted and verified.
5. **CSRF Custom Header Defense:** Enforces presence of `X-Requested-With: XMLHttpRequest` on cookie-based endpoints.
6. **OPTIONS Exemption:** Preflight OPTIONS requests pass through to `CorsFilter`.

### 5.3 Privacy-Preserving Caching
- **Public vs. Private Isolation:** Cache items have a `visibility` attribute (`PUBLIC` vs `PRIVATE`).
- Private cached queries are tied to `user_id` and are never returned to unauthenticated or different users.
- Prompts marked sensitive can be excluded from storage entirely.

---

## 6. Frontend Status & Gap Analysis

> [!WARNING]
> **Current Frontend State:** The React application in `frontend/src/App.jsx` is a **standalone prototype mockup**. It does **NOT** currently connect to the backend Spring Boot API.

### 6.1 What Is Currently in Frontend
- Single component (`frontend/src/App.jsx`) with simulated latency using `setTimeout`.
- Mock responses (`"This is a mocked response..."`, hardcoded tokens, fake latency counters).
- Tab navigation between Chat, Cache Visualizer, Analytics, and Settings.
- Visual styling for cache hit vs. miss badges and metric graphs.

### 6.2 What Is Missing in Frontend (To Be Implemented)
1. **HTTP Client & API Layer:** No `fetch` or `axios` wrappers. Need centralized client with base URL (`http://localhost:8080`), `credentials: 'include'`, and interceptors for 401 refresh retries.
2. **Authentication Flow & State:**
   - No login/registration modals or views.
   - No token storage in memory or auth context (`AuthContext.jsx`).
   - No silent refresh on app startup (`POST /api/auth/refresh`).
3. **API Key Management:** Settings tab input for user's Gemini API key does not connect to backend requests.
4. **Real Cache Query Integration:** Chat input does not call `POST /api/cache`.
5. **Real Analytics & Metrics:** Analytics tab does not poll `/metrics` or `/api/health`.

---

## 7. Environment Setup & Local Run Guide

### 7.1 Prerequisites
- **OS:** Windows 10/11, macOS, or Linux
- **Java:** JDK 21+ installed and configured in `PATH` / `JAVA_HOME`
- **Node.js:** v18.0.0+ and `npm`
- **Docker:** (Recommended) For running Qdrant and PostgreSQL locally

### 7.2 Environment Variables
Set the following environment variables before starting the backend:

| Variable Name | Required | Default (Dev) | Description |
|---|---|---|---|
| `GOOGLE_API_KEY` | **Yes** (for cache miss) | *None* | Google Gemini API key from AI Studio |
| `JWT_SECRET` | **Yes** (or dev default) | Built-in 256-bit dev key | HMAC-SHA256 secret for signing JWTs |
| `FRONTEND_URL` | Optional | `http://localhost:5173` | Allowed frontend origin for CORS |
| `SPRING_PROFILES_ACTIVE` | Optional | `default` | Set to `prod` to activate PostgreSQL |

### 7.3 Step-by-Step Local Startup

#### Step 1: Start Qdrant Vector Database
Using Docker:
```powershell
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.12.0
```

#### Step 2: Configure Environment in PowerShell
```powershell
$env:GOOGLE_API_KEY = "your-actual-gemini-api-key"
$env:JWT_SECRET = "your-secure-at-least-256-bit-secret-key-here-min-32-bytes"
```

#### Step 3: Run the Backend
From repository root:
```powershell
cd backend
mvn clean spring-boot:run
```
- In default development mode, the application uses local H2 file storage at `./data/authdb` and connects to Qdrant at `localhost:6334`.
- Flyway automatically runs database migrations on startup.
- Server starts on port `8080`.

#### Step 4: Run the Frontend
In a separate terminal:
```powershell
cd frontend
npm install
npm run dev
```
- Vite dev server starts on `http://localhost:5173`.

---

## 8. Comprehensive API Reference

All requests accept and return `application/json` unless otherwise noted.

### 8.1 Authentication Endpoints

#### 1. Register User
- **Endpoint:** `POST /api/auth/register`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "username": "developer123",
    "password": "Password123!"
  }
  ```
- **Success Response (201 Created):**
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "c1f7b824-3456-4c91-b1e8-7a8d56c80001",
      "username": "developer123"
    }
  }
  ```
- **Error Response (409 Conflict):**
  ```json
  {
    "status": 409,
    "error": "DUPLICATE_USERNAME",
    "message": "Username is already taken"
  }
  ```

#### 2. User Login
- **Endpoint:** `POST /api/auth/login`
- **Auth Required:** No
- **Request Body:**
  ```json
  {
    "username": "developer123",
    "password": "Password123!"
  }
  ```
- **Success Response (200 OK):**
  - Sets Cookie: `refreshToken=<token>; Path=/api/auth; HttpOnly; SameSite=Lax`
  - Body:
    ```json
    {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "tokenType": "Bearer",
      "expiresIn": 900,
      "user": {
        "id": "c1f7b824-3456-4c91-b1e8-7a8d56c80001",
        "username": "developer123"
      }
    }
    ```

#### 3. Refresh Access Token
- **Endpoint:** `POST /api/auth/refresh`
- **Auth Required:** Refresh Token Cookie
- **Required Headers:**
  - `Origin: http://localhost:5173`
  - `X-Requested-With: XMLHttpRequest`
- **Success Response (200 OK):**
  - Sets new `refreshToken` cookie (token rotation)
  - Body:
    ```json
    {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "tokenType": "Bearer",
      "expiresIn": 900
    }
    ```
- **Error Response (403 Forbidden / CSRF failure):**
  ```json
  {
    "status": 403,
    "error": "UNTRUSTED_ORIGIN",
    "message": "Origin header is not allowed"
  }
  ```

#### 4. Logout
- **Endpoint:** `POST /api/auth/logout`
- **Auth Required:** Refresh Token Cookie
- **Required Headers:**
  - `Origin: http://localhost:5173`
  - `X-Requested-With: XMLHttpRequest`
- **Success Response (200 OK):**
  - Clears `refreshToken` cookie (`Max-Age=0`)
  - Body:
    ```json
    {
      "message": "Logged out successfully"
    }
    ```

---

### 8.2 Cache & LLM Endpoints

#### Query Cache / Generate Response
- **Endpoint:** `POST /api/cache`
- **Auth Required:** Optional (Public queries allowed anonymously; Bearer token for private cache)
- **Request Body:**
  ```json
  {
    "prompt": "How do I reverse a string in Python?",
    "apiKey": "optional-user-provided-gemini-key",
    "similarityThreshold": 0.85
  }
  ```
- **Success Response (200 OK - Cache HIT):**
  ```json
  {
    "prompt": "How do I reverse a string in Python?",
    "response": "In Python, you can reverse a string using slicing: `reversed_str = original_str[::-1]`.",
    "cached": true,
    "similarityScore": 0.942,
    "latencyMs": 14,
    "model": "gemini-3.8-flash"
  }
  ```
- **Success Response (200 OK - Cache MISS):**
  ```json
  {
    "prompt": "How do I reverse a string in Python?",
    "response": "In Python, you can reverse a string using slicing: `reversed_str = original_str[::-1]`.",
    "cached": false,
    "similarityScore": 0.0,
    "latencyMs": 1420,
    "model": "gemini-3.8-flash"
  }
  ```

---

### 8.3 Observability Endpoints

#### Health Check
- **Endpoint:** `GET /api/health`
- **Auth Required:** No
- **Response (200 OK):**
  ```json
  {
    "status": "UP",
    "components": {
      "database": "UP",
      "qdrant": "UP",
      "gemini": "UP"
    }
  }
  ```

#### Cache Metrics
- **Endpoint:** `GET /metrics`
- **Auth Required:** No
- **Response (200 OK):**
  ```json
  {
    "totalRequests": 150,
    "cacheHits": 105,
    "cacheMisses": 45,
    "hitRatePercentage": 70.0,
    "averageHitLatencyMs": 18.2,
    "averageMissLatencyMs": 1640.5,
    "savedTokensEstimate": 31500
  }
  ```

---

## 9. Testing Strategy & Test Suite Details

### 9.1 Test Suite Summary
- **Total Tests:** 658
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Build Status:** Passing / Stable

### 9.2 Test Architecture
The test suite in `backend/src/test/java` covers multiple testing layers:
1. **Unit Tests (Mockito / JUnit 5):**
   - `AuthServiceTest`: User registration, password validation, token rotation, reuse revocation.
   - `JwtServiceTest`: Token creation, expiration validation, claim parsing.
   - `CacheServiceTest`: Hit/miss flow, threshold decision logic, Gemini fallback.
   - `DefaultCacheDecisionServiceTest`: Verification of cosine score comparisons and visibility checks.
2. **Filter & Security Tests:**
   - `OriginValidationFilterTest`: 50+ test cases covering absent Origin, empty Origin, untrusted origins, malformed URLs, port normalization, path stripping, and Referer fallback rules.
   - `JwtAuthenticationFilterTest`: Missing Bearer, malformed Bearer, expired token, valid token context propagation.
   - `CorsIntegrationTest`: Verifies preflight OPTIONS handling and CORS response headers.
3. **Integration & Controller Tests (`@SpringBootTest`, `@AutoConfigureMockMvc`):**
   - `AuthControllerTest` / `AuthControllerIntegrationTest`: Full HTTP request-response cycle, cookie extraction, validation errors.
   - `CacheControllerTest`: Request payload size limits, prompt handling.
4. **Mocking Strategy:**
   - External Qdrant client and Gemini API SDK calls are mocked in unit/integration tests to ensure tests run deterministically and fast without requiring external network connectivity or paid API credits.

### 9.3 Running Tests
Run the entire backend test suite from `backend/`:
```powershell
mvn test
```
To run a specific test class:
```powershell
mvn test -Dtest=OriginValidationFilterTest
```

---

## 10. Remaining Work & Backlog (P0–P3)

### Priority P0: Critical Path (Next Sprint)
1. **Frontend API Client Integration:**
   - Implement `apiClient.js` with base URL configuration and credentials inclusion (`credentials: 'include'`).
   - Create request interceptor to attach `Authorization: Bearer <accessToken>`.
   - Create response interceptor to automatically attempt `POST /api/auth/refresh` on 401 responses and retry original requests.
2. **Frontend Authentication Flow:**
   - Implement Login / Register UI modals or page.
   - Add global `AuthContext` to manage user state and tokens.
   - Implement silent authentication bootstrap on initial app load.
3. **Connect Frontend Chat to Backend Cache:**
   - Replace `setTimeout` mockup in `App.jsx` with calls to `POST /api/cache`.
   - Render real latency numbers, hit/miss badges, and similarity scores returned by backend.

### Priority P1: High Priority
1. **Qdrant Collection Auto-Provisioning:**
   - Ensure `semantic_cache` collection is automatically created with 768 dimensions and Cosine distance on application startup if it does not already exist.
2. **Frontend Settings & Analytics Wiring:**
   - Allow user to input custom Gemini API key in Settings and pass in `CacheRequest.apiKey`.
   - Wire Analytics dashboard to live `/metrics` endpoint with periodic polling or auto-refresh.

### Priority P2: Medium Priority (Enhancements)
1. **Two-Stage Cross-Encoder Re-ranking:**
   - The original architecture proposal included a Stage 2 Cross-Encoder (bi-encoder retrieval + cross-encoder re-ranking).
   - Evaluate integrating a lightweight local cross-encoder model (e.g. via ONNX runtime) for candidate pairs falling within the boundary zone (e.g., similarity between 0.80 and 0.90).
2. **Cache Invalidation & TTL Management:**
   - Provide administrative endpoints to flush cache entries by tag, prompt ID, or expiration date.
   - Implement dynamic TTL based on prompt domain (e.g., code snippets longer TTL than news/weather).

### Priority P3: Low Priority (Production Polish)
1. **Docker Compose Packaging:**
   - Provide a root `docker-compose.yml` that boots PostgreSQL, Qdrant, Spring Boot backend, and Nginx-hosted React frontend simultaneously.
2. **OpenAPI / Swagger Documentation:**
   - Add `springdoc-openapi-starter-webmvc-ui` to generate interactive Swagger UI at `/swagger-ui.html`.

---

## 11. Known Limitations, Edge Cases & Technical Debt

1. **Frontend Mock Disconnect:**
   - The frontend currently exhibits zero real communication with `http://localhost:8080`. Any testing of cache or auth features must currently be done via HTTP tools (Curl, Postman) or backend integration tests.
2. **H2 vs. PostgreSQL Constraint Behavior:**
   - In H2 development mode, constraint names may format differently than PostgreSQL. `GlobalExceptionHandler` includes defensive handling, but production PostgreSQL testing is required for schema edge cases.
3. **Cross-Origin Cookie Restrictions in Production:**
   - In production, if frontend and backend are hosted on different domains (e.g., `app.domain.com` vs `api.domain.com`), `SameSite=Strict` will block cookies on cross-origin requests. `SameSite=None` and `Secure=true` will be required for cross-site cookie transmission.
4. **Qdrant Availability Dependency:**
   - If Qdrant is down, `CacheService` will throw an error unless wrapped in a circuit breaker. A graceful fallback to direct LLM generation should be considered for high-availability setups.

---

## 12. Recommended Development Roadmap

```
+-------------------------------------------------------------------------------+
| PHASE 4: Frontend-Backend Integration (Immediate Next Step)                   |
| 1. Build api/client.js with auto-refresh on 401                               |
| 2. Implement AuthContext, Login, and Registration components                   |
| 3. Connect Chat Interface to POST /api/cache                                  |
| 4. Connect Analytics tab to GET /metrics and GET /api/health                   |
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
| PHASE 5: Qdrant Production Readiness & Resilience                             |
| 1. Implement QdrantStartupInitializer (auto-create collection & index)        |
| 2. Add Circuit Breaker / Fallback for Qdrant downtime                         |
| 3. Test on Dockerized PostgreSQL with Flyway migrations                        |
+-------------------------------------------------------------------------------+
                                      |
                                      v
+-------------------------------------------------------------------------------+
| PHASE 6: Advanced Semantic Features & Deployment                              |
| 1. Optional Stage-2 Cross-Encoder re-ranker integration                       |
| 2. Multi-model support (OpenAI / Claude API key adapters)                     |
| 3. Complete Docker Compose and production deployment guides                   |
+-------------------------------------------------------------------------------+
```

---

## 13. Git Workflow & Collaboration Guidelines

### 13.1 Branching Strategy
- **Baseline Branch:** `feature/backend-development` (commit `2e92ea4`).
- **New Feature Branches:** Branch off `feature/backend-development`:
  - `feature/frontend-api-integration`
  - `feature/frontend-auth-ui`
  - `feature/qdrant-auto-init`

### 13.2 Commit Conventions
Follow Conventional Commits:
- `feat: ...` for new features
- `fix: ...` for bug fixes
- `test: ...` for test suite additions
- `refactor: ...` for structural improvements without functional changes

### 13.3 Clean Code Rules
- Maintain all existing tests (do not comment out or ignore tests to make builds pass).
- Never commit hardcoded secrets, API keys, or JWT tokens.
- Keep `OriginValidationFilter` and `JwtAuthenticationFilter` security rules intact when altering endpoints.

---

## 14. Quickstart Guide for Next Developer

If you have just cloned or opened this repository, follow these exact steps to begin:

1. **Verify Git State:**
   ```powershell
   git status
   git log -n 1 --oneline
   # Verify you are on feature/backend-development at commit 2e92ea4
   ```

2. **Verify Backend Tests:**
   ```powershell
   cd backend
   mvn test
   # Expect 658 tests to pass with 0 failures
   ```

3. **Review Key Code Files:**
   - Security Filters: `backend/src/main/java/com/semanticcache/backend/filter/OriginValidationFilter.java`
   - Cache Pipeline: `backend/src/main/java/com/semanticcache/backend/service/CacheService.java`
   - Similarity Engine: `backend/src/main/java/com/semanticcache/backend/service/DefaultSimilaritySearchService.java`
   - Frontend Mock: `frontend/src/App.jsx`

4. **Pick Up Next Task:**
   - Begin **Priority P0, Task 1**: Create `frontend/src/api/client.js` and connect the React frontend to the running Spring Boot backend.
