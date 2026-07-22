import { useMemo, useState } from "react";
import { origination, risk, riskNotes, fmt } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, Col, DataTable, EmptyState, Field, GovSplit,
  GradeBadge, HumanBadge, Stat, Unchanged, statusTone, useAsync,
} from "../ui";

/**
 * Independent Risk Note (CLoM R1-13) — the risk function's OWN narrative opinion on a
 * credit, with a governed lifecycle (draft → submit → review → approve, plus reassign /
 * reject / reverse). Distinct from the statistical RAG overlay: this is a qualitative
 * opinion RECORD. It never mutates the authoritative rating — the GovSplit below shows
 * the advisory opinion on the left and the untouched rating of record on the right.
 */

const SECTIONS: { key: string; label: string; hint: string }[] = [
  { key: "RISK_OPINION", label: "Risk opinion", hint: "Independent risk view on the credit overall" },
  { key: "KEY_RISKS", label: "Key risks", hint: "Principal financial / business / structural / ESG risks" },
  { key: "MITIGANTS", label: "Mitigants", hint: "Structural protections that offset those risks" },
  { key: "RECOMMENDATION", label: "Recommendation", hint: "Risk-function recommendation and any conditions" },
];

const ACTIONS = ["SUPPORT", "SUPPORT_WITH_CONDITIONS", "DECLINE"];

function actionKind(a?: string): string {
  if (a === "SUPPORT") return "ok";
  if (a === "SUPPORT_WITH_CONDITIONS") return "warn";
  if (a === "DECLINE") return "bad";
  return "info";
}

export default function RiskNotes() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [subject, setSubject] = useState<string>("");
  const list = useAsync(() => riskNotes.list(subject || undefined), [subject]);
  const [selected, setSelected] = useState<string | null>(null);

  const reload = () => { list.reload(); };

  const columns: Col<any>[] = [
    { key: "riskNoteRef", header: "Ref", render: (r) => <span className="mono">{r.riskNoteRef}</span> },
    { key: "subjectRef", header: "Subject", render: (r) => <span className="mono">{r.subjectRef}</span> },
    {
      key: "recommendedAction", header: "Action",
      render: (r) => r.recommendedAction
        ? <Badge kind={actionKind(r.recommendedAction)}>{r.recommendedAction}</Badge>
        : <span className="muted">—</span>,
    },
    { key: "author", header: "Author" },
    { key: "assignedTo", header: "Assigned" },
    {
      key: "status", header: "Status",
      render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge>,
    },
    { key: "createdAt", header: "Created", value: (r) => r.createdAt || "", render: (r) => fmt.dateTime(r.createdAt) },
  ];

  return (
    <div className="grid">
      <div className="gov-banner">
        <h3>The risk function's own opinion — recorded, reviewed, approved. The rating never moves.</h3>
        <div className="gb-sub">
          An Independent Risk Note is a governed <b>opinion record</b>, distinct from the statistical RAG overlay.
          It forms a view <i>about</i> the deterministic rating and quotes a snapshot of it — but writes nothing back.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>AI</b> · optional advisory draft</span>
          <span className="gb-chip"><b>Human</b> · authors, reviews, approves (SoD)</span>
          <span className="gb-chip"><b>Deterministic</b> · rating of record unchanged</span>
        </div>
      </div>

      <div className="grid cols-2">
        <div className="grid">
          <Card title="Independent Risk Notes"
            right={<HumanBadge label="RISK OPINION RECORD" />}
            sub="Filter by deal, or browse every note. Click a row to open its lifecycle.">
            <Field label="Filter by deal">
              <select value={subject} onChange={(e) => { setSubject(e.target.value); setSelected(null); }}>
                <option value="">— all deals —</option>
                {(apps.data ?? []).map((a: any) => (
                  <option key={a.reference} value={a.reference}>
                    {a.reference} · {a.counterpartyName} · {a.status}
                  </option>
                ))}
              </select>
            </Field>
            {(list.data || []).length === 0 ? (
              <EmptyState glyph="◲" title="No risk notes yet" sub="Raise one with the form below." />
            ) : (
              <DataTable
                id="risk-notes"
                columns={columns}
                rows={list.data || []}
                rowKey={(r) => r.riskNoteRef}
                onRowClick={(r) => setSelected(r.riskNoteRef)}
                initialPageSize={10}
              />
            )}
          </Card>
          <CreateRiskNote apps={apps.data ?? []} preset={subject} actor={actor} notify={notify}
            onDone={(ref) => { reload(); setSelected(ref); }} />
        </div>
        <div>
          {selected ? (
            <RiskNoteDetail refId={selected} onChange={reload} />
          ) : (
            <Card>
              <EmptyState glyph="◳" title="Select a risk note"
                sub="Open a note to author its sections and drive its lifecycle — submit, review, approve, reassign, reject or reverse." />
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

function CreateRiskNote({ apps, preset, actor, notify, onDone }: {
  apps: any[]; preset: string; actor: string;
  notify: (t: string, e?: boolean) => void; onDone: (ref: string) => void;
}) {
  const [subjectRef, setSubjectRef] = useState(preset);
  const [action, setAction] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!subjectRef.trim()) { setErr("Select the deal this opinion is about"); return; }
    setErr(null); setBusy(true);
    try {
      const n = await riskNotes.create(
        { subjectRef: subjectRef.trim(), recommendedAction: action || undefined }, actor);
      notify(`Risk note ${n.riskNoteRef} raised (DRAFT)`);
      setAction("");
      onDone(n.riskNoteRef);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Raise an independent risk note"
      sub="Created as DRAFT under your name; author the sections next, then submit for review.">
      <Field label="Deal (subject)" required error={err}>
        <select value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)}>
          <option value="">— select a deal —</option>
          {apps.map((a) => (
            <option key={a.reference} value={a.reference}>
              {a.reference} · {a.counterpartyName}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Recommended action" hint="Optional at draft; set it before approval">
        <select value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="">— not set —</option>
          {ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      </Field>
      <Button onClick={submit} busy={busy}>Create draft</Button>
    </Card>
  );
}

function RiskNoteDetail({ refId, onChange }: { refId: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => riskNotes.get(refId), [refId]);
  const n = view.data;
  const ratingAsync = useAsync(
    () => (n?.subjectRef ? risk.summary(n.subjectRef).catch(() => null) : Promise.resolve(null)),
    [n?.subjectRef],
  );

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  if (view.error) return <Card title="Error"><div className="err">{view.error}</div></Card>;
  const st: string = n.status;
  const rating = ratingAsync.data?.rating ?? null;

  return (
    <div className="grid">
      <Card title={`${n.riskNoteRef}`}
        sub={`Independent risk note · subject ${n.subjectRef}`}
        right={<Badge kind={statusTone(st)}>{st}</Badge>}>
        <div className="gate">
          <HumanBadge label="HUMAN-GATED" /> Every transition is a named-human action, audited. An independent
          risk note is an opinion record — it never mutates the authoritative rating.
        </div>
        <table className="kv">
          <tbody>
            <tr><th>Author</th><td>{n.author}</td></tr>
            <tr><th>Assigned to</th><td>{n.assignedTo || "—"}</td></tr>
            {n.reviewer && <tr><th>Reviewer / approver</th><td>{n.reviewer}</td></tr>}
            <tr><th>Recommended action</th><td>
              {n.recommendedAction ? <Badge kind={actionKind(n.recommendedAction)}>{n.recommendedAction}</Badge> : <span className="muted">—</span>}
            </td></tr>
            {n.gradeSnapshot && <tr><th>Grade at authoring</th><td><GradeBadge grade={n.gradeSnapshot} /> <span className="muted">(snapshot · read-only)</span></td></tr>}
            {n.decisionNote && <tr><th>Note</th><td>{n.decisionNote}</td></tr>}
          </tbody>
        </table>
      </Card>

      {/* Signature governance frame: the advisory opinion ↔ the authoritative rating UNCHANGED */}
      <Card title="Governance view" sub="The risk opinion on the left; the figure of record on the right — untouched.">
        <GovSplit
          advisoryLabel="Independent risk opinion (advisory)"
          advisory={
            <div className="inline" style={{ gap: 12, flexWrap: "wrap" }}>
              {n.recommendedAction
                ? <Badge kind={actionKind(n.recommendedAction)}>{n.recommendedAction}</Badge>
                : <span className="muted">recommendation not set</span>}
              <Badge kind={statusTone(st)}>{st}</Badge>
            </div>
          }
          authLabel="Authoritative rating"
          auth={
            rating ? (
              <div className="inline" style={{ gap: 12 }}>
                <GradeBadge grade={rating.finalGrade} />
                <span className="muted" style={{ fontSize: 13 }}>PD {fmt.pct(rating.pd, 2)}</span>
              </div>
            ) : <span className="muted">Rate the deal to see the rating of record.</span>
          }
        />
      </Card>

      {st === "DRAFT" && <SectionsEditor n={n} actor={actor} notify={notify} onDone={() => { view.reload(); onChange(); }} />}

      {st !== "DRAFT" && <SectionsView sections={n.sections} />}

      <Card title="Workflow">
        <div className="btnrow">
          {st === "DRAFT" && (
            <Button onClick={() => run(() => riskNotes.submit(refId, actor), "Submitted for review")}>Submit for review</Button>
          )}
          {st === "SUBMITTED" && (
            <Button onClick={() => run(() => riskNotes.review(refId, actor), "Reviewed")}>Mark reviewed</Button>
          )}
          {st === "REVIEWED" && (
            <Button onClick={() => {
              const note = window.prompt("Approval note (optional):") ?? undefined;
              run(() => riskNotes.approve(refId, note || undefined, actor), "Approved");
            }}>Approve</Button>
          )}
          {(st === "SUBMITTED" || st === "REVIEWED") && (
            <button className="btn danger" onClick={() => {
              const reason = window.prompt("Rejection reason (mandatory):");
              if (reason && reason.trim()) run(() => riskNotes.reject(refId, reason.trim(), actor), "Rejected");
              else if (reason !== null) notify("A rejection reason is mandatory", true);
            }}>Reject…</button>
          )}
          {(st === "DRAFT" || st === "SUBMITTED" || st === "REVIEWED") && (
            <button className="btn" onClick={() => {
              const to = window.prompt("Reassign to (actor):");
              if (to && to.trim()) run(() => riskNotes.reassign(refId, to.trim(), actor), "Reassigned");
              else if (to !== null) notify("A target actor is required", true);
            }}>Reassign…</button>
          )}
          {st === "APPROVED" && (
            <button className="btn danger" onClick={() => {
              const reason = window.prompt("Reversal reason (mandatory):");
              if (reason && reason.trim()) {
                if (window.confirm(`Reverse risk note ${refId}? This records a reversal — it changes no figure.`))
                  run(() => riskNotes.reverse(refId, reason.trim(), actor), "Reversed");
              } else if (reason !== null) notify("A reversal reason is mandatory", true);
            }}>Reverse…</button>
          )}
        </div>
        <small className="prov">
          Acting as <b>{actor}</b>. Review and approval require a different human than the author (SoD); reject and
          reverse require a mandatory reason. All server-enforced.
        </small>
      </Card>
    </div>
  );
}

function SectionsEditor({ n, actor, notify, onDone }: {
  n: any; actor: string; notify: (t: string, e?: boolean) => void; onDone: () => void;
}) {
  const initial: Record<string, string> = {};
  for (const s of SECTIONS) initial[s.key] = (n.sections?.[s.key] as string) ?? "";
  const [sections, setSections] = useState<Record<string, string>>(initial);
  const [action, setAction] = useState<string>(n.recommendedAction ?? "");
  const [aiDraft, setAiDraft] = useState(false);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setBusy(true);
    try {
      const updated = await riskNotes.updateSections(
        n.riskNoteRef, { sections, aiDraft, recommendedAction: action || undefined }, actor);
      // Reflect any AI-drafted blanks the server filled in.
      const next: Record<string, string> = {};
      for (const s of SECTIONS) next[s.key] = (updated.sections?.[s.key] as string) ?? "";
      setSections(next);
      notify(aiDraft ? "Sections saved (AI drafted any blanks where a model is configured)" : "Sections saved");
      onDone();
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Author sections"
      right={<AiBadge label="AI DRAFT · OPTIONAL" />}
      sub="Draft the narrative while the note is a DRAFT. AI-draft fills only blank sections, grounded on the rating snapshot, and never overwrites your text.">
      {SECTIONS.map((s) => (
        <Field key={s.key} label={s.label} hint={s.hint}>
          <textarea rows={3} value={sections[s.key]}
            onChange={(e) => setSections((m) => ({ ...m, [s.key]: e.target.value }))}
            placeholder={`Author the ${s.label.toLowerCase()}…`} />
        </Field>
      ))}
      <Field label="Recommended action">
        <select value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="">— not set —</option>
          {ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      </Field>
      <label className="inline" style={{ gap: 8, alignItems: "center", margin: "8px 0" }}>
        <input type="checkbox" checked={aiDraft} onChange={(e) => setAiDraft(e.target.checked)} />
        <span>AI-draft blank sections <span className="muted">(advisory · fail-soft · audited as an AI event)</span></span>
      </label>
      <div className="btnrow">
        <Button kind="primary" busy={busy} onClick={save}>Save sections</Button>
        <Unchanged label="RATING OF RECORD UNTOUCHED" />
      </div>
    </Card>
  );
}

function SectionsView({ sections }: { sections?: Record<string, any> }) {
  const has = sections && Object.keys(sections).length > 0;
  return (
    <Card title="Risk note narrative" sub="Read-only once submitted.">
      {!has && <div className="muted">No sections were authored.</div>}
      {has && (
        <div className="rn-narrative">
          {SECTIONS.map((s) => (
            sections?.[s.key] ? (
              <div key={s.key} className="rn-section">
                <h4>{s.label}</h4>
                <p className="prov">{String(sections[s.key])}</p>
              </div>
            ) : null
          ))}
        </div>
      )}
    </Card>
  );
}
