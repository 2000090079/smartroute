// Relative paths so the Vite dev proxy (see vite.config.js) forwards to the
// Spring Boot backend on :8080. In production, serve the frontend behind the
// same origin/reverse proxy as the API, or set VITE_API_BASE.
const BASE = import.meta.env.VITE_API_BASE ?? "";

async function request(path, options) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message ? JSON.stringify(body.message) : `Request failed: ${res.status}`);
  }
  return res.json();
}

export function sendMessage(message) {
  return request("/api/chat", {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}

export function fetchStats() {
  return request("/api/stats");
}

export function fetchAuditLog(limit = 50) {
  return request(`/api/audit-log?limit=${limit}`);
}
