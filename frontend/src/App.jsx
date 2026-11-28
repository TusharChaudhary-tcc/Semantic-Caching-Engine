import { useCallback, useEffect, useRef, useState } from "react";
import Lenis from "lenis";
import useEmblaCarousel from "embla-carousel-react";
import {
  ChevronLeft,
  ChevronRight,
  Cloud,
  PanelLeft,
  PanelRight,
  Send,
  Sparkles,
} from "lucide-react";
import { authApi, cacheApi } from "./api/client";
import AuthModal from "./components/AuthModal";
import MetricsInspector from "./components/MetricsInspector";
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
  "Searching Vector Index (Stage 1)...",
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

  // Antigravity-style 3-pane snap controls
  const [leftOpen, setLeftOpen] = useState(true);
  const [rightOpen, setRightOpen] = useState(true);

  const [user, setUser] = useState(null);
  const [showAuthModal, setShowAuthModal] = useState(false);

  const [provider, setProvider] = useState("auto");
  const [promptInput, setPromptInput] = useState("");
  const [messages, setMessages] = useState([]);
  const [queryHistory, setQueryHistory] = useState([]);
  const [lastResponse, setLastResponse] = useState(null);
  const [loading, setLoading] = useState(false);
  const [pipelineStepIdx, setPipelineStepIdx] = useState(0);

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
  const chatScrollRef = useRef(null);
  const chatContentRef = useRef(null);
  const [emblaRef, emblaApi] = useEmblaCarousel({ loop: false, align: "start" });

  useEffect(() => {
    if (!chatScrollRef.current || !chatContentRef.current) return;
    const lenis = new Lenis({
      wrapper: chatScrollRef.current,
      content: chatContentRef.current,
      lerp: 0.1,
      smoothWheel: true,
    });

    let rafId = 0;
    const raf = (time) => {
      lenis.raf(time);
      rafId = requestAnimationFrame(raf);
    };
    rafId = requestAnimationFrame(raf);

    return () => {
      cancelAnimationFrame(rafId);
      lenis.destroy();
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === "r") {
        localStorage.removeItem(INTRO_STORAGE_KEY);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    if (introMode !== "quick") return;
    const timer = setTimeout(() => {
      setShowQuickSplash(false);
      setDashboardReady(true);
    }, 850);
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
      const entry = {
        id: Date.now() + 1,
        role: "assistant",
        text: res.answer,
        cacheHit: res.cacheHit,
        stage1Similarity: res.stage1Similarity,
        stage2CrossEncoderScore: res.stage2CrossEncoderScore,
        verificationStage: res.verificationStage,
        latencyMs: res.latencyMs,
        provider: res.provider,
      };
      setMessages((prev) => [...prev, entry]);
      setLastResponse(res);
      setQueryHistory((prev) => [
        ...prev,
        { id: entry.id, latencyMs: res.latencyMs ?? 0, cacheHit: Boolean(res.cacheHit) },
      ]);
      refreshTelemetry();
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          id: Date.now() + 1,
          role: "error",
          text: `Backend error: ${err.message}. Make sure Spring Boot (localhost:8080) is running.`,
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

  const wipePercent =
    introMode === "sky" ? Math.max(0, Math.min(100, ((skyProgress - 0.68) / 0.32) * 100)) : 100;

  const gridColsClass =
    leftOpen && rightOpen
      ? "panes-3"
      : leftOpen
      ? "panes-left-only"
      : rightOpen
      ? "panes-right-only"
      : "panes-center-only";

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

        {/* IDE-Style Top Bar with Left/Right Snap Buttons */}
        <header className="dash-header build-reveal delay-1">
          <div className="dash-brand">
            <button
              type="button"
              className={`snap-toggle-btn ${leftOpen ? "active" : ""}`}
              onClick={() => setLeftOpen((v) => !v)}
              title="Toggle Left Explorer Pane"
            >
              <PanelLeft size={16} />
            </button>
            <div className="dash-logo-3d">SC</div>
            <div>
              <h1 className="heading-build">Semantic Cache Engine</h1>
              <span>Two-Stage Vector + ONNX Cross-Encoder Gateway</span>
            </div>
          </div>

          <div className="dash-header-actions">
            <div className="status-pill">
              <span className={`status-dot ${healthUp ? "up" : "down"}`} />
              {healthUp === null ? "Checking..." : healthUp ? "Engine Online" : "Backend Offline"}
            </div>

            <button type="button" className="ghost-btn icon-inline" onClick={handleReplaySky}>
              <Cloud size={14} /> Replay Sky
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

            <button
              type="button"
              className={`snap-toggle-btn ${rightOpen ? "active" : ""}`}
              onClick={() => setRightOpen((v) => !v)}
              title="Toggle Right Telemetry & Graphs Pane"
            >
              <PanelRight size={16} />
            </button>
          </div>
        </header>

        {/* 3-Pane Antigravity Snap Workspace */}
        <main className={`dash-main-3pane ${gridColsClass} build-reveal delay-2`}>
          {/* PANE 1: Left Research Explorer */}
          {leftOpen && (
            <aside className="glass-card dash-sidebar">
              <div className="pane-header">
                <span className="pane-title">
                  <Sparkles size={14} /> Research Explorer
                </span>
              </div>

              <div className="embla-header">
                <h3 className="sidebar-section-title">Quick Test Prompts</h3>
                <div className="embla-controls">
                  <button
                    type="button"
                    className="embla-nav-btn"
                    onClick={() => emblaApi?.scrollPrev()}
                    aria-label="Previous test"
                  >
                    <ChevronLeft size={14} />
                  </button>
                  <button
                    type="button"
                    className="embla-nav-btn"
                    onClick={() => emblaApi?.scrollNext()}
                    aria-label="Next test"
                  >
                    <ChevronRight size={14} />
                  </button>
                </div>
              </div>

              <div className="embla" ref={emblaRef}>
                <div className="embla-container">
                  {DEMO_PROMPTS.map((item) => (
                    <button
                      key={item.text}
                      type="button"
                      className="preset-btn embla-slide"
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
                <strong>Architecture Pipeline</strong>
                <br />
                1. <strong>Stage 1:</strong> Vector Cosine ≥ 0.75
                <br />
                2. <strong>Stage 2:</strong> ONNX Intent ≥ 0.85
                <br />
                3. <strong>Eviction:</strong> K-Tail Density + Cost
              </div>
            </aside>
          )}

          {/* PANE 2: Center Query Console */}
          <section className="glass-card chat-panel">
            <div className="chat-panel-header">
              <h2 className="heading-build">Semantic Query Console</h2>
              <div className="provider-pills" role="group" aria-label="LLM Provider">
                {[
                  { id: "auto", label: "Auto" },
                  { id: "groq", label: "Groq" },
                  { id: "gemini", label: "Gemini" },
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

            <div className="chat-messages" ref={chatScrollRef}>
              <div className="chat-messages-inner" ref={chatContentRef}>
                {messages.length === 0 ? (
                  <div className="empty-state">
                    <h3>Ask anything to test Semantic Caching</h3>
                    <p>
                      Send a seed query, then rephrase it — or test an intent trap to watch
                      Stage-2 Cross-Encoder filter false positives in real time.
                    </p>
                  </div>
                ) : (
                  messages.map((msg) => (
                    <div key={msg.id} className={`msg-row ${msg.role}`}>
                      <div className="msg-bubble">{msg.text}</div>

                      {msg.role === "assistant" && (
                        <div className="subtle-telemetry">
                          <span
                            className={`subtle-dot ${
                              msg.cacheHit
                                ? "hit"
                                : msg.verificationStage === "CROSS_ENCODER_REJECTED"
                                ? "rejected"
                                : "miss"
                            }`}
                          />
                          <span>
                            {msg.cacheHit
                              ? "Cache hit"
                              : msg.verificationStage === "CROSS_ENCODER_REJECTED"
                              ? "Cross-encoder rejected → LLM"
                              : "Sent to LLM"}
                          </span>
                          {msg.latencyMs != null && <span>· {msg.latencyMs} ms</span>}
                          {typeof msg.stage1Similarity === "number" && (
                            <span>· S1 {(msg.stage1Similarity * 100).toFixed(1)}%</span>
                          )}
                          {typeof msg.stage2CrossEncoderScore === "number" && (
                            <span>· S2 {(msg.stage2CrossEncoderScore * 100).toFixed(1)}%</span>
                          )}
                          {msg.provider && <span>· {msg.provider}</span>}
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
            </div>

            <div className="chat-input-bar">
              <textarea
                rows={1}
                placeholder="Ask SemanticCache-LLM..."
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
                className="primary-btn icon-inline"
                onClick={() => handleSend()}
                disabled={loading || !promptInput.trim()}
              >
                <Send size={14} /> {loading ? "Running" : "Send"}
              </button>
            </div>
          </section>

          {/* PANE 3: Right Telemetry & Graphs Inspector */}
          {rightOpen && (
            <MetricsInspector
              metrics={metrics}
              queryHistory={queryHistory}
              lastResponse={lastResponse}
            />
          )}
        </main>
      </div>

      {showAuthModal && (
        <AuthModal onClose={() => setShowAuthModal(false)} onSuccess={(u) => setUser(u)} />
      )}
    </>
  );
}
