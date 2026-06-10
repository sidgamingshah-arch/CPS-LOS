import { useCallback, useEffect, useMemo, useState } from "react";
import { AppContext, ACTORS, AI_BY_NAV, isNavEnabled } from "./app-context";
import { governance } from "./api";
import { Toast, AiBadge, HumanBadge, DeterministicBadge, GovernanceStrip } from "./ui";
import CommandPalette from "./CommandPalette";
import Dashboard from "./pages/Dashboard";
import RulePacks from "./pages/RulePacks";
import Governance from "./pages/Governance";
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
import Cpt from "./pages/Cpt";

/**
 * Navigation grouped by the credit-lifecycle spine (counterparty → origination →
 * risk → decision → limit → portfolio → config). Each section maps to a phase of
 * work rather than to a service, so the IA reads as a journey, not a server list.
 */
type NavItem = { key: string; label: string };
type NavGroup = { title: string; items: NavItem[] };

const NAV_GROUPS: NavGroup[] = [
  {
    title: "Overview",
    items: [
      { key: "dashboard", label: "Portfolio Dashboard" },
      { key: "copilot", label: "Copilot" },
    ],
  },
  {
    title: "Originate",
    items: [
      { key: "counterparties", label: "Counterparties" },
      { key: "groups", label: "Borrower Groups" },
      { key: "cpt", label: "Client Planning" },
      { key: "deals", label: "Deals" },
      { key: "structuring", label: "Deal Structuring" },
      { key: "spreading", label: "Financial Spreading" },
      { key: "docintel", label: "Doc Intelligence" },
    ],
  },
  {
    title: "Assess & Decide",
    items: [
      { key: "risklab", label: "Risk Lab" },
      { key: "pricinglab", label: "Pricing Lab" },
      { key: "cad", label: "CAD · Documentation" },
      { key: "docgen", label: "Doc Generation" },
      { key: "commentary", label: "AI Commentary" },
    ],
  },
  {
    title: "Limits & Portfolio",
    items: [
      { key: "limits", label: "Limits" },
      { key: "monitoring", label: "Monitoring · MER" },
      { key: "customer360", label: "Customer-360" },
      { key: "mis", label: "MIS · Reports" },
      { key: "exports", label: "Downstream Exports" },
    ],
  },
  {
    title: "Configure & Govern",
    items: [
      { key: "rulepacks", label: "Jurisdictions & Rule Packs" },
      { key: "masters", label: "Master Data" },
      { key: "governance", label: "AI Governance" },
      { key: "audit", label: "Audit Trail" },
    ],
  },
];

/** Flat key→label lookup derived from the groups (topbar title). */
const NAV: NavItem[] = NAV_GROUPS.flatMap((g) => g.items);

/** Flat screen list (with section) for the command palette. */
const SCREENS = NAV_GROUPS.flatMap((g) =>
  g.items.map((n) => ({ section: g.title, key: n.key, label: n.label })),
);

const CRUMB: Record<string, string> = {
  dashboard: "Portfolio & book-level intelligence",
  deals: "Origination pipeline",
  spreading: "SpreadJS-style grid · multi-period · cell provenance · override-with-reason gate · ratios",
  structuring: "Specialised CP variants · group · joint/dual-obligor · syndication · FI ICR · renewal copy",
  docintel: "GenAI document intelligence · extraction (human-confirmed) · language · translation · checks",
  counterparties: "Onboarding · KYC/KYB · UBO",
  groups: "Advisory group identification · group insights · combined credit proposal (member figures unchanged)",
  cpt: "Client Planning Template · wallet sizing · cross-sell whitespace · completeness nudges (advisory)",
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
  governance: "AI off-switch · capability-level · per-jurisdiction override · 403 enforced",
  audit: "Immutable, examiner-ready trail",
  workspace: "Deal workspace — AI-executed, human-gated",
};

/* ---- tiny localStorage helpers (best-effort; never throw) ---- */
const lsGet = (k: string, fallback: string) => {
  try { return localStorage.getItem(k) ?? fallback; } catch { return fallback; }
};
const lsSet = (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

export default function App() {
  const [view, setView] = useState(() => lsGet("helix.view", "dashboard"));
  const [ref, setRef] = useState<string | undefined>(() => lsGet("helix.ref", "") || undefined);
  const [actor, setActor] = useState(() => lsGet("helix.actor", "rm.user"));
  const [msg, setMsg] = useState<{ text: string; err?: boolean } | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(() => {
    try { return JSON.parse(lsGet("helix.nav.collapsed", "{}")); } catch { return {}; }
  });
  const [navOpen, setNavOpen] = useState(false);   // mobile drawer
  const [cmdkOpen, setCmdkOpen] = useState(false); // ⌘K palette
  const [aiEnabled, setAiEnabled] = useState<Record<string, boolean>>({});

  // Pull the resolved AI-governance map on boot. Conservative fallback: if the
  // call fails we treat every capability as enabled (matches the server's TTL'd
  // client fallback), so a brief outage on config-service doesn't blank the UI.
  useEffect(() => {
    governance.resolved()
      .then((m) => {
        const out: Record<string, boolean> = {};
        for (const [k, v] of Object.entries(m.capabilities || {})) {
          out[k] = (v as any).enabled !== false;
        }
        setAiEnabled(out);
      })
      .catch(() => setAiEnabled({}));
  }, []);

  const notify = useCallback((text: string, err?: boolean) => setMsg({ text, err }), []);
  const nav = useCallback((v: string, r?: string) => {
    setView(v);
    if (r !== undefined) setRef(r);
    setNavOpen(false); // close the mobile drawer on navigation
  }, []);
  const toggleGroup = useCallback(
    (title: string) => setCollapsed((c) => ({ ...c, [title]: !c[title] })),
    [],
  );

  // Persist UI state so a reload lands you where you left off.
  useEffect(() => { lsSet("helix.view", view); }, [view]);
  useEffect(() => { lsSet("helix.ref", ref ?? ""); }, [ref]);
  useEffect(() => { lsSet("helix.actor", actor); }, [actor]);
  useEffect(() => { lsSet("helix.nav.collapsed", JSON.stringify(collapsed)); }, [collapsed]);

  // Global ⌘K / Ctrl-K to open the command palette.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setCmdkOpen((o) => !o);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const ctx = useMemo(() => ({ actor, notify, nav, aiEnabled }),
                       [actor, notify, nav, aiEnabled]);

  const title = NAV.find((n) => n.key === view)?.label || "Deal Workspace";
  // The active-deal context chip: show whenever a deal is selected but we're not
  // already inside its workspace (one-click way back into the deal you're on).
  const showDealChip = !!ref && view !== "workspace";

  return (
    <AppContext.Provider value={ctx}>
      <div className={`app${navOpen ? " nav-open" : ""}`}>
        <div className="scrim" onClick={() => setNavOpen(false)} />
        <aside className="sidebar">
          <div className="brand">
            <div className="logo">Heli<span>x</span></div>
            <div className="tag">Governed AI for Wholesale Credit</div>
          </div>
          <nav className="nav">
            {NAV_GROUPS.map((g) => {
              const isCollapsed = !!collapsed[g.title];
              // Hide AI-disabled screens from the nav. The server-side enforcement
              // (403 forbiddenAutonomy) is the real gate; this just stops users
              // landing on a screen whose primary action wouldn't work.
              const items = g.items.filter((n) => isNavEnabled(n.key, aiEnabled));
              if (items.length === 0) return null;
              return (
                <div key={g.title} className={`nav-group${isCollapsed ? " collapsed" : ""}`}>
                  <button
                    className="nav-group-title"
                    onClick={() => toggleGroup(g.title)}
                    aria-expanded={!isCollapsed}
                  >
                    <span>{g.title}</span>
                    <span className="chev">▾</span>
                  </button>
                  <div className="nav-group-items">
                    {items.map((n) => (
                      <button
                        key={n.key}
                        className={`nav-item${view === n.key ? " active" : ""}`}
                        onClick={() => nav(n.key)}
                      >
                        <span className="dot" /> {n.label}
                      </button>
                    ))}
                  </div>
                </div>
              );
            })}
          </nav>
        </aside>

        <main className="main">
          <div className="topbar">
            <div className="topbar-left">
              <button className="hamburger" aria-label="Toggle navigation" onClick={() => setNavOpen((o) => !o)}>≡</button>
              <div>
                <div className="crumb">{CRUMB[view] || ""}</div>
                <h2 style={{ margin: 0 }}>{title}</h2>
              </div>
              {showDealChip && (
                <button className="cmdk-btn" title="Open the deal workspace" onClick={() => nav("workspace", ref)}>
                  <span className="ci-dot" style={{ background: "var(--ocean-6)" }} />
                  Deal <b style={{ fontFamily: "var(--font-mono)" }}>{ref}</b> →
                </button>
              )}
            </div>
            <div className="topbar-right">
              <button className="cmdk-btn" onClick={() => setCmdkOpen(true)}>
                <span className="ico">⌕</span> Search <kbd>⌘K</kbd>
              </button>
              <div className="gov-chips">
                <AiBadge />
                <HumanBadge />
                <DeterministicBadge label="DETERMINISTIC FIGURES" />
              </div>
              <div className="topbar-actor">
                <span className="ta-label">Acting as</span>
                <select value={actor} onChange={(e) => setActor(e.target.value)} title="Named accountable user — drives every SoD check">
                  {ACTORS.map((a) => <option key={a} value={a}>{a}</option>)}
                </select>
              </div>
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
            {view === "cpt" && <Cpt />}
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
            {view === "governance" && <Governance />}
            {view === "masters" && <Masters />}
            {view === "audit" && <AuditLog />}
            {view === "workspace" && ref && <DealWorkspace reference={ref} />}
          </div>
        </main>
      </div>
      <CommandPalette open={cmdkOpen} onClose={() => setCmdkOpen(false)} screens={SCREENS} onPick={nav} />
      <Toast msg={msg} onClose={() => setMsg(null)} />
    </AppContext.Provider>
  );
}
