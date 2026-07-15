import { useCallback, useEffect, useMemo, useState } from "react";
import { AppContext, AI_BY_NAV, isNavEnabled } from "./app-context";
import { governance, setAuthToken } from "./api";
import { Toast, AiBadge, HumanBadge, DeterministicBadge, GovernanceStrip } from "./ui";
import CommandPalette from "./CommandPalette";
import CopilotDock from "./CopilotDock";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import RulePacks from "./pages/RulePacks";
import Governance from "./pages/Governance";
import Disbursement from "./pages/Disbursement";
import Counterparties from "./pages/Counterparties";
import Deals from "./pages/Deals";
import DealWorkspace from "./pages/DealWorkspace";
import AuditLog from "./pages/AuditLog";
import Copilot from "./pages/Copilot";
import Mis from "./pages/Mis";
import Customer360 from "./pages/Customer360";
import Cad from "./pages/Cad";
import Perfection from "./pages/Perfection";
import Monitoring from "./pages/Monitoring";
import MonitoringArtifacts from "./pages/MonitoringArtifacts";
import Escrow from "./pages/Escrow";
import Srm from "./pages/Srm";
import Limits from "./pages/Limits";
import Masters from "./pages/Masters";
import Structuring from "./pages/Structuring";
import Scf from "./pages/Scf";
import Syndication from "./pages/Syndication";
import DocIntel from "./pages/DocIntel";
import RiskLab from "./pages/RiskLab";
import DocGen from "./pages/DocGen";
import Commentary from "./pages/Commentary";
import PricingLab from "./pages/PricingLab";
import Spreading from "./pages/Spreading";
import Exports from "./pages/Exports";
import Groups from "./pages/Groups";
import Cpt from "./pages/Cpt";
import WorkflowTracker from "./pages/WorkflowTracker";
import ReportBuilder from "./pages/ReportBuilder";
import ModelBuilder from "./pages/ModelBuilder";
import Projections from "./pages/Projections";
import Committee from "./pages/Committee";
import Coi from "./pages/Coi";
import DrawingPower from "./pages/DrawingPower";
import Notifications from "./pages/Notifications";
import Notings from "./pages/Notings";
import IpNotes from "./pages/IpNotes";
import PostureChip from "./pages/PostureChip";
import NotificationBell from "./notification-center";
import { prefetchAllCodes } from "./code-values";

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
      { key: "ipnotes", label: "IP Notes" },
      { key: "deals", label: "Deals" },
      { key: "structuring", label: "Deal Structuring" },
      { key: "scf", label: "Supply-Chain Finance" },
      { key: "syndication", label: "Syndication" },
      { key: "spreading", label: "Financial Spreading" },
      { key: "docintel", label: "Doc Intelligence" },
    ],
  },
  {
    title: "Assess & Decide",
    items: [
      { key: "risklab", label: "Risk Lab" },
      { key: "projections", label: "Projections" },
      { key: "pricinglab", label: "Pricing Lab" },
      { key: "cad", label: "CAD · Documentation" },
      { key: "perfection", label: "MOE Perfection" },
      { key: "docgen", label: "Doc Generation" },
      { key: "commentary", label: "AI Commentary" },
      { key: "committee", label: "Committee Room" },
      { key: "coi", label: "Conflict of Interest" },
      { key: "notings", label: "Notings" },
    ],
  },
  {
    title: "Limits & Portfolio",
    items: [
      { key: "limits", label: "Limits" },
      { key: "disbursement", label: "Disbursement · CPs" },
      { key: "drawingpower", label: "Drawing Power" },
      { key: "monitoring", label: "Monitoring · MER" },
      { key: "srm", label: "SRM · Renewals" },
      { key: "monitoringartifacts", label: "Monitoring Artifacts" },
      { key: "escrow", label: "Escrow Monitoring" },
      { key: "customer360", label: "Customer-360" },
      { key: "mis", label: "MIS · Reports" },
      { key: "reportbuilder", label: "Ad-hoc Reports" },
      { key: "workflowtracker", label: "Workflow Tracker" },
      { key: "exports", label: "Downstream Exports" },
    ],
  },
  {
    title: "Configure & Govern",
    items: [
      { key: "rulepacks", label: "Jurisdictions & Rule Packs" },
      { key: "masters", label: "Master Data" },
      { key: "modelbuilder", label: "Model Builder" },
      { key: "governance", label: "AI Governance" },
      { key: "notifications", label: "Notifications" },
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
  scf: "Supply-chain finance · anchor programme · deterministic spoke eligibility · PRODUCT_PAPER noting · limit via limit-service",
  syndication: "Syndicate book · fee waterfall · agency reconciliation · participant feed",
  docintel: "GenAI document intelligence · extraction (human-confirmed) · language · translation · checks",
  counterparties: "Onboarding · KYC/KYB · UBO",
  groups: "Advisory group identification · group insights · combined credit proposal (member figures unchanged)",
  cpt: "Client Planning Template · wallet sizing · cross-sell whitespace · completeness nudges (advisory)",
  limits: "Multi-level limit tree · fungibility · View/Validation/Utilisation APIs",
  disbursement: "Pre-disbursement gate · CP register · drawdown maker-checker · limit-utilise booking",
  cad: "Credit Administration · checklist · waivers/deviations · limit release",
  perfection: "Mortgage / MOE security perfection · ordered role-gated steps · MOE-vetting SoD · vendor RFQ · optional limit-release gate",
  docgen: "Template-driven document generation · clause add/remove/edit · human-confirm gate",
  commentary: "AI narrative commentary · grounded · advisory · human-confirm gate",
  pricinglab: "Pricing scenario optimiser · goal-seek · advisory (authoritative pricing untouched)",
  monitoring: "Deferred docs · conditions subsequent · renewals · reminders · escalation · DMS feed",
  srm: "Structured review / renewal on the Noting engine · SRM_CHECKLIST · linked SRM_RENEWAL noting · AUTHORIZED advances the MER next-review date",
  monitoringartifacts: "One master-driven lifecycle · call memo/plant visit/LCR/QPR/broker/stock audit/audit note · review→approve→authorize SoD · vendor RFQ · ECL/exposure untouched",
  escrow: "Escrow monitoring · append-only versioned budget lines · category-tagged transactions · deterministic budget-vs-actual + RAG (VALIDATION_PARAMETER) · ECL/exposure/limit untouched",
  customer360: "Borrower 360 · profile · limits · triggers · financials · RAROC · provisioning",
  risklab: "Advisory overlays · statistical RAG scoring · macro directional impact (non-binding)",
  projections: "Multi-year proforma · driver assumptions · projected DSCR · sensitivity (advisory)",
  mis: "Composition · RAROC variance · ECL · ageing · watchlist",
  reportbuilder: "Self-service · whitelisted datasets · maker-checker on saved defs · deterministic figures",
  workflowtracker: "Lifecycle from WORKFLOW_DEFINITION pack · humanGate / autonomy guard · SLA",
  exports: "Canonical outbound feeds · ERM · Finance/GL · CPR · idempotent batches",
  copilot: "Scoped, grounded, non-binding assistant",
  rulepacks: "Regulatory abstraction layer",
  masters: "Generic Master-Data engine · maker-checker SoD · 22 master types",
  modelbuilder: "Configure scoring models · sections · typed questions · visibility rules · master-driven options · maker-checker",
  governance: "AI off-switch · capability-level · per-jurisdiction override · 403 enforced",
  committee: "Committee/quorum voting · SoD (router can't vote · no double-vote) · sanction letter (AI draft → human confirm)",
  coi: "Conflict-of-interest attestations · named-human self-declaration · gates the decision/vote only where the DOA pack requires it (default-off)",
  drawingpower: "Working-capital drawing power · borrowing base · deterministic + advisory · ledger unchanged",
  notifications: "Outbound notification outbox · EMAIL_TEMPLATE-rendered · SYSTEM · idempotent",
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
  // Auth: token + logged-in identity. The token is restored into the api client on
  // boot; without one the app renders the Login screen.
  const [token, setToken] = useState<string | null>(() => lsGet("helix.token", "") || null);
  const [actor, setActor] = useState(() => lsGet("helix.actor", "demo.user"));
  if (token) setAuthToken(token);
  const [msg, setMsg] = useState<{ text: string; err?: boolean } | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(() => {
    const stored = lsGet("helix.nav.collapsed", "");
    if (stored) { try { return JSON.parse(stored); } catch { /* fall through to curated default */ } }
    // First-time user (no saved preference): keep the two secondary groups collapsed so
    // fewer options show upfront. A returning user's saved map is honoured verbatim.
    return { "Limits & Portfolio": true, "Configure & Govern": true };
  });
  const [railCollapsed, setRailCollapsed] = useState(() => lsGet("helix.nav.rail", "0") === "1");
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

  const onLogin = useCallback((tok: string, who: string) => {
    setAuthToken(tok);
    setToken(tok);
    setActor(who);
  }, []);
  const onLogout = useCallback(() => {
    setAuthToken(null);
    setToken(null);
    lsSet("helix.token", "");
  }, []);
  const toggleGroup = useCallback(
    (title: string) => setCollapsed((c) => ({ ...c, [title]: !c[title] })),
    [],
  );

  // Prefetch the CODE_VALUE catalogue once so dropdowns paint immediately.
  useEffect(() => { prefetchAllCodes(); }, []);

  // Persist UI state so a reload lands you where you left off.
  useEffect(() => { lsSet("helix.view", view); }, [view]);
  useEffect(() => { lsSet("helix.ref", ref ?? ""); }, [ref]);
  useEffect(() => { lsSet("helix.actor", actor); }, [actor]);
  useEffect(() => { if (token) lsSet("helix.token", token); }, [token]);
  useEffect(() => { lsSet("helix.nav.collapsed", JSON.stringify(collapsed)); }, [collapsed]);
  useEffect(() => { lsSet("helix.nav.rail", railCollapsed ? "1" : "0"); }, [railCollapsed]);

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

  const ctx = useMemo(() => ({ actor, notify, nav, aiEnabled, ref }),
                       [actor, notify, nav, aiEnabled, ref]);

  const title = NAV.find((n) => n.key === view)?.label || "Deal Workspace";
  // The active-deal context chip: show whenever a deal is selected but we're not
  // already inside its workspace (one-click way back into the deal you're on).
  const showDealChip = !!ref && view !== "workspace";

  // No token → the front door. Everything behind it requires a verified identity.
  if (!token) return <Login onLogin={onLogin} />;

  return (
    <AppContext.Provider value={ctx}>
      <div className={`app${navOpen ? " nav-open" : ""}${railCollapsed ? " rail-collapsed" : ""}`}>
        <div className="scrim" onClick={() => setNavOpen(false)} />
        <aside className="sidebar">
          <div className="brand">
            <div className="logo">Heli<span>x</span></div>
            <div className="tag">Governed AI for Wholesale Credit</div>
            <button
              className="rail-toggle"
              onClick={() => setRailCollapsed((r) => !r)}
              aria-pressed={railCollapsed}
              aria-label={railCollapsed ? "Expand navigation" : "Collapse navigation"}
              title={railCollapsed ? "Expand navigation" : "Collapse navigation"}
            >
              {railCollapsed ? "»" : "«"}
            </button>
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
                        title={n.label}
                        data-glyph={n.label.charAt(0)}
                      >
                        <span className="dot" /> <span className="nav-label">{n.label}</span>
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
              <div className="topsearch" onClick={() => setCmdkOpen(true)}>
                <span className="ico">⌕</span>
                <input
                  className="topsearch-input"
                  placeholder="Search everything — screens, borrowers, deals, masters…"
                  aria-label="Universal search"
                  readOnly
                  onFocus={(e) => { setCmdkOpen(true); e.currentTarget.blur(); }}
                />
                <kbd>⌘K</kbd>
              </div>
              <div className="gov-chips">
                <AiBadge />
                <HumanBadge />
                <DeterministicBadge label="DETERMINISTIC FIGURES" />
                <PostureChip />
              </div>
              <NotificationBell />
              <div className="topbar-actor" title="Verified identity from your login token — drives every SoD check">
                <span className="ta-label">Signed in</span>
                <span className="mono" style={{ fontWeight: 600 }}>{actor}</span>
                <button className="cmdk-btn" style={{ marginLeft: 8 }} onClick={onLogout}>Logout</button>
              </div>
            </div>
          </div>
          <GovernanceStrip />
          <div className="content">
            {view === "dashboard" && <Dashboard />}
            {view === "deals" && <Deals />}
            {view === "ipnotes" && <IpNotes />}
            {view === "spreading" && <Spreading />}
            {view === "structuring" && <Structuring />}
            {view === "scf" && <Scf />}
            {view === "syndication" && <Syndication />}
            {view === "docintel" && <DocIntel />}
            {view === "counterparties" && <Counterparties />}
            {view === "groups" && <Groups />}
            {view === "cpt" && <Cpt />}
            {view === "limits" && <Limits />}
            {view === "disbursement" && <Disbursement />}
            {view === "cad" && <Cad />}
            {view === "perfection" && <Perfection />}
            {view === "docgen" && <DocGen />}
            {view === "commentary" && <Commentary />}
            {view === "committee" && <Committee />}
            {view === "coi" && <Coi />}
            {view === "notings" && <Notings />}
            {view === "drawingpower" && <DrawingPower />}
            {view === "notifications" && <Notifications />}
            {view === "pricinglab" && <PricingLab />}
            {view === "monitoring" && <Monitoring />}
            {view === "srm" && <Srm />}
            {view === "monitoringartifacts" && <MonitoringArtifacts />}
            {view === "escrow" && <Escrow />}
            {view === "customer360" && <Customer360 />}
            {view === "risklab" && <RiskLab />}
            {view === "projections" && <Projections />}
            {view === "mis" && <Mis />}
            {view === "reportbuilder" && <ReportBuilder />}
            {view === "workflowtracker" && <WorkflowTracker />}
            {view === "exports" && <Exports />}
            {view === "copilot" && <Copilot />}
            {view === "rulepacks" && <RulePacks />}
            {view === "governance" && <Governance />}
            {view === "masters" && <Masters />}
            {view === "modelbuilder" && <ModelBuilder />}
            {view === "audit" && <AuditLog />}
            {view === "workspace" && ref && <DealWorkspace reference={ref} />}
          </div>
        </main>
      </div>
      <CommandPalette open={cmdkOpen} onClose={() => setCmdkOpen(false)} screens={SCREENS} onPick={nav} />
      <CopilotDock reference={ref} />
      <Toast msg={msg} onClose={() => setMsg(null)} />
    </AppContext.Provider>
  );
}
