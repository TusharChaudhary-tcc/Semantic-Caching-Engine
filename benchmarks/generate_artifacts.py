"""
Step 4: Generate Publication-Grade Figures (300 DPI) & LaTeX Tables for the Research Paper.

Reads CSV/JSON outputs from benchmarks/results/ and produces:
- benchmarks/results/figures/fig1_eviction_hit_rate.png
- benchmarks/results/figures/fig2_hit_rate_timeseries.png
- benchmarks/results/figures/fig3_two_stage_roc_fpr.png
- benchmarks/results/figures/fig4_latency_delta.png
- benchmarks/results/figures/fig5_db_comparison.png
- benchmarks/results/tables.tex
"""

from __future__ import annotations

import json
from pathlib import Path
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns

RESULTS_DIR = Path(__file__).resolve().parent / "results"
FIG_DIR = RESULTS_DIR / "figures"

sns.set_theme(style="whitegrid", context="paper", font_scale=1.2)
PALETTE = {
    "exact": "#7f8c8d",
    "lru": "#e67e22",
    "lfu": "#3498db",
    "semantic_density": "#27ae60",
}
LABELS = {
    "exact": "Exact Match (Redis)",
    "lru": "Semantic + LRU",
    "lfu": "Semantic + LFU",
    "semantic_density": "Semantic Density + Cost (Ours)",
}


def plot_eviction_hit_rate() -> None:
    df = pd.read_csv(RESULTS_DIR / "eviction_benchmark.csv")
    df["Policy"] = df["policy"].map(LABELS)

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    sns.barplot(
        data=df,
        x="capacity",
        y="hit_rate_pct",
        hue="Policy",
        palette=[PALETTE[p] for p in ["exact", "lru", "lfu", "semantic_density"]],
        ax=axes[0],
    )
    axes[0].set_title("Cache Hit Ratio Across Cache Capacities (50K Queries)")
    axes[0].set_xlabel("Cache Capacity (Entries)")
    axes[0].set_ylabel("Cache Hit Ratio (%)")
    axes[0].legend(title="Eviction Policy", fontsize=9)

    sns.barplot(
        data=df,
        x="capacity",
        y="cost_saved_usd",
        hue="Policy",
        palette=[PALETTE[p] for p in ["exact", "lru", "lfu", "semantic_density"]],
        ax=axes[1],
    )
    axes[1].set_title("Estimated LLM API Cost Saved ($ USD)")
    axes[1].set_xlabel("Cache Capacity (Entries)")
    axes[1].set_ylabel("Cost Saved ($ USD)")
    axes[1].get_legend().remove()

    plt.tight_layout()
    plt.savefig(FIG_DIR / "fig1_eviction_hit_rate.png", dpi=300)
    plt.close()


def plot_timeseries() -> None:
    df = pd.read_csv(RESULTS_DIR / "eviction_timeseries.csv")
    sub = df[df["capacity"] == 1000].copy()
    sub["Policy"] = sub["policy"].map(LABELS)

    plt.figure(figsize=(8.5, 4.8))
    for pol in ["exact", "lru", "lfu", "semantic_density"]:
        p_df = sub[sub["policy"] == pol]
        plt.plot(
            p_df["queries_processed"],
            p_df["cumulative_hit_rate_pct"],
            label=LABELS[pol],
            color=PALETTE[pol],
            linewidth=2.4 if pol == "semantic_density" else 1.8,
        )
    plt.title("Cumulative Cache Hit Ratio Over Time (Capacity = 1,000 Entries)")
    plt.xlabel("Queries Processed")
    plt.ylabel("Cumulative Hit Ratio (%)")
    plt.legend()
    plt.tight_layout()
    plt.savefig(FIG_DIR / "fig2_hit_rate_timeseries.png", dpi=300)
    plt.close()


def plot_two_stage() -> None:
    df = pd.read_csv(RESULTS_DIR / "two_stage_sweep.csv")
    s1 = df[df["pipeline"] == "Stage-1 Bi-Encoder"]
    s2 = df[df["pipeline"] == "Two-Stage (Bi+Cross)"]

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    axes[0].plot(s1["stage1_threshold"], s1["fpr"] * 100, marker="o", color="#e74c3c", label="Stage-1 Bi-Encoder Only")
    axes[0].axhline(
        y=float(s2[s2["stage2_threshold"] == 0.85]["fpr"].iloc[0]) * 100,
        color="#27ae60",
        linestyle="--",
        linewidth=2.2,
        label="Two-Stage (S1>=0.75, S2>=0.85)",
    )
    axes[0].set_title("False Positive Rate (FPR) on Hard Negatives")
    axes[0].set_xlabel("Stage-1 Cosine Threshold")
    axes[0].set_ylabel("False Positive Rate (%)")
    axes[0].legend()

    axes[1].plot(s1["stage1_threshold"], s1["precision"] * 100, marker="s", color="#e67e22", label="Stage-1 Precision")
    axes[1].plot(s2["stage2_threshold"], s2["precision"] * 100, marker="^", color="#27ae60", label="Two-Stage Precision (S2 Sweep)")
    axes[1].set_title("Intent Verification Precision: Stage-1 vs. Two-Stage")
    axes[1].set_xlabel("Threshold")
    axes[1].set_ylabel("Precision (%)")
    axes[1].legend()

    plt.tight_layout()
    plt.savefig(FIG_DIR / "fig3_two_stage_roc_fpr.png", dpi=300)
    plt.close()


def plot_latency_delta() -> None:
    df = pd.read_csv(RESULTS_DIR / "latency_distribution.csv")
    order = [
        "Stage-1 Vector Hit",
        "Two-Stage Cache Hit",
        "Groq LLM Miss (Llama-3.3-70B)",
        "Gemini LLM Miss (Flash)",
    ]
    plt.figure(figsize=(9, 4.8))
    ax = sns.boxplot(data=df, x="latency_ms", y="path", order=order, palette="viridis")
    ax.set_xscale("log")
    plt.title("End-to-End Latency Distribution (Log Scale): Cache Hit vs. LLM Miss")
    plt.xlabel("Latency (ms, log scale)")
    plt.ylabel("")
    plt.tight_layout()
    plt.savefig(FIG_DIR / "fig4_latency_delta.png", dpi=300)
    plt.close()


def plot_db_comparison() -> None:
    df = pd.read_csv(RESULTS_DIR / "db_comparison.csv")
    plt.figure(figsize=(8.5, 4.5))
    sns.barplot(data=df, x="database", y="p95_ms", palette="mako")
    plt.title("Vector Store p95 Query Latency Comparison (10K Vectors, Top-3)")
    plt.xlabel("Engine / Baseline")
    plt.ylabel("p95 Query Latency (ms)")
    plt.xticks(rotation=15)
    plt.tight_layout()
    plt.savefig(FIG_DIR / "fig5_db_comparison.png", dpi=300)
    plt.close()


def export_latex_tables() -> None:
    ev_df = pd.read_csv(RESULTS_DIR / "eviction_benchmark.csv")
    ts_df = pd.read_csv(RESULTS_DIR / "two_stage_sweep.csv")
    db_df = pd.read_csv(RESULTS_DIR / "db_comparison.csv")
    ab_df = pd.read_csv(RESULTS_DIR / "eviction_ablation.csv")

    tex_parts = [
        "% Auto-generated LaTeX tables for SemanticCache-LLM Research Paper\n",
        "% Table 1: Eviction Policy Comparison\n",
        ev_df.to_latex(index=False, float_format="%.2f"),
        "\n% Table 2: Two-Stage Intent Verification Sweep\n",
        ts_df.to_latex(index=False, float_format="%.4f"),
        "\n% Table 3: Vector Store Performance Comparison\n",
        db_df.to_latex(index=False, float_format="%.4f"),
        "\n% Table 4: K-Tail & Weight Ablation (Capacity = 1000)\n",
        ab_df[["config_name", "hit_rate_pct", "token_savings_pct", "cost_saved_usd"]].to_latex(index=False, float_format="%.2f"),
    ]
    with open(RESULTS_DIR / "tables.tex", "w", encoding="utf-8") as f:
        f.write("\n".join(tex_parts))


def main() -> None:
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    plot_eviction_hit_rate()
    plot_timeseries()
    plot_two_stage()
    plot_latency_delta()
    plot_db_comparison()
    export_latex_tables()
    print(f"Generated 5 publication-grade figures in {FIG_DIR} and LaTeX tables in {RESULTS_DIR / 'tables.tex'}")


if __name__ == "__main__":
    main()
