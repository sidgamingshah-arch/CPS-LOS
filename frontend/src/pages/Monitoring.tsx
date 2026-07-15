import { useState } from "react";
import { cad, fmt, mer, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, Stat, useAsync } from "../ui";

/**
 * Monitoring of Exceptions & Renewals (MER) register (PRD deferred-documentation /
 * conditions-subsequent / renewal tracking). Build the register from a completed CAD
 * case, then work the inbox: submit evidence (DMS feed), verify (maker-checker SoD),
 * waive, or let the sweep age past-due items into OVERDUE / ESCALATED.
 */
function critTone(c: string) {
  return c === "HIGH" ? "bad" : c === "MEDIUM" ? "warn" : "info";
}
function statusKind(s: string) {
  if (s === "CLEARED") return "ok";
  if (s === "WAIVED") return "info";
  if (s === "OVERDUE") return "warn";
  if (s === "ESCALATED") return "bad";
  if (s === "SUBMITTED") return "ai";
  return "";
}

export default function Monitoring() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const cases = useAsync(() => cad.inbox(), []);
  const [ref, setRef] = useState<string>("");
  const summary = useAsync(() => mer.summary(ref || undefined), [ref]);
  const register = useAsync(() => (ref ? mer.forApp(ref) : mer.inbox()), [ref]);

  const reloadAll = () => { summary.reload(); register.reload(); };
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reloadAll(); } catch (e: any) { notify(e.message, true); }
  };

  const buildable = (cases.data || []).filter((c: any) => ["COMPLETED", "LIMIT_RELEASED"].includes(c.status));
  const s = summary.data || { byStatus: {}, total: 0, overdue: 0, dueSoon: 0 };
  const bs = s.byStatus || {};

  return (
    <div className="grid">
      <Card title="Monitoring of Exceptions & Renewals"
        sub="Deferred documents, conditions subsequent and recurring renewals (insurance · valuation · annual review). Reminders, escalation sweep, maker-checker clearance, DMS feed."
        right={
          <div className="btnrow">
            <Button kind="ghost" onClick={() => run(() => mer.sendReminders(30, actor), "Reminders dispatched")}>Send reminders</Button>
            <Button kind="ghost" onClick={() => run(() => mer.sweep(actor), "Register swept")}>Run sweep</Button>
          </div>
        }>
        <div className="grid cols-3" style={{ marginBottom: 4 }}>
          <Stat label="Open" value={(bs.OPEN || 0) + (bs.SUBMITTED || 0)} />
          <Stat label="Overdue / escalated" value={s.overdue || 0} tone={s.overdue ? "var(--bad)" : undefined} />
          <Stat label="Due soon" value={s.dueSoon || 0} />
        </div>
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Scope">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">Active inbox (all deals)</option>
              {(apps.data || []).map((a: any) => <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>)}
            </select>
          </Field>
          <BuildRegister cases={buildable} actor={actor} notify={notify}
            onBuilt={(builtRef) => { setRef(builtRef); reloadAll(); cases.reload(); }} />
        </div>
      </Card>

      <Card title={ref ? `Register · ${ref}` : "Active register (all deals)"}
        sub={register.loading ? "Loading…" : `${(register.data || []).length} item(s)`}>
        {(register.data || []).length === 0 ? (
          <div className="muted">No monitoring items. Build the register from a completed CAD case.</div>
        ) : (
          <div className="table-scroll">
          <table>
            <thead><tr><th>Due</th><th>Item</th><th>Owner</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>
              {(register.data || []).map((m: any) => (
                <tr key={m.id}>
                  <td className="mono">{fmt.date(m.dueDate)}{m.recurring && <span title="recurring"> ↻</span>}</td>
                  <td>
                    {m.description}
                    <br /><small className="prov">
                      <Badge>{m.itemType}</Badge> <Badge kind={critTone(m.criticality)}>{m.criticality}</Badge>
                      {m.cycleCount > 0 && <> · cycle {m.cycleCount}</>}
                      {m.docRef && <> · {m.docRef}</>}
                    </small>
                  </td>
                  <td className="mono">{m.owner}</td>
                  <td><Badge kind={statusKind(m.status)}>{m.status}</Badge></td>
                  <td><RowActions m={m} actor={actor} run={run} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function BuildRegister({ cases, actor, notify, onBuilt }:
                       { cases: any[]; actor: string; notify: any; onBuilt: (ref: string) => void }) {
  const [caseId, setCaseId] = useState<string>("");
  const [owner, setOwner] = useState<string>("rm.user");
  const submit = async () => {
    const c = cases.find((x) => String(x.id) === caseId);
    if (!c) { notify("Pick a completed CAD case", true); return; }
    try {
      const built = await mer.generateFromCad(c.id, owner, actor);
      notify(`Built ${built.length} monitoring item(s) for ${c.applicationRef}`);
      onBuilt(c.applicationRef);
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <div className="btnrow">
      <select value={caseId} onChange={(e) => setCaseId(e.target.value)} style={{ flex: 1 }}>
        <option value="">— build register from CAD —</option>
        {cases.map((c) => <option key={c.id} value={c.id}>{c.applicationRef} · {c.status}</option>)}
      </select>
      <input value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="owner" style={{ width: 96 }} />
      <Button kind="ghost" onClick={submit}>Build</Button>
    </div>
  );
}

function RowActions({ m, actor, run }: { m: any; actor: string; run: (fn: () => Promise<any>, ok: string) => void }) {
  const btn = { fontSize: 11, padding: "3px 8px" } as const;
  if (["CLEARED", "WAIVED"].includes(m.status)) return <small className="prov">closed</small>;
  if (m.status === "SUBMITTED") {
    return (
      <div className="btnrow">
        <button className="btn subtle" style={btn}
          onClick={() => run(() => mer.verify(m.id, { approve: true, comment: "verified" }, actor), "Verified")}>Verify</button>
        <button className="btn subtle" style={btn}
          onClick={() => run(() => mer.verify(m.id, { approve: false, comment: "returned" }, actor), "Returned")}>Return</button>
      </div>
    );
  }
  // OPEN / OVERDUE / ESCALATED
  return (
    <div className="btnrow">
      <button className="btn subtle" style={btn}
        onClick={() => {
          const docRef = window.prompt("DMS document reference:", "DMS-" + m.id);
          if (docRef) run(() => mer.submit(m.id, { docRef, comment: "submitted" }, actor), "Submitted (DMS-fed)");
        }}>Submit…</button>
      <button className="btn danger" style={btn}
        onClick={() => {
          const reason = window.prompt("Waiver reason:");
          if (reason) run(() => mer.waive(m.id, { reason }, actor), "Waived");
        }}>Waive…</button>
    </div>
  );
}
