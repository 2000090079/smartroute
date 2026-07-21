import { useEffect, useState } from "react";
import { fetchStats } from "./api.js";

const POLL_MS = 5000;

function StatCard({ label, value, sub }) {
  return (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4">
      <p className="text-sm text-slate-500">{label}</p>
      <p className="text-3xl font-bold text-slate-900 mt-1">{value}</p>
      {sub && <p className="text-xs text-slate-400 mt-1">{sub}</p>}
    </div>
  );
}

const QUEUE_COLORS = {
  ESCALATE: "bg-red-500",
  BILLING_QUEUE: "bg-amber-500",
  TECH_QUEUE: "bg-blue-500",
  AUTO_RESOLVE: "bg-green-500",
};

export default function Dashboard({ refreshKey }) {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await fetchStats();
        if (!cancelled) {
          setStats(data);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) setError(err.message);
      }
    }

    load();
    const interval = setInterval(load, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
    // refreshKey forces an immediate refetch right after a chat message is
    // routed, instead of waiting up to POLL_MS for the next poll tick.
  }, [refreshKey]);

  if (error) {
    return <p className="text-sm text-red-600">Failed to load stats: {error}</p>;
  }

  if (!stats) {
    return <p className="text-sm text-slate-400">Loading dashboard…</p>;
  }

  const maxQueueCount = Math.max(1, ...Object.values(stats.messages_per_queue));

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Total Messages" value={stats.total_messages} />
        <StatCard label="Auto-Resolve Rate" value={`${stats.auto_resolve_rate}%`} />
        <StatCard label="Escalation Rate" value={`${stats.escalation_rate}%`} />
        <StatCard label="Avg Confidence" value={stats.avg_confidence.toFixed(2)} />
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4">
        <h2 className="text-sm font-semibold text-slate-700 mb-3">Messages per Queue</h2>
        {Object.keys(stats.messages_per_queue).length === 0 && (
          <p className="text-sm text-slate-400">No messages yet.</p>
        )}
        <div className="space-y-2">
          {Object.entries(stats.messages_per_queue).map(([queue, count]) => (
            <div key={queue} className="flex items-center gap-3">
              <span className="w-32 text-xs text-slate-600">{queue.replace("_", " ")}</span>
              <div className="flex-1 bg-slate-100 rounded-full h-3 overflow-hidden">
                <div
                  className={`h-3 rounded-full ${QUEUE_COLORS[queue] ?? "bg-slate-400"}`}
                  style={{ width: `${(count / maxQueueCount) * 100}%` }}
                />
              </div>
              <span className="w-8 text-right text-xs font-medium text-slate-700">{count}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4">
        <h2 className="text-sm font-semibold text-slate-700 mb-3">Recent Activity</h2>
        {stats.recent.length === 0 && <p className="text-sm text-slate-400">No messages yet.</p>}
        <ul className="divide-y divide-slate-100">
          {stats.recent.slice(0, 8).map((entry) => (
            <li key={entry.id} className="py-2 flex items-center justify-between gap-3">
              <span className="text-sm text-slate-700 truncate">{entry.message}</span>
              <span className="text-xs text-slate-400 whitespace-nowrap">{entry.decision}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
