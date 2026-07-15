import React from "react";
import { useApp } from "../app-context";
import {
  tasks, queries, counterparty, origination, ipNotes, notings,
  optimiser, cad, perfection, mer, portfolio, masters, fmt,
} from "../api";
import { Card, Stat, Badge, Button, useAsync } from "../ui";
import { personaFor, personaLabel } from "../role-scope";

/**
 * Role-scoped landing dashboard ("My Workspace"). Composed entirely from existing
 * read-only surfaces + the Wave-1/2 case-management lanes (WorkItem inbox + query
 * inbox), laid out per the acting identity's persona. It holds NO authoritative
 * figures and mutates nothing — every card is fail-soft (an unreachable service
 * degrades that card to empty, never blanks the page).
 *
 * The existing portfolio Dashboard stays one click away (and is the landing for the
 * PORTFOLIO / see-all personas), so nothing is ever hidden from a super-user.
 */
export default function RoleDashboard() {
  const { actor, roles, nav } = useApp();
  const persona = personaFor(actor, roles);

  const isRel = persona === "RELATIONSHIP";
  const isCredit = persona === "CREDIT";
  const isRisk = persona === "RISK";
  const isCad = persona === "CAD";
  const isPortfolio = persona === "PORTFOLIO";
  const isAdmin = persona === "ADMIN";

  const empty = <span className="muted">Nothing here right now.</span>;
  const soft = <T,>(p: Promise<T[]>) => p.catch(() => [] as T[]);
  const none = Promise.resolve([] as any[]);

  // ---- always-on "my work" lanes ----
  const myTasks = useAsync(() => soft(tasks.inbox(actor)), [actor]);
  const myQueries = useAsync(() => soft(queries.list({ addressee: actor })), [actor]);

  // ---- persona-scoped surfaces (fetched only for the active persona) ----
  const cps = useAsync(() => (isRel ? soft(counterparty.list()) : none), [actor, persona]);
  const relDeals = useAsync(() => (isRel || isRisk ? soft(origination.list()) : none), [actor, persona]);
  const notes = useAsync(() => (isRel ? soft(ipNotes.list()) : none), [actor, persona]);

  const pendNotings = useAsync(() => (isCredit ? soft(notings.list({ status: "SUBMITTED" })) : none), [actor, persona]);
  const pendPricing = useAsync(() => (isCredit ? soft(optimiser.pendingExceptions()) : none), [actor, persona]);
  const cadCredit = useAsync(() => (isCredit ? soft(cad.inbox()) : none), [actor, persona]);

  const cadInbox = useAsync(() => (isCad ? soft(cad.inbox()) : none), [actor, persona]);
  const perfCases = useAsync(() => (isCad ? soft(perfection.list()) : none), [actor, persona]);
  const merInbox = useAsync(() => (isCad ? soft(mer.inbox(actor)) : none), [actor, persona]);

  const watch = useAsync(() => (isPortfolio ? soft(portfolio.watchlist()) : none), [actor, persona]);
  const pendMasters = useAsync(() => (isAdmin ? soft(masters.pending()) : none), [actor, persona]);

  const n = (x: any[] | null) => (x ? x.length : 0);
  const dealRef = (r: any) => r?.reference || r?.applicationReference || r?.ref;

  return (
    <div className="grid">
      {/* ---- hero: who am I, what can I do, escape to the full portfolio view ---- */}
      <div className="rolehome-hero">
        <div>
          <div className="rh-eyebrow">MY WORKSPACE</div>
          <h2 className="rh-title">{personaLabel(persona)}</h2>
          <div className="rh-sub">
            Signed in as <b className="mono">{actor}</b>
            {roles && roles.length > 0 && (
              <span className="rh-roles">
                {roles.map((r) => <Badge key={r} kind="info">{r}</Badge>)}
              </span>
            )}
          </div>
        </div>
        <div className="rh-actions">
          <Button kind="subtle" onClick={() => nav("dashboard")}>Portfolio Dashboard →</Button>
          <Button kind="subtle" onClick={() => nav("copilot")}>Ask Copilot</Button>
        </div>
      </div>

      {/* ---- at-a-glance work counts ---- */}
      <div className="grid cols-4">
        <Stat label="My open tasks" value={myTasks.loading ? "…" : n(myTasks.data)} />
        <Stat label="My open queries / RFIs" value={myQueries.loading ? "…" : n(myQueries.data)} />
        <PersonaStat persona={persona}
          credit={n(pendNotings.data) + n(pendPricing.data)}
          cad={n(cadInbox.data) + n(perfCases.data)}
          rel={n(cps.data)} risk={n(relDeals.data)}
          portfolio={n(watch.data)} admin={n(pendMasters.data)} />
        <Stat label="Persona" value={<Badge kind="info">{persona}</Badge>} />
      </div>

      {/* ---- my work: tasks + queries (Wave-1/2 case lanes) ---- */}
      <div className="grid cols-2">
        <ListCard title="My tasks" sub="Case-management work-items assigned to me (workflow-service)."
          loading={myTasks.loading} rows={myTasks.data} empty={empty}
          cols={[
            { head: "Task", cell: (t) => <span className="mono">{t.taskRef}</span> },
            { head: "Type", cell: (t) => <Badge>{t.taskType}</Badge> },
            { head: "Subject", cell: (t) => <span className="mono">{t.subjectRef}</span> },
            { head: "Status", cell: (t) => <Badge kind={t.slaBreached ? "bad" : t.status === "COMPLETED" ? "ok" : "warn"}>{t.status}</Badge> },
          ]}
          onRowClick={(t) => t.subjectRef && nav("workspace", t.subjectRef)} />

        <ListCard title="My queries / RFIs" sub="Collaboration threads addressed to me (awaiting my response)."
          loading={myQueries.loading} rows={myQueries.data} empty={empty}
          cols={[
            { head: "Ref", cell: (q) => <span className="mono">{q.threadRef || q.ref}</span> },
            { head: "Topic", cell: (q) => q.topic || q.subjectType || "—" },
            { head: "Subject", cell: (q) => <span className="mono">{q.subjectRef}</span> },
            { head: "Status", cell: (q) => <Badge kind={q.status === "RESOLVED" ? "ok" : q.status === "OPEN" ? "warn" : "info"}>{q.status}</Badge> },
          ]} />
      </div>

      {/* ---- RELATIONSHIP ---- */}
      {isRel && (
        <div className="grid cols-2">
          <ListCard title="Counterparties" sub="Onboarded & prospect obligors."
            loading={cps.loading} rows={cps.data} empty={empty}
            cols={[
              { head: "Name", cell: (c) => c.name || c.legalName || "—" },
              { head: "Segment", cell: (c) => <Badge>{c.segment || "—"}</Badge> },
              { head: "KYC", cell: (c) => <Badge kind={c.kycStatus === "VERIFIED" ? "ok" : "warn"}>{c.kycStatus || "—"}</Badge> },
            ]}
            onRowClick={() => nav("counterparties")} />
          <ListCard title="Deals pipeline" sub="Applications in origination."
            loading={relDeals.loading} rows={relDeals.data} empty={empty}
            cols={[
              { head: "Reference", cell: (d) => <span className="mono">{dealRef(d)}</span> },
              { head: "Borrower", cell: (d) => d.counterpartyName || d.borrower || "—" },
              { head: "Status", cell: (d) => <Badge>{d.status}</Badge> },
            ]}
            onRowClick={(d) => dealRef(d) && nav("workspace", dealRef(d))} />
          <ListCard title="In-Principle notes" sub="Sponsorship notes that precede a full application."
            loading={notes.loading} rows={notes.data} empty={empty}
            cols={[
              { head: "Ref", cell: (i) => <span className="mono">{i.reference || i.ref}</span> },
              { head: "Counterparty", cell: (i) => i.counterpartyRef || i.counterpartyName || "—" },
              { head: "Status", cell: (i) => <Badge>{i.status}</Badge> },
            ]}
            onRowClick={() => nav("ipnotes")} />
        </div>
      )}

      {/* ---- CREDIT ---- */}
      {isCredit && (
        <div className="grid cols-2">
          <ListCard title="Notings awaiting approval" sub="Governed decision records submitted for sign-off."
            loading={pendNotings.loading} rows={pendNotings.data} empty={empty}
            cols={[
              { head: "Ref", cell: (x) => <span className="mono">{x.reference || x.ref}</span> },
              { head: "Type", cell: (x) => <Badge>{x.type || x.notingType}</Badge> },
              { head: "Subject", cell: (x) => <span className="mono">{x.subjectRef}</span> },
              { head: "Status", cell: (x) => <Badge kind="warn">{x.status}</Badge> },
            ]}
            onRowClick={() => nav("notings")} />
          <ListCard title="Pricing exceptions pending" sub="Concession approvals awaiting an L1/L2 decision."
            loading={pendPricing.loading} rows={pendPricing.data} empty={empty}
            cols={[
              { head: "Deal", cell: (x) => <span className="mono">{x.applicationReference || x.reference}</span> },
              { head: "Requested", cell: (x) => x.requestedSpreadBps != null ? `${x.requestedSpreadBps} bps` : "—" },
              { head: "Status", cell: (x) => <Badge kind="warn">{x.status}</Badge> },
            ]}
            onRowClick={() => nav("pricinglab")} />
          <ListCard title="CAD cases" sub="Credit-administration checklists in flight."
            loading={cadCredit.loading} rows={cadCredit.data} empty={empty}
            cols={[
              { head: "Case", cell: (x) => <span className="mono">{x.reference || x.id}</span> },
              { head: "Subject", cell: (x) => <span className="mono">{x.applicationReference || x.subjectRef || "—"}</span> },
              { head: "Status", cell: (x) => <Badge>{x.status}</Badge> },
            ]}
            onRowClick={() => nav("cad")} />
          <QuickLinks nav={nav} links={[
            ["committee", "Committee Room"], ["risklab", "Risk Lab"],
            ["commentary", "AI Commentary"], ["docgen", "Doc Generation"],
          ]} />
        </div>
      )}

      {/* ---- RISK / ANALYSIS ---- */}
      {isRisk && (
        <div className="grid cols-2">
          <ListCard title="Deals to analyse" sub="Applications in the pipeline for rating & spreading."
            loading={relDeals.loading} rows={relDeals.data} empty={empty}
            cols={[
              { head: "Reference", cell: (d) => <span className="mono">{dealRef(d)}</span> },
              { head: "Borrower", cell: (d) => d.counterpartyName || d.borrower || "—" },
              { head: "Status", cell: (d) => <Badge>{d.status}</Badge> },
            ]}
            onRowClick={(d) => dealRef(d) && nav("workspace", dealRef(d))} />
          <QuickLinks nav={nav} links={[
            ["risklab", "Risk Lab"], ["projections", "Projections"],
            ["pricinglab", "Pricing Lab"], ["spreading", "Financial Spreading"],
          ]} />
        </div>
      )}

      {/* ---- CAD / OPERATIONS ---- */}
      {isCad && (
        <div className="grid cols-2">
          <ListCard title="CAD inbox" sub="Documentation checklists & waivers."
            loading={cadInbox.loading} rows={cadInbox.data} empty={empty}
            cols={[
              { head: "Case", cell: (x) => <span className="mono">{x.reference || x.id}</span> },
              { head: "Subject", cell: (x) => <span className="mono">{x.applicationReference || x.subjectRef || "—"}</span> },
              { head: "Status", cell: (x) => <Badge>{x.status}</Badge> },
            ]}
            onRowClick={() => nav("cad")} />
          <ListCard title="Perfection cases" sub="Security / MOE perfection in progress."
            loading={perfCases.loading} rows={perfCases.data} empty={empty}
            cols={[
              { head: "Ref", cell: (x) => <span className="mono">{x.perfRef || x.reference}</span> },
              { head: "Subject", cell: (x) => <span className="mono">{x.subjectRef || "—"}</span> },
              { head: "Status", cell: (x) => <Badge>{x.status}</Badge> },
            ]}
            onRowClick={() => nav("perfection")} />
          <ListCard title="MER inbox" sub="Deferred documents & renewals I own."
            loading={merInbox.loading} rows={merInbox.data} empty={empty}
            cols={[
              { head: "Ref", cell: (x) => <span className="mono">{x.reference || x.id}</span> },
              { head: "Item", cell: (x) => x.itemType || x.description || "—" },
              { head: "Due", cell: (x) => fmt.date(x.dueDate || x.nextReviewDate) },
              { head: "Status", cell: (x) => <Badge kind={x.status === "OVERDUE" ? "bad" : "warn"}>{x.status}</Badge> },
            ]}
            onRowClick={() => nav("monitoring")} />
          <QuickLinks nav={nav} links={[
            ["limits", "Limits"], ["disbursement", "Disbursement · CPs"], ["drawingpower", "Drawing Power"],
          ]} />
        </div>
      )}

      {/* ---- PORTFOLIO ---- */}
      {isPortfolio && (
        <div className="grid cols-2">
          <ListCard title="Early-warning watchlist" sub="Agentic EWS flags; humans classify & remediate."
            loading={watch.loading} rows={watch.data} empty={empty}
            cols={[
              { head: "Counterparty", cell: (s) => s.counterpartyName || "—" },
              { head: "Signal", cell: (s) => <span className="mono">{s.signalType}</span> },
              { head: "Severity", cell: (s) => <Badge kind={s.severity === "SEVERE" ? "bad" : s.severity === "HIGH" ? "warn" : "info"}>{s.severity}</Badge> },
              { head: "Score", cell: (s) => fmt.num(s.score, 2) },
            ]}
            onRowClick={() => nav("monitoring")} />
          <QuickLinks nav={nav} links={[
            ["dashboard", "Portfolio Dashboard"], ["mis", "MIS · Reports"],
            ["customer360", "Customer-360"], ["exports", "Downstream Exports"],
          ]} />
        </div>
      )}

      {/* ---- ADMIN / SUPER-USER (sees everything; quick links + master queue) ---- */}
      {isAdmin && (
        <div className="grid cols-2">
          <ListCard title="Master approvals pending" sub="Maker-checker queue across the generic master engine."
            loading={pendMasters.loading} rows={pendMasters.data} empty={empty}
            cols={[
              { head: "Type", cell: (m) => <Badge>{m.type || m.masterType}</Badge> },
              { head: "Key", cell: (m) => <span className="mono">{m.recordKey}</span> },
              { head: "Status", cell: (m) => <Badge kind="warn">{m.status}</Badge> },
            ]}
            onRowClick={() => nav("masters")} />
          <QuickLinks nav={nav} links={[
            ["dashboard", "Portfolio Dashboard"], ["rulepacks", "Rule Packs"],
            ["masters", "Master Data"], ["governance", "AI Governance"],
            ["audit", "Audit Trail"], ["workflowtracker", "Workflow Tracker"],
          ]} />
        </div>
      )}
    </div>
  );
}

/* ---- a lightweight, fail-soft list card (no persisted DataTable state needed here) ---- */
type MiniCol<T> = { head: string; cell: (row: T) => React.ReactNode };
function ListCard<T extends { id?: any }>(props: {
  title: string; sub?: string; loading: boolean; rows: T[] | null;
  cols: MiniCol<T>[]; empty: React.ReactNode; onRowClick?: (row: T) => void;
}) {
  const { title, sub, loading, rows, cols, empty, onRowClick } = props;
  const data = (rows || []).slice(0, 12);
  return (
    <Card title={title} sub={sub}>
      {loading ? <div className="loading">Loading…</div> :
        data.length === 0 ? <div className="muted">{empty}</div> : (
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr>{cols.map((c) => <th key={c.head}>{c.head}</th>)}</tr></thead>
              <tbody>
                {data.map((r, i) => (
                  <tr key={r.id ?? i} onClick={onRowClick ? () => onRowClick(r) : undefined}
                      style={onRowClick ? { cursor: "pointer" } : undefined}>
                    {cols.map((c) => <td key={c.head}>{c.cell(r)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
            {(rows || []).length > 12 && (
              <div className="table-more"><span>showing 12 of {(rows || []).length}</span></div>
            )}
          </div>
        )}
    </Card>
  );
}

/** A row of quick-jump buttons into the screens most relevant to a persona. */
function QuickLinks({ nav, links }: { nav: (v: string) => void; links: [string, string][] }) {
  return (
    <Card title="Quick links" sub="Jump straight to the screens you use most.">
      <div className="btnrow" style={{ flexWrap: "wrap" }}>
        {links.map(([key, label]) => (
          <Button key={key} kind="subtle" onClick={() => nav(key)}>{label}</Button>
        ))}
      </div>
    </Card>
  );
}

/** The persona-specific headline count in the KPI row. */
function PersonaStat(props: {
  persona: string; credit: number; cad: number; rel: number; risk: number; portfolio: number; admin: number;
}) {
  switch (props.persona) {
    case "CREDIT": return <Stat label="Awaiting my decision" value={props.credit} />;
    case "CAD": return <Stat label="Open CAD / perfection cases" value={props.cad} />;
    case "RELATIONSHIP": return <Stat label="My counterparties" value={props.rel} />;
    case "RISK": return <Stat label="Deals to analyse" value={props.risk} />;
    case "PORTFOLIO": return <Stat label="Open EWS signals" value={props.portfolio} />;
    default: return <Stat label="Master approvals pending" value={props.admin} />;
  }
}
