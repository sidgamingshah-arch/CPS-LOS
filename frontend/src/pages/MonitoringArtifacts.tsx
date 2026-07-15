import { useEffect, useState } from "react";
import { masters, monitoringArtifacts, fmt } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, DataTable, EmptyState, Field, HumanBadge, Toast, statusTone, useAsync,
} from "../ui";
import type { Col } from "../ui";

/**
 * Post-disbursement monitoring artifacts — ONE governed lifecycle for every artifact
 * type (call memo · plant/site visit · LCR · QPR · broker review · stock audit ·
 * audit note), driven by the MONITORING_ARTIFACT_TYPE master (config-as-data — a new
 * type is a new master row, no code change). Records / advisory: the workflow gathers
 * monitoring evidence and routes it DRAFT → SUBMITTED → REVIEWED → APPROVED (→ AUTHORIZED)
 * with maker-checker SoD at each gate. It NEVER moves an authoritative figure (ECL /
 * IRAC / exposure).
 */

type Artifact = {
  artifactRef: string; artifactType: string; subjectType?: string; subjectRef?: string;
  title?: string; status: string; owner: string; reviewer?: string; approver?: string;
  sections?: Record<string, any>; masterVersion?: number; requiresAuthorize?: boolean;
  vendorRfq?: boolean; vendorRef?: string; vendorQueryRef?: string;
  reviewNotes?: string; approvalNotes?: string; authorisationNotes?: string;
  updatedAt?: string; createdAt?: string;
};

export default function MonitoringArtifacts() {
  const { actor, notify } = useApp();
  const artifacts = useAsync(() => monitoringArtifacts.list(), []);
  const types = useAsync(() => masters.list("MONITORING_ARTIFACT_TYPE").catch(() => [] as any[]), []);
  const vendors = useAsync(() => masters.list("VENDOR_MASTER").catch(() => [] as any[]), []);

  // create form
  const [artifactType, setArtifactType] = useState("");
  const [subjectType, setSubjectType] = useState("OBLIGOR");
  const [subjectRef, setSubjectRef] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);

  // detail / workflow
  const [selectedRef, setSelectedRef] = useState<string | null>(null);
  const [detail, setDetail] = useState<any>(null);
  const [sectionEdits, setSectionEdits] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [vendorId, setVendorId] = useState("");
  const [vendorQuestion, setVendorQuestion] = useState("");
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  async function loadDetail(ref: string) {
    try {
      const v = await monitoringArtifacts.get(ref);
      setDetail(v);
      const a: Artifact = v.artifact;
      const edits: Record<string, string> = {};
      Object.entries(a.sections || {}).forEach(([k, cell]: [string, any]) => {
        edits[k] = (cell && typeof cell === "object" ? cell.content : cell) || "";
      });
      setSectionEdits(edits);
      setNote("");
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    }
  }

  useEffect(() => {
    if (selectedRef) loadDetail(selectedRef);
    else setDetail(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRef]);

  function ok(text: string) {
    setToast({ text });
    notify(text);
    artifacts.reload();
    if (selectedRef) loadDetail(selectedRef);
  }
  function fail(e: any) { setToast({ text: e.message, err: true }); }

  async function create() {
    if (!artifactType) return;
    setBusy(true);
    try {
      const a = await monitoringArtifacts.create(
        { artifactType, subjectType, subjectRef: subjectRef.trim(), title: title.trim() }, actor);
      setToast({ text: `Created ${a.artifactRef}` });
      setTitle(""); setSubjectRef("");
      artifacts.reload();
      setSelectedRef(a.artifactRef);
    } catch (e: any) { fail(e); } finally { setBusy(false); }
  }

  const a: Artifact | null = detail?.artifact ?? null;
  const vendorQuery = detail?.vendorQuery ?? null;

  async function saveSections() {
    if (!a) return;
    const sections: Record<string, any> = {};
    Object.entries(a.sections || {}).forEach(([k, cell]: [string, any]) => {
      const label = cell && typeof cell === "object" ? cell.label : k;
      sections[k] = { label, content: sectionEdits[k] ?? "" };
    });
    try { await monitoringArtifacts.updateSections(a.artifactRef, sections, actor); ok("Sections saved"); }
    catch (e: any) { fail(e); }
  }
  async function submit() {
    if (!a || !confirm(`Submit ${a.artifactRef}? Sections lock after submission.`)) return;
    try { await monitoringArtifacts.submit(a.artifactRef, actor); ok(`${a.artifactRef} submitted`); }
    catch (e: any) { fail(e); }
  }
  async function review() {
    if (!a) return;
    try { await monitoringArtifacts.review(a.artifactRef, note, actor); ok(`${a.artifactRef} reviewed`); }
    catch (e: any) { fail(e); }
  }
  async function approve() {
    if (!a) return;
    try { await monitoringArtifacts.approve(a.artifactRef, note, actor); ok(`${a.artifactRef} approved`); }
    catch (e: any) { fail(e); }
  }
  async function authorize() {
    if (!a || !confirm(`Authorise ${a.artifactRef}? This is the terminal state.`)) return;
    try { await monitoringArtifacts.authorize(a.artifactRef, note, actor); ok(`${a.artifactRef} authorised`); }
    catch (e: any) { fail(e); }
  }
  async function raiseRfq() {
    if (!a || !vendorId) return;
    try {
      await monitoringArtifacts.vendorRfq(a.artifactRef, vendorId, vendorQuestion, actor);
      ok(`Vendor RFQ raised to ${vendorId}`);
    } catch (e: any) { fail(e); }
  }

  const cols: Col<Artifact>[] = [
    { key: "artifactRef", header: "Ref" },
    { key: "artifactType", header: "Type" },
    { key: "subjectRef", header: "Subject", render: (r) => r.subjectRef || "—" },
    { key: "title", header: "Title", render: (r) => r.title || "—" },
    { key: "status", header: "Status", render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge> },
    { key: "owner", header: "Owner" },
    { key: "reviewer", header: "Reviewer", render: (r) => r.reviewer || "—" },
    { key: "approver", header: "Approver", render: (r) => r.approver || "—" },
    { key: "updatedAt", header: "Updated", render: (r) => fmt.dateTime(r.updatedAt), value: (r) => r.updatedAt || "" },
  ];

  const isDraft = a?.status === "DRAFT";

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card title="Create monitoring artifact"
        sub="One master-driven lifecycle for every artifact type. Sections are materialised from the MONITORING_ARTIFACT_TYPE master; the master version is pinned."
        right={<div className="gov-chips"><HumanBadge label="HUMAN-GATED RECORD" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Artifact type" required hint="Driven by the MONITORING_ARTIFACT_TYPE master">
            <select value={artifactType} onChange={(e) => setArtifactType(e.target.value)}>
              <option value="">— select type —</option>
              {(types.data ?? []).map((t: any) => (
                <option key={t.recordKey} value={t.recordKey}>{t.recordKey}</option>
              ))}
            </select>
          </Field>
          <Field label="Subject type">
            <select value={subjectType} onChange={(e) => setSubjectType(e.target.value)}>
              <option value="OBLIGOR">OBLIGOR</option>
              <option value="FACILITY">FACILITY</option>
              <option value="EXPOSURE">EXPOSURE</option>
            </select>
          </Field>
          <Field label="Subject ref" hint="Obligor / facility / exposure reference">
            <input value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)} placeholder="APP-… / CIF-…" />
          </Field>
          <Field label="Title"><input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="optional" /></Field>
          <Button onClick={create} busy={busy} disabled={!artifactType}>Create draft</Button>
        </div>
      </Card>

      <Card title="Monitoring artifacts" sub={`${(artifacts.data ?? []).length} artifact(s) · advisory records — ECL / IRAC / exposure never touched`}>
        {artifacts.error ? <EmptyState glyph="!" title="Could not load artifacts" sub={artifacts.error} />
          : (
            <DataTable<Artifact>
              id="monitoring-artifacts"
              columns={cols}
              rows={(artifacts.data ?? []) as Artifact[]}
              rowKey={(r) => r.artifactRef}
              onRowClick={(r) => setSelectedRef(r.artifactRef)}
              empty={<EmptyState glyph="◴" title="No artifacts yet" sub="Create a draft above." />}
            />
          )}
      </Card>

      {a && (
        <Card title={`${a.artifactRef} · ${a.artifactType}`}
          sub={`${a.title || ""} · master v${a.masterVersion ?? 0}${a.requiresAuthorize ? " · requires authorisation" : ""}`}
          right={
            <div className="gov-chips" style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <Badge kind={statusTone(a.status)}>{a.status}</Badge>
              <HumanBadge label="HUMAN-GATED" />
              <Button kind="ghost" onClick={() => setSelectedRef(null)}>Close</Button>
            </div>
          }>
          <div className="sub" style={{ marginBottom: 10 }}>
            Owner <b>{a.owner}</b>
            {a.reviewer && <> · Reviewer <b>{a.reviewer}</b></>}
            {a.approver && <> · Approver <b>{a.approver}</b></>}
            {a.subjectRef && <> · Subject <b>{a.subjectType} {a.subjectRef}</b></>}
          </div>

          {/* section editor */}
          <div className="stack" style={{ gap: 10 }}>
            {Object.entries(a.sections || {}).map(([k, cell]: [string, any]) => {
              const label = cell && typeof cell === "object" ? cell.label : k;
              return (
                <Field key={k} label={label}>
                  <textarea
                    rows={3}
                    disabled={!isDraft}
                    value={sectionEdits[k] ?? ""}
                    onChange={(e) => setSectionEdits({ ...sectionEdits, [k]: e.target.value })}
                    placeholder={isDraft ? "Enter monitoring notes…" : "(locked)"}
                  />
                </Field>
              );
            })}
          </div>

          <div className="btnrow" style={{ marginTop: 12, gap: 8, flexWrap: "wrap" }}>
            {isDraft && <Button kind="subtle" onClick={saveSections}>Save sections</Button>}
            {isDraft && <Button onClick={submit}>Submit</Button>}
            {(a.status === "SUBMITTED" || a.status === "REVIEWED" || a.status === "APPROVED") && (
              <input style={{ minWidth: 240 }} value={note} onChange={(e) => setNote(e.target.value)}
                placeholder="decision notes (optional)" />
            )}
            {a.status === "SUBMITTED" && <Button onClick={review}>Review</Button>}
            {a.status === "REVIEWED" && <Button onClick={approve}>Approve</Button>}
            {a.status === "APPROVED" && a.requiresAuthorize && (
              <Button onClick={authorize}>Authorise</Button>
            )}
          </div>

          {/* stock-audit vendor RFQ */}
          {a.vendorRfq && (
            <div style={{ marginTop: 16, borderTop: "1px solid var(--border)", paddingTop: 12 }}>
              <div className="flexbetween">
                <h4 style={{ margin: 0 }}>Vendor RFQ (stock audit)</h4>
                <HumanBadge label="HUMAN SELECTS VENDOR" />
              </div>
              {a.vendorQueryRef ? (
                <div className="sub" style={{ marginTop: 8 }}>
                  Query <b>{a.vendorQueryRef}</b> raised to vendor <b>{a.vendorRef}</b>
                  {vendorQuery?.thread && <> · status <Badge kind={statusTone(vendorQuery.thread.status)}>{vendorQuery.thread.status}</Badge></>}
                  {vendorQuery?.messages && (
                    <ul style={{ marginTop: 8 }}>
                      {vendorQuery.messages.map((m: any) => (
                        <li key={m.id}><b>{m.author}</b>{m.inbound ? " (inbound)" : ""}: {m.body}</li>
                      ))}
                    </ul>
                  )}
                </div>
              ) : (
                <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap", marginTop: 8 }}>
                  <Field label="Vendor" required hint="Chosen from VENDOR_MASTER — never auto-selected">
                    <select value={vendorId} onChange={(e) => setVendorId(e.target.value)}>
                      <option value="">— select vendor —</option>
                      {(vendors.data ?? []).map((v: any) => (
                        <option key={v.recordKey} value={v.recordKey}>
                          {v.payload?.name ? `${v.recordKey} · ${v.payload.name}` : v.recordKey}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Question">
                    <input value={vendorQuestion} onChange={(e) => setVendorQuestion(e.target.value)}
                      placeholder="optional" />
                  </Field>
                  <Button onClick={raiseRfq} disabled={!vendorId}>Raise RFQ</Button>
                </div>
              )}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
