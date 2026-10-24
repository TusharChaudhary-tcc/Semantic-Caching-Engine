import { useState } from "react";
import "./App.css";

const initialChats = [
  "How does semantic caching work?",
  "Explain LLM caching",
  "What are embeddings?",
  "How does a cross-encoder work?",
];

const mockResponses = [
  {
    text: "Semantic caching stores responses based on the meaning of a query rather than exact text matching.",
    hit: true,
    time: 42,
  },
  {
    text: "The engine converts the query into a semantic representation, searches for similar cached queries, and verifies the best match.",
    hit: true,
    time: 37,
  },
  {
    text: "No sufficiently similar cached response was found, so this request would normally be sent to the LLM.",
    hit: false,
    time: 184,
  },
];

function App() {
  const [showRecentChats, setShowRecentChats] = useState(true);
  const [message, setMessage] = useState("");
  const [messages, setMessages] = useState([]);
  const [chats, setChats] = useState(initialChats);
  const [activeChat, setActiveChat] = useState(null);
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState({ time: "—", hit: 0, miss: 0 });

  const totalRequests = metrics.hit + metrics.miss;
  const hitRate =
    totalRequests === 0 ? 0 : Math.round((metrics.hit / totalRequests) * 100);

  const handleSend = () => {
    const query = message.trim();
    if (!query || loading) return;

    setMessages((previous) => [
      ...previous,
      { id: Date.now(), type: "user", text: query },
    ]);
    setMessage("");
    setLoading(true);

    setTimeout(() => {
      const response =
        mockResponses[Math.floor(Math.random() * mockResponses.length)];

      setMessages((previous) => [
        ...previous,
        {
          id: Date.now() + 1,
          type: "assistant",
          text: response.text,
          hit: response.hit,
        },
      ]);

      setMetrics((previous) => ({
        time: response.time,
        hit: response.hit ? previous.hit + 1 : previous.hit,
        miss: response.hit ? previous.miss : previous.miss + 1,
      }));

      setChats((previous) =>
        previous.includes(query) ? previous : [query, ...previous].slice(0, 8)
      );
      setActiveChat(query);
      setLoading(false);
    }, 1100);
  };

  const handleKeyDown = (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      handleSend();
    }
  };

  const handleNewChat = () => {
    setMessages([]);
    setMessage("");
    setActiveChat(null);
    setMetrics({ time: "—", hit: 0, miss: 0 });
  };

  const handleOldChat = (chat) => {
    setActiveChat(chat);
    setMessage(chat);
  };

  return (
    <div className="app">
      <header className="header">
        <div className="brand">
          <div className="brand-icon">SC</div>
          <div>
            <h1>Semantic Caching Engine</h1>
            <p>Deep intent. Smarter caching.</p>
          </div>
        </div>

        <div className="header-right">
          <button
            className="header-button recent-chats-toggle"
            onClick={() => setShowRecentChats((visible) => !visible)}
            aria-expanded={showRecentChats}
            aria-controls="recent-chats-sidebar"
          >
            {showRecentChats ? "Hide chats" : "Recent chats"}
          </button>

          <div className="engine-status">
            <span className="pulse-dot" />
            <span>Engine Online</span>
          </div>

          <button className="header-button" title="Settings">
            Settings
          </button>
        </div>
      </header>

      <main
        className={`dashboard ${
          showRecentChats ? "recent-open" : "recent-closed"
        }`}
      >
        <aside className="sidebar" id="recent-chats-sidebar">
          <div className="sidebar-heading">
            <span>Recent Chats</span>
            <button
              className="new-chat-button"
              onClick={handleNewChat}
              title="New chat"
              aria-label="New chat"
            >
              +
            </button>
          </div>

          <div className="chat-list">
            {chats.map((chat, index) => (
              <button
                key={`${chat}-${index}`}
                className={`chat-item ${
                  activeChat === chat ? "selected" : ""
                }`}
                onClick={() => handleOldChat(chat)}
                title={chat}
              >
                <span className="chat-icon">↗</span>
                <span className="chat-title">{chat}</span>
              </button>
            ))}
          </div>

          <div className="sidebar-bottom">
            <div className="engine-card">
              <div className="engine-card-top">
                <span className="mini-brain">SC</span>
                <span className="online-label">ONLINE</span>
              </div>
              <strong>Semantic Engine</strong>
              <small>Ready to process semantic queries</small>
            </div>
          </div>
        </aside>

        <section className="chat-section">
          <div className="chat-header">
            <div>
              <div className="title-row">
                <h2>Semantic Query</h2>
                <span className="cache-ready">
                  <span /> CACHE READY
                </span>
              </div>
              <p>
                Ask a question and let the semantic cache find the best
                response.
              </p>
            </div>

            <div className="query-controls">
              <span className="control-chip">
                Model <strong>default</strong>
              </span>
              <span className="control-chip">
                Threshold <strong>0.82</strong>
              </span>
              <span className="control-chip">
                Region <strong>local</strong>
              </span>
            </div>
          </div>

          <div className="messages">
            {messages.length === 0 ? (
              <div className="welcome">
                <div className="welcome-icon">
                  <span className="brain-glow">SC</span>
                </div>
                <h2>Start a conversation</h2>
                <p>Test your semantic caching engine with a query.</p>
                <div className="suggestions">
                  <button onClick={() => setMessage("What is semantic caching?")}>
                    <span>01</span> What is semantic caching?
                  </button>
                  <button
                    onClick={() => setMessage("How does an LLM cache work?")}
                  >
                    <span>02</span> How does an LLM cache work?
                  </button>
                  <button
                    onClick={() => setMessage("Why are embeddings useful?")}
                  >
                    <span>03</span> Why are embeddings useful?
                  </button>
                </div>
              </div>
            ) : (
              <div className="message-container">
                {messages.map((item) => (
                  <div key={item.id} className={`message-row ${item.type}`}>
                    <div className="message-avatar">
                      {item.type === "user" ? "You" : "SC"}
                    </div>
                    <div className="message-content">
                      <div className="message-label">
                        {item.type === "user" ? "You" : "Semantic Engine"}
                      </div>
                      <div className="message-bubble">{item.text}</div>
                      {item.type === "assistant" && (
                        <div className="response-meta">
                          <span className={item.hit ? "hit-badge" : "miss-badge"}>
                            {item.hit ? "● CACHE HIT" : "● CACHE MISS"}
                          </span>
                          <span>Semantic verification complete</span>
                        </div>
                      )}
                    </div>
                  </div>
                ))}

                {loading && (
                  <div className="message-row assistant">
                    <div className="message-avatar">SC</div>
                    <div className="message-content">
                      <div className="message-label">Semantic Engine</div>
                      <div className="typing-bubble">
                        <span />
                        <span />
                        <span />
                      </div>
                      <div className="processing-text">
                        Searching semantic cache...
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="input-area">
            <div className="input-box">
              <textarea
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Ask your query..."
                rows="1"
              />
              <button
                className="send-button"
                onClick={handleSend}
                disabled={!message.trim() || loading}
                aria-label="Send query"
              >
                {loading ? "..." : "↑"}
              </button>
            </div>
            <div className="input-footer">
              <span>Semantic Cache v0.1</span>
              <span>Enter to send&nbsp; / &nbsp;Shift + Enter for new line</span>
            </div>
          </div>
        </section>

        <aside className="metrics-panel">
          <div className="panel-title">
            <div>
              <h2>System Metrics</h2>
              <span>REAL-TIME MONITORING</span>
            </div>
            <span className="live-indicator">LIVE</span>
          </div>

          <div className="metric-card">
            <div className="metric-header">
              <span>Cross-Encoder</span>
              <span className="metric-dot" />
            </div>
            <div className="metric-value ready">Ready</div>
            <div className="metric-description">Semantic verification</div>
          </div>

          <div className="metric-card">
            <div className="metric-header">
              <span>Time Taken</span>
              <span className="metric-symbol">ms</span>
            </div>
            <div className="metric-value">
              {metrics.time}
              {metrics.time !== "—" && <small> ms</small>}
            </div>
            <div className="metric-description">Latest request latency</div>
          </div>

          <div className="metric-card">
            <div className="metric-header">
              <span>Hit / Miss Ratio</span>
              <span className="metric-symbol">%</span>
            </div>
            <div className="ratio-value">
              <strong>{metrics.hit}</strong>
              <span>/</span>
              <strong>{metrics.miss}</strong>
            </div>
            <div className="metric-description">
              {totalRequests === 0
                ? "No requests yet"
                : `${hitRate}% cache efficiency`}
            </div>
          </div>

          <div className="cache-card">
            <div className="cache-header">
              <span>Cache Efficiency</span>
              <strong>{hitRate}%</strong>
            </div>
            <div className="progress">
              <div
                className="progress-fill"
                style={{ width: `${hitRate}%` }}
              />
            </div>
            <div className="cache-footer">
              <span>{totalRequests} requests</span>
              <span>
                {hitRate >= 70
                  ? "Excellent"
                  : totalRequests === 0
                  ? "Waiting"
                  : "Learning"}
              </span>
            </div>
          </div>

          <div className="pipeline-card">
            <div className="pipeline-title">Processing Pipeline</div>
            <PipelineStep number="01" title="Query" />
            <PipelineStep number="02" title="Embedding" />
            <PipelineStep number="03" title="Similarity Search" />
            <PipelineStep number="04" title="Cross-Encoder" />
          </div>
        </aside>
      </main>
    </div>
  );
}

function PipelineStep({ number, title }) {
  return (
    <div className="pipeline-step">
      <div className="pipeline-number active">{number}</div>
      <span>{title}</span>
    </div>
  );
}

export default App;