import { useCallback, useMemo, useState } from "react";
import { AppContext, ACTORS } from "./app-context";
import { Toast, AiBadge, HumanBadge, DeterministicBadge, GovernanceStrip } from "./ui";
import Dashboard from "./pages/Dashboard";
import RulePacks from "./pages/RulePacks";
import Counterparties from "./pages/Counterparties";
import Deals from "./pages/Deals";
import DealWorkspace from "./pages/DealWorkspace";
import AuditLog from "./pages/AuditLog";
import Copilot from "./pages/Copilot";
import Mis from "./pages/Mis";
import Customer360 from "./pages/Customer360";
import Cad from "./pages/Cad";
import Monitoring from "./pages/Monitoring";
import Limits from "./pages/Limits";
import Masters from "./pages/Masters";
import Structuring from "./pages/Structuring";
import DocIntel from "./pages/DocIntel";
import RiskLab from "./pages/RiskLab";
import DocGen from "./pages/DocGen";
import Commentary from "./pages/Commentary";
import PricingLab from "./pages/PricingLab";
import Spreading from "./pages/Spreading";
import Exports from "./pages/Exports";
import Groups from "./pages/Groups";

const NAV = [
  { key: "dashboard", label: "Portfolio Dashboard" },
  { key: "deals", label: "Deals" },
  { key: "spreading", label: "Financial Spreading" },
  { key: "structuring", label: "Deal Structuring" },
  { key: "docintel", label: "Doc Intelligence" },
  { key: "counterparties", label: "Counterparties" },
  { key: "groups", label: "Borrower Groups" },
  { key: "limits", label: "Limits" },
  { key: "cad", label: "CAD · Documentation" },
  { key: "docgen", label: "Doc Generation" },
  { key: "commentary", label: "AI Commentary" },
  { key: "pricinglab", label: "Pricing Lab" },
  { key: "monitoring", label: "Monitoring · MER" },
  { key: "customer360", label: "Customer-360" },
  { key: "risklab", label: "Risk Lab" },
  { key: "mis", label: "MIS · Reports" },
  { key: "exports", label: "Downstream Exports" },
  { key: "copilot", label: "Copilot" },
  { key: "rulepacks", label: "Jurisdictions & Rule Packs" },
  { key: "masters", label: "Master Data" },
  { key: "audit", label: "Audit Trail" },
];

const CRUMB: Record<string, string> = {
  dashboard: "Portfolio & book-level intelligence",
  deals: "Origination pipeline",
  spreading: "SpreadJS-style grid · multi-period · cell provenance · override-with-reason gate · ratios",
  structuring: "Specialised CP variants · group · joint/dual-obligor · syndication · FI ICR · renewal copy",
  docintel: "GenAI document intelligence · extraction (human-confirmed) · language · translation · checks",
  counterparties: "Onboarding · KYC/KYB · UBO",
  groups: "Advisory group identification · group insights · combined credit proposal (member figures unchanged)",
  limits: "Multi-level limit tree · fungibility · View/Validation/Utilisation APIs",
  cad: "Credit Administration · checklist · waivers/deviations · limit release",
  docgen: "Template-driven document generation · clause add/remove/edit · human-confirm gate",
  commentary: "AI narrative commentary · grounded · advisory · human-confirm gate",
  pricinglab: "Pricing scenario optimiser · goal-seek · advisory (authoritative pricing untouched)",
  monitoring: "Deferred docs · conditions subsequent · renewals · reminders · escalation · DMS feed",
  customer360: "Borrower 360 · profile · limits · triggers · financials · RAROC · provisioning",
  risklab: "Advisory overlays · statistical RAG scoring · macro directional impact (non-binding)",
  mis: "Composition · RAROC variance · ECL · ageing · watchlist",
  exports: "Canonical outbound feeds · ERM · Finance/GL · CPR · idempotent batches",
  copilot: "Scoped, grounded, non-binding assistant",
  rulepacks: "Regulatory abstraction layer",
  masters: "Generic Master-Data engine · maker-checker SoD · 22 master types",
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
            <div className="tag">Governed AI for Wholesale Credit</div>
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
            <div className="gov-chips">
              <AiBadge />
              <HumanBadge />
              <DeterministicBadge label="DETERMINISTIC FIGURES" />
            </div>
          </div>
          <GovernanceStrip />
          <div className="content">
            {view === "dashboard" && <Dashboard />}
            {view === "deals" && <Deals />}
            {view === "spreading" && <Spreading />}
            {view === "structuring" && <Structuring />}
            {view === "docintel" && <DocIntel />}
            {view === "counterparties" && <Counterparties />}
            {view === "groups" && <Groups />}
            {view === "limits" && <Limits />}
            {view === "cad" && <Cad />}
            {view === "docgen" && <DocGen />}
            {view === "commentary" && <Commentary />}
            {view === "pricinglab" && <PricingLab />}
            {view === "monitoring" && <Monitoring />}
            {view === "customer360" && <Customer360 />}
            {view === "risklab" && <RiskLab />}
            {view === "mis" && <Mis />}
            {view === "exports" && <Exports />}
            {view === "copilot" && <Copilot />}
            {view === "rulepacks" && <RulePacks />}
            {view === "masters" && <Masters />}
            {view === "audit" && <AuditLog />}
            {view === "workspace" && ref && <DealWorkspace reference={ref} />}
          </div>
        </main>
      </div>
      <Toast msg={msg} onClose={() => setMsg(null)} />
    </AppContext.Provider>
  );
}
