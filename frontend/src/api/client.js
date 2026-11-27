const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

let inMemoryAccessToken = null;
let refreshPromise = null;

export function setAccessToken(token) {
  inMemoryAccessToken = token || null;
}

export function getAccessToken() {
  return inMemoryAccessToken;
}

async function refreshAccessToken() {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-Requested-With": "XMLHttpRequest",
        },
      });
      if (!response.ok) {
        setAccessToken(null);
        return null;
      }
      const data = await response.json();
      setAccessToken(data.accessToken);
      return data.accessToken;
    } catch {
      setAccessToken(null);
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

export async function apiRequest(path, options = {}, retryOn401 = true) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {}),
  };

  if (inMemoryAccessToken) {
    headers["Authorization"] = `Bearer ${inMemoryAccessToken}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    credentials: "include",
    headers,
  });

  if (response.status === 401 && retryOn401 && !path.startsWith("/api/auth/")) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      return apiRequest(path, options, false);
    }
  }

  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }

  if (!response.ok) {
    const error = new Error(data?.message || `HTTP ${response.status}`);
    error.status = response.status;
    error.payload = data;
    throw error;
  }

  return data;
}

export const cacheApi = {
  queryPrompt(prompt, provider = null) {
    const body = provider && provider !== "auto" ? { prompt, provider } : { prompt };
    return apiRequest("/api/cache", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  getMetrics() {
    return apiRequest("/api/metrics", { method: "GET" });
  },
  getHealth() {
    return apiRequest("/api/health", { method: "GET" });
  },
};

export const authApi = {
  async login(username, password) {
    const data = await apiRequest("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    setAccessToken(data.accessToken);
    return data;
  },
  async register(username, password) {
    const data = await apiRequest("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    setAccessToken(data.accessToken);
    return data;
  },
  async silentRefresh() {
    return refreshAccessToken();
  },
  async logout() {
    try {
      await apiRequest("/api/auth/logout", {
        method: "POST",
        headers: { "X-Requested-With": "XMLHttpRequest" },
      });
    } finally {
      setAccessToken(null);
    }
  },
};
