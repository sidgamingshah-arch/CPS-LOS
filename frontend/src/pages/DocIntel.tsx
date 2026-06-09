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

const DECLARED_TYPES = [
  "FINANCIAL_STATEMENT",
  "KYC_ID",
  "FACILITY_DOC",
  "SECURITY_DOC",
  "BANK_STATEMENT",
  "TAX_GST",
  "MOA_AOA",
  "BUREAU_REPORT",
  "OTHER",
];

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

export default function DocIntel() {
  const { actor, notify } = useApp();

  // Deal + doc selection
  const deals = useAsync(() => origination.list(), []);
  const [selectedRef, setSelectedRef] = useState<string>("");
  const docs = useAsync(
    () => (selectedRef ? origination.docs(selectedRef) : Promise.resolve([])),
    [selectedRef],
  );
  const [selectedDocId, setSelectedDocId] = useState<number | null>(null);

  // Upload form
  const [uploadFileName, setUploadFileName] = useState("");
  const [uploadDeclaredType, setUploadDeclaredType] = useState(DECLARED_TYPES[0]);
  const [uploading, setUploading] = useState(false);

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
      notify("Extraction confirmed");
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
          sub="AI classification with confidence score; low-confidence items are routed to human review."
          right={
            <div className="btnrow">
              <input
                placeholder="file name"
                value={uploadFileName}
                onChange={(e) => setUploadFileName(e.target.value)}
                style={{ width: 200 }}
              />
              <select
                value={uploadDeclaredType}
                onChange={(e) => setUploadDeclaredType(e.target.value)}
              >
                {DECLARED_TYPES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
              <Button onClick={handleUpload} busy={uploading} disabled={!uploadFileName.trim()}>
                Upload document
              </Button>
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
          {selectedDocId && (
            <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>
              Selected document ID: <span className="mono">{selectedDocId}</span>
            </div>
          )}
        </Card>
      )}

      {/* ── Extraction ── */}
      {selectedDocId && (
        <Card
          title="Field extraction"
          sub="AI reads the document and suggests field values with per-field confidence and source-page citations. This is a SUGGESTION — it must be human-confirmed and is never auto-applied to the financial spread."
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
                    Confirming records your name in the audit trail; figures still require
                    the financial spread before they can feed rating or capital.
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
                <option value="en">English (en)</option>
                <option value="ar">Arabic (ar)</option>
                <option value="hi">Hindi (hi)</option>
                <option value="fr">French (fr)</option>
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
