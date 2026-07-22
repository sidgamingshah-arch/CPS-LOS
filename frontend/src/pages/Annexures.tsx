import { useEffect, useState } from "react";
import { annexures, masters, origination, fmt } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, DataTable, EmptyState, Field, GovFlow, HumanBadge, Toast,
  statusTone, useAsync,
} from "../ui";
import type { Col } from "../ui";

/**
 * CAM annexures (CLoM R1-09) — ONE master-driven authoring lifecycle for every annexure
 * type (CRI sheet · industry-scenario · ESG · exchange-risk · project-deferment ·
 * group-analysis), driven by the ANNEXURE_TYPE master (config-as-data — a new type is a
 * new master row, no code change). Sections are materialised from the master template and
 * the master version is pinned. An author drafts the sections (optionally with a governed
 * AI draft at the LLM boundary), then the workflow routes DRAFT → SUBMITTED → REVIEWED →
 * APPROVED (or → REJECTED) with maker-checker SoD (reviewer / approver ≠ author). It NEVER
 * moves the subject deal's authoritative grade / PD / spread.
 */

type Annexure = {
  annexureRef: string; annexureType: string; subjectType?: string; subjectRef?: string;
  title?: string; status: string; author: string; reviewer?: string;
  sections?: Record<string, any>; typeVersion?: number; advisory?: boolean;
  reviewNotes?: string; rejectReason?: string; approvedBy?: string; rejectedBy?: string;
  updatedAt?: string; createdAt?: string;
};

export default function Annexures() {
  const { actor, notify } = useApp();
  const rows = useAsync(() => annexures.list(), []);
  const types = useAsync(() => masters.list("ANNEXURE_TYPE").catch(() => [] as any[]), []);
  const deals = useAsync(() => origination.list().catch(() => [] as any[]), []);

  // create form
  const [annexureType, setAnnexureType] = useState("");
  const [subjectRef, setSubjectRef] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);

  // detail / workflow
  const [selectedRef, setSelectedRef] = useState<string | null>(null);
  const [detail, setDetail] = useState<Annexure | null>(null);
  const [sectionEdits, setSectionEdits] = useState<Record<string, string>>({});
  const [aiDrafted, setAiDrafted] = useState<Set<string>>(new Set());
  const [hint, setHint] = useState("");
  const [note, setNote] = useState("");
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  async function loadDetail(ref: string) {
    try {
      const a = await annexures.get(ref);
      setDetail(a);
      const edits: Record<string, string> = {};
      Object.entries(a.sections || {}).forEach(([k, cell]: [string, any]) => {
        edits[k] = (cell && typeof cell === "object" ? cell.content : cell) || "";
      });
      setSectionEdits(edits);
      setNote(""); setReason("");
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    }
  }

  useEffect(() => {
    if (selectedRef) loadDetail(selectedRef);
    else { setDetail(null); setAiDrafted(new Set()); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRef]);

  function ok(text: string) {
    setToast({ text });
    notify(text);
    rows.reload();
    if (selectedRef) loadDetail(selectedRef);
  }
  function fail(e: any) { setToast({ text: e.message, err: true }); }

  async function create() {
    if (!annexureType) return;
    setBusy(true);
    try {
      const a = await annexures.create(
        { annexureType, subjectType: "APPLICATION", subjectRef: subjectRef.trim(), title: title.trim() }, actor);
      setToast({ text: `Created ${a.annexureRef}` });
      setTitle("");
      rows.reload();
      setSelectedRef(a.annexureRef);
    } catch (e: any) { fail(e); } finally { setBusy(false); }
  }

  const a = detail;
  const isDraft = a?.status === "DRAFT";

  function cellsFromEdits(): Record<string, any> {
    const sections: Record<string, any> = {};
    Object.entries(a?.sections || {}).forEach(([k, cell]: [string, any]) => {
      const t = cell && typeof cell === "object" ? cell.title : k;
      sections[k] = { title: t, content: sectionEdits[k] ?? "" };
    });
    return sections;
  }

  async function saveSections() {
    if (!a) return;
    try { await annexures.updateSections(a.annexureRef, { sections: cellsFromEdits() }, actor); ok("Sections saved"); }
    catch (e: any) { fail(e); }
  }
  async function aiDraft() {
    if (!a) return;
    // remember which sections are empty NOW — those are the ones the AI will fill.
    const emptyBefore = Object.keys(sectionEdits).filter((k) => !(sectionEdits[k] ?? "").trim());
    try {
      // persist any manual edits alongside the AI-draft request; the server drafts only empties.
      await annexures.updateSections(a.annexureRef, { sections: cellsFromEdits(), aiDraft: true, hint }, actor);
      const refreshed = await annexures.get(a.annexureRef);
      const filled = new Set(aiDrafted);
      emptyBefore.forEach((k) => {
        const cell: any = (refreshed.sections || {})[k];
        const content = cell && typeof cell === "object" ? cell.content : cell;
        if (content && String(content).trim()) filled.add(k);
      });
      setAiDrafted(filled);
      ok("AI drafted empty sections (advisory — review before submit)");
    } catch (e: any) { fail(e); }
  }
  async function submit() {
    if (!a || !confirm(`Submit ${a.annexureRef}? Sections lock after submission.`)) return;
    try { await annexures.submit(a.annexureRef, actor); ok(`${a.annexureRef} submitted`); }
    catch (e: any) { fail(e); }
  }
  async function review() {
    if (!a) return;
    try { await annexures.review(a.annexureRef, note, actor); ok(`${a.annexureRef} reviewed`); }
    catch (e: any) { fail(e); }
  }
  async function approve() {
    if (!a) return;
    try { await annexures.approve(a.annexureRef, note, actor); ok(`${a.annexureRef} approved`); }
    catch (e: any) { fail(e); }
  }
  async function reject() {
    if (!a) return;
    if (!reason.trim()) { setToast({ text: "A rejection reason is mandatory", err: true }); return; }
    try { await annexures.reject(a.annexureRef, reason, actor); ok(`${a.annexureRef} rejected`); }
    catch (e: any) { fail(e); }
  }

  const cols: Col<Annexure>[] = [
    { key: "annexureRef", header: "Ref" },
    { key: "annexureType", header: "Type" },
    { key: "subjectRef", header: "Deal", render: (r) => r.subjectRef || "—" },
    { key: "title", header: "Title", render: (r) => r.title || "—" },
    { key: "status", header: "Status", render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge> },
    { key: "author", header: "Author" },
    { key: "reviewer", header: "Reviewer", render: (r) => r.reviewer || "—" },
    { key: "updatedAt", header: "Updated", render: (r) => fmt.dateTime(r.updatedAt), value: (r) => r.updatedAt || "" },
  ];

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card title="Create CAM annexure"
        sub="One master-driven authoring lifecycle for every annexure type. Sections are materialised from the ANNEXURE_TYPE master; the master version is pinned."
        right={<GovFlow ai="AI DRAFTS" human="HUMANS AUTHOR · APPROVE" note="figures quoted verbatim" />}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Annexure type" required hint="Driven by the ANNEXURE_TYPE master">
            <select value={annexureType} onChange={(e) => setAnnexureType(e.target.value)}>
              <option value="">— select type —</option>
              {(types.data ?? []).map((t: any) => (
                <option key={t.recordKey} value={t.recordKey}>
                  {t.payload?.label ? `${t.recordKey} · ${t.payload.label}` : t.recordKey}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Deal" required hint="The application / proposal this annexure attaches to">
            <select value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)}>
              <option value="">— select deal —</option>
              {(deals.data ?? []).map((d: any) => (
                <option key={d.reference} value={d.reference}>
                  {d.counterpartyName ? `${d.reference} · ${d.counterpartyName}` : d.reference}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Title"><input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="optional" /></Field>
          <Button onClick={create} busy={busy} disabled={!annexureType}>Create draft</Button>
        </div>
      </Card>

      <Card title="Annexures" sub={`${(rows.data ?? []).length} annexure(s) · advisory authoring artefacts — the deal's grade / PD / spread never touched`}>
        {rows.error ? <EmptyState glyph="!" title="Could not load annexures" sub={rows.error} />
          : (
            <DataTable<Annexure>
              id="annexures"
              columns={cols}
              rows={(rows.data ?? []) as Annexure[]}
              rowKey={(r) => r.annexureRef}
              onRowClick={(r) => setSelectedRef(r.annexureRef)}
              empty={<EmptyState glyph="◴" title="No annexures yet" sub="Create a draft above." />}
            />
          )}
      </Card>

      {a && (
        <Card title={`${a.annexureRef} · ${a.annexureType}`}
          sub={`${a.title || ""} · master v${a.typeVersion ?? 0}${a.subjectRef ? ` · deal ${a.subjectRef}` : ""}`}
          right={
            <div className="gov-chips" style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <Badge kind={statusTone(a.status)}>{a.status}</Badge>
              <HumanBadge label="HUMAN-GATED" />
              <Button kind="ghost" onClick={() => setSelectedRef(null)}>Close</Button>
            </div>
          }>
          <div className="sub" style={{ marginBottom: 10 }}>
            Author <b>{a.author}</b>
            {a.reviewer && <> · Reviewer <b>{a.reviewer}</b></>}
            {a.approvedBy && <> · Approved by <b>{a.approvedBy}</b></>}
            {a.rejectedBy && <> · Rejected by <b>{a.rejectedBy}</b></>}
          </div>

          {/* section editor */}
          <div className="stack" style={{ gap: 10 }}>
            {Object.entries(a.sections || {}).map(([k, cell]: [string, any]) => {
              const label = cell && typeof cell === "object" ? cell.title : k;
              return (
                <div key={k} className="anx-section">
                  <div className="anx-sec-label">
                    <span className="lbl">{label}</span>
                    {aiDrafted.has(k) && <AiBadge label="AI-DRAFTED" />}
                  </div>
                  <textarea
                    rows={3}
                    disabled={!isDraft}
                    value={sectionEdits[k] ?? ""}
                    onChange={(e) => setSectionEdits({ ...sectionEdits, [k]: e.target.value })}
                    placeholder={isDraft ? "Enter annexure content…" : "(locked)"}
                  />
                </div>
              );
            })}
          </div>

          {isDraft && (
            <div className="anx-ai-row">
              <Field label="AI draft hint (optional)">
                <input value={hint} onChange={(e) => setHint(e.target.value)} placeholder="steer the advisory AI draft" />
              </Field>
              <Button kind="subtle" onClick={aiDraft}>AI-draft empty sections</Button>
              <span className="sub">Advisory · grounded · figures quoted verbatim · you review before submit</span>
            </div>
          )}

          <div className="btnrow" style={{ marginTop: 12, gap: 8, flexWrap: "wrap" }}>
            {isDraft && <Button kind="subtle" onClick={saveSections}>Save sections</Button>}
            {isDraft && <Button onClick={submit}>Submit</Button>}
            {(a.status === "SUBMITTED" || a.status === "REVIEWED") && (
              <input style={{ minWidth: 220 }} value={note} onChange={(e) => setNote(e.target.value)}
                placeholder="review notes (optional)" />
            )}
            {a.status === "SUBMITTED" && <Button onClick={review}>Review</Button>}
            {a.status === "REVIEWED" && <Button onClick={approve}>Approve</Button>}
            {(a.status === "SUBMITTED" || a.status === "REVIEWED") && (
              <>
                <input style={{ minWidth: 220 }} value={reason} onChange={(e) => setReason(e.target.value)}
                  placeholder="rejection reason (required to reject)" />
                <Button kind="ghost" onClick={reject}>Reject</Button>
              </>
            )}
          </div>

          {a.status === "REJECTED" && a.rejectReason && (
            <div className="sub" style={{ marginTop: 10 }}>Rejection reason: <b>{a.rejectReason}</b></div>
          )}
        </Card>
      )}
    </div>
  );
}
