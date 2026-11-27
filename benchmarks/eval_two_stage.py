"""
Step 1: Two-Stage Pipeline Accuracy & False Positive Rate (FPR) Benchmark.

Compares:
- Stage 1 Only (Bi-Encoder Cosine Similarity across thresholds 0.70 .. 0.95)
- Two-Stage (Stage 1 Bi-Encoder >= 0.75 + Stage 2 Cross-Encoder ms-marco-MiniLM-L-6-v2)

Outputs:
- benchmarks/results/two_stage_sweep.csv
- benchmarks/results/two_stage_metrics.json
"""

from __future__ import annotations

import json
import math
from pathlib import Path
import numpy as np
import pandas as pd
from sentence_transformers import CrossEncoder, SentenceTransformer
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score, roc_auc_score

BENCH_DIR = Path(__file__).resolve().parent
QQP_CSV = BENCH_DIR / "data" / "qqp_intent_eval.csv"
RESULTS_DIR = BENCH_DIR / "results"

BI_ENCODER_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
CROSS_ENCODER_MODEL = "cross-encoder/ms-marco-MiniLM-L-6-v2"


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -50.0, 50.0)))


def run_two_stage_eval() -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    df = pd.read_csv(QQP_CSV)
    q1_list = df["question1"].astype(str).tolist()
    q2_list = df["question2"].astype(str).tolist()
    y_true = df["label"].to_numpy(dtype=int)

    print(f"Loaded {len(df):,} QQP pairs ({y_true.sum()} positives, {(1 - y_true).sum()} hard negatives).")

    print(f"Encoding Stage-1 Bi-Encoder ({BI_ENCODER_MODEL})...")
    bi_encoder = SentenceTransformer(BI_ENCODER_MODEL)
    emb1 = bi_encoder.encode(q1_list, batch_size=128, normalize_embeddings=True, show_progress_bar=True)
    emb2 = bi_encoder.encode(q2_list, batch_size=128, normalize_embeddings=True, show_progress_bar=True)
    stage1_cos = np.sum(emb1 * emb2, axis=1)

    print(f"Scoring Stage-2 Cross-Encoder ({CROSS_ENCODER_MODEL})...")
    cross_encoder = CrossEncoder(CROSS_ENCODER_MODEL)
    pairs = list(zip(q1_list, q2_list))
    raw_logits = cross_encoder.predict(pairs, batch_size=64, show_progress_bar=True)
    # Note: ms-marco-MiniLM-L-6-v2 outputs logits; sigmoid maps to [0, 1] matching Java OnnxCrossEncoderService
    stage2_prob = sigmoid(np.asarray(raw_logits, dtype=float))

    # Threshold sweep
    sweep_rows = []
    stage1_thresholds = [0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95]
    for th in stage1_thresholds:
        y_pred_s1 = (stage1_cos >= th).astype(int)
        fp = int(np.sum((y_pred_s1 == 1) & (y_true == 0)))
        tn = int(np.sum((y_pred_s1 == 0) & (y_true == 0)))
        fpr = fp / max(1, fp + tn)
        sweep_rows.append(
            {
                "pipeline": "Stage-1 Bi-Encoder",
                "stage1_threshold": th,
                "stage2_threshold": None,
                "accuracy": round(float(accuracy_score(y_true, y_pred_s1)), 4),
                "precision": round(float(precision_score(y_true, y_pred_s1, zero_division=0)), 4),
                "recall": round(float(recall_score(y_true, y_pred_s1, zero_division=0)), 4),
                "f1": round(float(f1_score(y_true, y_pred_s1, zero_division=0)), 4),
                "fpr": round(float(fpr), 4),
            }
        )

    # Two-stage sweep (Stage-1 gate = 0.75, matching application.properties)
    stage2_thresholds = [0.20, 0.35, 0.50, 0.65, 0.75, 0.85]
    for s2_th in stage2_thresholds:
        y_pred_2s = ((stage1_cos >= 0.75) & (stage2_prob >= s2_th)).astype(int)
        fp = int(np.sum((y_pred_2s == 1) & (y_true == 0)))
        tn = int(np.sum((y_pred_2s == 0) & (y_true == 0)))
        fpr = fp / max(1, fp + tn)
        sweep_rows.append(
            {
                "pipeline": "Two-Stage (Bi+Cross)",
                "stage1_threshold": 0.75,
                "stage2_threshold": s2_th,
                "accuracy": round(float(accuracy_score(y_true, y_pred_2s)), 4),
                "precision": round(float(precision_score(y_true, y_pred_2s, zero_division=0)), 4),
                "recall": round(float(recall_score(y_true, y_pred_2s, zero_division=0)), 4),
                "f1": round(float(f1_score(y_true, y_pred_2s, zero_division=0)), 4),
                "fpr": round(float(fpr), 4),
            }
        )

    sweep_df = pd.DataFrame(sweep_rows)
    sweep_df.to_csv(RESULTS_DIR / "two_stage_sweep.csv", index=False)

    # Explicit intent-contrastive stress test (from THEORY.md / Core-Idea.pdf)
    stress_pairs = [
        ("How do I reset my password?", "I forgot my password, how can I reset it?", 1),
        ("How do I reset my password?", "What is a good password strength?", 0),
        ("How to sort a list in Python?", "What is the syntax to sort a Python array?", 1),
        ("How to sort a list in Python?", "How to reverse a list in Python?", 0),
        ("Explain Docker vs Kubernetes", "What is the difference between Docker and Kubernetes?", 1),
        ("Explain Docker vs Kubernetes", "How do I install Docker on Ubuntu?", 0),
    ]
    s_e1 = bi_encoder.encode([p[0] for p in stress_pairs], normalize_embeddings=True)
    s_e2 = bi_encoder.encode([p[1] for p in stress_pairs], normalize_embeddings=True)
    s_cos = np.sum(s_e1 * s_e2, axis=1)
    s_ce = sigmoid(np.asarray(cross_encoder.predict([(p[0], p[1]) for p in stress_pairs]), dtype=float))

    stress_results = []
    for i, (qa, qb, lbl) in enumerate(stress_pairs):
        stress_results.append(
            {
                "query_a": qa,
                "query_b": qb,
                "true_intent_match": lbl,
                "stage1_cosine": round(float(s_cos[i]), 4),
                "stage2_cross_encoder": round(float(s_ce[i]), 4),
            }
        )

    summary = {
        "dataset": "GLUE QQP (1000 positives, 1000 high-lexical-overlap negatives)",
        "bi_encoder_model": BI_ENCODER_MODEL,
        "cross_encoder_model": CROSS_ENCODER_MODEL,
        "stage1_roc_auc": round(float(roc_auc_score(y_true, stage1_cos)), 4),
        "stage2_roc_auc": round(float(roc_auc_score(y_true, stage2_prob)), 4),
        "stress_test_cases": stress_results,
    }
    with open(RESULTS_DIR / "two_stage_metrics.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print("Saved two_stage_sweep.csv and two_stage_metrics.json")
    print(sweep_df.to_string(index=False))


if __name__ == "__main__":
    run_two_stage_eval()
