import { useState } from "react";
import { audit } from "../api";
import { Badge, Card, useAsync } from "../ui";

const SERVICES = ["config", "counterparty", "origination", "risk", "decision", "portfolio"];

export default function AuditLog() {
  const [svc, setSvc] = useState("risk");
  const { data, loading, error } = useAsync(() => audit.recent(svc), [svc]);

  return (
    <Card title="Immutable audit trail" sub="Append-only, per-service. Every credit-consequential action is attributed to a named human or a governed AI capability (PRD §9/§13).">
      <div className="btnrow" style={{ marginBottom: 12 }}>
        {SERVICES.map((s) => (
          <button key={s} className={`btn ${svc === s ? "" : "subtle"}`} onClick={() => setSvc(s)}>{s}</button>
        ))}
      </div>
      {error && <div className="alert err">{error}</div>}
      {loading ? <div className="loading">Loading…</div> : (
        <table>
          <thead><tr><th>When</th><th>Actor</th><th>Type</th><th>Event</th><th>Summary</th></tr></thead>
          <tbody>
            {(data || []).map((e: any) => (
              <tr key={e.id}>
                <td className="mono" style={{ whiteSpace: "nowrap" }}>{new Date(e.occurredAt).toLocaleString()}</td>
                <td>{e.actor}</td>
                <td><Badge kind={e.actorType === "HUMAN" ? "ok" : e.actorType === "AI" ? "ai" : "info"}>{e.actorType}</Badge></td>
                <td className="mono">{e.eventType}</td>
                <td>{e.summary}</td>
              </tr>
            ))}
            {(data || []).length === 0 && <tr><td colSpan={5} className="muted">No events yet for {svc}.</td></tr>}
          </tbody>
        </table>
      )}
    </Card>
  );
}
