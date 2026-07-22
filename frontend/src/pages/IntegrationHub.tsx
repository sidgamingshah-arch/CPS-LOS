/**
 * Integration Hub — one console over the platform's integration surface.
 *
 * Three read-only panels, honestly framed:
 *   1. Inbound connectors  — the canonical ingestion contract (com.helix.common.ingest):
 *      Credit Bureau / CRM / Sanctions-Screening / Core-Banking. Activity is sourced
 *      from the append-only audit trail (counterparty + portfolio services), never a
 *      bespoke connector-status store.
 *   2. Outbound feeds      — the symmetric export contract (com.helix.common.export):
 *      ERM / Finance-GL / CPR / CRILC idempotent batches, reusing the exports client.
 *   3. Event / activity     — the immutable audit stream filtered to SYSTEM + ingest +
 *      export events. Labelled honestly: an append-only audit stream, NOT a message bus.
 *
 * Nothing here mutates an authoritative figure; ingest is advisory input and every
 * export is idempotent per as-of day.
 */
import { useMemo } from "react";
import { audit, exports as feeds, fmt } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, type Col, DataTable, DeterministicBadge, EmptyState, Stat, useAsync,
} from "../ui";

type AuditEvt = {
  id: number;
  service?: string;
  actor?: string;
  actorType?: string;
  eventType?: string;
  subjectType?: string;
  subjectId?: string;
  summary?: string;
  detail?: Record<string, any>;
  occurredAt?: string;
  /** Endpoint (gateway prefix) this row was fetched from — set client-side. */
  _endpoint?: string;
};

type ExportBatch = {
  id: number;
  destination: string;
  feedType: string;
  asOf: string;
  recordCount: number;
  status: string;
  generatedBy: string;
  createdAt: string;
};

type Connector = {
  key: string;
  kind: string;
  /** Gateway prefix whose /api/audit carries this connector's activity. */
  svc: string;
  /** eventType regex identifying this connector's ingest activity. */
  match: RegExp;
  blurb: string;
};

// The four reference adapters that sit behind the canonical ingestion contract. Each is
// tied to an eventType family stamped on the append-only trail when a feed is ingested.
const CONNECTORS: Connector[] = [
  {
    key: "bureau", kind: "Credit Bureau", svc: "counterparty", match: /BUREAU/,
    blurb: "PD score & tradeline pull — advisory input; no authoritative figure moves.",
  },
  {
    key: "crm", kind: "CRM", svc: "counterparty", match: /CRM/,
    blurb: "Relationship & obligor master pull from the front-office CRM.",
  },
  {
    key: "screening", kind: "Sanctions / Screening", svc: "counterparty", match: /SCREENING/,
    blurb: "Sanctions, PEP & adverse-media hits — every hit is human-dispositioned.",
  },
  {
    key: "cbs", kind: "Core Banking (CBS)", svc: "portfolio", match: /CORE_BANKING/,
    blurb: "Value-dated conduct & drawn-balance feed from the servicing system.",
  },
];

// SYSTEM + ingest + export event families for the activity stream.
const EVENT_FAMILY = /INGEST|BUREAU|CRM|SCREENING|CORE_BANKING|EXPORT/;

// A connector is "healthy" (green) if it has ingest activity within this window.
const RECENT_DAYS = 30;
const RECENT_MS = RECENT_DAYS * 24 * 60 * 60 * 1000;

function ts(v?: string): number {
  if (!v) return 0;
  const t = new Date(v).getTime();
  return Number.isNaN(t) ? 0 : t;
}

// Mode is read off the trail (the vendor recorded on ingest), defaulting to the demo's
// simulated reference adapters when nothing live was recorded.
function deriveMode(matched: AuditEvt[]): "Simulated" | "Live" {
  for (const e of matched) {
    const v = String(e.detail?.vendor ?? "").toLowerCase();
    if (v) return v.includes("sim") ? "Simulated" : "Live";
  }
  return "Simulated";
}

function destBadgeKind(dest: string): string {
  if (dest === "ERM") return "info";
  if (dest === "FINANCE_GL") return "ai";
  if (dest === "CPR") return "ok";
  if (dest === "CRILC") return "warn";
  return "";
}

export default function IntegrationHub() {
  const { actor, notify } = useApp();

  // Inbound + activity: the append-only audit trail on the two services that own the
  // ingestion contract. Best-effort — a service being down degrades to an empty panel,
  // never a hard error across the whole hub.
  const activity = useAsync<AuditEvt[]>(async () => {
    const svcs = ["counterparty", "portfolio"];
    const results = await Promise.allSettled(svcs.map((s) => audit.recent(s)));
    const all: AuditEvt[] = [];
    results.forEach((r, i) => {
      if (r.status === "fulfilled" && Array.isArray(r.value)) {
        for (const e of r.value as AuditEvt[]) all.push({ ...e, _endpoint: svcs[i] });
      }
    });
    return all;
  }, []);

  const batches = useAsync<ExportBatch[]>(() => feeds.batches(), []);

  const events: AuditEvt[] = activity.data ?? [];
  const batchList: ExportBatch[] = batches.data ?? [];

  // Per-connector rollup off the trail.
  const connStats = useMemo(() => {
    const now = Date.now();
    return CONNECTORS.map((c) => {
      const matched = events
        .filter((e) => e._endpoint === c.svc && c.match.test(e.eventType || ""))
        .sort((a, b) => ts(b.occurredAt) - ts(a.occurredAt));
      const latest = matched.length ? ts(matched[0].occurredAt) : 0;
      const healthy = matched.length > 0 && now - latest <= RECENT_MS;
      return {
        conn: c,
        count: matched.length,
        subjects: new Set(matched.map((e) => e.subjectId).filter(Boolean)).size,
        latestAt: matched.length ? matched[0].occurredAt : undefined,
        latestEvent: matched.length ? matched[0].eventType : undefined,
        healthy,
        mode: deriveMode(matched),
      };
    });
  }, [events]);

  const inboundTotal = connStats.reduce((s, c) => s + c.count, 0);
  const activeConnectors = connStats.filter((c) => c.healthy).length;
  const recordsExported = batchList.reduce((s, b) => s + (b.recordCount || 0), 0);

  // Event / activity stream: SYSTEM actor OR an ingest/export event family, newest first.
  const streamRows = useMemo(
    () =>
      events
        .filter((e) => e.actorType === "SYSTEM" || EVENT_FAMILY.test(e.eventType || ""))
        .sort((a, b) => ts(b.occurredAt) - ts(a.occurredAt)),
    [events],
  );

  const generate = async (fn: (a: string) => Promise<ExportBatch>, label: string) => {
    try {
      const batch = await fn(actor);
      notify(`${label} — ${batch.recordCount} record(s)`);
      batches.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const batchCols: Col<ExportBatch>[] = [
    { key: "id", header: "ID", width: "64px", render: (b) => <span className="mono">{b.id}</span>, value: (b) => b.id },
    { key: "destination", header: "Destination", render: (b) => <Badge kind={destBadgeKind(b.destination)}>{b.destination}</Badge>, value: (b) => b.destination ?? "" },
    { key: "feedType", header: "Feed Type", render: (b) => <span className="mono">{b.feedType}</span>, value: (b) => b.feedType ?? "" },
    { key: "asOf", header: "As-Of", render: (b) => fmt.date(b.asOf), value: (b) => b.asOf ?? "" },
    { key: "recordCount", header: "Records", align: "right", render: (b) => b.recordCount, value: (b) => b.recordCount ?? 0 },
    { key: "status", header: "Status", render: (b) => <Badge kind={b.status === "DELIVERED" ? "ok" : "info"}>{b.status}</Badge>, value: (b) => b.status ?? "" },
    { key: "createdAt", header: "Created At", render: (b) => fmt.dateTime(b.createdAt), value: (b) => b.createdAt ?? "" },
  ];

  const streamCols: Col<AuditEvt>[] = [
    {
      key: "occurredAt", header: "When", width: "170px",
      render: (e) => <span className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(e.occurredAt)}</span>,
      value: (e) => e.occurredAt ?? "",
    },
    { key: "service", header: "Service", render: (e) => <span className="mono">{e.service ?? e._endpoint ?? "—"}</span>, value: (e) => e.service ?? e._endpoint ?? "" },
    { key: "eventType", header: "Event", render: (e) => <span className="mono">{e.eventType}</span>, value: (e) => e.eventType ?? "" },
    {
      key: "actorType", header: "Actor",
      render: (e) => <Badge kind={e.actorType === "HUMAN" ? "ok" : e.actorType === "AI" ? "ai" : "info"}>{e.actorType}</Badge>,
      value: (e) => e.actorType ?? "",
    },
    {
      key: "subject", header: "Subject",
      render: (e) => (e.subjectType || e.subjectId ? <span className="mono">{[e.subjectType, e.subjectId].filter(Boolean).join(" ")}</span> : <span className="muted">—</span>),
      value: (e) => [e.subjectType, e.subjectId].filter(Boolean).join(" "),
    },
  ];

  return (
    <div className="grid">
      <Card
        title="Integration Hub"
        sub="One console over the platform's integration surface — the canonical ingestion contract (inbound), the symmetric export contract (outbound), and the immutable audit event stream that records both."
        right={<DeterministicBadge label="READ-ONLY CONSOLE" />}
      >
        <div className="ih-note">
          Inbound connectors are governed by <span className="mono">com.helix.common.ingest</span> (Envelope → validate/map →
          idempotency guard). Outbound feeds are governed by the symmetric <span className="mono">com.helix.common.export</span> contract
          (idempotent <span className="mono">ExportBatch</span> per as-of day). Connector activity below is derived from the append-only
          audit trail — nothing here is a live socket, and ingest is advisory input that never moves an authoritative figure.
        </div>
        <div className="ih-stats" style={{ marginTop: 12 }}>
          <Stat label="Inbound events (recent)" value={inboundTotal} />
          <Stat label="Active connectors" value={`${activeConnectors} / ${CONNECTORS.length}`} />
          <Stat label="Outbound batches" value={batchList.length} />
          <Stat label="Records exported" value={recordsExported} />
        </div>
      </Card>

      <Card
        title="Inbound connectors"
        sub="Reference adapters behind the canonical ingestion contract. Health is green when ingest activity is on the trail within the last 30 days, amber otherwise."
      >
        {activity.error && <div className="alert err">{activity.error}</div>}
        <div className="ih-connectors">
          {connStats.map(({ conn, count, subjects, latestAt, latestEvent, healthy, mode }) => (
            <div key={conn.key} className="ih-conn">
              <div className="ih-conn-head">
                <div>
                  <div className="ih-conn-kind">{conn.kind}</div>
                  <div className="ih-conn-svc">{conn.svc}-service · /api/audit</div>
                </div>
                <span className={`ih-health ${healthy ? "green" : "amber"}`}>
                  <span className="dot" aria-hidden="true" />{healthy ? "Active" : "No recent activity"}
                </span>
              </div>
              <div className="ih-conn-blurb">{conn.blurb}</div>
              <div className="ih-conn-grid">
                <div className="ih-conn-fig">
                  <span className="k">Kind</span>
                  <span className="v">Ingestion connector</span>
                </div>
                <div className="ih-conn-fig">
                  <span className="k">Mode</span>
                  <span className="v"><span className={`ih-mode ${mode === "Live" ? "live" : ""}`}>{mode}</span></span>
                </div>
                <div className="ih-conn-fig">
                  <span className="k">Ingest events</span>
                  <span className="v">{count}{subjects > 0 ? ` · ${subjects} subject(s)` : ""}</span>
                </div>
                <div className="ih-conn-fig">
                  <span className="k">Last activity</span>
                  <span className="v">{latestAt ? fmt.dateTime(latestAt) : "—"}</span>
                </div>
              </div>
              {latestEvent && <div className="ih-conn-svc">latest · <span className="mono">{latestEvent}</span></div>}
            </div>
          ))}
        </div>
      </Card>

      <Card
        title="Outbound feeds"
        sub="Canonical downstream feeds — ERM (obligor risk), Finance/GL (provision entries), CPR (portfolio composition), CRILC (regulatory). Each batch is idempotent per as-of day; re-triggering the same day returns the same idempotency key, not a duplicate."
        right={
          <div className="btnrow">
            <Button onClick={() => generate(feeds.erm, "ERM feed")}>Generate ERM</Button>
            <Button onClick={() => generate(feeds.financeGl, "Finance/GL feed")}>Generate Finance/GL</Button>
            <Button onClick={() => generate(feeds.cpr, "CPR feed")}>Generate CPR</Button>
            <Button onClick={() => generate(feeds.crilc, "CRILC feed")}>Generate CRILC</Button>
          </div>
        }
      >
        {batches.loading && <div className="loading">Loading batches…</div>}
        {batches.error && <div className="alert err">{batches.error}</div>}
        {!batches.loading && !batches.error && (
          <DataTable
            id="ih-export-batches"
            columns={batchCols}
            rows={batchList}
            rowKey={(b) => String(b.id)}
            initialPageSize={10}
            empty={
              <EmptyState
                glyph="⇪"
                title="No export batches yet"
                sub="Generate one with the buttons above. The full envelope + records are inspectable on the Downstream Exports screen."
              />
            }
          />
        )}
      </Card>

      <Card
        title="Event layer — append-only audit stream (not a message bus)"
        sub="The immutable audit trail across the counterparty + portfolio services, filtered to SYSTEM, ingest and export events. This is a read-only projection of what already happened — not a live queue or broker."
      >
        {activity.error && <div className="alert err">{activity.error}</div>}
        <DataTable
          id="ih-event-layer"
          columns={streamCols}
          rows={streamRows}
          rowKey={(e) => `${e._endpoint ?? e.service ?? "?"}-${e.id}`}
          initialPageSize={25}
          empty={
            activity.loading
              ? <div className="loading">Loading events…</div>
              : <EmptyState glyph="≋" title="No integration events yet" sub="Run a bureau/CRM pull or generate an export batch to populate the stream." />
          }
        />
      </Card>
    </div>
  );
}
