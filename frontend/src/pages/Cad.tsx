import { useState } from "react";
import { cad, mer, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, statusTone, useAsync } from "../ui";

/**
 * CAD inbox + per-case workspace (PRD CAD module).
 * Open a case, comply / waiver / deviation per item with SoD-enforced 2-level
 * approval, complete, then run the limit-release checklist.
 */
export default function Cad() {
  const { actor, notify } = useApp();
  const inbox = useAsync(() => cad.inbox(), []);
  const apps = useAsync(() => origination.list(), []);
  const [selected, setSelected] = useState<number | null>(null);
  const [opening, setOpening] = useState(false);

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card title="CAD inbox"
          right={<Button kind="ghost" onClick={() => setOpening((o) => !o)}>{opening ? "Close" : "+ Open case"}</Button>}
          sub="Post-approval documentation. Open a case to start the checklist.">
          {(inbox.data || []).length === 0 ? <div className="muted">No CAD cases yet.</div> : (
            <table>
              <thead><tr><th>Application</th><th>Counterparty</th><th>Type</th><th>Status</th></tr></thead>
              <tbody>
                {(inbox.data || []).map((c: any) => (
                  <tr key={c.id} className="rowlink" onClick={() => setSelected(c.id)}>
                    <td className="mono">{c.applicationRef}</td>
                    <td>{c.counterpartyName || "—"}</td>
                    <td>{c.cpType}</td>
                    <td><Badge kind={statusTone(c.status)}>{c.status}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        {opening && (
          <OpenCase apps={apps.data || []} actor={actor} notify={notify}
            onDone={(id) => { setOpening(false); inbox.reload(); setSelected(id); }} />
        )}
      </div>
      <div>
        {selected ? <CaseDetail caseId={selected} onChange={inbox.reload} />
          : <Card title="Select a case" sub="Pick a row to open the checklist."><div className="muted" /></Card>}
      </div>
    </div>
  );
}

function OpenCase({ apps, actor, notify, onDone }: { apps: any[]; actor: string; notify: any; onDone: (id: number) => void }) {
  const approved = apps.filter((a) => ["APPROVED", "CONDITIONALLY_APPROVED", "DISBURSED"].includes(a.status));
  const [ref, setRef] = useState<string>(approved[0]?.reference || "");
  const submit = async () => {
    const cp = approved.find((a) => a.reference === ref);
    if (!cp) { notify("Pick an approved application", true); return; }
    try {
      const c = await cad.initiate({ applicationRef: cp.reference, counterpartyName: cp.counterpartyName, cpType: "NEW" }, actor);
      notify(`CAD case opened for ${cp.reference}`);
      onDone(c.cadCase.id);
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <Card title="Open new CAD case">
      <Field label="Approved deal">
        <select value={ref} onChange={(e) => setRef(e.target.value)}>
          {approved.length === 0 ? <option>(no approved deals)</option>
            : approved.map((a) => <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>)}
        </select>
      </Field>
      <Button onClick={submit} disabled={approved.length === 0}>Open case</Button>
    </Card>
  );
}

function CaseDetail({ caseId, onChange }: { caseId: number; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => cad.view(caseId), [caseId]);
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };
  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  const d = view.data;
  const c = d.cadCase;
  return (
    <div className="grid">
      <Card title={`CAD ${c.applicationRef}`} sub={`${c.cpType} · ${c.checklistKey}`}
        right={<Badge kind={statusTone(c.status)}>{c.status}</Badge>}>
        <div className="gate">HITL gate: every item must be complied or waived before completion. Waivers go through a 2-level approval (raiser ≠ approver, L2 ≠ L1).</div>
        <h4>Checklist items</h4>
        <table>
          <thead><tr><th>Item</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>
            {(d.items || []).map((it: any) => (
              <tr key={it.id}>
                <td>{it.description}<br /><small className="prov">{it.code}{it.docRef && ` · ${it.docRef}`}</small></td>
                <td><Badge kind={it.status === "COMPLIED" ? "ok" : it.status === "WAIVED" ? "info" : it.status === "DEVIATION" ? "warn" : ""}>{it.status}</Badge></td>
                <td>
                  {!["COMPLIED", "WAIVED"].includes(it.status) && (
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => run(() => cad.updateItem(it.id, { status: "COMPLIED", docRef: "DMS-1" }, actor), "Marked complied")}>Comply</button>
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => {
                          const reason = window.prompt("Waiver reason:");
                          if (reason) run(() => cad.raiseDeviation(it.id, { type: "WAIVER", reason }, actor), "Waiver raised");
                        }}>Waive…</button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {(d.deviations || []).length > 0 && (
        <Card title="Deviations / waivers" sub="Sequential 2-level approval; SoD enforced.">
          <table>
            <thead><tr><th>When</th><th>Type</th><th>Reason</th><th>Status</th><th>Approve</th></tr></thead>
            <tbody>
              {d.deviations.map((dv: any) => (
                <tr key={dv.id}>
                  <td className="mono">{new Date(dv.createdAt).toLocaleString()}</td>
                  <td>{dv.type}</td>
                  <td>{dv.reason}</td>
                  <td><Badge kind={dv.status === "APPROVED" ? "ok" : dv.status === "REJECTED" ? "bad" : "warn"}>{dv.status}</Badge></td>
                  <td>
                    {dv.status.startsWith("PENDING") && (
                      <Button kind="subtle" onClick={() => run(() => cad.decideDeviation(dv.id, { approve: true, comment: "ok" }, actor), "Approved")}>Approve</Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <Card title="Completion + limit release">
        <div className="btnrow">
          <Button onClick={() => run(() => cad.complete(caseId, actor), "Case completed")}
            disabled={c.status !== "IN_PROGRESS" && c.status !== "CHECKLIST" && c.status !== "DEVIATION"}>Complete checklist</Button>
          <Button kind="ghost" disabled={c.status !== "COMPLETED"}
            onClick={() => run(() => cad.limitRelease(caseId, { processingFeeAmortised: true, lienMarked: true, cashMarginCaptured: true, comment: "all set" }, actor),
              "Limit release triggered")}>Trigger limit release</Button>
          <Button kind="ghost" disabled={!["COMPLETED", "LIMIT_RELEASED"].includes(c.status)}
            onClick={() => run(async () => {
              const built = await mer.generateFromCad(caseId, actor, actor);
              notify(`Built ${built.length} monitoring item(s) — see Monitoring · MER`);
            }, "Monitoring register built")}>Build monitoring register</Button>
        </div>
        <small className="prov">On release, a LIMIT_RELEASE_TRIGGER event feeds limit-service so the line becomes utilisable. Building the monitoring register carries deferred documents and renewals (insurance · valuation · annual review) into the MER tracker.</small>
      </Card>
    </div>
  );
}
