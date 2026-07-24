import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AppContext, AI_BY_NAV, isNavEnabled } from "./app-context";
import { governance, masters, setAuthToken } from "./api";
import {
  resolveWorkspace, parseRoleWorkspaceMaster, isNavItemInScope,
  type RoleWorkspaceMap,
} from "./role-scope";
import { Toast, AiBadge, HumanBadge, DeterministicBadge, GovernanceStrip } from "./ui";
import CommandPalette from "./CommandPalette";
import CopilotDock from "./CopilotDock";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import RoleDashboard from "./pages/RoleDashboard";
import RulePacks from "./pages/RulePacks";
import Governance from "./pages/Governance";
import Disbursement from "./pages/Disbursement";
import Counterparties from "./pages/Counterparties";
import Deals from "./pages/Deals";
import DealWorkspace from "./pages/DealWorkspace";
import DecisionCockpit from "./pages/DecisionCockpit";
import AuditLog from "./pages/AuditLog";
import Copilot from "./pages/Copilot";
import Mis from "./pages/Mis";
import Customer360 from "./pages/Customer360";
import Cad from "./pages/Cad";
import Perfection from "./pages/Perfection";
import Monitoring from "./pages/Monitoring";
import MonitoringArtifacts from "./pages/MonitoringArtifacts";
import Escrow from "./pages/Escrow";
import GlobalCashflow from "./pages/GlobalCashflow";
import Exceptions from "./pages/Exceptions";
import Srm from "./pages/Srm";
import Limits from "./pages/Limits";
import Masters from "./pages/Masters";
import Structuring from "./pages/Structuring";
import Scf from "./pages/Scf";
import Syndication from "./pages/Syndication";
import SyndicationIm from "./pages/SyndicationIm";
import Portal from "./pages/Portal";
import DocIntel from "./pages/DocIntel";
import RiskLab from "./pages/RiskLab";
import RiskNotes from "./pages/RiskNotes";
import CreditProposal from "./pages/CreditProposal";
import DocCompare from "./pages/DocCompare";
import ApprovalRules from "./pages/ApprovalRules";
import DocGen from "./pages/DocGen";
import Execution from "./pages/Execution";
import Commentary from "./pages/Commentary";
import PricingLab from "./pages/PricingLab";
import Spreading from "./pages/Spreading";
import Exports from "./pages/Exports";
import IntegrationHub from "./pages/IntegrationHub";
import Groups from "./pages/Groups";
import Cpt from "./pages/Cpt";
import WorkflowTracker from "./pages/WorkflowTracker";
import Casework from "./pages/Casework";
import TatReports from "./pages/TatReports";
import ReportBuilder from "./pages/ReportBuilder";
import ModelBuilder from "./pages/ModelBuilder";
import Projections from "./pages/Projections";
import Committee from "./pages/Committee";
import Coi from "./pages/Coi";
import DrawingPower from "./pages/DrawingPower";
import Notifications from "./pages/Notifications";
import Notings from "./pages/Notings";
import Annexures from "./pages/Annexures";
import IpNotes from "./pages/IpNotes";
import BankingAsr from "./pages/BankingAsr";
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
      { key: "home", label: "My Workspace" },
      { key: "dashboard", label: "Portfolio Dashboard" },
      { key: "copilot", label: "Copilot" },
      { key: "portal", label: "Customer Portal" },
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
      { key: "syndicationim", label: "Syndication IM" },
      { key: "spreading", label: "Financial Spreading" },
      { key: "bankingasr", label: "Banking ASR" },
      { key: "docintel", label: "Doc Intelligence" },
    ],
  },
  {
    title: "Assess & Decide",
    items: [
      { key: "cockpit", label: "Decision Cockpit" },
      { key: "risklab", label: "Risk Lab" },
      { key: "risknotes", label: "Risk Notes" },
      { key: "projections", label: "Projections" },
      { key: "pricinglab", label: "Pricing Lab" },
      { key: "cad", label: "CAD · Documentation" },
      { key: "perfection", label: "MOE Perfection" },
      { key: "docgen", label: "Doc Generation" },
      { key: "execution", label: "Document Execution" },
      { key: "commentary", label: "AI Commentary" },
      { key: "creditproposal", label: "Credit Proposal" },
      { key: "annexures", label: "CAM Annexures" },
      { key: "doccompare", label: "Document Compare" },
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
      { key: "globalcashflow", label: "Global Cash-flow" },
      { key: "exceptions", label: "Exceptions · Ticklers" },
      { key: "customer360", label: "Customer-360" },
      { key: "mis", label: "MIS · Reports" },
      { key: "reportbuilder", label: "Ad-hoc Reports" },
      { key: "workflowtracker", label: "Workflow Tracker" },
      { key: "casework", label: "Delegation · Casework" },
      { key: "tatreports", label: "TAT · MIS Reports" },
      { key: "exports", label: "Downstream Exports" },
    ],
  },
  {
    title: "Configure & Govern",
    items: [
      { key: "rulepacks", label: "Jurisdictions & Rule Packs" },
      { key: "masters", label: "Master Data" },
      { key: "modelbuilder", label: "Model Builder" },
      { key: "approvalrules", label: "Approval Rules" },
      { key: "integrationhub", label: "Integration Hub" },
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
  home: "Role-scoped workspace — my tasks, queries, approvals & pipeline",
  dashboard: "Portfolio & book-level intelligence",
  deals: "Origination pipeline",
  spreading: "SpreadJS-style grid · multi-period · cell provenance · override-with-reason gate · ratios",
  bankingasr: "Account statement review · deterministic conduct metrics · advisory narrative (human-confirmed) · never touches a figure",
  structuring: "Specialised CP variants · group · joint/dual-obligor · syndication · FI ICR · renewal copy",
  scf: "Supply-chain finance · anchor programme · deterministic spoke eligibility · PRODUCT_PAPER noting · limit via limit-service",
  syndication: "Syndicate book · fee waterfall · agency reconciliation · participant feed",
  syndicationim: "Information Memorandum · draft → circulate → finalise · versioned · human-gated (finaliser ≠ creator)",
  portal: "External counterparty portal · token-secured RFI response + document upload",
  docintel: "GenAI document intelligence · extraction (human-confirmed) · language · translation · checks",
  counterparties: "Onboarding · KYC/KYB · UBO",
  groups: "Advisory group identification · group insights · combined credit proposal (member figures unchanged)",
  cpt: "Client Planning Template · wallet sizing · cross-sell whitespace · completeness nudges (advisory)",
  limits: "Multi-level limit tree · fungibility · View/Validation/Utilisation APIs",
  disbursement: "Pre-disbursement gate · CP register · drawdown maker-checker · limit-utilise booking",
  cad: "Credit Administration · checklist · waivers/deviations · limit release",
  perfection: "Mortgage / MOE security perfection · ordered role-gated steps · MOE-vetting SoD · vendor RFQ · optional limit-release gate",
  docgen: "Template-driven document generation · clause add/remove/edit · human-confirm gate",
  execution: "Document execution workflow · signatory matrix (INTERNAL/CUSTOMER) · e-sign facade · status stepper · deferral/waiver tags · source document unchanged",
  commentary: "AI narrative commentary · grounded · advisory · human-confirm gate",
  creditproposal: "CAM authoring · format picker · generate (versioned) · side-by-side format compare (non-persisting preview) · figures identical across formats",
  annexures: "CAM annexures · ANNEXURE_TYPE-master-driven · sections materialised + version-pinned · optional advisory AI draft · DRAFT→SUBMITTED→REVIEWED→APPROVED (SoD reviewer/approver≠author) · deal grade/PD/spread untouched",
  doccompare: "Deterministic incremental-change diff · two proposal or document versions · ADDED/REMOVED/CHANGED/UNCHANGED change table · side-by-side · read-only over sources",
  approvalrules: "Scoring-approval policy · visual matrix · first-match-wins routing · simulate · maker-checker save · never touches a figure",
  pricinglab: "Pricing scenario optimiser · goal-seek · advisory (authoritative pricing untouched)",
  monitoring: "Deferred docs · conditions subsequent · renewals · reminders · escalation · DMS feed",
  srm: "Structured review / renewal on the Noting engine · SRM_CHECKLIST · linked SRM_RENEWAL noting · AUTHORIZED advances the MER next-review date",
  monitoringartifacts: "One master-driven lifecycle · call memo/plant visit/LCR/QPR/broker/stock audit/audit note · review→approve→authorize SoD · vendor RFQ · ECL/exposure untouched",
  escrow: "Escrow monitoring · append-only versioned budget lines · category-tagged transactions · deterministic budget-vs-actual + RAG (VALIDATION_PARAMETER) · ECL/exposure/limit untouched",
  globalcashflow: "Global / combined cash-flow · relationship consolidated debt-service across obligor + guarantors + group members · deterministic combined DSCR + per-member contribution · member spreads untouched",
  exceptions: "Unified exception cockpit · read-only rollup of open covenant/MER/CAD/limit/EWS items (best-effort, never mutates a source) · manual ticklers with maker-checker resolve (resolver ≠ owner)",
  customer360: "Borrower 360 · profile · limits · triggers · financials · RAROC · provisioning",
  cockpit: "Approver decision cockpit · one read-first screen · rating · pricing · covenants · exposure · AI summary · sticky decision bar",
  risklab: "Advisory overlays · statistical RAG scoring · macro directional impact (non-binding)",
  risknotes: "Independent risk note · risk-function opinion record · draft → submit → review → approve · reassign / reject / reverse · rating of record never moves",
  projections: "Multi-year proforma · driver assumptions · projected DSCR · sensitivity (advisory)",
  mis: "Composition · RAROC variance · ECL · ageing · watchlist",
  reportbuilder: "Self-service · whitelisted datasets · maker-checker on saved defs · deterministic figures",
  workflowtracker: "Lifecycle from WORKFLOW_DEFINITION pack · humanGate / autonomy guard · SLA",
  casework: "Delegation & casework · inbox + team roll-up · queues + claim · reassign / send-back / complete / withdraw · round-robin & OOO delegation · per-task TAT timeline · server-enforced SoD",
  tatreports: "TAT / MIS over the case & query layer · cycle time · SLA breach · rework · throughput · deterministic",
  exports: "Canonical outbound feeds · ERM · Finance/GL · CPR · idempotent batches",
  copilot: "Scoped, grounded, non-binding assistant",
  rulepacks: "Regulatory abstraction layer",
  masters: "Generic Master-Data engine · maker-checker SoD · 22 master types",
  modelbuilder: "Configure scoring models · sections · typed questions · visibility rules · master-driven options · maker-checker",
  integrationhub: "Integration Hub · inbound connectors (canonical ingestion) · outbound feeds (symmetric export) · append-only audit event stream · read-only",
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
  // The acting identity's roles (from the ACTOR_ROLE master, echoed at login).
  // Restored from localStorage on reload so role-scope survives a refresh.
  const [roles, setRoles] = useState<string[]>(() => {
    try { return JSON.parse(lsGet("helix.roles", "[]")) as string[]; } catch { return []; }
  });
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
  // Config-driven role→workspace overrides from the ROLE_WORKSPACE master (generic
  // master engine). Empty until fetched; role-scope.ts falls back to its baked-in
  // conservative map when this is empty, so the nav works even if the master is absent.
  const [roleWorkspace, setRoleWorkspace] = useState<RoleWorkspaceMap>({});
  // The main content region, focused on view change so keyboard / screen-reader
  // users land on the freshly-rendered screen (skipped on first mount).
  const mainRef = useRef<HTMLElement>(null);
  const didMountRef = useRef(false);

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

  // Pull the config-driven role→workspace map (ROLE_WORKSPACE master) on boot.
  // Best-effort: any failure/empty simply leaves the baked-in fallback map in force.
  useEffect(() => {
    masters.list("ROLE_WORKSPACE")
      .then((recs) => setRoleWorkspace(parseRoleWorkspaceMaster(recs)))
      .catch(() => setRoleWorkspace({}));
  }, []);

  const notify = useCallback((text: string, err?: boolean) => setMsg({ text, err }), []);
  const nav = useCallback((v: string, r?: string) => {
    setView(v);
    if (r !== undefined) setRef(r);
    setNavOpen(false); // close the mobile drawer on navigation
  }, []);

  const onLogin = useCallback((tok: string, who: string, _displayName?: string, rolesArg: string[] = []) => {
    setAuthToken(tok);
    setToken(tok);
    setActor(who);
    setRoles(rolesArg);
    lsSet("helix.roles", JSON.stringify(rolesArg));
    // Route a role-scoped (non-see-all) identity to its landing view. See-all
    // identities (demo / admin / CRO) are left exactly where they were, so the
    // existing demo UX is unchanged.
    const ws = resolveWorkspace(who, rolesArg, roleWorkspace);
    if (!ws.seeAll) setView(ws.landing);
  }, [roleWorkspace]);
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
  useEffect(() => { lsSet("helix.roles", JSON.stringify(roles)); }, [roles]);
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

  // On view change, move focus to the main region so keyboard / screen-reader users
  // land on the new content. Additive: skipped on first mount, and preventScroll keeps
  // the viewport still, so there is no observable change for mouse users.
  useEffect(() => {
    if (!didMountRef.current) { didMountRef.current = true; return; }
    mainRef.current?.focus({ preventScroll: true });
  }, [view]);

  // Resolve the effective role workspace (nav scope + landing). Default-permissive:
  // see-all identities (demo / admin / CRO / unmapped) resolve to "show everything".
  const workspace = useMemo(
    () => resolveWorkspace(actor, roles, roleWorkspace),
    [actor, roles, roleWorkspace],
  );

  // Guard: if a role-scoped user lands on a nav screen outside their scope (e.g. a
  // persisted view after login as a narrower role), bounce them to their landing.
  // Never fires for see-all identities, and Overview screens are always in scope,
  // so the deal workspace + dashboards stay reachable for everyone.
  useEffect(() => {
    if (workspace.seeAll) return;
    const group = NAV_GROUPS.find((g) => g.items.some((n) => n.key === view));
    if (!group) return; // non-nav views (e.g. "workspace") are always allowed
    if (!isNavItemInScope(workspace, group.title, view)) setView(workspace.landing);
  }, [workspace, view]);

  const ctx = useMemo(() => ({ actor, roles, notify, nav, aiEnabled, ref }),
                       [actor, roles, notify, nav, aiEnabled, ref]);

  const title = NAV.find((n) => n.key === view)?.label || "Deal Workspace";
  // The active-deal context chip: show whenever a deal is selected but we're not
  // already inside its workspace (one-click way back into the deal you're on).
  const showDealChip = !!ref && view !== "workspace";

  // No token → the front door. Everything behind it requires a verified identity.
  if (!token) return <Login onLogin={onLogin} />;

  return (
    <AppContext.Provider value={ctx}>
      <div className={`app${navOpen ? " nav-open" : ""}${railCollapsed ? " rail-collapsed" : ""}`}>
        <a href="#main" className="skip-link">Skip to content</a>
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
          <nav className="nav" aria-label="Primary">
            {NAV_GROUPS.map((g) => {
              const isCollapsed = !!collapsed[g.title];
              // Hide AI-disabled screens from the nav. The server-side enforcement
              // (403 forbiddenAutonomy) is the real gate; this just stops users
              // landing on a screen whose primary action wouldn't work. On top of
              // that, layer the ROLE scope (default-permissive: see-all identities
              // pass every item — see role-scope.ts).
              const items = g.items.filter(
                (n) => isNavEnabled(n.key, aiEnabled) && isNavItemInScope(workspace, g.title, n.key),
              );
              if (items.length === 0) return null;
              const groupId = `navgrp-${g.title.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}`;
              return (
                <div key={g.title} className={`nav-group${isCollapsed ? " collapsed" : ""}`}>
                  <button
                    className="nav-group-title"
                    onClick={() => toggleGroup(g.title)}
                    aria-expanded={!isCollapsed}
                    aria-controls={groupId}
                  >
                    <span>{g.title}</span>
                    <span className="chev" aria-hidden="true">▾</span>
                  </button>
                  <div className="nav-group-items" id={groupId}>
                    {items.map((n) => (
                      <button
                        key={n.key}
                        className={`nav-item${view === n.key ? " active" : ""}`}
                        onClick={() => nav(n.key)}
                        title={n.label}
                        data-glyph={n.label.charAt(0)}
                        aria-current={view === n.key ? "page" : undefined}
                      >
                        <span className="dot" aria-hidden="true" /> <span className="nav-label">{n.label}</span>
                      </button>
                    ))}
                  </div>
                </div>
              );
            })}
          </nav>
        </aside>

        <main className="main" id="main" tabIndex={-1} ref={mainRef}>
          <header className="topbar">
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
          </header>
          <GovernanceStrip />
          <div className="content">
            {view === "home" && <RoleDashboard />}
            {view === "dashboard" && <Dashboard />}
            {view === "deals" && <Deals />}
            {view === "ipnotes" && <IpNotes />}
            {view === "bankingasr" && <BankingAsr />}
            {view === "spreading" && <Spreading />}
            {view === "structuring" && <Structuring />}
            {view === "scf" && <Scf />}
            {view === "syndication" && <Syndication />}
            {view === "syndicationim" && <SyndicationIm />}
            {view === "portal" && <Portal />}
            {view === "docintel" && <DocIntel />}
            {view === "counterparties" && <Counterparties />}
            {view === "groups" && <Groups />}
            {view === "cpt" && <Cpt />}
            {view === "limits" && <Limits />}
            {view === "disbursement" && <Disbursement />}
            {view === "cad" && <Cad />}
            {view === "perfection" && <Perfection />}
            {view === "docgen" && <DocGen />}
            {view === "execution" && <Execution />}
            {view === "commentary" && <Commentary />}
            {view === "creditproposal" && <CreditProposal />}
            {view === "annexures" && <Annexures />}
            {view === "doccompare" && <DocCompare />}
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
            {view === "globalcashflow" && <GlobalCashflow />}
            {view === "exceptions" && <Exceptions />}
            {view === "customer360" && <Customer360 />}
            {view === "risklab" && <RiskLab />}
            {view === "risknotes" && <RiskNotes />}
            {view === "projections" && <Projections />}
            {view === "mis" && <Mis />}
            {view === "reportbuilder" && <ReportBuilder />}
            {view === "workflowtracker" && <WorkflowTracker />}
            {view === "casework" && <Casework />}
            {view === "tatreports" && <TatReports />}
            {view === "exports" && <Exports />}
            {view === "integrationhub" && <IntegrationHub />}
            {view === "copilot" && <Copilot />}
            {view === "rulepacks" && <RulePacks />}
            {view === "governance" && <Governance />}
            {view === "masters" && <Masters />}
            {view === "modelbuilder" && <ModelBuilder />}
            {view === "approvalrules" && <ApprovalRules />}
            {view === "audit" && <AuditLog />}
            {view === "cockpit" && <DecisionCockpit />}
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
