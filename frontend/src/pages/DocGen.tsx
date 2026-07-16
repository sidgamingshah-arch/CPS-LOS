/**
 * DocGen — Template-driven Document Generation with Clause Surgery
 *
 * Generates formal credit documents (sanction letters, term sheets, facility
 * agreements) from versioned templates held in decision-service. Each document
 * starts as a DRAFT with AI-assembled clauses; the credit officer can add
 * standard T&C clauses from the clause library, remove or inline-edit individual
 * clauses, and preview the full rendered HTML before committing. Nothing becomes
 * binding until a named human hits "Confirm" — that event is recorded in the
 * audit trail with the actor's identity (X-Actor header). Withdrawn documents
 * move to WITHDRAWN and cannot be edited. All AI-produced content carries an
 * explicit advisory label so users understand that the generation step is
 * advisory; the confirm gate is the human accountability checkpoint (PRD §11).
 */

import { useState } from "react";
import { origination, docs, printing } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, GovFlow, useAsync } from "../ui";

/** Shape of a generated document as returned by docs.list / docs.generate */
type GeneratedDocument = {
  id: number;
  applicationReference: string;
  templateKey: string;
  format: string;
  title: string;
  html: string;
  clauseOrder: string[];
  clauses: Record<string, { title: string; text: string; source: string; addedBy?: string; editedBy?: string }>;
  variables: Record<string, unknown>;
  status: "DRAFT" | "CONFIRMED" | "ISSUED" | "WITHDRAWN";
  advisory: string;
  generatedBy: string;
  confirmedBy?: string;
  confirmedAt?: string;
};

function statusBadgeKind(status: string): string {
  if (status === "CONFIRMED" || status === "ISSUED") return "ok";
  if (status === "WITHDRAWN") return "bad";
  if (status === "DRAFT") return "ai";
  return "info";
}

export default function DocGen() {
  const { actor, notify, ref: ctxRef } = useApp();

  /* ── Deal selector ─────────────────────────────────────────── */
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");

  /* ── Document list for selected deal ──────────────────────── */
  const docList = useAsync<GeneratedDocument[]>(
    () => (ref ? docs.list(ref) as Promise<GeneratedDocument[]> : Promise.resolve([])),
    [ref],
  );

  /* ── Selected document (derived from list) ─────────────────── */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selectedDoc: GeneratedDocument | undefined =
    (docList.data ?? []).find((d) => d.id === selectedId);

  /* ── Template list ─────────────────────────────────────────── */
  const templates = useAsync(() => docs.templates(), []);

  /* ── T&C clause library ────────────────────────────────────── */
  const tncClauses = useAsync(() => docs.tncClauses(), []);

  /* ── Generate form state ───────────────────────────────────── */
  const [genTemplate, setGenTemplate] = useState<string>("");
  const [genBusy, setGenBusy] = useState(false);

  const handleGenerate = async () => {
    if (!ref || !genTemplate) { notify("Select a deal and a template first.", true); return; }
    setGenBusy(true);
    try {
      const newDoc = await docs.generate(ref, { templateKey: genTemplate, variables: {} }, actor) as GeneratedDocument;
      notify(`Document "${newDoc.title}" generated.`);
      docList.reload();
      setSelectedId(newDoc.id);
    } catch (e: any) { notify(e.message, true); }
    finally { setGenBusy(false); }
  };

  /* ── Add-clause form state ─────────────────────────────────── */
  const [addClauseRef, setAddClauseRef] = useState<string>("");
  const [addTncKey, setAddTncKey] = useState<string>("");
  const [addTitle, setAddTitle] = useState<string>("");
  const [addText, setAddText] = useState<string>("");
  const [addPos, setAddPos] = useState<string>("");
  const [addBusy, setAddBusy] = useState(false);

  // The clause library is the primary selector — picking a library clause keys the
  // new clause by its recordKey; the typed reference is only the custom-clause fallback.
  const effectiveClauseRef = (addTncKey || addClauseRef).trim();

  const handleAddClause = async () => {
    if (!selectedDoc || !effectiveClauseRef) { notify("Pick a library clause or enter a clause reference.", true); return; }
    setAddBusy(true);
    try {
      await docs.addClause(
        selectedDoc.id,
        {
          clauseRef: effectiveClauseRef,
          tncRecordKey: addTncKey || undefined,
          customTitle: addTitle || undefined,
          customText: addText || undefined,
          position: addPos !== "" ? Number(addPos) : undefined,
        },
        actor,
      );
      notify("Clause added.");
      setAddClauseRef(""); setAddTncKey(""); setAddTitle(""); setAddText(""); setAddPos("");
      docList.reload();
    } catch (e: any) { notify(e.message, true); }
    finally { setAddBusy(false); }
  };

  /* ── Clause edit ───────────────────────────────────────────── */
  const handleEditClause = async (clauseRef: string, currentText: string) => {
    if (!selectedDoc) return;
    const newText = window.prompt("Edit clause text:", currentText);
    if (newText === null || newText === currentText) return;
    try {
      await docs.editClause(selectedDoc.id, clauseRef, { text: newText }, actor);
      notify("Clause updated.");
      docList.reload();
    } catch (e: any) { notify(e.message, true); }
  };

  /* ── Clause remove ─────────────────────────────────────────── */
  const handleRemoveClause = async (clauseRef: string) => {
    if (!selectedDoc) return;
    if (!window.confirm(`Remove clause "${clauseRef}"?`)) return;
    try {
      await docs.removeClause(selectedDoc.id, clauseRef, actor);
      notify("Clause removed.");
      docList.reload();
    } catch (e: any) { notify(e.message, true); }
  };

  /* ── Confirm ───────────────────────────────────────────────── */
  const handleConfirm = async () => {
    if (!selectedDoc) return;
    const comment = window.prompt("Confirm document — enter a comment (required):");
    if (!comment) return;
    try {
      await docs.confirm(selectedDoc.id, { comment }, actor);
      notify("Document confirmed.");
      docList.reload();
    } catch (e: any) { notify(e.message, true); }
  };

  /* ── Download PDF (print pipeline) ─────────────────────────── */
  // Opens the backend print-optimised standalone HTML in a new window and lets the
  // browser save it as a PDF. The rendering is a faithful copy of the authoritative
  // document body — no clause or figure is recomputed.
  const handleDownloadPdf = () => {
    if (!selectedDoc) return;
    printing.print(printing.documentHtml(selectedDoc.id, actor), notify);
  };

  /* ── Withdraw ──────────────────────────────────────────────── */
  const handleWithdraw = async () => {
    if (!selectedDoc) return;
    if (!window.confirm("Withdraw this document? This cannot be undone.")) return;
    try {
      await docs.withdraw(selectedDoc.id, actor);
      notify("Document withdrawn.");
      setSelectedId(null);
      docList.reload();
    } catch (e: any) { notify(e.message, true); }
  };

  const isDraft = selectedDoc?.status === "DRAFT";

  return (
    <div className="grid">

      {/* ── Deal selector ── */}
      <Card title="Document Generation" sub="Select a deal to manage its generated documents."
        right={<GovFlow ai="AI DRAFTS" human="HUMAN CONFIRMS" note="confirmation locks the document" />}>
        <Field label="Deal reference">
          <select
            value={ref}
            onChange={(e) => { setRef(e.target.value); setSelectedId(null); }}
          >
            <option value="">— pick a deal —</option>
            {(apps.data ?? []).map((a: any) => (
              <option key={a.reference} value={a.reference}>
                {a.reference} · {a.counterpartyName} · {a.status}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {!ref && (
        <Card>
          <EmptyState
            glyph="◰"
            title="Select a deal to draft documents"
            sub="Pick an application above. Generation drafts from the template + clause library; nothing reaches the deal record until a named human confirms the draft."
          />
        </Card>
      )}

      {ref && (
        <>
          {/* ── Generate panel ── */}
          <Card title="Generate document" sub="Pick a template and generate a new DRAFT.">
            <div className="grid cols-2">
              <Field label="Template">
                <select value={genTemplate} onChange={(e) => setGenTemplate(e.target.value)}>
                  <option value="">— select template —</option>
                  {(templates.data ?? []).map((t: any) => (
                    <option key={t.recordKey} value={t.recordKey}>
                      {t.recordKey} ({t.format}) — {(t.clauses ?? []).length} clauses
                    </option>
                  ))}
                </select>
              </Field>
              <div style={{ display: "flex", alignItems: "flex-end" }}>
                <Button onClick={handleGenerate} busy={genBusy} disabled={!genTemplate || !ref}>
                  Generate
                </Button>
              </div>
            </div>
          </Card>

          {/* ── Documents table ── */}
          <Card
            title="Documents"
            sub={`${(docList.data ?? []).length} document(s) for ${ref}`}
          >
            {docList.loading && <div className="loading">Loading…</div>}
            {!docList.loading && (docList.data ?? []).length === 0 && (
              <EmptyState
                glyph="◰"
                title="No documents on this deal yet"
                sub="Generate one above to begin. Drafts are AI-authored; a named human must confirm before the document is locked to the deal."
              />
            )}
            {(docList.data ?? []).length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Title</th>
                    <th>Template</th>
                    <th>Status</th>
                    <th>Confirmed by</th>
                  </tr>
                </thead>
                <tbody>
                  {(docList.data ?? []).map((d) => (
                    <tr
                      key={d.id}
                      className="rowlink"
                      onClick={() => setSelectedId(d.id === selectedId ? null : d.id)}
                      style={d.id === selectedId ? { background: "var(--surface-raised, #f5f5f5)" } : undefined}
                    >
                      <td>{d.title}</td>
                      <td><Badge kind="info">{d.templateKey}</Badge></td>
                      <td><Badge kind={statusBadgeKind(d.status)}>{d.status}</Badge></td>
                      <td className="muted">{d.confirmedBy ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          {/* ── Document workspace ── */}
          {selectedDoc && (
            <>
              {/* Header card */}
              <Card
                title={selectedDoc.title}
                sub={`Generated by ${selectedDoc.generatedBy}${selectedDoc.confirmedBy ? ` · Confirmed by ${selectedDoc.confirmedBy}` : ""}`}
                right={
                  <div className="btnrow">
                    <Badge kind="info">{selectedDoc.templateKey}</Badge>
                    <Badge kind={statusBadgeKind(selectedDoc.status)}>{selectedDoc.status}</Badge>
                    {isDraft && <Badge kind="ai">AI · advisory</Badge>}
                  </div>
                }
              >
                <div className="btnrow print-actions" style={{ marginTop: 8 }}>
                  <Button kind="ghost" onClick={handleDownloadPdf}>Download PDF</Button>
                  {isDraft && <Button onClick={handleConfirm}>Confirm</Button>}
                  {isDraft && <Button kind="danger" onClick={handleWithdraw}>Withdraw</Button>}
                </div>
              </Card>

              {/* Two-column workspace */}
              <div className="grid cols-2">

                {/* Left — clauses */}
                <div className="grid">
                  <Card title="Clauses" sub="Ordered list of document clauses.">
                    {selectedDoc.clauseOrder.length === 0 && (
                      <div className="muted">No clauses in this document.</div>
                    )}
                    {selectedDoc.clauseOrder.length > 0 && (
                      <table>
                        <thead>
                          <tr>
                            <th className="num">#</th>
                            <th>Title</th>
                            <th>Source</th>
                            {isDraft && <th>Actions</th>}
                          </tr>
                        </thead>
                        <tbody>
                          {selectedDoc.clauseOrder.map((cRef, idx) => {
                            const cl = selectedDoc.clauses[cRef];
                            if (!cl) return null;
                            return (
                              <tr key={cRef}>
                                <td className="num">{idx + 1}</td>
                                <td>
                                  {cl.title}
                                  {cl.editedBy && (
                                    <small className="prov"> · edited by {cl.editedBy}</small>
                                  )}
                                </td>
                                <td>
                                  <small className="prov">{cl.source}{cl.addedBy ? ` · added by ${cl.addedBy}` : ""}</small>
                                </td>
                                {isDraft && (
                                  <td>
                                    <div className="btnrow">
                                      <Button
                                        kind="subtle"
                                        onClick={() => handleEditClause(cRef, cl.text)}
                                      >
                                        Edit
                                      </Button>
                                      <Button
                                        kind="danger"
                                        onClick={() => handleRemoveClause(cRef)}
                                      >
                                        Remove
                                      </Button>
                                    </div>
                                  </td>
                                )}
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    )}
                  </Card>

                  {/* Add clause form — DRAFT only */}
                  {isDraft && (
                    <Card title="Add clause" sub="Pick a standard T&C clause from the library, or write a custom one.">
                      {(tncClauses.data ?? []).length > 0 && (
                        <Field label="T&C clause (library)" hint="Picking a library clause keys it automatically — no reference needed.">
                          <select value={addTncKey} onChange={(e) => setAddTncKey(e.target.value)}>
                            <option value="">— custom clause —</option>
                            {(tncClauses.data ?? []).map((c: any) => (
                              <option key={c.recordKey} value={c.recordKey}>
                                {c.recordKey}{c.appliesTo ? ` (${c.appliesTo})` : ""}
                              </option>
                            ))}
                          </select>
                        </Field>
                      )}
                      {!addTncKey && (
                        <Field label="Clause reference" required>
                          <input
                            type="text"
                            value={addClauseRef}
                            onChange={(e) => setAddClauseRef(e.target.value)}
                            placeholder="e.g. EVENTS_OF_DEFAULT"
                          />
                        </Field>
                      )}
                      <Field label="Custom title (optional)">
                        <input
                          type="text"
                          value={addTitle}
                          onChange={(e) => setAddTitle(e.target.value)}
                          placeholder="Override clause title"
                        />
                      </Field>
                      <Field label="Custom text (optional)">
                        <textarea
                          value={addText}
                          onChange={(e) => setAddText(e.target.value)}
                          rows={3}
                          placeholder="Override clause body text"
                        />
                      </Field>
                      <Field label="Position (optional, 1-based)">
                        <input
                          type="number"
                          value={addPos}
                          onChange={(e) => setAddPos(e.target.value)}
                          placeholder="Leave blank to append"
                          min={1}
                        />
                      </Field>
                      <Button onClick={handleAddClause} busy={addBusy} disabled={!effectiveClauseRef}>
                        Add clause
                      </Button>
                    </Card>
                  )}
                </div>

                {/* Right — HTML preview */}
                <Card title="Document preview">
                  {selectedDoc.html ? (
                    <div
                      className="helix-doc-preview"
                      dangerouslySetInnerHTML={{ __html: selectedDoc.html }}
                    />
                  ) : (
                    <div className="muted">No preview available.</div>
                  )}
                </Card>

              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
