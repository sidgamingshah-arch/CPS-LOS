import { useState } from "react";
import { fmt, masters, origination, perfection } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Col, DataTable, EmptyState, Field, HumanBadge, statusTone, useAsync } from "../ui";

/**
 * Mortgage / MOE security-perfection workspace.
 * Open a case over an obligor / facility / collateral; steps are materialised from
 * the CHECKLIST_MASTER PERFECTION_MOE row (title search → legal opinion → valuation →
 * MOE execution → MOE vetting → CERSAI filing). Each step is role-gated to its owner
 * role; the MOE-vetting step is SoD-gated against the MOE-execution actor. VENDOR steps
 * raise an EXTERNAL_VENDOR RFQ through the Query module.
 */
export default function Perfection() {
  const { actor, notify } = useApp();
  const inbox = useAsync(() => perfection.list(), []);
  const apps = useAsync(() => origination.list(), []);
  const [selected, setSelected] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);

  const cols: Col<any>[] = [
    { key: "perfRef", header: "Case", render: (c) => <span className="mono">{c.perfRef}</span> },
    { key: "subject", header: "Subject", value: (c) => `${c.subjectType} ${c.subjectRef}`,
      render: (c) => <>{c.subjectType} · <span className="mono">{c.subjectRef}</span></> },
    { key: "checklistKey", header: "Checklist" },
    { key: "status", header: "Status", render: (c) => <Badge kind={statusTone(c.status)}>{c.status}</Badge> },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card title="MOE perfection cases"
          right={<Button kind="ghost" onClick={() => setOpening((o) => !o)}>{opening ? "Close" : "+ New case"}</Button>}
          sub="Mortgage / MOE security perfection. Steps come from the PERFECTION_MOE checklist master.">
          {(inbox.data || []).length === 0 ? (
            <EmptyState glyph="◰" title="No perfection cases yet"
              sub="Open a case with +&nbsp;New case to materialise the ordered MOE-perfection checklist." />
          ) : (
            <DataTable id="perfection.cases" columns={cols} rows={inbox.data || []}
              rowKey={(c) => c.perfRef} onRowClick={(c) => setSelected(c.perfRef)} initialPageSize={10} />
          )}
        </Card>
        {opening && (
          <NewCase apps={apps.data || []} actor={actor} notify={notify}
            onDone={(ref) => { setOpening(false); inbox.reload(); setSelected(ref); }} />
        )}
      </div>
      <div>
        {selected ? <CaseDetail perfRef={selected} onChange={inbox.reload} />
          : (
            <Card>
              <EmptyState glyph="◧" title="Select a case to open its checklist"
                sub="Click any row to load its ordered perfection steps — complete or waive per role, or raise a vendor RFQ." />
            </Card>
          )}
      </div>
    </div>
  );
}

function NewCase({ apps, actor, notify, onDone }: { apps: any[]; actor: string; notify: any; onDone: (ref: string) => void }) {
  const [subjectType, setSubjectType] = useState("COLLATERAL");
  const [subjectRef, setSubjectRef] = useState("");
  const [applicationRef, setApplicationRef] = useState<string>(apps[0]?.reference || "");
  const submit = async () => {
    if (!subjectRef.trim()) { notify("A subject reference is required", true); return; }
    try {
      const c = await perfection.create(
        { subjectType, subjectRef: subjectRef.trim(), applicationRef: applicationRef || undefined }, actor);
      notify(`Perfection case ${c.perfectionCase.perfRef} opened (${c.steps.length} steps)`);
      onDone(c.perfectionCase.perfRef);
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <Card title="Open new perfection case">
      <Field label="Subject type">
        <select value={subjectType} onChange={(e) => setSubjectType(e.target.value)}>
          <option value="OBLIGOR">OBLIGOR</option>
          <option value="FACILITY">FACILITY</option>
          <option value="COLLATERAL">COLLATERAL</option>
        </select>
      </Field>
      <Field label="Subject reference">
        <input value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)} placeholder="e.g. COL-000123" />
      </Field>
      <Field label="Application (optional — deal linkage)">
        <select value={applicationRef} onChange={(e) => setApplicationRef(e.target.value)}>
          <option value="">(none)</option>
          {apps.map((a) => <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>)}
        </select>
      </Field>
      <Button onClick={submit}>Open case</Button>
    </Card>
  );
}

function CaseDetail({ perfRef, onChange }: { perfRef: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => perfection.view(perfRef), [perfRef]);
  // Vendors for VENDOR-step RFQs come from the VENDOR_MASTER — the human selects one,
  // never a hardcoded literal (config-as-data, mirrors MonitoringArtifacts).
  const vendors = useAsync(() => masters.list("VENDOR_MASTER").catch(() => [] as any[]), []);
  const [vendorSel, setVendorSel] = useState<Record<string, string>>({});
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };
  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  const d = view.data;
  const c = d.perfectionCase;
  return (
    <div className="grid">
      <Card title={`Perfection ${c.perfRef}`} sub={`${c.subjectType} · ${c.subjectRef}${c.applicationRef ? " · " + c.applicationRef : ""}`}
        right={<Badge kind={statusTone(c.status)}>{c.status}</Badge>}>
        <div className="perf-gate">
          <HumanBadge label="ROLE-GATED" />
          <span>Each step is completed / waived by its owner role. MOE vetting must be done by
            someone other than the MOE-execution actor (SoD). Case completes when every step is DONE or WAIVED.</span>
        </div>
        <table>
          <thead><tr><th>#</th><th>Step</th><th>Owner</th><th>Status</th><th>Actions</th></tr></thead>
          <tbody>
            {(d.steps || []).map((s: any) => (
              <tr key={s.id}>
                <td className="mono">{s.stepOrder + 1}</td>
                <td>{s.title}<br /><small className="prov">{s.stepKey}
                  {s.vendorQueryRef && <> · vendor {s.vendorQueryRef}</>}
                  {s.completedBy && <> · by {s.completedBy}</>}</small></td>
                <td><Badge kind="info">{s.ownerRole}</Badge></td>
                <td><Badge kind={s.status === "DONE" ? "ok" : s.status === "WAIVED" ? "info" : ""}>{s.status}</Badge></td>
                <td>
                  {!["DONE", "WAIVED"].includes(s.status) && (
                    <div className="btnrow">
                      <button className="btn subtle perf-btn"
                        onClick={() => run(() => perfection.complete(perfRef, s.stepKey,
                          { role: s.ownerRole, evidence: "DMS-PERF" }, actor), "Step completed")}>Complete</button>
                      <button className="btn subtle perf-btn"
                        onClick={() => {
                          const reason = window.prompt("Waiver note:");
                          if (reason !== null) run(() => perfection.waive(perfRef, s.stepKey,
                            { role: s.ownerRole, notes: reason }, actor), "Step waived");
                        }}>Waive…</button>
                      {s.ownerRole === "VENDOR" && !s.vendorQueryRef && (
                        <span style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
                          <select
                            style={{ width: "auto", minWidth: 130, padding: "3px 6px", fontSize: 11 }}
                            value={vendorSel[s.stepKey] ?? ""}
                            onChange={(e) => setVendorSel({ ...vendorSel, [s.stepKey]: e.target.value })}>
                            <option value="">— select vendor —</option>
                            {(vendors.data ?? []).map((v: any) => (
                              <option key={v.recordKey} value={v.recordKey}>
                                {v.payload?.name ? `${v.recordKey} · ${v.payload.name}` : v.recordKey}
                              </option>
                            ))}
                          </select>
                          <button className="btn subtle perf-btn"
                            disabled={!vendorSel[s.stepKey]}
                            onClick={() => run(() => perfection.vendorRfq(perfRef, s.stepKey,
                              { vendorId: vendorSel[s.stepKey] }, actor), "Vendor RFQ raised")}>Vendor RFQ</button>
                        </span>
                      )}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <small className="prov">Acting as <b>{actor}</b>. The declared role is the step's owner role;
          completing a step whose role you do not act as returns a 403.</small>
      </Card>
    </div>
  );
}
