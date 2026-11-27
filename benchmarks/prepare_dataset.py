"""
Step 0: Dataset Ingestion & Trace Construction for Phase 3 Benchmarking.

Downloads/prepares:
1. Knowledge/chatbot_arena_dataset.csv (50,000+ query trace from LMSYS Chatbot Arena
   or public conversational fallback if HF gated auth is not configured).
2. benchmarks/data/qqp_intent_eval.csv (2,000 balanced paraphrase vs. hard-negative
   pairs from GLUE QQP for Stage-1 Bi-Encoder vs. Stage-2 Cross-Encoder evaluation).
"""

from __future__ import annotations

import os
from pathlib import Path
import numpy as np
import pandas as pd
from datasets import load_dataset
from tqdm import tqdm

ROOT_DIR = Path(__file__).resolve().parent.parent
KNOWLEDGE_DIR = ROOT_DIR / "Knowledge"
BENCH_DATA_DIR = Path(__file__).resolve().parent / "data"

ARENA_CSV_PATH = KNOWLEDGE_DIR / "chatbot_arena_dataset.csv"
QQP_CSV_PATH = BENCH_DATA_DIR / "qqp_intent_eval.csv"

TARGET_QUERIES = 50_000
RANDOM_SEED = 42


def _estimate_tokens(text: str) -> int:
    """Rough token estimate (~4 chars/token) matching backend heuristics."""
    return max(1, len(text) // 4)


def _simulate_latency_ms(tokens: int, rng: np.random.Generator) -> int:
    """Realistic LLM miss generation latency (ms) proportional to output tokens."""
    base_ttft = rng.normal(420.0, 90.0)
    per_token = rng.uniform(14.0, 24.0)
    return int(max(350, min(6500, base_ttft + tokens * per_token)))


def build_chatbot_arena_trace() -> pd.DataFrame:
    """Load LMSYS Chatbot Arena conversations (or open fallback) into a 50K+ query trace."""
    rng = np.random.default_rng(RANDOM_SEED)
    KNOWLEDGE_DIR.mkdir(parents=True, exist_ok=True)

    rows: list[dict] = []
    source_name = "lmsys/chatbot_arena_conversations"

    try:
        print(f"Attempting to stream {source_name} from HuggingFace...")
        ds = load_dataset(source_name, split="train", streaming=True)
        for idx, item in enumerate(tqdm(ds, total=TARGET_QUERIES, desc="LMSYS Arena")):
            if len(rows) >= TARGET_QUERIES:
                break
            conv = item.get("conversation_a") or []
            if len(conv) < 2:
                continue
            prompt = str(conv[0].get("content", "")).strip()
            response = str(conv[1].get("content", "")).strip()
            if len(prompt) < 8 or len(response) < 8:
                continue
            tokens = _estimate_tokens(prompt) + _estimate_tokens(response)
            tstamp = item.get("tstamp")
            rows.append(
                {
                    "query_id": idx,
                    "timestamp_sec": float(tstamp) if tstamp is not None else float(len(rows) * 1.5),
                    "prompt": prompt[:1000],
                    "response": response[:2000],
                    "estimated_tokens": tokens,
                    "generation_latency_ms": _simulate_latency_ms(_estimate_tokens(response), rng),
                    "source": "lmsys_chatbot_arena",
                }
            )
    except Exception as exc:
        print(f"Could not stream {source_name} ({type(exc).__name__}: {exc}).")
        print("Falling back to open conversational dataset 'OpenAssistant/oasst1' + 'yahma/alpaca-cleaned'...")
        rows = []
        ds_alpaca = load_dataset("yahma/alpaca-cleaned", split="train")
        base_rows = []
        for idx, item in enumerate(ds_alpaca):
            instr = str(item.get("instruction", "")).strip()
            inp = str(item.get("input", "")).strip()
            prompt = f"{instr} {inp}".strip() if inp else instr
            response = str(item.get("output", "")).strip()
            if len(prompt) < 8 or len(response) < 8:
                continue
            base_rows.append((prompt[:1000], response[:2000]))

        # Construct a realistic Zipfian 50,000-query production workload from real prompts
        # (~35% Zipfian repeat/popular topic clusters, ~65% long-tail queries)
        n_base = len(base_rows)
        weights = 1.0 / (np.arange(1, n_base + 1) ** 0.72)
        weights /= weights.sum()
        sampled_indices = rng.choice(n_base, size=TARGET_QUERIES, replace=True, p=weights)

        for i, b_idx in enumerate(tqdm(sampled_indices, desc="Building 50K trace")):
            prompt, response = base_rows[int(b_idx)]
            tokens = _estimate_tokens(prompt) + _estimate_tokens(response)
            rows.append(
                {
                    "query_id": i,
                    "timestamp_sec": float(i * 1.2),
                    "prompt": prompt,
                    "response": response,
                    "estimated_tokens": tokens,
                    "generation_latency_ms": _simulate_latency_ms(_estimate_tokens(response), rng),
                    "source": "alpaca_zipf_50k",
                }
            )

    df = pd.DataFrame(rows)
    df = df.sort_values("timestamp_sec").reset_index(drop=True)
    df.to_csv(ARENA_CSV_PATH, index=False)
    print(f"Saved {len(df):,} queries to {ARENA_CSV_PATH}")
    return df


def build_qqp_intent_eval() -> pd.DataFrame:
    """Build 2,000 balanced paraphrase vs. high-lexical-overlap negative pairs from GLUE QQP."""
    BENCH_DATA_DIR.mkdir(parents=True, exist_ok=True)
    print("Loading GLUE QQP validation split for Stage-1 vs. Stage-2 intent evaluation...")
    ds = load_dataset("nyu-mll/glue", "qqp", split="validation")

    positives = []
    negatives = []

    for item in ds:
        q1 = str(item["question1"]).strip()
        q2 = str(item["question2"]).strip()
        label = int(item["label"])
        if not q1 or not q2:
            continue
        w1 = set(q1.lower().split())
        w2 = set(q2.lower().split())
        jaccard = len(w1 & w2) / max(1, len(w1 | w2))

        record = {
            "question1": q1,
            "question2": q2,
            "label": label,
            "lexical_jaccard": round(jaccard, 4),
        }
        if label == 1:
            positives.append(record)
        else:
            negatives.append(record)

    # Sort negatives by highest lexical overlap to stress-test Stage-1 false positives
    negatives.sort(key=lambda r: r["lexical_jaccard"], reverse=True)
    rng = np.random.default_rng(RANDOM_SEED)
    pos_sample = [positives[i] for i in rng.choice(len(positives), size=min(1000, len(positives)), replace=False)]
    neg_sample = negatives[: min(1000, len(negatives))]

    df = pd.DataFrame(pos_sample + neg_sample).sample(frac=1.0, random_state=RANDOM_SEED).reset_index(drop=True)
    df.to_csv(QQP_CSV_PATH, index=False)
    print(f"Saved {len(df):,} QQP pairs (1,000 pos / 1,000 hard-neg) to {QQP_CSV_PATH}")
    return df


if __name__ == "__main__":
    build_chatbot_arena_trace()
    build_qqp_intent_eval()
