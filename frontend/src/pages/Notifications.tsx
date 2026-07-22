import { useState } from "react";
import { fmt, notifications } from "../api";
import { useApp } from "../app-context";
import { Badge, Card, type Col, DataTable, DeterministicBadge, Field, HumanBadge, statusTone, useAsync } from "../ui";

// The outbound notification outbox (G5-notify) — auto-present on every service.
const SERVICES = ["decision", "portfolio", "counterparty", "config", "origination", "risk", "limits", "workflow"];

// The raw one-time token/link ride ONLY on the transient carriers of the enqueue response (the
// persisted row keeps only the hashes), so they are present the moment a notification is minted but
// never returned by a later GET of the shared outbox — that is the SoD guarantee. This helper reads
// them if present (top-level transient, or legacy vars) and returns null otherwise.
function tokenOf(n: any, kind: "approve" | "reject"): string | null {
  const v = n?.vars || {};
  const direct = kind === "approve" ? (n?.approveToken ?? v.approveToken) : (n?.rejectToken ?? v.rejectToken);
  if (direct) return String(direct);
  const link = kind === "approve" ? (n?.approveLink ?? v.approveLink) : (n?.rejectLink ?? v.rejectLink);
  if (link) { const parts = String(link).split("/"); return parts[parts.length - 1] || null; }
  return null;
}

export default function Notifications() {
  const { actor, notify } = useApp();
  const [svc, setSvc] = useState("decision");
  const [status, setStatus] = useState("");
  const [eventType, setEventType] = useState("");
  const [open, setOpen] = useState<any>(null);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);

  const { data, loading, error, reload } = useAsync(
    () => notifications.list(svc, { status: status || undefined, eventType: eventType || undefined }),
    [svc, status, eventType]);

  async function act(row: any, kind: "approve" | "reject") {
    const token = tokenOf(row, kind);
    if (!token) { notify("This notification carries no action link", true); return; }
    setBusy(true);
    try {
      const updated = await notifications.action(svc, token, comment, actor);
      notify(`Notification ${kind === "approve" ? "APPROVED" : "REJECTED"} — recorded as a human decision`);
      setComment("");
      setOpen(updated);
      reload();
    } catch (e: any) {
      notify(e.message || "Action failed", true);
    } finally {
      setBusy(false);
    }
  }

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
      key: "action", header: "Approval",
      render: (n) => n.actionState
        ? <Badge kind={n.actionState === "APPROVED" ? "ok" : n.actionState === "REJECTED" ? "err" : "warn"}>{n.actionState}</Badge>
        : <span className="muted">—</span>,
      value: (n) => n.actionState ?? "",
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
        <button className="btn subtle" onClick={() => { setOpen(open?.id === n.id ? null : n); setComment(""); }}>
          {open?.id === n.id ? "Hide" : "View"}
        </button>
      ),
    },
  ];

  const isApproval = open && open.actionState;
  const decided = isApproval && open.actionState !== "PENDING";

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
          right={isApproval ? <HumanBadge label="APPROVE / REJECT · HUMAN-GATED" /> : undefined}
          sub={`${open.channel} · ${open.transport} · ${open.templateKey || "raw"}` +
            (open.readAt ? ` · read by ${open.readBy || "—"} on ${fmt.dateTime(open.readAt)}` : " · unread")}>
          <div style={{ whiteSpace: "pre-wrap" }} dangerouslySetInnerHTML={{ __html: open.renderedBody || "" }} />

          {isApproval && (
            <div className="notif-action">
              {decided ? (
                <div className="alert ok">
                  <b>{open.actionState}</b> by {open.actionActor || "—"}
                  {open.actionDecidedAt ? ` on ${fmt.dateTime(open.actionDecidedAt)}` : ""}
                  {open.actionComment ? ` — "${open.actionComment}"` : ""}
                  <div className="muted" style={{ marginTop: 4 }}>
                    Single-use: the approve/reject links are now spent. Recorded as a HUMAN decision in the audit trail.
                  </div>
                </div>
              ) : (tokenOf(open, "approve") || tokenOf(open, "reject")) ? (
                <>
                  <Field label="Comment (recorded with the decision)">
                    <input value={comment} onChange={(e) => setComment(e.target.value)}
                      placeholder="Optional note attached to the approve/reject" />
                  </Field>
                  <div className="btnrow" style={{ marginTop: 8, gap: 8 }}>
                    <button className="btn" disabled={busy} onClick={() => act(open, "approve")}>Approve</button>
                    <button className="btn danger" disabled={busy} onClick={() => act(open, "reject")}>Reject</button>
                  </div>
                  <div className="muted" style={{ marginTop: 6 }}>
                    One-time, single-use action links. Acting as <span className="mono">{actor}</span>.
                  </div>
                </>
              ) : (
                <div className="muted">
                  Approve / reject links are delivered out-of-band to the addressed recipient (e.g. by email)
                  and are never exposed in the shared outbox — possession of the one-time link is the SoD
                  credential. The decision is recorded via that emailed link and appears here once made.
                </div>
              )}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
