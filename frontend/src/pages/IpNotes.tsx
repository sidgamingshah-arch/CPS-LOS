import { useState } from "react";
import { fmt, ipNotes } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, Col, DataTable, EmptyState, Field, HumanBadge, statusTone, useAsync,
} from "../ui";

/**
 * IP Notes — In-Principle sponsorship notes that PRECEDE a full application. An RM raises a
 * lightweight note for a prospect / obligor with the proposed structure, routes it for a
 * named-human credit sign-off (SoD + authority gated), and once APPROVED converts it into a
 * real LoanApplication via the existing origination path. The note is authoritative AS A
 * RECORD — it never mutates a limit, exposure, rating or price. Every action is human-gated.
 */

const SEGMENTS = ["MID_CORPORATE", "LARGE_CORPORATE", "SME", "FINANCIAL_INSTITUTION", "NBFC"];
const FACILITY_TYPES = ["TERM_LOAN", "WORKING_CAPITAL", "OVERDRAFT", "LC_LINE", "GUARANTEE", "REVOLVING"];
const JURISDICTIONS = ["IN-RBI", "AE-CBUAE"];

export default function IpNotes() {
  const { actor, notify } = useApp();
  const list = useAsync(() => ipNotes.list(), []);
  const [selected, setSelected] = useState<string | null>(null);

  const reload = () => { list.reload(); };

  const columns: Col<any>[] = [
    { key: "ipNoteRef", header: "Ref", render: (r) => <span className="mono">{r.ipNoteRef}</span> },
    { key: "counterpartyName", header: "Prospect / Obligor" },
    { key: "facilityType", header: "Facility" },
    {
      key: "proposedAmount", header: "Proposed", align: "right",
      value: (r) => r.proposedAmount || 0,
      render: (r) => <span className="mono">{fmt.moneyFull(r.proposedAmount, r.currency)}</span>,
    },
    { key: "raisedBy", header: "Raised by" },
    {
      key: "status", header: "Status",
      render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge>,
    },
    { key: "applicationRef", header: "Application", render: (r) => r.applicationRef
        ? <span className="mono">{r.applicationRef}</span> : <span className="muted">—</span> },
    { key: "createdAt", header: "Created", value: (r) => r.createdAt || "", render: (r) => fmt.dateTime(r.createdAt) },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="In-Principle Notes"
          right={<HumanBadge label="HUMAN DECISION RECORD" />}
          sub="Sponsorship notes that precede the full application — routed for credit sign-off, then converted."
        >
          <div className="ipnote-note">
            An IP note captures the prospect and proposed structure, routes for a named-human sign-off
            (approver ≠ raiser, authority-gated), and on approval <b>converts</b> into a real application.
            It never writes to a limit, exposure, rating or price.
          </div>
          {(list.data || []).length === 0 ? (
            <EmptyState glyph="◲" title="No IP notes yet" sub="Raise one with the form below." />
          ) : (
            <DataTable
              id="ip-notes"
              initialSort={{ key: "createdAt", dir: "desc" }}
              columns={columns}
              rows={list.data || []}
              rowKey={(r) => r.ipNoteRef}
              onRowClick={(r) => setSelected(r.ipNoteRef)}
              initialPageSize={10}
            />
          )}
        </Card>
        <CreateIpNote actor={actor} notify={notify}
          onDone={(ref) => { reload(); setSelected(ref); }} />
      </div>
      <div>
        {selected ? (
          <IpNoteDetail refId={selected} onChange={reload} />
        ) : (
          <Card>
            <EmptyState glyph="◳" title="Select an IP note"
              sub="Click a row to open its workflow — submit, approve, reject, withdraw or convert." />
          </Card>
        )}
      </div>
    </div>
  );
}

function CreateIpNote({ actor, notify, onDone }: {
  actor: string; notify: (t: string, e?: boolean) => void; onDone: (ref: string) => void;
}) {
  const [counterpartyId, setCounterpartyId] = useState("");
  const [counterpartyRef, setCounterpartyRef] = useState("");
  const [counterpartyName, setCounterpartyName] = useState("");
  const [jurisdiction, setJurisdiction] = useState(JURISDICTIONS[0]);
  const [segment, setSegment] = useState(SEGMENTS[0]);
  const [facilityType, setFacilityType] = useState(FACILITY_TYPES[0]);
  const [proposedAmount, setProposedAmount] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [tenorMonths, setTenorMonths] = useState("60");
  const [purpose, setPurpose] = useState("");
  const [prospectSummary, setProspectSummary] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!counterpartyId.trim() || !counterpartyRef.trim()) { setErr("Counterparty id and reference are required"); return; }
    if (!counterpartyName.trim()) { setErr("Counterparty name is required"); return; }
    if (!(Number(proposedAmount) > 0)) { setErr("Proposed amount must be positive"); return; }
    if (!(Number(tenorMonths) > 0)) { setErr("Tenor must be positive"); return; }
    setErr(null); setBusy(true);
    try {
      const n = await ipNotes.create({
        counterpartyId: Number(counterpartyId),
        counterpartyRef: counterpartyRef.trim(),
        counterpartyName: counterpartyName.trim(),
        jurisdiction, segment, facilityType,
        proposedAmount: Number(proposedAmount),
        currency, tenorMonths: Number(tenorMonths),
        purpose, prospectSummary,
      }, actor);
      notify(`IP note ${n.ipNoteRef} created (DRAFT)`);
      setCounterpartyId(""); setCounterpartyRef(""); setCounterpartyName("");
      setProposedAmount(""); setPurpose(""); setProspectSummary("");
      onDone(n.ipNoteRef);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Raise an IP note" sub="Created as DRAFT under your name; submit to route it for credit sign-off.">
      <Field label="Counterparty id" required hint="Prospect / obligor id from Counterparties"
        error={err && !counterpartyId.trim() ? err : null}>
        <input value={counterpartyId} onChange={(e) => setCounterpartyId(e.target.value)} inputMode="numeric" placeholder="e.g. 12" />
      </Field>
      <Field label="Counterparty reference" required error={err && counterpartyId.trim() && !counterpartyRef.trim() ? err : null}>
        <input value={counterpartyRef} onChange={(e) => setCounterpartyRef(e.target.value)} placeholder="e.g. CP-ABCD1234" />
      </Field>
      <Field label="Counterparty name" required error={err && counterpartyRef.trim() && !counterpartyName.trim() ? err : null}>
        <input value={counterpartyName} onChange={(e) => setCounterpartyName(e.target.value)} placeholder="Legal name" />
      </Field>
      <Field label="Jurisdiction" required>
        <select value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)}>
          {JURISDICTIONS.map((j) => <option key={j} value={j}>{j}</option>)}
        </select>
      </Field>
      <Field label="Segment" required>
        <select value={segment} onChange={(e) => setSegment(e.target.value)}>
          {SEGMENTS.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </Field>
      <Field label="Facility type" required>
        <select value={facilityType} onChange={(e) => setFacilityType(e.target.value)}>
          {FACILITY_TYPES.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
      </Field>
      <Field label="Proposed amount" required hint="Deterministic figure — quoted verbatim, never mutated"
        error={err && counterpartyName.trim() && !(Number(proposedAmount) > 0) ? err : null}>
        <input value={proposedAmount} onChange={(e) => setProposedAmount(e.target.value)} inputMode="numeric" placeholder="e.g. 400000000" />
      </Field>
      <Field label="Currency" required>
        <input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={5} />
      </Field>
      <Field label="Tenor (months)" required>
        <input value={tenorMonths} onChange={(e) => setTenorMonths(e.target.value)} inputMode="numeric" />
      </Field>
      <Field label="Purpose">
        <input value={purpose} onChange={(e) => setPurpose(e.target.value)} placeholder="e.g. Capex" />
      </Field>
      <Field label="Prospect summary">
        <textarea value={prospectSummary} onChange={(e) => setProspectSummary(e.target.value)} rows={3}
          placeholder="Relationship / rationale supporting the in-principle ask" />
      </Field>
      <Button onClick={submit} busy={busy}>Create draft</Button>
    </Card>
  );
}

function IpNoteDetail({ refId, onChange }: { refId: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => ipNotes.get(refId), [refId]);

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  if (view.error) return <Card title="Error"><div className="err">{view.error}</div></Card>;
  const n = view.data;
  const st: string = n.status;

  return (
    <div className="grid">
      <Card title={`IP note · ${n.ipNoteRef}`}
        sub={`${n.counterpartyName} · ${n.counterpartyRef}`}
        right={<Badge kind={statusTone(st)}>{st}</Badge>}>
        <div className="gate">
          <HumanBadge label="HUMAN-GATED" /> Every transition is a named-human action, audited. This note
          never mutates a limit, exposure, rating or price.
        </div>
        <table className="kv">
          <tbody>
            <tr><th>Facility</th><td>{n.facilityType}</td></tr>
            <tr><th>Proposed amount</th><td className="mono">{fmt.moneyFull(n.proposedAmount, n.currency)}</td></tr>
            <tr><th>Tenor</th><td>{n.tenorMonths} months</td></tr>
            <tr><th>Jurisdiction · Segment</th><td>{n.jurisdiction} · {n.segment}</td></tr>
            {n.purpose && <tr><th>Purpose</th><td>{n.purpose}</td></tr>}
            <tr><th>Raised by</th><td>{n.raisedBy}</td></tr>
            {n.approverRole && <tr><th>Authority</th><td>{n.approverRole}</td></tr>}
            {n.decidedBy && <tr><th>Decided by</th><td>{n.decidedBy}{n.decidedAt ? ` · ${fmt.dateTime(n.decidedAt)}` : ""}</td></tr>}
            {n.decisionNote && <tr><th>Note</th><td>{n.decisionNote}</td></tr>}
            {n.applicationRef && <tr><th>Application</th><td className="mono">{n.applicationRef}</td></tr>}
          </tbody>
        </table>
        {n.prospectSummary && <p className="prov">{n.prospectSummary}</p>}
      </Card>

      <Card title="Workflow">
        <div className="btnrow">
          {st === "DRAFT" && (
            <Button onClick={() => run(() => ipNotes.submit(refId, actor), "Submitted for sign-off")}>Submit for approval</Button>
          )}
          {st === "PENDING_APPROVAL" && (
            <Button onClick={() => run(() => ipNotes.approve(refId, undefined, actor), "Approved")}>Approve</Button>
          )}
          {st === "PENDING_APPROVAL" && (
            <button className="btn danger" onClick={() => {
              const reason = window.prompt("Rejection reason (mandatory):");
              if (reason && reason.trim()) run(() => ipNotes.reject(refId, reason.trim(), actor), "Rejected");
              else if (reason !== null) notify("A rejection reason is mandatory", true);
            }}>Reject…</button>
          )}
          {(st === "DRAFT" || st === "PENDING_APPROVAL") && (
            <button className="btn danger" onClick={() => {
              if (window.confirm(`Withdraw IP note ${refId}? Only the raiser can withdraw.`))
                run(() => ipNotes.withdraw(refId, actor), "Withdrawn");
            }}>Withdraw…</button>
          )}
          {st === "APPROVED" && (
            <Button onClick={() => run(() => ipNotes.convert(refId, actor), "Converted to application")}>Convert to application</Button>
          )}
          {st === "CONVERTED" && n.applicationRef && (
            <span className="ipnote-converted mono">→ {n.applicationRef}</span>
          )}
        </div>
        <small className="prov">
          Acting as <b>{actor}</b>. Approval requires a credit-approval authority and a different human than the
          raiser (SoD). Convert reuses the existing application-creation path; the new application carries this note's ref.
          All server-enforced.
        </small>
      </Card>
    </div>
  );
}
