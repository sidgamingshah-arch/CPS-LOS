/**
 * Execution — Document Execution Workflow + Signatory Matrix (CLoM R1-14 / F73-F74)
 *
 * Tracks the signing / receipt of the documents generated for a deal. Pick a deal, pick
 * the generated documents to execute, and open an execution package. Each document runs an
 * execution lifecycle (PENDING → SENT → SIGNED → RECEIVED) with a facade e-sign envelope id
 * stamped on SENT, a per-document signatory matrix (INTERNAL / CUSTOMER sides, mark-signed),
 * and deferral / waiver tags. The package auto-completes once every document is received or
 * waived.
 *
 * Governance: execution tracks status only — the source generated document (content +
 * confirm-lock) is never edited here, so the authoritative document stays byte-identical.
 * Every transition is recorded against the acting human (X-Actor).
 */

import { useMemo, useState } from "react";
import { docs, execution, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, HumanBadge, statusTone, useAsync } from "../ui";

type GeneratedDocument = {
  id: number;
  title: string;
  templateKey: string;
  status: string;
};

type Signatory = {
  id: number;
  signatoryName: string;
  signatoryRole?: string;
  side: "INTERNAL" | "CUSTOMER";
  status: "PENDING" | "SIGNED";
  signedAt?: string;
};

type DocumentExecution = {
  id: number;
  execRef: string;
  docRef: string;
  documentTitle: string;
  status: "PENDING" | "SENT" | "SIGNED" | "RECEIVED";
  esignEnvelopeId?: string;
  deferralTag?: string;
  waiverTag?: string;
};

type PackageView = {
  executionPackage: { execRef: string; subjectRef: string; status: string; createdBy?: string };
  documents: { document: DocumentExecution; signatories: Signatory[] }[];
};

const STATUS_STEPS: DocumentExecution["status"][] = ["PENDING", "SENT", "SIGNED", "RECEIVED"];

function docStatusTone(status: string): string {
  if (status === "RECEIVED") return "ok";
  if (status === "SIGNED") return "info";
  if (status === "SENT") return "ai";
  return "";
}

export default function Execution() {
  const { actor, notify, ref: ctxRef } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");
  const [selected, setSelected] = useState<string | null>(null);

  const packages = useAsync<any[]>(
    () => (ref ? execution.list(ref) : Promise.resolve([])),
    [ref],
  );

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="Document Execution"
          sub="Select a deal to track the signing / receipt of its generated documents."
          right={<HumanBadge label="HUMAN-EXECUTED" />}
        >
          <Field label="Deal reference">
            <select
              value={ref}
              onChange={(e) => { setRef(e.target.value); setSelected(null); }}
            >
              <option value="">— pick a deal —</option>
              {(apps.data ?? []).map((a: any) => (
                <option key={a.reference} value={a.reference}>
                  {a.reference} · {a.counterpartyName} · {a.status}
                </option>
              ))}
            </select>
          </Field>
          <div className="exec-gate">
            <span>Execution tracks status only. The source generated document (content +
              confirm-lock) is never edited — the authoritative document stays unchanged.</span>
          </div>
        </Card>

        {ref && (
          <Card
            title="Execution packages"
            sub={`${(packages.data ?? []).length} package(s) for ${ref}`}
          >
            {(packages.data ?? []).length === 0 ? (
              <EmptyState glyph="◰" title="No execution packages yet"
                sub="Build one on the right from this deal's generated documents." />
            ) : (
              <table>
                <thead><tr><th>Package</th><th>Documents</th><th>Status</th></tr></thead>
                <tbody>
                  {(packages.data ?? []).map((p: any) => (
                    <tr key={p.execRef} className="rowlink"
                      onClick={() => setSelected(p.execRef)}
                      style={p.execRef === selected ? { background: "var(--surface-raised, #f5f5f5)" } : undefined}>
                      <td className="mono">{p.execRef}</td>
                      <td className="muted">{p.subjectRef}</td>
                      <td><Badge kind={statusTone(p.status)}>{p.status}</Badge></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        )}

        {ref && (
          <BuildPackage ref_={ref} actor={actor} notify={notify}
            onDone={(execRef) => { packages.reload(); setSelected(execRef); }} />
        )}
      </div>

      <div>
        {selected ? (
          <PackageDetail execRef={selected} onChange={packages.reload} />
        ) : (
          <Card>
            <EmptyState glyph="◧" title="Select a package to track execution"
              sub="Pick a package to open its documents — build the signatory matrix, mark signatures, drive the status stepper, and tag deferrals / waivers." />
          </Card>
        )}
      </div>
    </div>
  );
}

function BuildPackage({ ref_, actor, notify, onDone }: {
  ref_: string; actor: string; notify: any; onDone: (execRef: string) => void;
}) {
  const docList = useAsync<GeneratedDocument[]>(() => docs.list(ref_) as Promise<GeneratedDocument[]>, [ref_]);
  const [picked, setPicked] = useState<Record<number, boolean>>({});
  const [busy, setBusy] = useState(false);

  const toggle = (id: number) => setPicked((p) => ({ ...p, [id]: !p[id] }));
  const chosen = useMemo(
    () => (docList.data ?? []).filter((d) => picked[d.id]),
    [docList.data, picked],
  );

  const submit = async () => {
    if (chosen.length === 0) { notify("Pick at least one document to execute.", true); return; }
    setBusy(true);
    try {
      const body = {
        subjectRef: ref_,
        documents: chosen.map((d) => ({ docRef: String(d.id), title: d.title })),
      };
      const view = await execution.create(body, actor) as PackageView;
      notify(`Execution package ${view.executionPackage.execRef} opened (${chosen.length} document(s)).`);
      setPicked({});
      onDone(view.executionPackage.execRef);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Build execution package" sub="Select the generated documents to execute for this deal.">
      {docList.loading && <div className="loading">Loading…</div>}
      {!docList.loading && (docList.data ?? []).length === 0 && (
        <EmptyState glyph="◰" title="No generated documents on this deal"
          sub="Generate documents in Doc Generation first, then return here to track their execution." />
      )}
      {(docList.data ?? []).length > 0 && (
        <>
          <table>
            <thead><tr><th /><th>Title</th><th>Template</th><th>Doc status</th></tr></thead>
            <tbody>
              {(docList.data ?? []).map((d) => (
                <tr key={d.id}>
                  <td><input type="checkbox" checked={!!picked[d.id]} onChange={() => toggle(d.id)} /></td>
                  <td>{d.title}</td>
                  <td><Badge kind="info">{d.templateKey}</Badge></td>
                  <td><Badge kind={d.status === "CONFIRMED" || d.status === "ISSUED" ? "ok" : "ai"}>{d.status}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
          <Button onClick={submit} busy={busy} disabled={chosen.length === 0}>
            Open package ({chosen.length})
          </Button>
        </>
      )}
    </Card>
  );
}

function PackageDetail({ execRef, onChange }: { execRef: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync<PackageView>(() => execution.view(execRef), [execRef]);

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  const d = view.data!;
  const p = d.executionPackage;

  return (
    <div className="grid">
      <Card title={`Execution ${p.execRef}`} sub={`Deal ${p.subjectRef}${p.createdBy ? " · opened by " + p.createdBy : ""}`}
        right={<Badge kind={statusTone(p.status)}>{p.status}</Badge>}>
        <div className="exec-gate">
          <HumanBadge label="STATUS-ONLY" />
          <span>Each document runs PENDING → SENT → SIGNED → RECEIVED with a facade e-sign envelope
            stamped on SENT. The package completes when every document is received or waived.</span>
        </div>
      </Card>

      {d.documents.map(({ document: doc, signatories }) => (
        <Card key={doc.id} title={doc.documentTitle}
          sub={`doc ${doc.docRef}${doc.esignEnvelopeId ? " · envelope " + doc.esignEnvelopeId : ""}`}
          right={<Badge kind={docStatusTone(doc.status)}>{doc.status}</Badge>}>

          {/* Status stepper */}
          <div className="exec-stepper">
            {STATUS_STEPS.map((s, i) => {
              const activeIdx = STATUS_STEPS.indexOf(doc.status);
              const done = i <= activeIdx;
              return (
                <span key={s} className={`exec-step${done ? " done" : ""}${s === doc.status ? " current" : ""}`}>
                  {i > 0 && <span className="exec-step-sep" aria-hidden="true">→</span>}
                  {s}
                </span>
              );
            })}
          </div>

          <div className="btnrow" style={{ marginTop: 8 }}>
            {doc.status === "PENDING" && (
              <Button kind="subtle" onClick={() => run(() => execution.setStatus(execRef, doc.id, { status: "SENT" }, actor), "Document sent for signature")}>Send</Button>
            )}
            {doc.status !== "RECEIVED" && doc.status !== "SIGNED" && (
              <Button kind="subtle" onClick={() => run(() => execution.setStatus(execRef, doc.id, { status: "SIGNED" }, actor), "Document marked signed")}>Mark signed</Button>
            )}
            {doc.status !== "RECEIVED" && (
              <Button kind="subtle" onClick={() => run(() => execution.setStatus(execRef, doc.id, { status: "RECEIVED" }, actor), "Document received")}>Mark received</Button>
            )}
            <Button kind="ghost" onClick={() => {
              const tag = window.prompt("Deferral tag / reason:");
              if (tag) run(() => execution.defer(execRef, doc.id, { deferralTag: tag }, actor), "Document deferred");
            }}>Defer…</Button>
            <Button kind="ghost" onClick={() => {
              const tag = window.prompt("Waiver tag / reason:");
              if (tag) run(() => execution.waive(execRef, doc.id, { waiverTag: tag }, actor), "Document waived");
            }}>Waive…</Button>
          </div>

          {(doc.deferralTag || doc.waiverTag) && (
            <div className="btnrow" style={{ marginTop: 6 }}>
              {doc.deferralTag && <Badge kind="warn">Deferred · {doc.deferralTag}</Badge>}
              {doc.waiverTag && <Badge kind="warn">Waived · {doc.waiverTag}</Badge>}
            </div>
          )}

          {/* Signatory matrix */}
          <h4 className="exec-matrix-title">Signatory matrix</h4>
          {signatories.length === 0 ? (
            <div className="muted">No signatories yet — add the parties expected to sign below.</div>
          ) : (
            <table>
              <thead><tr><th>Signatory</th><th>Role</th><th>Side</th><th>Status</th><th /></tr></thead>
              <tbody>
                {signatories.map((s) => (
                  <tr key={s.id}>
                    <td>{s.signatoryName}</td>
                    <td className="muted">{s.signatoryRole ?? "—"}</td>
                    <td><Badge kind={s.side === "INTERNAL" ? "info" : ""}>{s.side}</Badge></td>
                    <td><Badge kind={s.status === "SIGNED" ? "ok" : ""}>{s.status}</Badge></td>
                    <td>
                      {s.status === "PENDING" && (
                        <button className="btn subtle"
                          onClick={() => run(() => execution.sign(execRef, doc.id, s.id, actor), "Signature recorded")}>Mark signed</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <AddSignatory execRef={execRef} docId={doc.id} actor={actor}
            onAdded={() => { view.reload(); onChange(); }} notify={notify} />
        </Card>
      ))}
    </div>
  );
}

function AddSignatory({ execRef, docId, actor, onAdded, notify }: {
  execRef: string; docId: number; actor: string; onAdded: () => void; notify: any;
}) {
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [side, setSide] = useState<"INTERNAL" | "CUSTOMER">("CUSTOMER");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!name.trim()) { notify("Signatory name is required.", true); return; }
    setBusy(true);
    try {
      await execution.addSignatory(execRef, docId, { name: name.trim(), role: role.trim() || undefined, side }, actor);
      notify("Signatory added.");
      setName(""); setRole("");
      onAdded();
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <div className="exec-add-sig">
      <input placeholder="Signatory name" value={name} onChange={(e) => setName(e.target.value)}
        style={{ minWidth: 160 }} />
      <input placeholder="Role (optional)" value={role} onChange={(e) => setRole(e.target.value)}
        style={{ minWidth: 130 }} />
      <select value={side} onChange={(e) => setSide(e.target.value as "INTERNAL" | "CUSTOMER")}
        style={{ width: "auto" }}>
        <option value="CUSTOMER">CUSTOMER</option>
        <option value="INTERNAL">INTERNAL</option>
      </select>
      <Button kind="subtle" onClick={submit} busy={busy} disabled={!name.trim()}>Add signatory</Button>
    </div>
  );
}
