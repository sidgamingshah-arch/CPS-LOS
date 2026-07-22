/**
 * Credit Proposal — a dedicated CAM authoring & compare workspace (complements the inline card on
 * the Deal Workspace). Pick a deal, choose a CAM format and generate the formal proposal
 * (persisting, versioned), download the PDF, and compare two formats SIDE-BY-SIDE — both the
 * section layouts and, optionally, the fully-rendered bodies via a NON-persisting preview so
 * comparing never spams versions.
 *
 * Governance: the AI narrative is advisory (AI drafts → human approves & signs); the FIGURES
 * (grade / PD / pricing / DSCR) are deterministic and byte-identical across every format — a CAM
 * format is a RENDERING, not a figure source.
 */
import { useEffect, useMemo, useState } from "react";
import { origination, decision, printing, fmt } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, DeterministicBadge, EmptyState, Field, GovFlow, Unchanged, useAsync,
} from "../ui";

// The segment-specific section builders — rendered from already-computed figures only (no new math).
const SEGMENT_SECTION_KEYS = new Set(["dscr_waterfall", "rent_roll", "scf_program"]);

type Section = { key: string; title: string };
type FormatView = { code: string; label: string; segment?: string; sections: Section[]; recommended?: boolean };
type Preview = {
  applicationReference: string; format: string; label: string; sections: string[];
  markdown: string; html: string; citations: Record<string, any>; llmDrafted: boolean;
};

/** A rendered proposal HTML pane, matching the Deal Workspace's proposal frame. */
function ProposalPane({ html, maxHeight = 460 }: { html: string; maxHeight?: number }) {
  return (
    <div style={{ background: "#fff", border: "1px solid var(--line)", borderRadius: 10, padding: 16, maxHeight, overflow: "auto", fontSize: 13 }}
      dangerouslySetInnerHTML={{ __html: html }} />
  );
}

function Citations({ citations }: { citations: Record<string, any> }) {
  const entries = Object.entries(citations || {});
  if (entries.length === 0) return null;
  return (
    <div style={{ marginTop: 10 }}>
      <small className="prov">Citations (every figure traces to a platform service):</small>
      <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
        {entries.map(([k, v]) => (
          <li key={k}><small className="prov"><span className="mono">{k}</span> — {String(v)}</small></li>
        ))}
      </ul>
    </div>
  );
}

export default function CreditProposal() {
  const { actor, notify, ref: ctxRef, nav } = useApp();

  const deals = useAsync(() => origination.list(), []);
  const [selectedRef, setSelectedRef] = useState<string>(ctxRef ?? "");

  // The selected deal (for its segment → format default) and the format catalogue for that segment.
  const deal = useAsync(() => (selectedRef ? origination.get(selectedRef).catch(() => null) : Promise.resolve(null)), [selectedRef]);
  const segment: string | undefined = deal.data?.segment;
  const formats = useAsync<FormatView[]>(
    () => (selectedRef ? decision.proposalFormats(segment).catch(() => [] as any) : Promise.resolve([] as any)),
    [selectedRef, segment],
  );
  const fmtList = formats.data || [];
  const segDefault = useMemo(
    () => fmtList.find((f) => f.recommended)?.code || "STANDARD",
    [fmtList],
  );

  // Persisting-generate side
  const latest = useAsync<any>(
    () => (selectedRef ? decision.latestProposal(selectedRef).catch(() => null) : Promise.resolve(null)),
    [selectedRef],
  );
  const versions = useAsync<any[]>(
    () => (selectedRef ? decision.proposalVersions(selectedRef).catch(() => [] as any[]) : Promise.resolve([] as any[])),
    [selectedRef],
  );
  const [genFormat, setGenFormat] = useState<string>("");
  const chosenFormat = genFormat || segDefault;
  const [generating, setGenerating] = useState(false);

  // Keep the picker in sync with the resolved segment default until the user overrides it.
  useEffect(() => { setGenFormat(""); }, [selectedRef]);

  const generate = async () => {
    if (!selectedRef) return;
    setGenerating(true);
    try {
      await decision.generateProposal(selectedRef, actor, chosenFormat);
      notify(`Credit proposal generated (${chosenFormat}) — a named human reviews & signs`);
      latest.reload();
      versions.reload();
    } catch (e: any) { notify(e.message, true); } finally { setGenerating(false); }
  };

  const downloadPdf = () => printing.print(printing.proposalHtml(selectedRef, actor), notify);
  // Word (.rtf) / Excel (SpreadsheetML) office output — every figure quoted verbatim from the
  // authoritative proposal body; served as an attachment, the proposal stays byte-identical.
  const downloadRtf = () => printing.downloadProposal(selectedRef, "rtf", actor, notify);
  const downloadExcel = () => printing.downloadProposal(selectedRef, "xlsx", actor, notify);

  // Compare side
  const [fmtA, setFmtA] = useState<string>("");
  const [fmtB, setFmtB] = useState<string>("");
  useEffect(() => {
    // Seed A = segment default, B = STANDARD (or the first format that isn't A).
    if (fmtList.length === 0) return;
    setFmtA((prev) => prev || segDefault);
    setFmtB((prev) => prev || (fmtList.find((f) => f.code !== segDefault)?.code ?? "STANDARD"));
  }, [fmtList, segDefault]);

  const defA = fmtList.find((f) => f.code === fmtA);
  const defB = fmtList.find((f) => f.code === fmtB);

  // Ordered union of section keys across A then B (preserve A's order, append B-only).
  const compareRows = useMemo(() => {
    const rows: { key: string; title: string; inA: boolean; inB: boolean; seg: boolean }[] = [];
    const seen = new Set<string>();
    const push = (s: Section) => {
      if (seen.has(s.key)) return;
      seen.add(s.key);
      rows.push({
        key: s.key, title: s.title,
        inA: !!defA?.sections.some((x) => x.key === s.key),
        inB: !!defB?.sections.some((x) => x.key === s.key),
        seg: SEGMENT_SECTION_KEYS.has(s.key),
      });
    };
    (defA?.sections || []).forEach(push);
    (defB?.sections || []).forEach(push);
    return rows;
  }, [defA, defB]);

  const [previewA, setPreviewA] = useState<Preview | null>(null);
  const [previewB, setPreviewB] = useState<Preview | null>(null);
  const [rendering, setRendering] = useState(false);

  const renderBoth = async () => {
    if (!selectedRef) return;
    setRendering(true);
    try {
      const [pa, pb] = await Promise.all([
        decision.previewProposal(selectedRef, fmtA),
        decision.previewProposal(selectedRef, fmtB),
      ]);
      setPreviewA(pa); setPreviewB(pb);
    } catch (e: any) { notify(e.message, true); } finally { setRendering(false); }
  };
  // Clear stale previews when the deal or chosen formats change.
  useEffect(() => { setPreviewA(null); setPreviewB(null); }, [selectedRef, fmtA, fmtB]);

  const formatOptions = () =>
    fmtList.length === 0
      ? [<option key="STANDARD" value="STANDARD">Standard universal CAM</option>]
      : fmtList.map((f) => (
          <option key={f.code} value={f.code}>{f.label || f.code}{f.recommended ? " · default" : ""}</option>
        ));

  return (
    <div className="grid">
      {/* ── governance header ── */}
      <Card title="Credit Proposal · CAM"
        sub="Assemble the formal committee memo in a chosen CAM format, compare formats, and download the signed PDF. The AI narrative is advisory; the figures it quotes are deterministic and identical across formats."
        right={<GovFlow ai="AI DRAFTS NARRATIVE" human="HUMAN APPROVES & SIGNS" note="figures are deterministic" />}>
        <div className="muted" style={{ fontSize: 13, lineHeight: 1.65 }}>
          A CAM format reshapes only the section <b>layout</b> — the grade, PD, pricing and DSCR are quoted verbatim
          from the engines and are <Unchanged label="IDENTICAL ACROSS FORMATS" />. <DeterministicBadge /> figures are
          never produced or altered here; the format is a rendering, not a figure source.
        </div>
      </Card>

      {/* ── deal selector ── */}
      <Card title="Deal selector">
        <div className="grid cols-2" style={{ alignItems: "end" }}>
          <Field label="Deal">
            <select value={selectedRef} onChange={(e) => setSelectedRef(e.target.value)}>
              <option value="">— select deal —</option>
              {(deals.data ?? []).map((d: any) => (
                <option key={d.reference} value={d.reference}>{d.reference} · {d.counterpartyName} · {d.status}</option>
              ))}
            </select>
          </Field>
          {selectedRef && (
            <div className="btnrow" style={{ gap: 10, alignItems: "center" }}>
              {segment && <Badge kind="info">segment · {segment}</Badge>}
              <Badge kind="ok">default format · {segDefault}</Badge>
              <Button kind="ghost" onClick={() => nav("workspace", selectedRef)}>Open deal workspace →</Button>
            </div>
          )}
        </div>
      </Card>

      {!selectedRef && (
        <Card>
          <EmptyState glyph="§" title="Select a deal to author its credit proposal"
            sub="Pick an application above. The proposal is grounded in the deal's data; the AI narrative is advisory and a named human signs. Figures are deterministic and identical across CAM formats." />
        </Card>
      )}

      {selectedRef && (
        <>
          {/* ── generate (persisting) ── */}
          <Card title="Generate proposal · persisting"
            sub="Generate assembles a NEW, versioned proposal under the chosen CAM format and records it (the real action). Compare below uses a non-persisting preview instead."
            right={
              <div className="btnrow">
                <select value={chosenFormat} onChange={(e) => setGenFormat(e.target.value)} title="CAM format" style={{ maxWidth: 240 }}>
                  {formatOptions()}
                </select>
                <Button onClick={generate} busy={generating}>{latest.data ? "Re-generate" : "Generate proposal"}</Button>
              </div>
            }>
            {!latest.data ? (
              <div className="muted">No proposal generated yet. Pick a format and generate.</div>
            ) : (
              <>
                <div className="btnrow" style={{ marginBottom: 10, alignItems: "center" }}>
                  <Badge kind="info">v{latest.data.version}</Badge>
                  {latest.data.format && <Badge kind="info">CAM · {latest.data.format}</Badge>}
                  <small className="prov">Generated {fmt.dateTime(latest.data.generatedAt)} by {latest.data.generatedBy}</small>
                  <Button kind="ghost" onClick={downloadPdf}>Download PDF</Button>
                  <Button kind="ghost" onClick={downloadRtf}>Word (.rtf)</Button>
                  <Button kind="ghost" onClick={downloadExcel}>Excel</Button>
                </div>
                <ProposalPane html={latest.data.html} />
                <Citations citations={latest.data.citations || {}} />
              </>
            )}
          </Card>

          {/* ── version history ── */}
          <Card title="Version history"
            sub="Every generate creates a new version so the trail is intact. Comparing formats below never adds a version.">
            {(versions.data || []).length === 0 ? (
              <div className="muted">No versions yet.</div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Ver</th><th>Format</th><th>Sections</th><th>Generated</th><th>By</th></tr></thead>
                  <tbody>
                    {(versions.data || []).map((v: any) => (
                      <tr key={v.id}>
                        <td className="mono">v{v.version}</td>
                        <td>{v.format ? <Badge kind="info">{v.format}</Badge> : "—"}</td>
                        <td className="mono"><small>{(v.sections || []).length}</small></td>
                        <td className="mono"><small>{fmt.dateTime(v.generatedAt)}</small></td>
                        <td className="mono"><small>{v.generatedBy || "—"}</small></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          {/* ── side-by-side format compare ── */}
          <Card title="Compare formats · side-by-side"
            sub="Choose two CAM formats to see how their section layouts differ. Optionally render both bodies via a NON-persisting preview — no version is created, so you can compare freely."
            right={<DeterministicBadge label="COMPARE · NO PERSIST" />}>
            <div className="grid cols-2" style={{ alignItems: "end" }}>
              <Field label="Format A">
                <select value={fmtA} onChange={(e) => setFmtA(e.target.value)}>{formatOptions()}</select>
              </Field>
              <Field label="Format B">
                <select value={fmtB} onChange={(e) => setFmtB(e.target.value)}>{formatOptions()}</select>
              </Field>
            </div>

            {/* section-layout diff */}
            <div className="table-scroll" style={{ marginTop: 10 }}>
              <table>
                <thead>
                  <tr>
                    <th>Section</th>
                    <th className="dt-center">{defA?.label || fmtA}</th>
                    <th className="dt-center">{defB?.label || fmtB}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {compareRows.map((r) => {
                    const differs = r.inA !== r.inB;
                    return (
                      <tr key={r.key} className={differs ? "cmp-differs" : undefined}>
                        <td>{r.title}{r.seg && <Badge kind="warn" >segment-specific</Badge>}</td>
                        <td className="dt-center">{r.inA ? <span className="cmp-yes">✓</span> : <span className="cmp-no">—</span>}</td>
                        <td className="dt-center">{r.inB ? <span className="cmp-yes">✓</span> : <span className="cmp-no">—</span>}</td>
                        <td>{differs && <Badge kind="warn">differs</Badge>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="btnrow" style={{ marginTop: 10 }}>
              <Button onClick={renderBoth} busy={rendering} disabled={fmtA === fmtB}>Render both (no version)</Button>
              {fmtA === fmtB && <small className="prov">Pick two different formats to render a comparison.</small>}
            </div>

            {(previewA || previewB) && (
              <>
                <div className="muted" style={{ fontSize: 12, margin: "10px 0 6px" }}>
                  Rendered from a non-persisting preview — no proposal version was created. The figures are
                  identical in both; only the section layout changes. <Unchanged label="FIGURES PRESERVED" />
                </div>
                <div className="grid cols-2">
                  <div>
                    <div className="btnrow" style={{ marginBottom: 6 }}>
                      <Badge kind="info">{previewA?.format}</Badge>
                      <small className="prov">{previewA?.sections.length} sections</small>
                    </div>
                    {previewA && <ProposalPane html={previewA.html} maxHeight={520} />}
                  </div>
                  <div>
                    <div className="btnrow" style={{ marginBottom: 6 }}>
                      <Badge kind="info">{previewB?.format}</Badge>
                      <small className="prov">{previewB?.sections.length} sections</small>
                    </div>
                    {previewB && <ProposalPane html={previewB.html} maxHeight={520} />}
                  </div>
                </div>
              </>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
