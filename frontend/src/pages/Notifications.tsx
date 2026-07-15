import { useState } from "react";
import { fmt, notifications } from "../api";
import { Badge, Card, DeterministicBadge, Field, statusTone, useAsync } from "../ui";

// The outbound notification outbox (G5-notify) — auto-present on every service.
const SERVICES = ["decision", "portfolio", "counterparty", "config", "origination", "risk", "limits", "workflow"];

export default function Notifications() {
  const [svc, setSvc] = useState("decision");
  const [status, setStatus] = useState("");
  const [eventType, setEventType] = useState("");
  const [open, setOpen] = useState<any>(null);

  const { data, loading, error } = useAsync(
    () => notifications.list(svc, { status: status || undefined, eventType: eventType || undefined }),
    [svc, status, eventType]);

  return (
    <div className="stack">
      <Card title="Notification outbox"
        sub="Governed outbound lane behind EMAIL_TEMPLATE. The default transport records only — the rendered, idempotent row IS the deliverable (a real SMTP/webhook is a drop-in). SYSTEM-stamped, additive to the audit trail."
        right={<DeterministicBadge label="SYSTEM · OUTBOX" />}>
        <div className="btnrow" style={{ marginBottom: 10, flexWrap: "wrap" }}>
          {SERVICES.map((s) => (
            <button key={s} className={`btn ${svc === s ? "" : "subtle"}`} onClick={() => setSvc(s)}>{s}</button>
          ))}
        </div>
        <div className="btnrow" style={{ marginBottom: 10, alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Status">
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All</option><option>SENT</option><option>PENDING</option><option>FAILED</option><option>SUPPRESSED</option>
            </select>
          </Field>
          <Field label="Event type"><input value={eventType} onChange={(e) => setEventType(e.target.value)} placeholder="e.g. COVENANT_BREACH, REKYC_DUE" /></Field>
        </div>
        {error && <div className="alert err">{error}</div>}
        {loading ? <div className="loading">Loading…</div> : (
          <div className="table-scroll">
          <table>
            <thead><tr><th>When</th><th>Event</th><th>Subject</th><th>Recipients</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {(data || []).map((n: any) => (
                <tr key={n.id}>
                  <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(n.createdAt)}</td>
                  <td className="mono">{n.eventType}</td>
                  <td>{n.subjectType} {n.subjectRef}</td>
                  <td>{(n.recipientRoles || []).join(", ") || "—"}</td>
                  <td><Badge kind={statusTone(n.status)}>{n.status}</Badge></td>
                  <td><button className="btn subtle" onClick={() => setOpen(open?.id === n.id ? null : n)}>{open?.id === n.id ? "Hide" : "View"}</button></td>
                </tr>
              ))}
              {(data || []).length === 0 && <tr><td colSpan={6} className="muted">No notifications for {svc}.</td></tr>}
            </tbody>
          </table>
          </div>
        )}
      </Card>

      {open && (
        <Card title={open.renderedSubject || open.eventType} sub={`${open.channel} · ${open.transport} · ${open.templateKey || "raw"}`}>
          <div style={{ whiteSpace: "pre-wrap" }} dangerouslySetInnerHTML={{ __html: open.renderedBody || "" }} />
        </Card>
      )}
    </div>
  );
}
