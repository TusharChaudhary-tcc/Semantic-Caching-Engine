import { useState } from "react";
import { authApi } from "../api/client";

export default function AuthModal({ onClose, onSuccess }) {
  const [mode, setMode] = useState("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      const data =
        mode === "login"
          ? await authApi.login(username.trim(), password)
          : await authApi.register(username.trim(), password);
      onSuccess(data.user || { username: username.trim() });
      onClose();
    } catch (err) {
      setError(err.message || "Authentication failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="auth-modal glass-card" onClick={(e) => e.stopPropagation()}>
        <div className="auth-modal-header">
          <div>
            <span className="pill-badge">JWT + Token Rotation</span>
            <h3>{mode === "login" ? "Sign In" : "Create Account"}</h3>
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        <form onSubmit={handleSubmit} className="auth-form">
          <label>
            <span>Username</span>
            <input
              type="text"
              required
              minLength={3}
              maxLength={50}
              placeholder="developer123"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label>
            <span>Password</span>
            <input
              type="password"
              required
              minLength={8}
              placeholder="Min 8 chars, upper, lower, digit, symbol"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>

          {error && <div className="auth-error">{error}</div>}

          <button type="submit" className="primary-btn" disabled={submitting}>
            {submitting ? "Authenticating..." : mode === "login" ? "Sign In" : "Register"}
          </button>
        </form>

        <div className="auth-switch">
          {mode === "login" ? (
            <>
              New here?{" "}
              <button type="button" onClick={() => setMode("register")}>
                Create account
              </button>
            </>
          ) : (
            <>
              Already registered?{" "}
              <button type="button" onClick={() => setMode("login")}>
                Sign in
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
