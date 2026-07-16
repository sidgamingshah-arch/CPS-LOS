import { useMemo, useState } from "react";
import { fmt, masters, srm } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, Col, DataTable, EmptyState, Field, HumanBadge, statusTone, useAsync,
} from "../ui";

/**
 * SRM — Structured Review / renewal. A renewal decision built ON the Noting engine:
 * creating a review materialises the SRM_CHECKLIST (config-as-data) and raises a linked
 * SRM_RENEWAL noting. The governed approval runs through the Noting workflow; when that
 * noting reaches AUTHORIZED, the subject's MER next-review date is advanced. A review is
 * a RECORD — it never mutates a limit, exposure, rating or price. Every action is
 * human-gated and audited.
 */

export default function Srm() {
  const { actor, notify } = useApp();
  const list = useAsync(() => srm.list(), []);
  const checklistRows = useAsync(() => masters.list("SRM_CHECKLIST"), []);
  const [selected, setSelected] = useState<number | null>(null);

  const checklistKeys = useMemo(
    () => (checklistRows.data || []).map((r: any) => r.recordKey).filter(Boolean),
    [checklistRows.data],
  );

  const reload = () => { list.reload(); };

  const columns: Col<any>[] = [
    { key: "srmRef", header: "Ref", render: (r) => <span className="mono">{r.srmRef}</span> },
    { key: "subjectRef", header: "Subject", render: (r) => <span className="mono">{r.subjectRef}</span> },
    { key: "title", header: "Title" },
    { key: "notingRef", header: "Noting", render: (r) => <span className="mono">{r.notingRef || "—"}</span> },
    {
      key: "notingStatus", header: "Noting status",
      render: (r) => r.notingStatus ? <Badge kind={statusTone(r.notingStatus)}>{r.notingStatus}</Badge> : <>—</>,
    },
    {
      key: "status", header: "SRM status",
      render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge>,
    },
    { key: "createdAt", header: "Created", value: (r) => r.createdAt || "", render: (r) => fmt.dateTime(r.createdAt) },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="Structured reviews (SRM)"
          right={<HumanBadge label="HUMAN DECISION RECORD" />}
          sub="Renewal reviews built on the Noting engine — checklist + a linked SRM_RENEWAL noting; a record, never a figure mutation."
        >
          <div className="srm-note">
            An SRM materialises its checklist from the <b>SRM_CHECKLIST</b> master and delegates the
            governed decision to a <b>SRM_RENEWAL</b> noting. When that noting is <b>AUTHORIZED</b> the
            subject's MER next-review date is advanced. It never writes to limits, exposure, ratings or pricing.
          </div>
          {(list.data || []).length === 0 ? (
            <EmptyState glyph="◲" title="No SRM reviews yet" sub="Open one with the form below." />
          ) : (
            <DataTable
              id="srm"
              columns={columns}
              rows={list.data || []}
              rowKey={(r) => r.id}
              onRowClick={(r) => setSelected(r.id)}
              initialPageSize={10}
            />
          )}
        </Card>
        <CreateSrm checklistKeys={checklistKeys} actor={actor} notify={notify}
          onDone={(id) => { reload(); setSelected(id); }} />
      </div>
      <div>
        {selected != null ? (
          <SrmDetail reviewId={selected} onChange={reload} />
        ) : (
          <Card>
            <EmptyState glyph="◳" title="Select an SRM review"
              sub="Click a row to mark checklist items, submit the renewal noting, and refresh its status." />
          </Card>
        )}
      </div>
    </div>
  );
}

function CreateSrm({ checklistKeys, actor, notify, onDone }: {
  checklistKeys: string[]; actor: string; notify: (t: string, e?: boolean) => void; onDone: (id: number) => void;
}) {
  const [subjectRef, setSubjectRef] = useState("");
  const [subjectType, setSubjectType] = useState("Counterparty");
  const [counterpartyName, setCounterpartyName] = useState("");
  const [checklistKey, setChecklistKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!subjectRef.trim()) { setErr("Subject reference is required"); return; }
    setErr(null); setBusy(true);
    try {
      const r = await srm.create(
        { subjectRef: subjectRef.trim(), subjectType, counterpartyName: counterpartyName.trim() || undefined,
          checklistKey: checklistKey || undefined }, actor);
      notify(`SRM ${r.srmRef} opened — noting ${r.notingRef} raised (DRAFT)`);
      setSubjectRef(""); setCounterpartyName("");
      onDone(r.id);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Open an SRM review" sub="Materialises the checklist and raises a linked SRM_RENEWAL noting under your name.">
      <Field label="Subject reference" required hint="Obligor / facility this renewal is about (also the MER reference)"
        error={err && !subjectRef.trim() ? err : null}>
        <input value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)} placeholder="e.g. CIF-000123 or APP-000123" />
      </Field>
      <Field label="Subject type">
        <select value={subjectType} onChange={(e) => setSubjectType(e.target.value)}>
          {["Counterparty", "Facility", "Application"].map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </Field>
      <Field label="Counterparty name (optional)">
        <input value={counterpartyName} onChange={(e) => setCounterpartyName(e.target.value)} placeholder="Legal name" />
      </Field>
      <Field label="Checklist" hint="From the SRM_CHECKLIST master; leave as default to use the first active row">
        <select value={checklistKey} onChange={(e) => setChecklistKey(e.target.value)}>
          <option value="">Default (first active)</option>
          {checklistKeys.map((k) => <option key={k} value={k}>{k}</option>)}
        </select>
      </Field>
      <Button onClick={submit} busy={busy}>Open review</Button>
    </Card>
  );
}

function SrmDetail({ reviewId, onChange }: { reviewId: number; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => srm.get(reviewId), [reviewId]);

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  if (view.error) return <Card title="Error"><div className="err">{view.error}</div></Card>;
  const r = view.data;
  const items: any[] = (r.checklist && r.checklist.items) || [];
  const ns: string = r.notingStatus || "—";

  return (
    <div className="grid">
      <Card title={`SRM · ${r.srmRef}`}
        sub={`Subject ${r.subjectRef}`}
        right={<Badge kind={statusTone(r.status)}>{r.status}</Badge>}>
        <div className="gate">
          <HumanBadge label="HUMAN-GATED" /> Every transition is a named-human action, audited. This review
          delegates its decision to a noting and never mutates a limit, exposure, rating or price.
        </div>
        <table className="kv">
          <tbody>
            <tr><th>Title</th><td>{r.title}</td></tr>
            <tr><th>Raised by</th><td>{r.raisedBy}</td></tr>
            <tr><th>Checklist</th><td className="mono">{r.checklistKey}</td></tr>
            <tr><th>Linked noting</th><td className="mono">{r.notingRef || "—"}</td></tr>
            <tr><th>Noting status</th><td><Badge kind={statusTone(ns)}>{ns}</Badge></td></tr>
            {r.renewalDueDate && <tr><th>Next review advanced to</th><td>{r.renewalDueDate}</td></tr>}
          </tbody>
        </table>
      </Card>

      <Card title="Renewal checklist" sub="Mark each item as the evidence is gathered; state is persisted on the review.">
        {items.length === 0 ? (
          <EmptyState glyph="◴" title="No checklist items" />
        ) : (
          <ul className="srm-checklist">
            {items.map((it) => (
              <li key={it.code} className={it.done ? "done" : ""}>
                <label>
                  <input type="checkbox" checked={!!it.done}
                    onChange={(e) => run(() => srm.markItem(reviewId, it.code, e.target.checked, actor),
                      `Checklist item ${it.code} ${e.target.checked ? "marked done" : "reopened"}`)} />
                  <span className="mono srm-code">{it.code}</span> {it.label}
                  {it.mandatory && <span className="srm-req">required</span>}
                </label>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Workflow">
        <div className="btnrow">
          {(ns === "DRAFT") && (
            <Button onClick={() => run(() => srm.submitNoting(reviewId, actor), "Renewal noting submitted for approval")}>
              Submit renewal noting
            </Button>
          )}
          <Button kind="ghost" onClick={() => run(() => srm.refresh(reviewId, actor), "Status refreshed")}>
            Refresh status
          </Button>
        </div>
        <small className="prov">
          Acting as <b>{actor}</b>. The linked noting is approved / CAD-authorised on the <b>Notings</b> screen
          (routed authority + SoD, server-enforced). Once it is <b>AUTHORIZED</b>, a refresh here advances the
          subject's MER next-review date.
        </small>
      </Card>
    </div>
  );
}
