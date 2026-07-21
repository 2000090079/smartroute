import { useState } from "react";
import ChatWidget from "./ChatWidget.jsx";
import Dashboard from "./Dashboard.jsx";
import AuditLog from "./AuditLog.jsx";

const TABS = [
  { id: "chat", label: "Chat" },
  { id: "dashboard", label: "Dashboard" },
  { id: "audit", label: "Audit Log" },
];

export default function App() {
  const [tab, setTab] = useState("chat");
  // Bumped after every chat send so Dashboard/AuditLog know to refetch
  // without polling on a timer.
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900">
      <header className="bg-slate-900 text-white">
        <div className="mx-auto max-w-5xl px-4 py-4 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold tracking-tight">SmartRoute</h1>
            <p className="text-slate-400 text-sm">AI-powered contact center routing</p>
          </div>
          <nav className="flex gap-1 bg-slate-800 rounded-lg p-1">
            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                  tab === t.id
                    ? "bg-white text-slate-900"
                    : "text-slate-300 hover:text-white"
                }`}
              >
                {t.label}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-6">
        {tab === "chat" && (
          <ChatWidget onRouted={() => setRefreshKey((k) => k + 1)} />
        )}
        {tab === "dashboard" && <Dashboard refreshKey={refreshKey} />}
        {tab === "audit" && <AuditLog refreshKey={refreshKey} />}
      </main>
    </div>
  );
}
