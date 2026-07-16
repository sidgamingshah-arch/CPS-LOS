import { useMemo, useState } from "react";
import { fmt, masters, notings } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, Col, DataTable, EmptyState, Field, HumanBadge, statusTone, useAsync,
} from "../ui";

/**
 * Notings — governed decision RECORDS (TOD/intraday excess, CAM note, product paper,
 * deferral extension/waiver/closure, second-stage disbursement, SRM renewal). A noting
 * routes for approval (DoA or fixed-role, driven by the NOTING_TYPE master), captures a
 * named human approval + optional CAD authorisation, and can be rejected / reversed /
 * withdrawn. It is authoritative AS A RECORD — it never mutates a limit, exposure,
 * rating or price. Every action is human-gated and audited.
 */

// Conservative fallback list if no NOTING_TYPE master rows are seeded yet.
const FALLBACK_TYPES = [
  "TOD_INTRADAY", "CAM_NOTE", "PRODUCT_PAPER", "DEFERRAL_EXTENSION",
  "DEFERRAL_WAIVER", "DEFERRAL_CLOSURE", "DISB_SECOND_STAGE", "SRM_RENEWAL",
];

const PENDING = ["PENDING_APPROVAL", "PENDING_CAD"];

export default function Notings() {
  const { actor, notify } = useApp();
  const list = useAsync(() => notings.list(), []);
  const typeRows = useAsync(() => masters.list("NOTING_TYPE"), []);
  const [selected, setSelected] = useState<string | null>(null);

  const typeKeys = useMemo(() => {
    const keys = (typeRows.data || []).map((r: any) => r.recordKey).filter(Boolean);
    return keys.length ? keys : FALLBACK_TYPES;
  }, [typeRows.data]);

  const reload = () => { list.reload(); };

  const columns: Col<any>[] = [
    { key: "notingRef", header: "Ref", render: (r) => <span className="mono">{r.notingRef}</span> },
    { key: "notingType", header: "Type" },
    { key: "subjectRef", header: "Subject", render: (r) => <span className="mono">{r.subjectRef}</span> },
    { key: "title", header: "Title" },
    { key: "raisedBy", header: "Raised by" },
    {
      key: "status", header: "Status",
      render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge>,
    },
    { key: "createdAt", header: "Created", value: (r) => r.createdAt || "", render: (r) => fmt.dateTime(r.createdAt) },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="Notings"
          right={<HumanBadge label="HUMAN DECISION RECORD" />}
          sub="Governed decision records — routed for approval, authoritative as a record, never a figure mutation."
        >
          <div className="noting-note">
            A noting routes per its <b>NOTING_TYPE</b> master (DoA or fixed-role) and captures a named human
            approval. It never writes to limits, exposure, ratings or pricing.
          </div>
          {(list.data || []).length === 0 ? (
            <EmptyState glyph="◲" title="No notings yet" sub="Raise one with the form below." />
          ) : (
            <DataTable
              id="notings"
              columns={columns}
              rows={list.data || []}
              rowKey={(r) => r.notingRef}
              onRowClick={(r) => setSelected(r.notingRef)}
              initialPageSize={10}
            />
          )}
        </Card>
        <CreateNoting typeKeys={typeKeys} actor={actor} notify={notify}
          onDone={(ref) => { reload(); setSelected(ref); }} />
      </div>
      <div>
        {selected ? (
          <NotingDetail refId={selected} onChange={reload} />
        ) : (
          <Card>
            <EmptyState glyph="◳" title="Select a noting"
              sub="Click a row to open its workflow — submit, approve, CAD-authorise, reject, reverse or withdraw." />
          </Card>
        )}
      </div>
    </div>
  );
}

function CreateNoting({ typeKeys, actor, notify, onDone }: {
  typeKeys: string[]; actor: string; notify: (t: string, e?: boolean) => void; onDone: (ref: string) => void;
}) {
  const [notingType, setNotingType] = useState(typeKeys[0] || "CAM_NOTE");
  const [subjectRef, setSubjectRef] = useState("");
  const [title, setTitle] = useState("");
  const [narrative, setNarrative] = useState("");
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!subjectRef.trim()) { setErr("Subject reference is required"); return; }
    if (!title.trim()) { setErr("Title is required"); return; }
    setErr(null); setBusy(true);
    try {
      const payload: any = {};
      if (amount.trim()) payload.amount = Number(amount);
      const n = await notings.create(
        { notingType, subjectRef: subjectRef.trim(), title: title.trim(), narrative, payload }, actor);
      notify(`Noting ${n.notingRef} created (DRAFT)`);
      setSubjectRef(""); setTitle(""); setNarrative(""); setAmount("");
      onDone(n.notingRef);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Raise a noting" sub="Created as DRAFT under your name; submit to route it for approval.">
      <Field label="Noting type" required>
        <select value={notingType} onChange={(e) => setNotingType(e.target.value)}>
          {typeKeys.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </Field>
      <Field label="Subject reference" required hint="Application ref / obligor / facility this noting is about"
        error={err && !subjectRef.trim() ? err : null}>
        <input value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)} placeholder="e.g. APP-000123" />
      </Field>
      <Field label="Title" required error={err && subjectRef.trim() && !title.trim() ? err : null}>
        <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Short note title" />
      </Field>
      <Field label="Amount (optional)" hint="Used only for DoA-routed types; deterministic figure — never mutated">
        <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="numeric" placeholder="e.g. 50000000" />
      </Field>
      <Field label="Narrative">
        <textarea value={narrative} onChange={(e) => setNarrative(e.target.value)} rows={3}
          placeholder="Context / justification for the note" />
      </Field>
      <Button onClick={submit} busy={busy}>Create draft</Button>
    </Card>
  );
}

function NotingDetail({ refId, onChange }: { refId: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => notings.get(refId), [refId]);

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
      <Card title={`${n.notingType} · ${n.notingRef}`}
        sub={`Subject ${n.subjectRef}`}
        right={<Badge kind={statusTone(st)}>{st}</Badge>}>
        <div className="gate">
          <HumanBadge label="HUMAN-GATED" /> Every transition is a named-human action, audited. This record
          never mutates a limit, exposure, rating or price.
        </div>
        <table className="kv">
          <tbody>
            <tr><th>Title</th><td>{n.title}</td></tr>
            <tr><th>Raised by</th><td>{n.raisedBy}</td></tr>
            <tr><th>Routing</th><td>{n.routing || "—"}{n.approverRole ? ` → ${n.approverRole}` : ""}</td></tr>
            <tr><th>CAD required</th><td>{n.cadRequired ? "Yes" : "No"}</td></tr>
            {n.approver && <tr><th>Approved by</th><td>{n.approver}{n.decidedAt ? ` · ${fmt.dateTime(n.decidedAt)}` : ""}</td></tr>}
            {n.authorisedBy && <tr><th>CAD-authorised by</th><td>{n.authorisedBy}{n.authorisedAt ? ` · ${fmt.dateTime(n.authorisedAt)}` : ""}</td></tr>}
            {n.decisionNote && <tr><th>Note</th><td>{n.decisionNote}</td></tr>}
            {typeof n.payload?.amount === "number" && <tr><th>Amount (record)</th><td>{fmt.moneyFull(n.payload.amount)}</td></tr>}
          </tbody>
        </table>
        {n.narrative && <p className="prov">{n.narrative}</p>}
      </Card>

      <Card title="Workflow">
        <div className="btnrow">
          {st === "DRAFT" && (
            <Button onClick={() => run(() => notings.submit(refId, actor), "Submitted for approval")}>Submit for approval</Button>
          )}
          {st === "PENDING_APPROVAL" && (
            <Button onClick={() => run(() => notings.approve(refId, undefined, actor), "Approved")}>Approve</Button>
          )}
          {st === "PENDING_CAD" && (
            <Button onClick={() => run(() => notings.cadAuthorize(refId, undefined, actor), "CAD authorised")}>CAD authorise</Button>
          )}
          {PENDING.includes(st) && (
            <button className="btn danger" onClick={() => {
              const reason = window.prompt("Rejection reason (mandatory):");
              if (reason && reason.trim()) run(() => notings.reject(refId, reason.trim(), actor), "Rejected");
              else if (reason !== null) notify("A rejection reason is mandatory", true);
            }}>Reject…</button>
          )}
          {(st === "APPROVED" || st === "AUTHORIZED") && (
            <button className="btn danger" onClick={() => {
              const reason = window.prompt("Reversal reason (mandatory):");
              if (reason && reason.trim()) {
                if (window.confirm(`Reverse noting ${refId}? This records a reversal — it does not change any figure.`))
                  run(() => notings.reverse(refId, reason.trim(), actor), "Reversed");
              } else if (reason !== null) notify("A reversal reason is mandatory", true);
            }}>Reverse…</button>
          )}
          {(st === "DRAFT" || st === "PENDING_APPROVAL") && (
            <button className="btn danger" onClick={() => {
              if (window.confirm(`Withdraw noting ${refId}? Only the raiser can withdraw.`))
                run(() => notings.withdraw(refId, actor), "Withdrawn");
            }}>Withdraw…</button>
          )}
        </div>
        <small className="prov">
          Acting as <b>{actor}</b>. Approval requires the routed authority and a different human than the raiser (SoD);
          CAD authorisation requires a CAD-authority role. All server-enforced.
        </small>
      </Card>
    </div>
  );
}
