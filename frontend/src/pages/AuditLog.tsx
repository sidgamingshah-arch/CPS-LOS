import { useState } from "react";
import { audit, fmt, type AuditQuery } from "../api";
import { AiBadge, Badge, Button, Card, type Col, DataTable, DeterministicBadge, Field, HumanBadge, useAsync } from "../ui";

// G8 — every service that includes helix-common auto-exposes /api/audit + /api/audit/subject.
const SERVICES = ["config", "counterparty", "origination", "risk", "decision", "portfolio",
  "copilot", "limits", "workflow"];
const ACTOR_TYPES = ["ALL", "HUMAN", "AI", "SYSTEM"];

export default function AuditLog() {
  const [svc, setSvc] = useState("risk");
  const [actorType, setActorType] = useState("ALL");
  const [subjType, setSubjType] = useState("");
  const [subjId, setSubjId] = useState("");

  // Server-side filter inputs (draft) + the applied query the fetch actually uses. Applying is
  // explicit (Apply button / Enter) so we don't fire a request on every keystroke.
  const [actorInput, setActorInput] = useState("");
  const [qInput, setQInput] = useState("");
  const [fromInput, setFromInput] = useState("");
  const [toInput, setToInput] = useState("");
  const [applied, setApplied] = useState<AuditQuery>({});

  const apply = () =>
    setApplied({
      actor: actorInput.trim() || undefined,
      q: qInput.trim() || undefined,
      from: fromInput.trim() || undefined,
      to: toInput.trim() || undefined,
    });
  const clear = () => {
    setActorInput(""); setQInput(""); setFromInput(""); setToInput("");
    setApplied({});
  };
  const onEnter = (e: { key: string }) => { if (e.key === "Enter") apply(); };
  const appliedKey = JSON.stringify(applied);
  const hasFilters = !!(applied.actor || applied.q || applied.from || applied.to);

  // A populated (type + id) swaps the source to the subject trail — the examiner
  // "show me everything touching subject X" view; otherwise the recent per-service window,
  // now passing the server-side actor / free-text / date filters.
  const bySubject = subjType.trim() !== "" && subjId.trim() !== "";
  const { data, loading, error } = useAsync(
    () => (bySubject ? audit.subject(svc, subjType.trim(), subjId.trim()) : audit.recent(svc, applied)),
    [svc, bySubject, subjType, subjId, appliedKey]);

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

      {/* Server-side filters — actor (user-name contains) + free-text (counterparty name, since names
          appear in summaries) + a date window. Applied on Enter / Apply so typing stays snappy. */}
      <div className="btnrow" style={{ marginBottom: 10, alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
        <Field label="User (actor)" hint="Matches the acting user-name (contains)">
          <input value={actorInput} onChange={(e) => setActorInput(e.target.value)} onKeyDown={onEnter}
            placeholder="e.g. credit.officer" />
        </Field>
        <Field label="Counterparty / text" hint="Free-text over summary · subject · actor">
          <input value={qInput} onChange={(e) => setQInput(e.target.value)} onKeyDown={onEnter}
            placeholder="e.g. Meridian Steel / APP-123" />
        </Field>
        <Field label="From">
          <input type="date" value={fromInput} onChange={(e) => setFromInput(e.target.value)} onKeyDown={onEnter} />
        </Field>
        <Field label="To">
          <input type="date" value={toInput} onChange={(e) => setToInput(e.target.value)} onKeyDown={onEnter} />
        </Field>
        <div className="btnrow">
          <Button onClick={apply}>Apply filters</Button>
          {hasFilters && <Button kind="subtle" onClick={clear}>Clear</Button>}
        </div>
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
      {bySubject
        ? <div className="sub" style={{ marginBottom: 8 }}>Showing the append-only trail for <b>{subjType} {subjId}</b> on <b>{svc}</b>.</div>
        : hasFilters && <div className="sub" style={{ marginBottom: 8 }}>Server-side filtered on <b>{svc}</b>{applied.actor ? ` · actor “${applied.actor}”` : ""}{applied.q ? ` · text “${applied.q}”` : ""}{applied.from ? ` · from ${applied.from}` : ""}{applied.to ? ` · to ${applied.to}` : ""}.</div>}
      {error && <div className="alert err">{error}</div>}
      <DataTable
        id="audit-log"
        initialSort={{ key: "occurredAt", dir: "desc" }}
        columns={cols}
        rows={rows}
        rowKey={(e) => String(e.id)}
        initialPageSize={50}
        empty={loading ? <div className="loading">Loading…</div> : <div className="muted">No matching events for {svc}.</div>}
      />
    </Card>
  );
}
