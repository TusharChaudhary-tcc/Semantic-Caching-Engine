# 📘 SemanticCache-LLM — Beginner-Friendly Theory & Study Guide (`THEORY.md`)

> **Target Audience:** 2nd-Year CSE Students & Project Team Members  
> **Goal:** Understand *every* technology and concept used in our project in plain, intuitive language — no heavy math or confusing jargon!

---

## 1. What Problem Are We Solving? (Exact Cache vs. Semantic Cache)

Normally, when a user asks an AI (like ChatGPT or Gemini) a question:
- It takes **1 to 4 seconds** to reply.
- Every single API call **costs money** (charged per token/word).

### Traditional Caching (Redis / HashMap)
In normal Data Structures, a cache works like a Python `dict` or Java `HashMap`:
- Key: `"How do I reset my password?"` $\rightarrow$ Value: `"Click Forgot Password..."`
- If another user asks `"I forgot my password, help!"`, the exact string is **different**. So a traditional cache says **CACHE MISS** and pays the AI again!

### Semantic Caching (Our Project)
Instead of matching exact letters, **Semantic Caching** matches **meaning (semantics)**.
If User A asks *"How do I reset my password?"* and User B asks *"I forgot my password"*, our engine realizes both mean the exact same thing and returns the saved answer in **under 40 milliseconds** — **for free!**

---

## 2. Embeddings & Vector Similarity (How Computers Understand Meaning)

Computers don't understand English words; they only understand numbers.

### What is an Embedding?
An **Embedding Model** (like Google's `gemini-embedding-001`) reads a sentence and converts its *meaning* into a list of **768 numbers** (called a **Vector**):
- `"How to sort a list in Python?"` $\rightarrow$ `[0.12, -0.84, 0.33, ..., 0.09]`
- `"Python array sorting syntax"` $\rightarrow$ `[0.11, -0.82, 0.31, ..., 0.10]`

Sentences with similar meanings end up with **nearly identical numbers**, even if they use completely different words!

### What is Cosine Similarity?
Imagine each 768-number vector as an arrow pointing in space:
- If two arrows point in the **exact same direction**, the angle between them is $0^\circ$, and **Cosine Similarity = `1.0` (100%)**.
- If they are totally unrelated (e.g., *"Python sorting"* vs. *"Pizza recipe"*), the arrows point in different directions, and Cosine Similarity is close to **`0.0`**.

In Stage 1 of our project, we check if `Cosine Similarity >= 0.75` (75%).

---

## 3. Vector Database (Why Qdrant Instead of MySQL/PostgreSQL?)

You might wonder: *"We already have SQL databases like PostgreSQL or H2. Why do we need Qdrant?"*

- **SQL Databases** are built for exact lookups (`WHERE username = 'vedant'`). If you store 100,000 vectors of 768 numbers in SQL and ask *"find the closest arrow"*, SQL has to compare your query against **all 100,000 rows** one by one. That is too slow!
- **Qdrant (Vector Database)** is built specifically for vectors. It organizes vectors into smart neighborhood graphs (called **HNSW** — Hierarchical Navigable Small World). Instead of checking all 100,000 entries, it jumps straight to the right "neighborhood" and finds the Top-3 closest matches in **~5 milliseconds**.
- We talk to Qdrant using **gRPC** (a super-fast binary protocol created by Google, much faster than normal JSON over HTTP).

---

## 4. The Two-Stage Pipeline: Bi-Encoder vs. Cross-Encoder

This is one of the **most important concepts** for your project viva and research paper!

### Why Stage 1 Alone (Bi-Encoder) Makes Mistakes
In Stage 1, the embedding model encodes each sentence **independently** (one at a time). This is called a **Bi-Encoder**.
Because it doesn't compare words side-by-side, it can get tricked by shared keywords:
- Query A: `"How do I reset my password?"`
- Query B: `"What is a good password strength?"`

Both contain the word `"password"`, so a Bi-Encoder might give them a high cosine similarity like **`0.88` (88%)** — even though the user's **intent** is completely different! Returning a password-strength answer when someone wants to reset their password is a **False Positive**.

### How Stage 2 (Cross-Encoder) Fixes It
A **Cross-Encoder** (`ms-marco-MiniLM-L-6-v2`) is a smarter, deeper AI model. Instead of encoding sentences separately, it takes **both sentences together** in one input:
`[CLS] How do I reset my password? [SEP] What is a good password strength? [SEP]`

Because it reads both sentences *at the same time*, its attention layers notice `"reset"` vs. `"strength"` and output a low intent match score like **`0.22` (22%)** $\rightarrow$ **Rejected!**

```
Stage 1 (Bi-Encoder + Qdrant):  Fast filter (checks 100,000+ items in ~5ms)  --> Picks Top 3 candidates
Stage 2 (Cross-Encoder ONNX):   Deep check  (checks only Top 3 in ~10ms)    --> Verifies true intent (>= 0.85)
```

### Mini-Concepts Inside Stage 2
1. **WordPiece Tokenizer (`WordPieceTokenizer.java`):** Breaks sentences into numeric IDs using a vocabulary dictionary (`vocab.txt`). Rare words are split into subwords with `##` (e.g., `"unknown"` $\rightarrow$ `"un"` + `"##known"`). Special tokens `[CLS]` marks the start and `[SEP]` separates the two sentences.
2. **Sigmoid Function:** The raw AI model outputs any number from $-\infty$ to $+\infty$ (called a *logit*). Sigmoid squashes it cleanly into a percentage between `0.0` and `1.0`:
   $$\text{sigmoid}(x) = \frac{1}{1 + e^{-x}}$$

---

## 5. ONNX Runtime in Java (Running AI Without Python!)

Most AI models are trained in **Python (PyTorch)**. Normally, Java teams build a separate Python server and call it over HTTP.
**Problem:** Calling a separate Python server adds 15–30ms of network delay on every request.

**Our Solution — ONNX (Open Neural Network Exchange):**
Think of ONNX as **".pdf for AI models"**. You export a PyTorch model once into a `.onnx` file, and then **any language** (Java, C++, C#) can run it natively.
Using **ONNX Runtime Java**, our Spring Boot backend loads the `.onnx` model directly into memory and runs C++ speed inference right inside Java!

---

## 6. Cache Eviction Algorithms (Our Core Research Contribution)

Caches don't have infinite RAM or disk space. When the cache gets full, we must delete (evict) old entries.

### Standard LRU (Least Recently Used)
LRU simply deletes whichever item hasn't been used for the longest time.
**Why LRU is bad for LLMs:** Imagine Query A took **4 seconds & 1,000 tokens** to generate, and Query B took **0.3 seconds & 20 tokens**. LRU might delete the expensive Query A just because it was asked 5 minutes earlier!

### Our Novel Algorithm: Semantic Density & Cost-Weighted Eviction
Instead of looking only at time, we calculate a **Keep Score** for each cached item:
$$\text{Score} = w_1 \cdot \text{Recency} + w_2 \cdot \text{Frequency} + w_3 \cdot \text{Semantic Cluster Density} + w_4 \cdot \text{Cost}$$

- **Recency:** How recently was it used?
- **Frequency:** How many times has it been a cache hit?
- **Semantic Cluster Density:** Are many users asking questions around this topic? (Dense topics are high-value!).
- **Cost Weight:** How expensive (tokens/latency) was it to generate from the LLM?

### The "Dusty Corner" (K-Tail) Optimization
Scanning 1,000,000 items to find the lowest score is slow ($O(N)$). Instead, **K-Tail** grabs a small sample of the oldest/coldest items (say, $K = 100$) and only evicts the lowest-scoring item from that small sample ($O(1)$ constant time!).

---

## 7. Backend Stack & Security (Java + Spring Boot)

### Why Java 21 + Spring Boot?
- **Spring Boot** handles HTTP routing (`@RestController`), dependency injection (`@Service`), database connections (JPA/Hibernate), and configuration automatically.
- **Java 21** handles thousands of concurrent requests in parallel without Python's single-threaded bottleneck (GIL), making it ideal for an enterprise API gateway.

### How Authentication Works (JWT + Refresh Token Rotation)
1. **Access Token (JWT):** Like a **15-minute hotel keycard**. The frontend sends it in the `Authorization: Bearer <token>` header on every request. It is *stateless* (the server verifies the cryptographic signature without even checking the database).
2. **Refresh Token:** Like your **ID card at reception** (valid for 7 days, stored in an `HttpOnly` cookie so JavaScript/hackers cannot steal it). When the 15-minute keycard expires, the frontend silently asks `/api/auth/refresh` for a new one.
3. **Token Family Rotation (Anti-Theft):** Every time you refresh, the old refresh token is deleted and a new one is issued. If a hacker steals an old refresh token and tries to reuse it, our backend detects the reuse and **immediately revokes the entire token family**, logging the attacker out!

### CORS, Origin Validation & CSRF
- **CORS:** Browsers block `localhost:5173` (React) from calling `localhost:8080` (Spring Boot) unless the backend explicitly allows that origin.
- **Origin Validation & `X-Requested-With`:** Protects cookie endpoints (`/refresh`, `/logout`) so malicious third-party websites cannot trick a user's browser into making hidden requests.

---

## 8. LLM APIs: Why Gemini + Groq?

- **Google Gemini:** Gives us **both** high-quality 768-dimension **Embeddings** (`gemini-embedding-001`) and fast **Text Generation** (`gemini-3.8-flash` / `gemini-2.0-flash`) with a generous free tier.
- **Groq:** Runs open-source models (like Llama-3.3-70B) on custom LPU chips at **500–800 tokens/sec**, making it the fastest free-tier generator — *however*, Groq has **no embedding API**.
- **Our Design:** Use Gemini for embeddings, and allow Gemini or Groq for generating answers on a cache miss!

---

## 9. Quick Viva / Presentation Q&A (Cheat Sheet)

**Q1: Why not just use Redis for caching?**  
> Redis only matches exact strings or hashes. Natural language varies (*"reset password"* vs. *"forgot password"*), so Redis gets misses on paraphrased queries. Semantic caching matches *meaning* via vector embeddings.

**Q2: If embeddings already match meaning, why do we need a Cross-Encoder?**  
> Bi-encoders encode queries independently and can produce false positives when two queries share keywords but have different intents (e.g., *"password reset"* vs. *"password strength"*). The Cross-Encoder reads both queries jointly to verify true intent.

**Q3: Why run the Cross-Encoder in Java using ONNX instead of calling a Python API?**  
> Running ONNX in-process inside the JVM eliminates HTTP serialization and network overhead, keeping total cache hit latency under 40ms while preserving Java's high multi-threaded throughput.

**Q4: What is the novelty of your eviction algorithm?**  
> Standard LRU only checks time and accidentally deletes expensive LLM responses. Our Semantic Density & Cost-Weighted algorithm preserves high-cost, high-frequency, and densely-clustered queries while using K-Tail sampling for $O(1)$ eviction speed.

---

## 10. How We Benchmark & Prove It (Phase 3 Evaluation Concepts)

To prove our system works for a research paper, we don't just guess — we replay **50,000 real user conversations** from the LMSYS Chatbot Arena dataset and measure four things:
1. **Hit Ratio vs. Tokens Saved:** Even a 1–2% higher hit rate matters, and **which** items you keep matters even more! At 500-entry capacity, our Semantic Density + Cost policy saved **1,578,980 tokens** vs. **1,170,482 tokens** for LRU (**+34.9% more tokens saved**), because it keeps expensive, long-answer queries alive.
2. **Latency Percentiles (`p50` vs. `p95`):** Averages hide slow spikes. `p95` means 95% of all requests finish faster than that number. Our Two-Stage cache hit achieves **`23.46 ms` median (`26.70 ms` p95)** — **61x faster** than Gemini Flash (`1434.22 ms` median).
3. **K-Tail Ablation Study:** An *ablation* changes one knob at a time (e.g., `K = 25` vs. `100` vs. `250`) to prove our default `K = 100` hits the sweet spot between speed (`12.67s` vs. `73.01s` for `K=250`) and hit rate (`18.98%`).
4. **Vector Store Tradeoff:** Exact HashMaps (like Redis) are ultra-fast (`0.0005 ms`) but get **`0.0000` semantic recall** on paraphrased queries, whereas HNSW vector indexes (Qdrant / FAISS) achieve **~`0.995–1.000` recall@3** in milliseconds.
