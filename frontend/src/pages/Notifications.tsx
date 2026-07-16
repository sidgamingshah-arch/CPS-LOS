import { useState } from "react";
import { fmt, notifications } from "../api";
import { Badge, Card, type Col, DataTable, DeterministicBadge, Field, statusTone, useAsync } from "../ui";

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

  const cols: Col<any>[] = [
    {
      key: "createdAt", header: "When", width: "160px",
      render: (n) => <span className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(n.createdAt)}</span>,
      value: (n) => n.createdAt ?? "",
    },
    { key: "eventType", header: "Event", render: (n) => <span className="mono">{n.eventType}</span> },
    {
      key: "subject", header: "Subject",
      render: (n) => <>{n.subjectType} {n.subjectRef}</>,
      value: (n) => `${n.subjectType ?? ""} ${n.subjectRef ?? ""}`,
    },
    {
      key: "recipients", header: "Recipients",
      render: (n) => (n.recipientRoles || []).join(", ") || "—",
      value: (n) => (n.recipientRoles || []).join(", "),
    },
    {
      key: "status", header: "Status",
      render: (n) => <Badge kind={statusTone(n.status)}>{n.status}</Badge>, value: (n) => n.status ?? "",
    },
    {
      key: "read", header: "Read",
      render: (n) => n.readAt
        ? <Badge kind="ok" >Read</Badge>
        : <Badge kind="warn">Unread</Badge>,
      value: (n) => (n.readAt ? "Read" : "Unread"),
    },
    {
      key: "_view", header: "", sortable: false, filterable: false, csv: false,
      render: (n) => (
        <button className="btn subtle" onClick={() => setOpen(open?.id === n.id ? null : n)}>
          {open?.id === n.id ? "Hide" : "View"}
        </button>
      ),
    },
  ];

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
        <DataTable
          id="notifications"
          columns={cols}
          rows={data || []}
          rowKey={(n) => String(n.id)}
          empty={loading ? <div className="loading">Loading…</div> : <div className="muted">No notifications for {svc}.</div>}
        />
      </Card>

      {open && (
        <Card title={open.renderedSubject || open.eventType}
          sub={`${open.channel} · ${open.transport} · ${open.templateKey || "raw"}` +
            (open.readAt ? ` · read by ${open.readBy || "—"} on ${fmt.dateTime(open.readAt)}` : " · unread")}>
          <div style={{ whiteSpace: "pre-wrap" }} dangerouslySetInnerHTML={{ __html: open.renderedBody || "" }} />
        </Card>
      )}
    </div>
  );
}
