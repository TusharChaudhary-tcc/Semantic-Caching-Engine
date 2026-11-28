import { Activity, BarChart3, Cpu, Gauge, Layers } from "lucide-react";

/**
 * Collapsible Right Inspector Pane (Antigravity-style) displaying live SVG graphs,
 * latency timeline, Stage-1 vs Stage-2 threshold breakdown, and Phase 3 eviction comparison.
 */
export default function MetricsInspector({ metrics, queryHistory, lastResponse }) {
  const totalRequests = metrics.totalRequests || 0;
  const cacheHits = metrics.cacheHits || 0;
  const cacheMisses = metrics.cacheMisses || 0;
  const hitRatePct = Math.round((metrics.hitRate || 0) * 100);

  const ringRadius = 26;
  const ringCircumference = 2 * Math.PI * ringRadius;
  const ringOffset = ringCircumference - (hitRatePct / 100) * ringCircumference;

  // Build SVG polyline points for recent query latencies
  const recentLatencies = queryHistory.slice(-14);
  const maxLatency = Math.max(100, ...recentLatencies.map((q) => q.latencyMs || 0));
  const chartW = 260;
  const chartH = 74;

  const points = recentLatencies.map((item, idx) => {
    const x =
      recentLatencies.length <= 1
        ? chartW / 2
        : (idx / (recentLatencies.length - 1)) * (chartW - 16) + 8;
    const y = chartH - 10 - ((item.latencyMs || 0) / maxLatency) * (chartH - 22);
    return { x, y, ...item };
  });

  const polylineStr = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ");
  const areaStr =
    points.length > 1
      ? `${points[0].x.toFixed(1)},${chartH} ${polylineStr} ${points[points.length - 1].x.toFixed(1)},${chartH}`
      : "";

  return (
    <aside className="inspector-pane glass-card">
      <div className="pane-header">
        <span className="pane-title">
          <Activity size={14} /> Telemetry &amp; Graphs
        </span>
        <span className="pane-pill">LIVE</span>
      </div>

      {/* 1. Hit Rate Gauge + KPI Summary */}
      <div className="inspector-card">
        <div className="inspector-card-row">
          <div>
            <div className="metric-label">Cache Hit Ratio</div>
            <div className="inspector-big-num">{hitRatePct}%</div>
            <div className="inspector-sub">
              {cacheHits} hits · {cacheMisses} misses · {totalRequests} total
            </div>
          </div>
          <svg className="ring-svg-lg" viewBox="0 0 68 68">
            <circle className="ring-bg" cx="34" cy="34" r={ringRadius} />
            <circle
              className="ring-fg"
              cx="34"
              cy="34"
              r={ringRadius}
              strokeDasharray={ringCircumference}
              strokeDashoffset={ringOffset}
            />
          </svg>
        </div>
      </div>

      {/* 2. Live Latency Timeline Chart */}
      <div className="inspector-card">
        <div className="inspector-card-head">
          <span className="metric-label">
            <Gauge size={12} /> Query Latency Timeline (ms)
          </span>
          <span className="mono-dim">
            {lastResponse?.latencyMs != null ? `${lastResponse.latencyMs} ms` : "—"}
          </span>
        </div>

        {points.length === 0 ? (
          <div className="chart-empty">Send queries to plot live Hit vs. Miss latency</div>
        ) : (
          <svg className="latency-chart" viewBox={`0 0 ${chartW} ${chartH}`}>
            <defs>
              <linearGradient id="latencyAreaGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#38bdf8" stopOpacity="0.32" />
                <stop offset="100%" stopColor="#38bdf8" stopOpacity="0.0" />
              </linearGradient>
            </defs>
            <line x1="0" y1={chartH - 10} x2={chartW} y2={chartH - 10} className="chart-axis" />
            {areaStr && <polygon points={areaStr} fill="url(#latencyAreaGrad)" />}
            {points.length > 1 && (
              <polyline points={polylineStr} fill="none" stroke="#38bdf8" strokeWidth="2" />
            )}
            {points.map((p) => (
              <circle
                key={p.id}
                cx={p.x}
                cy={p.y}
                r="3.5"
                fill={p.cacheHit ? "#34d399" : "#fbbf24"}
                stroke="#061326"
                strokeWidth="1.2"
              >
                <title>{`${p.latencyMs} ms (${p.cacheHit ? "HIT" : "MISS"})`}</title>
              </circle>
            ))}
          </svg>
        )}
        <div className="chart-legend">
          <span>
            <i className="dot hit" /> Cache Hit
          </span>
          <span>
            <i className="dot miss" /> LLM Miss
          </span>
        </div>
      </div>

      {/* 3. Stage-1 vs Stage-2 Decision Breakdown */}
      <div className="inspector-card">
        <div className="inspector-card-head">
          <span className="metric-label">
            <Layers size={12} /> Last Verification Gates
          </span>
        </div>

        <div className="gate-row">
          <div className="gate-labels">
            <span>Stage 1 · Cosine (≥ 75%)</span>
            <span className="mono-val">
              {typeof lastResponse?.stage1Similarity === "number"
                ? `${(lastResponse.stage1Similarity * 100).toFixed(1)}%`
                : "—"}
            </span>
          </div>
          <div className="gate-track">
            <div
              className="gate-fill s1"
              style={{
                width: `${Math.min(100, (lastResponse?.stage1Similarity || 0) * 100)}%`,
              }}
            />
            <span className="gate-marker" style={{ left: "75%" }} />
          </div>
        </div>

        <div className="gate-row">
          <div className="gate-labels">
            <span>Stage 2 · ONNX Intent (≥ 85%)</span>
            <span className="mono-val">
              {typeof lastResponse?.stage2CrossEncoderScore === "number"
                ? `${(lastResponse.stage2CrossEncoderScore * 100).toFixed(1)}%`
                : "—"}
            </span>
          </div>
          <div className="gate-track">
            <div
              className="gate-fill s2"
              style={{
                width: `${Math.min(100, (lastResponse?.stage2CrossEncoderScore || 0) * 100)}%`,
              }}
            />
            <span className="gate-marker" style={{ left: "85%" }} />
          </div>
        </div>
      </div>

      {/* 4. Upstream Ops & Phase 3 Benchmark Reference */}
      <div className="inspector-card">
        <div className="inspector-card-head">
          <span className="metric-label">
            <BarChart3 size={12} /> Paper Benchmark (50K Arena)
          </span>
        </div>
        <div className="bench-bar-group">
          <div className="bench-row">
            <span>Exact Cache</span>
            <div className="bench-track">
              <div className="bench-fill muted" style={{ width: "61%" }} />
            </div>
            <span className="mono-dim">11.66%</span>
          </div>
          <div className="bench-row">
            <span>Standard LRU</span>
            <div className="bench-track">
              <div className="bench-fill muted" style={{ width: "64%" }} />
            </div>
            <span className="mono-dim">12.13%</span>
          </div>
          <div className="bench-row">
            <span>Semantic + Cost</span>
            <div className="bench-track">
              <div className="bench-fill accent" style={{ width: "74%" }} />
            </div>
            <span className="mono-val">14.05%</span>
          </div>
        </div>
        <div className="inspector-foot">
          <Cpu size={12} /> {metrics.qdrantOperations || 0} vector ops ·{" "}
          {metrics.geminiGenerationCalls || 0} LLM calls
        </div>
      </div>
    </aside>
  );
}
