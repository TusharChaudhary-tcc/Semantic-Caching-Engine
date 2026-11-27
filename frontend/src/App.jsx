import { useCallback, useEffect, useRef, useState } from "react";
import { authApi, cacheApi } from "./api/client";
import AuthModal from "./components/AuthModal";
import SkyIntro from "./components/SkyIntro";
import "./App.css";

const INTRO_STORAGE_KEY = "sce_intro_seen";

const DEMO_PROMPTS = [
  { tag: "SEED QUERY", text: "How do I reset my password?" },
  { tag: "PARAPHRASE (HIT)", text: "I forgot my password, how can I reset it?" },
  { tag: "INTENT TRAP (MISS)", text: "What is a good password strength?" },
  { tag: "TECH QUERY", text: "Explain Docker vs Kubernetes in 3 bullets" },
];

const PIPELINE_STEPS = [
  "Generating 768-dim Gemini Embedding...",
  "Searching Qdrant Vector Index (Stage 1)...",
  "Running ONNX Cross-Encoder Intent Check (Stage 2)...",
  "Routing to Upstream LLM (Groq / Gemini)...",
];

function detectInitialIntroMode() {
  try {
    const navEntry = performance.getEntriesByType?.("navigation")?.[0];
    const isHardReload = navEntry?.type === "reload" && navEntry?.transferSize > 0;
    if (isHardReload) {
      localStorage.removeItem(INTRO_STORAGE_KEY);
      return "sky";
    }
    return localStorage.getItem(INTRO_STORAGE_KEY) === "1" ? "quick" : "sky";
  } catch {
    return "sky";
  }
}

export default function App() {
  const [introMode, setIntroMode] = useState(detectInitialIntroMode);
  const [skyProgress, setSkyProgress] = useState(0);
  const [dashboardReady, setDashboardReady] = useState(() => introMode === "quick");
  const [showQuickSplash, setShowQuickSplash] = useState(() => introMode === "quick");

  const [user, setUser] = useState(null);
  const [showAuthModal, setShowAuthModal] = useState(false);

  const [provider, setProvider] = useState("auto");
  const [promptInput, setPromptInput] = useState("");
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [pipelineStepIdx, setPipelineStepIdx] = useState(0);
  const [lastLatency, setLastLatency] = useState(null);

  const [metrics, setMetrics] = useState({
    totalRequests: 0,
    cacheHits: 0,
    cacheMisses: 0,
    hitRate: 0,
    geminiGenerationCalls: 0,
    qdrantOperations: 0,
  });
  const [healthUp, setHealthUp] = useState(null);

  const messagesEndRef = useRef(null);

  // Detect Ctrl+Shift+R so hard reload always replays the 3D Sky Intro
  useEffect(() => {
    const onKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === "r") {
        localStorage.removeItem(INTRO_STORAGE_KEY);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  // Quick splash timer on repeat visits
  useEffect(() => {
    if (introMode !== "quick") return;
    const timer = setTimeout(() => {
      setShowQuickSplash(false);
      setDashboardReady(true);
    }, 900);
    return () => clearTimeout(timer);
  }, [introMode]);

  const refreshTelemetry = useCallback(async () => {
    try {
      const [m, h] = await Promise.allSettled([cacheApi.getMetrics(), cacheApi.getHealth()]);
      if (m.status === "fulfilled" && m.value) {
        setMetrics(m.value);
      }
      if (h.status === "fulfilled" && h.value) {
        setHealthUp(h.value.status === "UP");
      } else {
        setHealthUp(false);
      }
    } catch {
      setHealthUp(false);
    }
  }, []);

  useEffect(() => {
    authApi.silentRefresh().catch(() => {});
    refreshTelemetry();
    const interval = setInterval(refreshTelemetry, 15000);
    return () => clearInterval(interval);
  }, [refreshTelemetry]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, loading]);

  useEffect(() => {
    if (!loading) return;
    setPipelineStepIdx(0);
    const id = setInterval(() => {
      setPipelineStepIdx((prev) => (prev + 1) % PIPELINE_STEPS.length);
    }, 650);
    return () => clearInterval(id);
  }, [loading]);

  const handleSkyComplete = useCallback(() => {
    localStorage.setItem(INTRO_STORAGE_KEY, "1");
    setIntroMode("done");
    setDashboardReady(true);
  }, []);

  const handleReplaySky = () => {
    localStorage.removeItem(INTRO_STORAGE_KEY);
    setSkyProgress(0);
    setDashboardReady(false);
    setIntroMode("sky");
  };

  const handleSend = async (overridePrompt = null) => {
    const query = (overridePrompt ?? promptInput).trim();
    if (!query || loading) return;

    setMessages((prev) => [...prev, { id: Date.now(), role: "user", text: query }]);
    if (!overridePrompt) setPromptInput("");
    setLoading(true);

    try {
      const res = await cacheApi.queryPrompt(query, provider);
      setLastLatency(res.latencyMs ?? null);
      setMessages((prev) => [
        ...prev,
        {
          id: Date.now() + 1,
          role: "assistant",
          text: res.answer,
          cacheHit: res.cacheHit,
          stage1Similarity: res.stage1Similarity,
          stage2CrossEncoderScore: res.stage2CrossEncoderScore,
          verificationStage: res.verificationStage,
          latencyMs: res.latencyMs,
          provider: res.provider,
        },
      ]);
      refreshTelemetry();
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          id: Date.now() + 1,
          role: "error",
          text: `Backend error: ${err.message}. Make sure Spring Boot (localhost:8080) and Qdrant (localhost:6334) are running.`,
        },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = async () => {
    await authApi.logout();
    setUser(null);
  };

  const hitRatePct = Math.round((metrics.hitRate || 0) * 100);
  const ringRadius = 22;
  const ringCircumference = 2 * Math.PI * ringRadius;
  const ringOffset = ringCircumference - (hitRatePct / 100) * ringCircumference;

  // Top-to-bottom reveal progress synchronized with SkyIntro scroll-through
  const wipePercent =
    introMode === "sky" ? Math.max(0, Math.min(100, ((skyProgress - 0.68) / 0.32) * 100)) : 100;

  return (
    <>
      {introMode === "sky" && (
        <SkyIntro onProgressChange={setSkyProgress} onComplete={handleSkyComplete} />
      )}

      {showQuickSplash && (
        <div className="quick-resume-splash">
          <span className="quick-resume-badge">CACHE STATE RESTORED</span>
          <h2>Semantic Cache Engine</h2>
        </div>
      )}

      <div
        className={`dashboard-root ${dashboardReady ? "dashboard-ready reveal-active" : ""}`}
        style={
          introMode === "sky"
            ? {
                clipPath: `inset(0 0 ${100 - wipePercent}% 0)`,
                opacity: wipePercent > 0 ? 0.35 + (wipePercent / 100) * 0.65 : 0,
              }
            : undefined
        }
      >
        <div className="dashboard-grid-bg" />

        {/* Header */}
        <header className="dash-header build-reveal delay-1">
          <div className="dash-brand">
            <div className="dash-logo-3d">SC</div>
            <div>
              <h1 className="heading-build">Semantic Cache Engine</h1>
              <span>Two-Stage Vector + ONNX Cross-Encoder Gateway</span>
            </div>
          </div>

          <div className="dash-header-actions">
            <div className="status-pill">
              <span className={`status-dot ${healthUp ? "up" : "down"}`} />
              {healthUp === null ? "Checking..." : healthUp ? "Backend + Qdrant UP" : "Backend Offline"}
            </div>

            <button type="button" className="ghost-btn" onClick={handleReplaySky}>
              ☁️ Replay 3D Sky
            </button>

            {user ? (
              <button type="button" className="ghost-btn" onClick={handleLogout}>
                {user.username} · Sign Out
              </button>
            ) : (
              <button type="button" className="primary-btn" onClick={() => setShowAuthModal(true)}>
                Sign In
              </button>
            )}
          </div>
        </header>

        {/* Main 3D Workspace */}
        <main className="dash-main">
          {/* 3D KPI Row */}
          <section className="metrics-row">
            <div className="glass-card metric-card-3d build-reveal delay-2">
              <div>
                <div className="metric-label">Cache Hit Rate</div>
                <div className="metric-value">{hitRatePct}%</div>
                <div className="metric-sub">
                  {metrics.cacheHits} hits / {metrics.cacheMisses} misses
                </div>
              </div>
              <svg className="ring-svg" viewBox="0 0 56 56">
                <circle className="ring-bg" cx="28" cy="28" r={ringRadius} />
                <circle
                  className="ring-fg"
                  cx="28"
                  cy="28"
                  r={ringRadius}
                  strokeDasharray={ringCircumference}
                  strokeDashoffset={ringOffset}
                />
              </svg>
            </div>

            <div className="glass-card metric-card-3d build-reveal delay-3">
              <div>
                <div className="metric-label">Total Queries</div>
                <div className="metric-value">{metrics.totalRequests}</div>
                <div className="metric-sub">{metrics.qdrantOperations} Qdrant ops</div>
              </div>
            </div>

            <div className="glass-card metric-card-3d build-reveal delay-4">
              <div>
                <div className="metric-label">Last Response Latency</div>
                <div className="metric-value">{lastLatency !== null ? `${lastLatency} ms` : "—"}</div>
                <div className="metric-sub">Target &lt; 40 ms on HIT</div>
              </div>
            </div>

            <div className="glass-card metric-card-3d build-reveal delay-5">
              <div>
                <div className="metric-label">LLM Calls Saved</div>
                <div className="metric-value">{metrics.cacheHits}</div>
                <div className="metric-sub">{metrics.geminiGenerationCalls} Gemini calls</div>
              </div>
            </div>
          </section>

          {/* Sidebar + Chat Workspace */}
          <section className="workspace-grid build-reveal delay-4">
            <aside className="glass-card dash-sidebar">
              <div>
                <h3 className="sidebar-section-title heading-build">Quick Research Tests</h3>
                <div className="preset-list">
                  {DEMO_PROMPTS.map((item) => (
                    <button
                      key={item.text}
                      type="button"
                      className="preset-btn"
                      onClick={() => handleSend(item.text)}
                      disabled={loading}
                    >
                      <span className="preset-tag">{item.tag}</span>
                      <div>{item.text}</div>
                    </button>
                  ))}
                </div>
              </div>

              <div className="pipeline-legend">
                <strong>How It Evaluates:</strong>
                <br />
                1. <strong>Stage 1:</strong> Qdrant Cosine ≥ 0.75
                <br />
                2. <strong>Stage 2:</strong> ONNX Cross-Encoder ≥ 0.85
                <br />
                3. <strong>Eviction:</strong> K-Tail Semantic Density + Cost
              </div>
            </aside>

            <div className="glass-card chat-panel">
              <div className="chat-panel-header">
                <h2 className="heading-build">Live Semantic Query Console</h2>
                <div className="provider-pills" role="group" aria-label="LLM Provider">
                  {[
                    { id: "auto", label: "Auto" },
                    { id: "groq", label: "Groq (Llama 3.3)" },
                    { id: "gemini", label: "Gemini Flash" },
                  ].map((p) => (
                    <button
                      key={p.id}
                      type="button"
                      className={`provider-pill ${provider === p.id ? "active" : ""}`}
                      onClick={() => setProvider(p.id)}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="chat-messages">
                {messages.length === 0 ? (
                  <div className="empty-state">
                    <h3>Ask anything to test Semantic Caching</h3>
                    <p>
                      Try sending a seed question, then rephrase it — or test an intent-trap query to
                      watch Stage-2 Cross-Encoder block false positives in real time.
                    </p>
                  </div>
                ) : (
                  messages.map((msg) => (
                    <div key={msg.id} className={`msg-row ${msg.role}`}>
                      <div className="msg-bubble">{msg.text}</div>

                      {msg.role === "assistant" && (
                        <div className="telemetry-strip">
                          <span
                            className={`verdict-badge ${
                              msg.cacheHit
                                ? "hit"
                                : msg.verificationStage === "CROSS_ENCODER_REJECTED"
                                ? "rejected"
                                : "miss"
                            }`}
                          >
                            {msg.cacheHit
                              ? "⚡ CACHE HIT"
                              : msg.verificationStage === "CROSS_ENCODER_REJECTED"
                              ? "🛡️ CROSS-ENCODER REJECTED → LLM"
                              : "🧠 SENT TO LLM"}
                          </span>

                          {typeof msg.stage1Similarity === "number" && (
                            <div className="score-bar-group">
                              <span>S1: {(msg.stage1Similarity * 100).toFixed(1)}%</span>
                              <div className="score-track">
                                <div
                                  className="score-fill"
                                  style={{ width: `${Math.min(100, msg.stage1Similarity * 100)}%` }}
                                />
                              </div>
                            </div>
                          )}

                          {typeof msg.stage2CrossEncoderScore === "number" && (
                            <div className="score-bar-group">
                              <span>S2: {(msg.stage2CrossEncoderScore * 100).toFixed(1)}%</span>
                              <div className="score-track">
                                <div
                                  className="score-fill"
                                  style={{
                                    width: `${Math.min(100, msg.stage2CrossEncoderScore * 100)}%`,
                                  }}
                                />
                              </div>
                            </div>
                          )}

                          {msg.latencyMs !== null && msg.latencyMs !== undefined && (
                            <span className="meta-chip">⏱ {msg.latencyMs} ms</span>
                          )}

                          {msg.provider && <span className="meta-chip">via {msg.provider}</span>}
                        </div>
                      )}
                    </div>
                  ))
                )}

                {loading && (
                  <div className="pipeline-loader">
                    <span className="pulse-dot" />
                    {PIPELINE_STEPS[pipelineStepIdx]}
                  </div>
                )}

                <div ref={messagesEndRef} />
              </div>

              <div className="chat-input-bar">
                <textarea
                  rows={1}
                  placeholder="Enter a prompt to query SemanticCache-LLM..."
                  value={promptInput}
                  onChange={(e) => setPromptInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey) {
                      e.preventDefault();
                      handleSend();
                    }
                  }}
                />
                <button
                  type="button"
                  className="primary-btn"
                  onClick={() => handleSend()}
                  disabled={loading || !promptInput.trim()}
                >
                  {loading ? "Running..." : "Send"}
                </button>
              </div>
            </div>
          </section>
        </main>
      </div>

      {showAuthModal && (
        <AuthModal onClose={() => setShowAuthModal(false)} onSuccess={(u) => setUser(u)} />
      )}
    </>
  );
}
