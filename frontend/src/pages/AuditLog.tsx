import { useState } from "react";
import { audit, fmt } from "../api";
import { AiBadge, Badge, Card, type Col, DataTable, DeterministicBadge, Field, HumanBadge, useAsync } from "../ui";

// G8 — every service that includes helix-common auto-exposes /api/audit + /api/audit/subject.
const SERVICES = ["config", "counterparty", "origination", "risk", "decision", "portfolio",
  "copilot", "limits", "workflow"];
const ACTOR_TYPES = ["ALL", "HUMAN", "AI", "SYSTEM"];

export default function AuditLog() {
  const [svc, setSvc] = useState("risk");
  const [actorType, setActorType] = useState("ALL");
  const [subjType, setSubjType] = useState("");
  const [subjId, setSubjId] = useState("");

  // A populated (type + id) swaps the source to the subject trail — the examiner
  // "show me everything touching subject X" view; otherwise the recent per-service window.
  const bySubject = subjType.trim() !== "" && subjId.trim() !== "";
  const { data, loading, error } = useAsync(
    () => (bySubject ? audit.subject(svc, subjType.trim(), subjId.trim()) : audit.recent(svc)),
    [svc, bySubject, subjType, subjId]);

  // Actor-type is a quick client-side pre-filter; free-text search + per-column
  // filtering are handled by the DataTable below.
  const rows = (data || []).filter((e: any) => actorType === "ALL" || e.actorType === actorType);

  const cols: Col<any>[] = [
    {
      key: "occurredAt", header: "When", width: "160px",
      render: (e) => <span className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(e.occurredAt)}</span>,
      value: (e) => e.occurredAt ?? "",
    },
    { key: "actor", header: "Actor" },
    {
      key: "actorType", header: "Type",
      render: (e) => <Badge kind={e.actorType === "HUMAN" ? "ok" : e.actorType === "AI" ? "ai" : "info"}>{e.actorType}</Badge>,
      value: (e) => e.actorType ?? "",
    },
    { key: "eventType", header: "Event", render: (e) => <span className="mono">{e.eventType}</span> },
    { key: "summary", header: "Summary" },
  ];

  return (
    <Card
      title="Immutable audit trail"
      sub="Append-only, per-service, all nine services. Every credit-consequential action is attributed to a named human or a governed AI capability (PRD §9/§13)."
      right={<div className="gov-chips"><HumanBadge /><AiBadge /><DeterministicBadge label="SYSTEM" /></div>}
    >
      <div className="btnrow" style={{ marginBottom: 10, flexWrap: "wrap" }}>
        {SERVICES.map((s) => (
          <button key={s} className={`btn ${svc === s ? "" : "subtle"}`} onClick={() => setSvc(s)}>{s}</button>
        ))}
      </div>
      <div className="btnrow" style={{ marginBottom: 10, alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
        <div>
          <div className="lbl" style={{ marginBottom: 4 }}>Actor type</div>
          <div className="btnrow">
            {ACTOR_TYPES.map((a) => (
              <button key={a} className={`btn subtle ${actorType === a ? "" : "ghost"}`} onClick={() => setActorType(a)}>{a}</button>
            ))}
          </div>
        </div>
        <Field label="Subject type">
          <input value={subjType} onChange={(e) => setSubjType(e.target.value)} placeholder="e.g. Application" />
        </Field>
        <Field label="Subject id">
          <input value={subjId} onChange={(e) => setSubjId(e.target.value)} placeholder="e.g. APP-123 / CP-123" />
        </Field>
      </div>
      {bySubject && <div className="sub" style={{ marginBottom: 8 }}>Showing the append-only trail for <b>{subjType} {subjId}</b> on <b>{svc}</b>.</div>}
      {error && <div className="alert err">{error}</div>}
      <DataTable
        id="audit-log"
        columns={cols}
        rows={rows}
        rowKey={(e) => String(e.id)}
        initialPageSize={50}
        empty={loading ? <div className="loading">Loading…</div> : <div className="muted">No matching events for {svc}.</div>}
      />
    </Card>
  );
}
