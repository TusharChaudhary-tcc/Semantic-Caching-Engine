"""
Step 2: Eviction Policy Simulator (Exact vs. LRU vs. LFU vs. Semantic Density + K-Tail).

Replays 50,000+ queries from Knowledge/chatbot_arena_dataset.csv using precomputed
384-d normalized embeddings and FAISS similarity search, faithfully mirroring
SemanticDensityEvictionPolicy.java & SemanticEvictionManager.java:
  Score = w1 * Recency + w2 * Frequency + w3 * SemanticClusterDensity + w4 * CostWeight
  w1=0.30, w2=0.25, w3=0.25, w4=0.20, recency_decay=0.05/hr, K-Tail=100.

Outputs:
- benchmarks/results/eviction_benchmark.csv
- benchmarks/results/eviction_timeseries.csv
- benchmarks/results/eviction_ablation.csv
"""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
import math
from pathlib import Path
import time
import faiss
import numpy as np
import pandas as pd
from sentence_transformers import SentenceTransformer

BENCH_DIR = Path(__file__).resolve().parent
ROOT_DIR = BENCH_DIR.parent
ARENA_CSV = ROOT_DIR / "Knowledge" / "chatbot_arena_dataset.csv"
EMB_CACHE = BENCH_DIR / "data" / "arena_embeddings.npz"
RESULTS_DIR = BENCH_DIR / "results"

BI_ENCODER_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
SIMILARITY_THRESHOLD = 0.85
COST_PER_1M_TOKENS_USD = 0.30  # Blended Gemini Flash / Groq Llama-3.3-70B API cost


@dataclass
class CacheItem:
    key_idx: int
    prompt_norm: str
    created_at_sec: float
    last_accessed_sec: float
    hit_count: int
    generation_latency_ms: float
    estimated_tokens: int
    cluster_density: float


def load_or_compute_embeddings(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """Encode unique prompts once and map each row to its unique prompt index."""
    BENCH_DIR.joinpath("data").mkdir(parents=True, exist_ok=True)
    prompts = df["prompt"].astype(str).tolist()
    unique_prompts, inverse_idx = np.unique(prompts, return_inverse=True)

    if EMB_CACHE.exists():
        data = np.load(EMB_CACHE)
        if data["unique_embeddings"].shape[0] == len(unique_prompts):
            print(f"Loaded cached embeddings from {EMB_CACHE} ({len(unique_prompts):,} unique prompts).")
            return data["unique_embeddings"].astype(np.float32), inverse_idx

    print(f"Encoding {len(unique_prompts):,} unique prompts with {BI_ENCODER_MODEL}...")
    model = SentenceTransformer(BI_ENCODER_MODEL)
    embs = model.encode(
        unique_prompts.tolist(),
        batch_size=256,
        normalize_embeddings=True,
        show_progress_bar=True,
    ).astype(np.float32)
    np.savez_compressed(EMB_CACHE, unique_embeddings=embs)
    print(f"Cached embeddings to {EMB_CACHE}")
    return embs, inverse_idx


class ExactMatchCache:
    """Traditional exact string cache (Redis / HashMap baseline) with LRU eviction."""

    def __init__(self, capacity: int) -> None:
        self.capacity = capacity
        self.store: OrderedDict[str, CacheItem] = OrderedDict()

    def lookup(self, prompt_norm: str, vec: np.ndarray, now_sec: float) -> CacheItem | None:
        item = self.store.get(prompt_norm)
        if item is not None:
            self.store.move_to_end(prompt_norm)
            item.last_accessed_sec = now_sec
            item.hit_count += 1
        return item

    def insert(self, item: CacheItem) -> None:
        if item.prompt_norm in self.store:
            self.store.move_to_end(item.prompt_norm)
            return
        if len(self.store) >= self.capacity:
            self.store.popitem(last=False)
        self.store[item.prompt_norm] = item


class SemanticCacheSimulator:
    """
    Vector-backed semantic cache supporting LRU, LFU, and SemanticDensity (K-Tail).
    Uses periodic FAISS index rebuilds + active vector buffer for fast O(1) amortized lookup.
    """

    def __init__(
        self,
        capacity: int,
        policy: str,
        unique_embs: np.ndarray,
        w1: float = 0.30,
        w2: float = 0.25,
        w3: float = 0.25,
        w4: float = 0.20,
        k_tail: int = 100,
        recency_decay_per_hour: float = 0.05,
    ) -> None:
        self.capacity = capacity
        self.policy = policy
        self.unique_embs = unique_embs
        self.w1 = w1
        self.w2 = w2
        self.w3 = w3
        self.w4 = w4
        self.k_tail = k_tail
        self.recency_decay_per_hour = recency_decay_per_hour

        self.entries: OrderedDict[int, CacheItem] = OrderedDict()
        self.dim = unique_embs.shape[1]
        self.index = faiss.IndexFlatIP(self.dim)
        self.indexed_keys: list[int] = []
        self.pending_keys: list[int] = []
        self.rebuild_threshold = 256

    def _rebuild_index_if_needed(self) -> None:
        if len(self.pending_keys) >= self.rebuild_threshold or len(self.indexed_keys) > len(self.entries) * 1.35 + 256:
            keys = list(self.entries.keys())
            self.index.reset()
            if keys:
                vecs = self.unique_embs[keys]
                self.index.add(vecs)
            self.indexed_keys = keys
            self.pending_keys.clear()

    def lookup(self, vec: np.ndarray, now_sec: float) -> tuple[CacheItem | None, float]:
        if not self.entries:
            return None, 0.0

        best_sim = -1.0
        best_key: int | None = None

        # Search FAISS index
        if self.indexed_keys:
            sims, idxs = self.index.search(vec.reshape(1, -1), min(5, len(self.indexed_keys)))
            for sim, pos in zip(sims[0], idxs[0]):
                if pos < 0:
                    continue
                k = self.indexed_keys[pos]
                if k in self.entries and float(sim) > best_sim:
                    best_sim = float(sim)
                    best_key = k

        # Search small pending buffer
        if self.pending_keys:
            valid_pending = [k for k in self.pending_keys if k in self.entries]
            if valid_pending:
                p_vecs = self.unique_embs[valid_pending]
                p_sims = p_vecs @ vec
                max_i = int(np.argmax(p_sims))
                if float(p_sims[max_i]) > best_sim:
                    best_sim = float(p_sims[max_i])
                    best_key = valid_pending[max_i]

        if best_key is not None and best_sim >= SIMILARITY_THRESHOLD:
            item = self.entries[best_key]
            item.last_accessed_sec = now_sec
            item.hit_count += 1
            if self.policy in ("lru", "semantic_density"):
                self.entries.move_to_end(best_key)
            return item, best_sim

        return None, max(0.0, best_sim)

    def _compute_keep_score(self, item: CacheItem, now_sec: float) -> float:
        hours = max(0.0, (now_sec - item.last_accessed_sec) / 3600.0)
        recency = math.exp(-self.recency_decay_per_hour * hours)
        frequency = item.hit_count / (item.hit_count + 5.0) if item.hit_count > 0 else 0.0
        density = item.cluster_density
        latency_factor = min(1.0, item.generation_latency_ms / 5000.0)
        token_factor = min(1.0, item.estimated_tokens / 1000.0)
        cost = 0.5 * latency_factor + 0.5 * token_factor
        return self.w1 * recency + self.w2 * frequency + self.w3 * density + self.w4 * cost

    def _evict_one(self, now_sec: float) -> None:
        if not self.entries:
            return
        if self.policy == "lru":
            self.entries.popitem(last=False)
        elif self.policy == "lfu":
            # Scan K-tail or full LFU victim (min hit_count, tie-break oldest)
            victim_key = min(
                self.entries.keys(),
                key=lambda k: (self.entries[k].hit_count, self.entries[k].last_accessed_sec),
            )
            del self.entries[victim_key]
        elif self.policy == "semantic_density":
            # Dusty Corner (K-Tail): inspect oldest K entries in access-order
            tail_keys = []
            for k in self.entries.keys():
                tail_keys.append(k)
                if len(tail_keys) >= self.k_tail:
                    break
            victim_key = min(tail_keys, key=lambda k: self._compute_keep_score(self.entries[k], now_sec))
            del self.entries[victim_key]

    def insert(self, item: CacheItem, now_sec: float) -> None:
        if item.key_idx in self.entries:
            self.entries.move_to_end(item.key_idx)
            return
        if len(self.entries) >= self.capacity:
            self._evict_one(now_sec)
        self.entries[item.key_idx] = item
        self.pending_keys.append(item.key_idx)
        self._rebuild_index_if_needed()


def run_single_simulation(
    df: pd.DataFrame,
    unique_embs: np.ndarray,
    inverse_idx: np.ndarray,
    capacity: int,
    policy: str,
    w1: float = 0.30,
    w2: float = 0.25,
    w3: float = 0.25,
    w4: float = 0.20,
    k_tail: int = 100,
) -> tuple[dict, list[dict]]:
    t0 = time.perf_counter()
    hits = 0
    total = len(df)
    tokens_saved = 0
    total_tokens = 0
    latency_saved_ms = 0.0
    ts_rows = []

    exact_cache = ExactMatchCache(capacity) if policy == "exact" else None
    sem_cache = (
        SemanticCacheSimulator(capacity, policy, unique_embs, w1=w1, w2=w2, w3=w3, w4=w4, k_tail=k_tail)
        if policy != "exact"
        else None
    )

    prompts = df["prompt"].astype(str).tolist()
    timestamps = df["timestamp_sec"].to_numpy(dtype=float)
    tokens_arr = df["estimated_tokens"].to_numpy(dtype=int)
    latencies_arr = df["generation_latency_ms"].to_numpy(dtype=float)

    for i in range(total):
        u_idx = int(inverse_idx[i])
        now_sec = float(timestamps[i])
        tok = int(tokens_arr[i])
        lat = float(latencies_arr[i])
        total_tokens += tok

        if exact_cache is not None:
            p_norm = prompts[i].strip().lower()
            hit_item = exact_cache.lookup(p_norm, unique_embs[u_idx], now_sec)
            if hit_item is not None:
                hits += 1
                tokens_saved += tok
                latency_saved_ms += max(0.0, lat - 5.0)
            else:
                exact_cache.insert(
                    CacheItem(u_idx, p_norm, now_sec, now_sec, 0, lat, tok, 0.0)
                )
        else:
            assert sem_cache is not None
            vec = unique_embs[u_idx]
            hit_item, sim = sem_cache.lookup(vec, now_sec)
            if hit_item is not None:
                hits += 1
                tokens_saved += tok
                latency_saved_ms += max(0.0, lat - 22.0)
            else:
                # Estimate local semantic cluster density from nearest-neighbor similarity
                density = max(0.0, min(1.0, sim))
                sem_cache.insert(
                    CacheItem(u_idx, "", now_sec, now_sec, 0, lat, tok, density),
                    now_sec,
                )

        if (i + 1) % 2500 == 0 or i == total - 1:
            ts_rows.append(
                {
                    "capacity": capacity,
                    "policy": policy,
                    "queries_processed": i + 1,
                    "cumulative_hit_rate_pct": round(100.0 * hits / (i + 1), 2),
                    "tokens_saved": tokens_saved,
                    "cost_saved_usd": round(tokens_saved * COST_PER_1M_TOKENS_USD / 1_000_000.0, 4),
                }
            )

    elapsed = time.perf_counter() - t0
    summary = {
        "capacity": capacity,
        "policy": policy,
        "total_queries": total,
        "cache_hits": hits,
        "hit_rate_pct": round(100.0 * hits / total, 2),
        "tokens_saved": tokens_saved,
        "total_tokens": total_tokens,
        "token_savings_pct": round(100.0 * tokens_saved / max(1, total_tokens), 2),
        "cost_saved_usd": round(tokens_saved * COST_PER_1M_TOKENS_USD / 1_000_000.0, 4),
        "avg_latency_saved_ms": round(latency_saved_ms / total, 1),
        "sim_runtime_sec": round(elapsed, 2),
    }
    return summary, ts_rows


def main() -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    df = pd.read_csv(ARENA_CSV)
    unique_embs, inverse_idx = load_or_compute_embeddings(df)

    capacities = [500, 1000, 5000, 10000]
    policies = ["exact", "lru", "lfu", "semantic_density"]

    summaries = []
    all_ts = []

    for cap in capacities:
        for pol in policies:
            print(f"Running simulation: capacity={cap}, policy={pol}...")
            s, ts = run_single_simulation(df, unique_embs, inverse_idx, cap, pol)
            summaries.append(s)
            all_ts.extend(ts)
            print(f"  -> Hit Rate: {s['hit_rate_pct']}% | Tokens Saved: {s['tokens_saved']:,} (${s['cost_saved_usd']:.2f})")

    pd.DataFrame(summaries).to_csv(RESULTS_DIR / "eviction_benchmark.csv", index=False)
    pd.DataFrame(all_ts).to_csv(RESULTS_DIR / "eviction_timeseries.csv", index=False)

    # K-Tail & Weight Ablation at capacity=1000
    ablation_configs = [
        ("Balanced (w=[0.30,0.25,0.25,0.20], K=100)", 0.30, 0.25, 0.25, 0.20, 100),
        ("K-Tail Small (K=25)", 0.30, 0.25, 0.25, 0.20, 25),
        ("K-Tail Large (K=250)", 0.30, 0.25, 0.25, 0.20, 250),
        ("Cost-Heavy (w=[0.20,0.20,0.20,0.40], K=100)", 0.20, 0.20, 0.20, 0.40, 100),
        ("Density-Heavy (w=[0.20,0.20,0.40,0.20], K=100)", 0.20, 0.20, 0.40, 0.20, 100),
    ]
    ablation_rows = []
    for label, w1, w2, w3, w4, k in ablation_configs:
        s, _ = run_single_simulation(df, unique_embs, inverse_idx, 1000, "semantic_density", w1, w2, w3, w4, k)
        s["config_name"] = label
        ablation_rows.append(s)
    pd.DataFrame(ablation_rows).to_csv(RESULTS_DIR / "eviction_ablation.csv", index=False)
    print("Saved eviction_benchmark.csv, eviction_timeseries.csv, and eviction_ablation.csv")


if __name__ == "__main__":
    main()
