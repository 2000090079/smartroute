import { useState } from "react";
import { sendMessage } from "./api.js";

const DECISION_STYLES = {
  ESCALATE: "bg-red-100 text-red-800 border-red-300",
  BILLING_QUEUE: "bg-amber-100 text-amber-800 border-amber-300",
  TECH_QUEUE: "bg-blue-100 text-blue-800 border-blue-300",
  AUTO_RESOLVE: "bg-green-100 text-green-800 border-green-300",
};

const SENTIMENT_EMOJI = { positive: "🙂", neutral: "😐", negative: "☹️" };

function DecisionBadge({ decision }) {
  const cls = DECISION_STYLES[decision] ?? "bg-slate-100 text-slate-800 border-slate-300";
  return (
    <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-semibold border ${cls}`}>
      {decision.replace("_", " ")}
    </span>
  );
}

export default function ChatWidget({ onRouted }) {
  const [input, setInput] = useState("");
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    const message = input.trim();
    if (!message || loading) return;

    setLoading(true);
    setError(null);
    setHistory((h) => [...h, { role: "user", message }]);
    setInput("");

    try {
      const result = await sendMessage(message);
      setHistory((h) => [...h, { role: "system", result }]);
      onRouted?.();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 flex flex-col h-[70vh]">
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {history.length === 0 && (
          <p className="text-slate-400 text-sm text-center mt-10">
            Send a message to see how SmartRoute classifies and routes it.
          </p>
        )}
        {history.map((item, idx) =>
          item.role === "user" ? (
            <div key={idx} className="flex justify-end">
              <div className="bg-slate-900 text-white rounded-2xl rounded-br-sm px-4 py-2 max-w-[75%]">
                {item.message}
              </div>
            </div>
          ) : (
            <div key={idx} className="flex justify-start">
              <div className="bg-slate-50 border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-3 max-w-[85%] space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <DecisionBadge decision={item.result.decision} />
                  <span className="text-xs text-slate-500">
                    intent: <strong>{item.result.intent}</strong>
                  </span>
                  <span className="text-xs text-slate-500">
                    sentiment: {SENTIMENT_EMOJI[item.result.sentiment]} {item.result.sentiment}
                  </span>
                  <span className="text-xs text-slate-500">
                    confidence: {(item.result.confidence * 100).toFixed(0)}%
                  </span>
                  {item.result.intent_source === "keyword_fallback" && (
                    <span className="text-xs text-slate-400 italic">(keyword fallback)</span>
                  )}
                </div>
                {item.result.resolution && (
                  <p className="text-sm text-slate-800">{item.result.resolution}</p>
                )}
                {!item.result.resolution && (
                  <p className="text-sm text-slate-500 italic">
                    Routed to {item.result.decision.replace("_", " ").toLowerCase()} — no
                    automated resolution.
                  </p>
                )}
              </div>
            </div>
          ),
        )}
        {loading && <p className="text-sm text-slate-400">Routing…</p>}
        {error && <p className="text-sm text-red-600">Error: {error}</p>}
      </div>

      <form onSubmit={handleSubmit} className="border-t border-slate-200 p-3 flex gap-2">
        <input
          type="text"
          autoComplete="off"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type a customer message…"
          className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900"
          disabled={loading}
        />
        <button
          type="submit"
          disabled={loading || !input.trim()}
          className="bg-slate-900 text-white px-4 py-2 rounded-lg text-sm font-medium disabled:opacity-40"
        >
          Send
        </button>
      </form>
    </div>
  );
}
