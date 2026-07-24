/**
 * DocIntel — GenAI Document Intelligence
 *
 * Provides AI-assisted document processing across four capabilities, all audited
 * and advisory-only; none touch the figure path or auto-apply credit-consequential
 * data:
 *
 *  1. Extraction — GenAI reads a classified document and suggests field values with
 *     per-field confidence and source-page citations. Every extraction is a
 *     SUGGESTED record that a human analyst must CONFIRM or REJECT; it is never
 *     applied to the financial spread automatically (that requires the separate
 *     human-gated spreading + confirm-spread workflow).
 *
 *  2. Document checks — structural/completeness checks on a selected document
 *     (e.g. missing signatures, stale dates, mismatched entity names). Findings
 *     carry an OK/INFO/WARN/ERROR level; purely advisory.
 *
 *  3. Language normalisation — rewrites freeform contract or financial text to a
 *     target register (LEGAL or PLAIN). Produces a rewritten version plus a notes
 *     list explaining each change. Marked "AI · advisory".
 *
 *  4. Translation — translates a snippet to a target language, reporting source
 *     language and confidence. Marked "AI · advisory".
 *
 * Every mutation carries X-Actor (from app-context) so the audit trail records
 * actorType for human accountability. The copilot / advisory label is displayed
 * prominently so users understand that no figure here feeds the rating or capital
 * engines.
 */

import { useState } from "react";
import { origination, docIntel, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, GovFlow, useAsync } from "../ui";
import { useCodes } from "../code-values";

const FALLBACK_DECLARED_TYPE = "FINANCIAL_STATEMENT";

type FieldEntry = { value: unknown; confidence: number; sourcePage: number };
type DocExtraction = {
  id: number;
  documentId: number;
  applicationReference: string;
  classifiedType: string;
  detectedLanguage: string;
  model: string;
  overallConfidence: number;
  status: "SUGGESTED" | "CONFIRMED" | "REJECTED";
  fields: Record<string, FieldEntry>;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewNote?: string;
};
type NormaliseResponse = {
  target: string;
  original: string;
  rewritten: string;
  notes: string[];
  advisory: string;
};
type TranslateResponse = {
  sourceLanguage: string;
  targetLanguage: string;
  original: string;
  translated: string;
  confidence: number;
  advisory: string;
};
type CheckFinding = { level: "OK" | "INFO" | "WARN" | "ERROR"; code: string; message: string };
type DocCheckResponse = {
  documentId: number;
  classifiedType: string;
  passed: boolean;
  findings: CheckFinding[];
  advisory: string;
};

function extractionStatusBadge(status: DocExtraction["status"]) {
  if (status === "CONFIRMED") return <Badge kind="ok">CONFIRMED</Badge>;
  if (status === "REJECTED") return <Badge kind="bad">REJECTED</Badge>;
  return <Badge kind="ai">SUGGESTED</Badge>;
}

function findingBadge(level: CheckFinding["level"]) {
  if (level === "OK") return <Badge kind="ok">OK</Badge>;
  if (level === "INFO") return <Badge kind="info">INFO</Badge>;
  if (level === "WARN") return <Badge kind="warn">WARN</Badge>;
  return <Badge kind="bad">ERROR</Badge>;
}

// Human-readable badge for how a document's text was captured from the real bytes.
function methodBadge(method?: string, ocrUsed?: boolean) {
  if (!method) return null;
  if (method === "PDFBOX") return <Badge kind="ok">PDFBox · real text</Badge>;
  if (method === "TEXT") return <Badge kind="ok">Text · real content</Badge>;
  if (method.startsWith("OCR_") && method !== "OCR_NONE") return <Badge kind="info">OCR{ocrUsed ? "" : ""}</Badge>;
  if (method === "OCR_NONE") return <Badge kind="warn">No embedded text</Badge>;
  return <Badge kind="warn">{method}</Badge>;
}

export default function DocIntel() {
  const { actor, notify, ref: ctxRef } = useApp();

  // Deal + doc selection
  const deals = useAsync(() => origination.list(), []);
  const [selectedRef, setSelectedRef] = useState<string>(ctxRef ?? "");
  const docs = useAsync(
    () => (selectedRef ? origination.docs(selectedRef) : Promise.resolve([])),
    [selectedRef],
  );
  const [selectedDocId, setSelectedDocId] = useState<number | null>(null);

  // Upload form
  const [uploadFileName, setUploadFileName] = useState("");
  const docKinds = useCodes("DOC_KIND");
  const translationLanguages = useCodes("TRANSLATION_LANGUAGE");
  const [uploadDeclaredType, setUploadDeclaredType] = useState(FALLBACK_DECLARED_TYPE);
  const [uploading, setUploading] = useState(false);
  // Real file upload (bytes → DMS + text extraction)
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadingFile, setUploadingFile] = useState(false);

  // Extractions
  const extractions = useAsync<DocExtraction[]>(
    () =>
      selectedDocId
        ? (docIntel.extractions(selectedDocId) as Promise<DocExtraction[]>)
        : Promise.resolve([]),
    [selectedDocId],
  );
  const [extracting, setExtracting] = useState(false);

  // Checks
  const [checks, setChecks] = useState<DocCheckResponse | null>(null);
  const [checking, setChecking] = useState(false);

  // Language tools
  const [normText, setNormText] = useState("");
  const [normTarget, setNormTarget] = useState<"LEGAL" | "PLAIN">("PLAIN");
  const [normResult, setNormResult] = useState<NormaliseResponse | null>(null);
  const [norming, setNorming] = useState(false);

  const [transText, setTransText] = useState("");
  const [transLang, setTransLang] = useState("en");
  const [transResult, setTransResult] = useState<TranslateResponse | null>(null);
  const [translating, setTranslating] = useState(false);

  const handleSelectRef = (ref: string) => {
    setSelectedRef(ref);
    setSelectedDocId(null);
    setChecks(null);
  };

  const handleUpload = async () => {
    if (!selectedRef || !uploadFileName.trim()) return;
    setUploading(true);
    try {
      await origination.uploadDoc(
        selectedRef,
        { fileName: uploadFileName.trim(), declaredType: uploadDeclaredType },
        actor,
      );
      notify("Document uploaded");
      setUploadFileName("");
      docs.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setUploading(false);
    }
  };

  const handleUploadFile = async () => {
    if (!selectedRef || !uploadFile) return;
    setUploadingFile(true);
    try {
      const doc = await origination.uploadDocumentFile(selectedRef, uploadFile, uploadDeclaredType || undefined, actor);
      notify(`Uploaded ${doc.fileName} — ${doc.extractionMethod} · classified ${doc.classifiedType}`);
      setUploadFile(null);
      docs.reload();
      // Select the freshly uploaded document so its extracted text + extraction flow show at once.
      if (doc.id) { setSelectedDocId(doc.id); setChecks(null); }
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setUploadingFile(false);
    }
  };

  const handleExtract = async () => {
    if (!selectedDocId) return;
    setExtracting(true);
    try {
      await docIntel.extract(selectedDocId, actor);
      notify("Extraction complete — review the suggestion below");
      extractions.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setExtracting(false);
    }
  };

  const handleConfirm = async (ex: DocExtraction) => {
    const note = window.prompt("Confirmation note (optional):") ?? "";
    try {
      await docIntel.confirm(ex.id, { note }, actor);
      // Confirming a FINANCIAL_STATEMENT extraction auto-drafts an (unconfirmed) spread — surface that.
      notify(ex.classifiedType === "FINANCIAL_STATEMENT"
        ? "Extraction confirmed — a DRAFT spread was auto-drafted; review & confirm it on Spreading"
        : "Extraction confirmed");
      extractions.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const handleReject = async (ex: DocExtraction) => {
    const reason = window.prompt("Rejection reason:");
    if (!reason) return;
    try {
      await docIntel.reject(ex.id, { reason }, actor);
      notify("Extraction rejected");
      extractions.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  };

  const handleChecks = async () => {
    if (!selectedDocId) return;
    setChecking(true);
    try {
      const result = (await docIntel.checks(selectedDocId)) as DocCheckResponse;
      setChecks(result);
      notify("Checks complete");
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setChecking(false);
    }
  };

  const handleNormalise = async () => {
    if (!normText.trim()) return;
    setNorming(true);
    try {
      const result = (await docIntel.normalise({ text: normText, target: normTarget }, actor)) as NormaliseResponse;
      setNormResult(result);
      notify("Normalisation complete");
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setNorming(false);
    }
  };

  const handleTranslate = async () => {
    if (!transText.trim()) return;
    setTranslating(true);
    try {
      const result = (await docIntel.translate({ text: transText, targetLanguage: transLang }, actor)) as TranslateResponse;
      setTransResult(result);
      notify("Translation complete");
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setTranslating(false);
    }
  };

  return (
    <div className="grid">

      {/* ── Deal selector ── */}
      <Card title="Document Intelligence" sub="Select a deal to browse and analyse its documents. AI extraction is advisory — it never auto-applies to the figure path; a human analyst must confirm."
        right={<GovFlow ai="AI EXTRACTS" human="HUMAN CONFIRMS" note="figures stay human-spread" />}>
        <Field label="Deal">
          <select value={selectedRef} onChange={(e) => handleSelectRef(e.target.value)}>
            <option value="">— select deal —</option>
            {(deals.data || []).map((d: any) => (
              <option key={d.reference} value={d.reference}>
                {d.reference} · {d.counterpartyName} · {d.status}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {!selectedRef && (
        <Card>
          <EmptyState
            glyph="◴"
            title="Select a deal to load its documents"
            sub="Pick an application above. You can then upload financials / approvals / KYC docs and let AI classify and extract — every extraction is advisory until an analyst confirms it."
          />
        </Card>
      )}

      {/* ── Documents table + upload ── */}
      {selectedRef && (
        <Card
          title="Documents"
          sub="Upload the real file — the server stores the bytes, extracts the document's actual text (PDFBox / OCR) and classifies from content. AI classification confidence routes low-confidence items to human review."
          right={
            <div style={{ display: "flex", flexDirection: "column", gap: 8, alignItems: "flex-end" }}>
              {/* Real file upload (bytes → DMS + text extraction) */}
              <div className="btnrow">
                <input
                  type="file"
                  onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)}
                  style={{ maxWidth: 220 }}
                />
                <select
                  value={uploadDeclaredType}
                  onChange={(e) => setUploadDeclaredType(e.target.value)}
                  title="Optional declared type"
                >
                  <option value="">(auto-classify)</option>
                  {docKinds.map((t) => (
                    <option key={t.code} value={t.code}>{t.label}</option>
                  ))}
                </select>
                <Button onClick={handleUploadFile} busy={uploadingFile} disabled={!uploadFile}>
                  Upload &amp; extract
                </Button>
              </div>
              {/* Legacy filename-only quick path (kept for demos / seeded records) */}
              <div className="btnrow">
                <input
                  placeholder="or file name only"
                  value={uploadFileName}
                  onChange={(e) => setUploadFileName(e.target.value)}
                  style={{ width: 200 }}
                />
                <Button kind="subtle" onClick={handleUpload} busy={uploading} disabled={!uploadFileName.trim()}>
                  Quick add (name only)
                </Button>
              </div>
            </div>
          }
        >
          {docs.loading && <div className="loading">Loading documents…</div>}
          {!docs.loading && (docs.data || []).length === 0 && (
            <EmptyState
              glyph="⤴"
              title="No documents on this deal yet"
              sub="Upload one with the controls above. AI will classify it and extract structured fields — you confirm what's right before any data lands on the figure path."
            />
          )}
          {(docs.data || []).length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>File name</th>
                  <th>Declared type</th>
                  <th>Classified type</th>
                  <th className="num">Confidence</th>
                  <th>Review flag</th>
                </tr>
              </thead>
              <tbody>
                {docs.data!.map((doc: any) => (
                  <tr
                    key={doc.id}
                    className={`rowlink${selectedDocId === doc.id ? " selected" : ""}`}
                    onClick={() => { setSelectedDocId(doc.id); setChecks(null); }}
                  >
                    <td>{doc.fileName}</td>
                    <td><span className="mono">{doc.declaredType}</span></td>
                    <td><span className="mono">{doc.classifiedType}</span></td>
                    <td className="num">{fmt.pct(doc.classificationConfidence)}</td>
                    <td>
                      {doc.needsReview
                        ? <Badge kind="warn">needs review</Badge>
                        : <Badge kind="ok">auto-routed</Badge>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {selectedDocId && (() => {
            const sel = (docs.data || []).find((d: any) => d.id === selectedDocId);
            const text: string | null = sel?.extractedText ?? null;
            const lines = text ? text.split(/\r?\n/).filter((l: string) => l.trim().length > 0) : [];
            const preview = lines.slice(0, 15);
            return (
              <div style={{ marginTop: 10 }}>
                <div className="btnrow" style={{ marginBottom: 6, alignItems: "center" }}>
                  <span className="muted" style={{ fontSize: 12 }}>
                    Selected document <span className="mono">#{selectedDocId}</span>
                  </span>
                  {sel && methodBadge(sel.extractionMethod, sel.ocrUsed)}
                  {sel?.pageCount ? <span className="muted" style={{ fontSize: 12 }}>{sel.pageCount}p</span> : null}
                  {sel?.sha256 ? (
                    <span className="prov" style={{ fontSize: 11 }} title={sel.sha256}>
                      sha256 {String(sel.sha256).slice(0, 12)}…
                    </span>
                  ) : null}
                </div>
                {text ? (
                  <>
                    <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>
                      Extracted text (first {preview.length} line{preview.length === 1 ? "" : "s"}):
                    </div>
                    <pre
                      style={{
                        background: "#0f1021",
                        color: "#d5d7f0",
                        borderRadius: 8,
                        padding: 10,
                        fontSize: 12,
                        maxHeight: 220,
                        overflow: "auto",
                        whiteSpace: "pre-wrap",
                        margin: 0,
                      }}
                    >
                      {preview.join("\n")}
                    </pre>
                  </>
                ) : (
                  <div className="muted" style={{ fontSize: 12 }}>
                    No embedded text captured for this document
                    {sel?.extractionMethod === "OCR_NONE"
                      ? " (scanned/image — configure an OCR provider at deploy)."
                      : sel?.storedDocId
                        ? "."
                        : " — this is a filename-only record (use Upload & extract for real capture)."}
                  </div>
                )}
              </div>
            );
          })()}
        </Card>
      )}

      {/* ── Extraction ── */}
      {selectedDocId && (
        <Card
          title="Field extraction"
          sub="AI reads the document and suggests field values with per-field confidence and source-page citations. Confirming a financial-statement extraction auto-drafts an UNCONFIRMED spread from it — advisory only; the authoritative figure still requires the separate human confirm-spread gate."
          right={
            <Button onClick={handleExtract} busy={extracting}>
              Extract
            </Button>
          }
        >
          <div className="muted" style={{ marginBottom: 10, fontSize: 12 }}>
            <Badge kind="ai">AI suggestion</Badge>{" "}
            Extraction results are advisory. Figures do not enter the credit engines until an
            analyst manually confirms them in the financial spread and clicks "Confirm spread".
          </div>

          {extractions.loading && <div className="loading">Loading extractions…</div>}
          {!extractions.loading && (extractions.data || []).length === 0 && (
            <EmptyState
              glyph="✦"
              title="No extractions on this document yet"
              sub="Click Extract to let AI pull structured fields. The results are advisory — they only reach the figure path when an analyst confirms them."
            />
          )}

          {(extractions.data || []).map((ex) => (
            <div key={ex.id} className="card" style={{ marginBottom: 12, background: "#fbfaff" }}>
              <div className="btnrow" style={{ marginBottom: 8 }}>
                {extractionStatusBadge(ex.status)}
                <span className="mono" style={{ fontSize: 12 }}>{ex.detectedLanguage}</span>
                <span className="num" style={{ fontSize: 12 }}>
                  {fmt.pct(ex.overallConfidence)} confidence
                </span>
                <span className="muted" style={{ fontSize: 12 }}>model: {ex.model}</span>
                {ex.reviewedBy && (
                  <small className="prov">
                    reviewed by {ex.reviewedBy}{ex.reviewedAt ? ` · ${new Date(ex.reviewedAt).toLocaleString()}` : ""}
                    {ex.reviewNote ? ` · "${ex.reviewNote}"` : ""}
                  </small>
                )}
              </div>

              {Object.keys(ex.fields).length > 0 && (
                <table>
                  <thead>
                    <tr>
                      <th>Field</th>
                      <th>Value</th>
                      <th className="num">Confidence</th>
                      <th className="num">Page</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(ex.fields).map(([key, f]) => (
                      <tr key={key}>
                        <td><span className="mono">{key}</span></td>
                        <td>{String(f.value)}</td>
                        <td className="num">{fmt.pct(f.confidence)}</td>
                        <td className="num">{f.sourcePage}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              {ex.status === "SUGGESTED" && (
                <div className="btnrow" style={{ marginTop: 8 }}>
                  <Button kind="ghost" onClick={() => handleConfirm(ex)}>Confirm</Button>
                  <Button kind="subtle" onClick={() => handleReject(ex)}>Reject</Button>
                  <small className="prov">
                    Confirming records your name in the audit trail. For a financial statement it also
                    auto-drafts an unconfirmed spread you review on Spreading; figures still require the
                    human confirm-spread gate before they can feed rating or capital.
                  </small>
                </div>
              )}
            </div>
          ))}
        </Card>
      )}

      {/* ── Document checks ── */}
      {selectedDocId && (
        <Card
          title="Document checks"
          sub="Structural and completeness checks — missing signatures, stale dates, entity name mismatches. Advisory only."
          right={
            <Button onClick={handleChecks} busy={checking}>
              Run checks
            </Button>
          }
        >
          {checks ? (
            <>
              <div className="btnrow" style={{ marginBottom: 8 }}>
                {checks.passed
                  ? <Badge kind="ok">Passed</Badge>
                  : <Badge kind="bad">Failed</Badge>}
                <span className="mono" style={{ fontSize: 12 }}>{checks.classifiedType}</span>
              </div>
              {checks.findings.length === 0 && (
                <div className="muted">No findings.</div>
              )}
              {checks.findings.length > 0 && (
                <table>
                  <thead>
                    <tr>
                      <th>Level</th>
                      <th>Code</th>
                      <th>Message</th>
                    </tr>
                  </thead>
                  <tbody>
                    {checks.findings.map((f, i) => (
                      <tr key={i}>
                        <td>{findingBadge(f.level)}</td>
                        <td><span className="mono">{f.code}</span></td>
                        <td>{f.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {checks.advisory && (
                <div className="muted" style={{ marginTop: 8, fontSize: 12 }}>
                  <Badge kind="ai">AI · advisory</Badge> {checks.advisory}
                </div>
              )}
            </>
          ) : (
            <div className="muted">Click "Run checks" to analyse the selected document.</div>
          )}
        </Card>
      )}

      {/* ── Language tools ── */}
      <Card
        title="Language tools"
        sub="Normalise contract or financial text to a target register, or translate a snippet. Both are AI-generated advisory outputs — not saved to any deal record unless you copy the text manually."
      >
        <div className="grid cols-2">

          {/* Normalise */}
          <div>
            <h4>
              Normalise language <Badge kind="ai">AI · advisory</Badge>
            </h4>
            <Field label="Text to normalise">
              <textarea
                rows={5}
                value={normText}
                onChange={(e) => setNormText(e.target.value)}
                placeholder="Paste contract clause or financial text…"
              />
            </Field>
            <Field label="Target register">
              <select
                value={normTarget}
                onChange={(e) => setNormTarget(e.target.value as "LEGAL" | "PLAIN")}
              >
                <option value="LEGAL">LEGAL</option>
                <option value="PLAIN">PLAIN</option>
              </select>
            </Field>
            <div className="btnrow">
              <Button onClick={handleNormalise} busy={norming} disabled={!normText.trim()}>
                Normalise
              </Button>
            </div>
            {normResult && (
              <div style={{ marginTop: 10 }}>
                <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>
                  Rewritten ({normResult.target}):
                </div>
                <div
                  style={{
                    background: "#fff",
                    border: "1px solid var(--line)",
                    borderRadius: 8,
                    padding: 10,
                    fontSize: 13,
                    whiteSpace: "pre-wrap",
                  }}
                >
                  {normResult.rewritten}
                </div>
                {normResult.notes.length > 0 && (
                  <ul style={{ marginTop: 8, paddingLeft: 18, fontSize: 12 }}>
                    {normResult.notes.map((n, i) => (
                      <li key={i} className="prov">{n}</li>
                    ))}
                  </ul>
                )}
                {normResult.advisory && (
                  <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>
                    <Badge kind="ai">AI · advisory</Badge> {normResult.advisory}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Translate */}
          <div>
            <h4>
              Translate <Badge kind="ai">AI · advisory</Badge>
            </h4>
            <Field label="Text to translate">
              <textarea
                rows={5}
                value={transText}
                onChange={(e) => setTransText(e.target.value)}
                placeholder="Paste text to translate…"
              />
            </Field>
            <Field label="Target language">
              <select value={transLang} onChange={(e) => setTransLang(e.target.value)}>
                {translationLanguages.map((l) => (
                  <option key={l.code} value={l.code}>{l.label} ({l.code})</option>
                ))}
              </select>
            </Field>
            <div className="btnrow">
              <Button onClick={handleTranslate} busy={translating} disabled={!transText.trim()}>
                Translate
              </Button>
            </div>
            {transResult && (
              <div style={{ marginTop: 10 }}>
                <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>
                  {transResult.sourceLanguage} → {transResult.targetLanguage}
                  <span className="num" style={{ marginLeft: 8 }}>
                    {fmt.pct(transResult.confidence)} confidence
                  </span>
                </div>
                <div
                  style={{
                    background: "#fff",
                    border: "1px solid var(--line)",
                    borderRadius: 8,
                    padding: 10,
                    fontSize: 13,
                    whiteSpace: "pre-wrap",
                  }}
                >
                  {transResult.translated}
                </div>
                {transResult.advisory && (
                  <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>
                    <Badge kind="ai">AI · advisory</Badge> {transResult.advisory}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
}
