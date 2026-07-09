import { useState } from "react";
import { audit } from "../api";
import { AiBadge, Badge, Card, DeterministicBadge, Field, HumanBadge, useAsync } from "../ui";

// G8 — every service that includes helix-common auto-exposes /api/audit + /api/audit/subject.
const SERVICES = ["config", "counterparty", "origination", "risk", "decision", "portfolio",
  "copilot", "limits", "workflow"];
const ACTOR_TYPES = ["ALL", "HUMAN", "AI", "SYSTEM"];

export default function AuditLog() {
  const [svc, setSvc] = useState("risk");
  const [actorType, setActorType] = useState("ALL");
  const [q, setQ] = useState("");
  const [subjType, setSubjType] = useState("");
  const [subjId, setSubjId] = useState("");

  // A populated (type + id) swaps the source to the subject trail — the examiner
  // "show me everything touching subject X" view; otherwise the recent per-service window.
  const bySubject = subjType.trim() !== "" && subjId.trim() !== "";
  const { data, loading, error } = useAsync(
    () => (bySubject ? audit.subject(svc, subjType.trim(), subjId.trim()) : audit.recent(svc)),
    [svc, bySubject, subjType, subjId]);

  const rows = (data || []).filter((e: any) => {
    if (actorType !== "ALL" && e.actorType !== actorType) return false;
    if (q.trim()) {
      const hay = `${e.eventType} ${e.summary} ${e.actor}`.toLowerCase();
      if (!hay.includes(q.trim().toLowerCase())) return false;
    }
    return true;
  });

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
        <Field label="Filter (event / summary / actor)">
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="e.g. OVERRIDE, DECISION, cro" />
        </Field>
        <Field label="Subject type">
          <input value={subjType} onChange={(e) => setSubjType(e.target.value)} placeholder="e.g. Application" />
        </Field>
        <Field label="Subject id">
          <input value={subjId} onChange={(e) => setSubjId(e.target.value)} placeholder="e.g. APP-123 / CP-123" />
        </Field>
      </div>
      {bySubject && <div className="sub" style={{ marginBottom: 8 }}>Showing the append-only trail for <b>{subjType} {subjId}</b> on <b>{svc}</b>.</div>}
      {error && <div className="alert err">{error}</div>}
      {loading ? <div className="loading">Loading…</div> : (
        <table>
          <thead><tr><th>When</th><th>Actor</th><th>Type</th><th>Event</th><th>Summary</th></tr></thead>
          <tbody>
            {rows.map((e: any) => (
              <tr key={e.id}>
                <td className="mono" style={{ whiteSpace: "nowrap" }}>{new Date(e.occurredAt).toLocaleString()}</td>
                <td>{e.actor}</td>
                <td><Badge kind={e.actorType === "HUMAN" ? "ok" : e.actorType === "AI" ? "ai" : "info"}>{e.actorType}</Badge></td>
                <td className="mono">{e.eventType}</td>
                <td>{e.summary}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={5} className="muted">No matching events for {svc}.</td></tr>}
          </tbody>
        </table>
      )}
    </Card>
  );
}
