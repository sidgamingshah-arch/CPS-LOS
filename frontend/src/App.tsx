import { useCallback, useMemo, useState } from "react";
import { AppContext, ACTORS } from "./app-context";
import { Toast } from "./ui";
import Dashboard from "./pages/Dashboard";
import RulePacks from "./pages/RulePacks";
import Counterparties from "./pages/Counterparties";
import Deals from "./pages/Deals";
import DealWorkspace from "./pages/DealWorkspace";
import AuditLog from "./pages/AuditLog";
import Copilot from "./pages/Copilot";

const NAV = [
  { key: "dashboard", label: "Portfolio Dashboard" },
  { key: "deals", label: "Deals" },
  { key: "counterparties", label: "Counterparties" },
  { key: "copilot", label: "Copilot" },
  { key: "rulepacks", label: "Jurisdictions & Rule Packs" },
  { key: "audit", label: "Audit Trail" },
];

const CRUMB: Record<string, string> = {
  dashboard: "Portfolio & book-level intelligence",
  deals: "Origination pipeline",
  counterparties: "Onboarding · KYC/KYB · UBO",
  copilot: "Scoped, grounded, non-binding assistant",
  rulepacks: "Regulatory abstraction layer",
  audit: "Immutable, examiner-ready trail",
  workspace: "Deal workspace — AI-executed, human-gated",
};

export default function App() {
  const [view, setView] = useState("dashboard");
  const [ref, setRef] = useState<string | undefined>();
  const [actor, setActor] = useState("rm.user");
  const [msg, setMsg] = useState<{ text: string; err?: boolean } | null>(null);

  const notify = useCallback((text: string, err?: boolean) => setMsg({ text, err }), []);
  const nav = useCallback((v: string, r?: string) => { setView(v); if (r !== undefined) setRef(r); }, []);

  const ctx = useMemo(() => ({ actor, notify, nav }), [actor, notify, nav]);

  return (
    <AppContext.Provider value={ctx}>
      <div className="app">
        <aside className="sidebar">
          <div className="brand">
            <div className="logo">Heli<span>x</span></div>
            <div className="tag">AI-First Wholesale Loan Origination</div>
          </div>
          <nav className="nav">
            {NAV.map((n) => (
              <button key={n.key} className={view === n.key ? "active" : ""} onClick={() => nav(n.key)}>
                <span className="dot" /> {n.label}
              </button>
            ))}
          </nav>
          <div className="actor">
            Acting as (named accountable user)
            <select value={actor} onChange={(e) => setActor(e.target.value)}>
              {ACTORS.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </div>
        </aside>

        <main className="main">
          <div className="topbar">
            <div>
              <div className="crumb">{CRUMB[view] || ""}</div>
              <h2 style={{ margin: 0 }}>{NAV.find((n) => n.key === view)?.label || "Deal Workspace"}</h2>
            </div>
            <div className="badge ai">AI-executed · human-gated</div>
          </div>
          <div className="content">
            {view === "dashboard" && <Dashboard />}
            {view === "deals" && <Deals />}
            {view === "counterparties" && <Counterparties />}
            {view === "copilot" && <Copilot />}
            {view === "rulepacks" && <RulePacks />}
            {view === "audit" && <AuditLog />}
            {view === "workspace" && ref && <DealWorkspace reference={ref} />}
          </div>
        </main>
      </div>
      <Toast msg={msg} onClose={() => setMsg(null)} />
    </AppContext.Provider>
  );
}
