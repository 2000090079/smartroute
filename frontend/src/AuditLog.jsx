import { useEffect, useState } from "react";
import { fetchAuditLog } from "./api.js";

const POLL_MS = 5000;

function formatTime(iso) {
  return new Date(iso).toLocaleString();
}

export default function AuditLog({ refreshKey }) {
  const [entries, setEntries] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await fetchAuditLog(100);
        if (!cancelled) {
          setEntries(data);
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
  }, [refreshKey]);

  if (error) {
    return <p className="text-sm text-red-600">Failed to load audit log: {error}</p>;
  }

  return (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-slate-500 text-xs uppercase">
            <tr>
              <th className="text-left px-4 py-2">Time</th>
              <th className="text-left px-4 py-2">Message</th>
              <th className="text-left px-4 py-2">Intent</th>
              <th className="text-left px-4 py-2">Sentiment</th>
              <th className="text-left px-4 py-2">Confidence</th>
              <th className="text-left px-4 py-2">Decision</th>
              <th className="text-left px-4 py-2">Resolution</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {entries.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-6 text-center text-slate-400">
                  No routing decisions logged yet.
                </td>
              </tr>
            )}
            {entries.map((e) => (
              <tr key={e.id} className="hover:bg-slate-50">
                <td className="px-4 py-2 whitespace-nowrap text-slate-500">
                  {formatTime(e.timestamp)}
                </td>
                <td className="px-4 py-2 max-w-xs truncate" title={e.message}>
                  {e.message}
                </td>
                <td className="px-4 py-2">{e.intent}</td>
                <td className="px-4 py-2">{e.sentiment}</td>
                <td className="px-4 py-2">{(e.confidence * 100).toFixed(0)}%</td>
                <td className="px-4 py-2">
                  <span className="text-xs font-semibold">{e.decision}</span>
                </td>
                <td className="px-4 py-2 max-w-xs truncate" title={e.resolution ?? ""}>
                  {e.resolution ?? "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
