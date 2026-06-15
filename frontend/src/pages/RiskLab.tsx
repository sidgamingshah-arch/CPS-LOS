/**
 * RiskLab — advisory risk overlays: statistical RAG scoring + macro directional
 * impact assessments. Non-binding; never rewrites the authoritative deterministic
 * rating produced by the risk engine.
 */
import { Fragment, useState } from "react";
import { origination, risk, modelDoc, fmt } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, EmptyState, Field, GradeBadge, GovSplit, Stat, useAsync } from "../ui";

const SECTOR_OUTLOOKS = ["IMPROVING", "STABLE", "DETERIORATING"] as const;
type SectorOutlook = (typeof SECTOR_OUTLOOKS)[number];

interface MacroForm {
  scenarioName: string;
  interestRateBps: string;
  gdpGrowthDeltaPct: string;
  fxDepreciationPct: string;
  sectorOutlook: SectorOutlook;
  commodityShockPct: string;
}

const BLANK_MACRO: MacroForm = {
  scenarioName: "",
  interestRateBps: "0",
  gdpGrowthDeltaPct: "0",
  fxDepreciationPct: "0",
  sectorOutlook: "STABLE",
  commodityShockPct: "0",
};

function ragBandKind(band: string): string {
  if (band === "RED") return "bad";
  if (band === "AMBER") return "warn";
  return "ok";
}

function directionKind(dir: string): string {
  if (dir === "UP") return "bad";
  if (dir === "DOWN") return "ok";
  return "info";
}

export default function RiskLab() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");

  // Authoritative rating summary
  const ratingAsync = useAsync(
    () => (ref ? risk.summary(ref) : Promise.reject(new Error("no-ref"))),
    [ref],
  );

  // RAG history
  const ragAsync = useAsync(
    () => (ref ? risk.ragHistory(ref) : Promise.resolve([] as any[])),
    [ref],
  );

  // Macro history
  const macroAsync = useAsync(
    () => (ref ? risk.macroHistory(ref) : Promise.resolve([] as any[])),
    [ref],
  );

  // RAG busy state
  const [ragBusy, setRagBusy] = useState(false);

  // Macro form state
  const [macroForm, setMacroForm] = useState<MacroForm>(BLANK_MACRO);
  const [macroBusy, setMacroBusy] = useState(false);

  const reloadAll = () => {
    ratingAsync.reload();
    ragAsync.reload();
    macroAsync.reload();
  };

  const handleAssessRag = async () => {
    if (!ref) return;
    setRagBusy(true);
    try {
      await risk.assessRag(ref, actor);
      notify("RAG assessment complete");
      ragAsync.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setRagBusy(false);
    }
  };

  const handleMacroSubmit = async () => {
    if (!ref || !macroForm.scenarioName.trim()) {
      notify("Enter a scenario name first", true);
      return;
    }
    setMacroBusy(true);
    try {
      await risk.macroImpact(
        ref,
        {
          scenarioName: macroForm.scenarioName,
          interestRateBps: Number(macroForm.interestRateBps),
          gdpGrowthDeltaPct: Number(macroForm.gdpGrowthDeltaPct),
          fxDepreciationPct: Number(macroForm.fxDepreciationPct),
          sectorOutlook: macroForm.sectorOutlook,
          commodityShockPct: Number(macroForm.commodityShockPct),
        },
        actor,
      );
      notify("Macro impact assessment complete");
      macroAsync.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setMacroBusy(false);
    }
  };

  const applyPreset = (preset: Partial<MacroForm>) => {
    setMacroForm((f) => ({ ...f, ...preset }));
  };

  const sum = ratingAsync.data;
  const rating = sum?.rating ?? null;
  const latestRag = (ragAsync.data ?? [])[0] ?? null;
  const latestMacro = (macroAsync.data ?? [])[0] ?? null;

  return (
    <div className="grid">
      {/* Governance banner */}
      <div className="gov-banner">
        <h3>AI recommends. Humans decide. The rating of record never moves.</h3>
        <div className="gb-sub">
          Risk Lab runs statistical RAG scoring and macro stress overlays as <b>advisory, non-binding</b> signals.
          They read the same ratios the deterministic scorecard reads — but they never rewrite the authoritative rating.
        </div>
        <div className="gb-chips">
          <span className="gb-chip"><b>AI</b> · advisory overlays</span>
          <span className="gb-chip"><b>Human</b> · credit decision</span>
          <span className="gb-chip"><b>Deterministic</b> · PD / LGD / EAD</span>
          <span className="gb-chip">Every output <b>audited</b> as an AI event</span>
        </div>
      </div>

      {/* Deal selector */}
      <Card title="Deal selector">
        <div className="grid cols-2" style={{ alignItems: "end" }}>
          <Field label="Application">
            <select
              value={ref}
              onChange={(e) => { setRef(e.target.value); }}
            >
              <option value="">— select a deal —</option>
              {(apps.data ?? []).map((a: any) => (
                <option key={a.reference} value={a.reference}>
                  {a.reference} · {a.counterpartyName} · {a.status}
                </option>
              ))}
            </select>
          </Field>
          <Button kind="subtle" onClick={reloadAll} disabled={!ref}>Refresh</Button>
        </div>

        {/* Authoritative rating summary */}
        {ref && (
          <div style={{ marginTop: 14 }}>
            {ratingAsync.loading && <div className="loading">Loading rating…</div>}
            {!ratingAsync.loading && ratingAsync.error && (
              <div className="muted">Rate the deal first to see the authoritative rating here.</div>
            )}
            {!ratingAsync.loading && rating && (
              <div className="grid cols-3">
                <Stat label="Final grade" value={<GradeBadge grade={rating.finalGrade} />} />
                <Stat label="Model grade" value={<GradeBadge grade={rating.modelGrade} />} />
                <Stat label="PD" value={fmt.pct(rating.pd, 2)} />
              </div>
            )}
          </div>
        )}
      </Card>

      {/* Signature governance frame: AI advisory ↔ authoritative rating UNCHANGED */}
      {ref && rating && (
        <Card title="Governance view" sub="One glance: the AI overlay on the left, the figure of record on the right — untouched.">
          <GovSplit
            advisoryLabel="Statistical RAG (advisory)"
            advisory={
              latestRag ? (
                <div className="inline" style={{ gap: 14 }}>
                  <Badge kind={ragBandKind(latestRag.band)}>{latestRag.band}</Badge>
                  <span style={{ fontSize: 22, fontWeight: 750 }}>{latestRag.score}<span className="muted" style={{ fontSize: 13, fontWeight: 500 }}> / 100</span></span>
                </div>
              ) : <span className="muted">Run “Assess RAG” to generate the advisory band.</span>
            }
            authLabel="Authoritative rating"
            auth={
              <div className="inline" style={{ gap: 12 }}>
                <GradeBadge grade={rating.finalGrade} />
                <span className="muted" style={{ fontSize: 13 }}>PD {fmt.pct(rating.pd, 2)}</span>
              </div>
            }
          />
        </Card>
      )}

      {!ref && (
        <Card>
          <EmptyState
            glyph="◎"
            title="Select a deal to run advisory overlays"
            sub="Pick an application above. Risk Lab then offers a statistical RAG band and macro stress overlays — both advisory, neither ever rewrites the authoritative rating."
          />
        </Card>
      )}

      {ref && (
      <>
      {/* RAG card */}
      <Card
        title="RAG Assessment"
        sub="Statistical scoring of deal-level risk indicators into RED / AMBER / GREEN bands. Advisory overlay — does not modify the authoritative rating."
        right={<Badge kind="ai">AI · advisory</Badge>}
      >
        <div className="btnrow">
          <Button onClick={handleAssessRag} disabled={!ref} busy={ragBusy}>
            Assess RAG
          </Button>
          <span className="muted">Acting as {actor}</span>
        </div>

        {ragAsync.loading && ref && <div className="loading">Loading RAG history…</div>}

        {latestRag && (
          <>
            <div className="grid cols-3" style={{ marginTop: 12 }}>
              <Stat
                label="Band"
                value={
                  <Badge kind={ragBandKind(latestRag.band)}>
                    {latestRag.band}
                  </Badge>
                }
              />
              <Stat label="Score" value={`${latestRag.score} / 100`} />
              <Stat label="Grade snapshot" value={<GradeBadge grade={latestRag.gradeSnapshot} />} />
            </div>

            {latestRag.advisory && (
              <div className="prov" style={{ marginTop: 10 }}>{latestRag.advisory}</div>
            )}

            {latestRag.factors?.breakdown?.length > 0 && (
              <div style={{ marginTop: 14 }}>
                <h4>Factor breakdown</h4>
                <table>
                  <thead>
                    <tr>
                      <th>Factor</th>
                      <th className="num">Value</th>
                      <th className="num">Sub-score</th>
                      <th className="num">Weight</th>
                      <th className="num">Contribution</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {latestRag.factors.breakdown.map((row: any) => (
                      <tr key={row.key}>
                        <td className="mono">{row.key}</td>
                        <td className="num">{fmt.num(row.value, 2)}</td>
                        <td className="num">{fmt.num(row.subScore, 1)}</td>
                        <td className="num">{fmt.num(row.weight, 2)}</td>
                        <td className="num">{fmt.num(row.contribution, 2)}</td>
                        <td>
                          {row.imputed && <Badge kind="info">imputed</Badge>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <small className="prov" style={{ display: "block", marginTop: 8 }}>
              Assessed {new Date(latestRag.createdAt).toLocaleString()} · method: {latestRag.method}
            </small>
          </>
        )}

        {!ragAsync.loading && !latestRag && ref && (
          <div className="muted" style={{ marginTop: 8 }}>No RAG assessments yet — click Assess RAG.</div>
        )}
      </Card>

      {/* Macro card */}
      <Card
        title="Macro Impact Assessment"
        sub="Directional overlay estimating PD impact under macro scenario assumptions. Non-binding; never overwrites the authoritative rating."
        right={<Badge kind="ai">AI · advisory</Badge>}
      >
        <div className="btnrow" style={{ marginBottom: 10 }}>
          <Button
            kind="ghost"
            onClick={() =>
              applyPreset({
                scenarioName: "Stagflation",
                interestRateBps: "200",
                gdpGrowthDeltaPct: "-2",
                sectorOutlook: "DETERIORATING",
              })
            }
          >
            Stagflation
          </Button>
          <Button
            kind="ghost"
            onClick={() =>
              applyPreset({
                scenarioName: "Soft landing",
                interestRateBps: "-100",
                gdpGrowthDeltaPct: "2",
                sectorOutlook: "IMPROVING",
              })
            }
          >
            Soft landing
          </Button>
        </div>

        <div className="grid cols-2">
          <Field label="Scenario name">
            <input
              value={macroForm.scenarioName}
              onChange={(e) => setMacroForm((f) => ({ ...f, scenarioName: e.target.value }))}
              placeholder="e.g. Stagflation"
            />
          </Field>
          <Field label="Sector outlook">
            <select
              value={macroForm.sectorOutlook}
              onChange={(e) =>
                setMacroForm((f) => ({ ...f, sectorOutlook: e.target.value as SectorOutlook }))
              }
            >
              {SECTOR_OUTLOOKS.map((o) => (
                <option key={o}>{o}</option>
              ))}
            </select>
          </Field>
          <Field label="Interest rate shift (bps)">
            <input
              type="number"
              value={macroForm.interestRateBps}
              onChange={(e) => setMacroForm((f) => ({ ...f, interestRateBps: e.target.value }))}
            />
          </Field>
          <Field label="GDP growth delta (%)">
            <input
              type="number"
              value={macroForm.gdpGrowthDeltaPct}
              onChange={(e) => setMacroForm((f) => ({ ...f, gdpGrowthDeltaPct: e.target.value }))}
            />
          </Field>
          <Field label="FX depreciation (%)">
            <input
              type="number"
              value={macroForm.fxDepreciationPct}
              onChange={(e) => setMacroForm((f) => ({ ...f, fxDepreciationPct: e.target.value }))}
            />
          </Field>
          <Field label="Commodity shock (%)">
            <input
              type="number"
              value={macroForm.commodityShockPct}
              onChange={(e) => setMacroForm((f) => ({ ...f, commodityShockPct: e.target.value }))}
            />
          </Field>
        </div>

        <div className="btnrow" style={{ marginTop: 8 }}>
          <Button onClick={handleMacroSubmit} disabled={!ref} busy={macroBusy}>
            Run macro impact
          </Button>
        </div>

        {macroAsync.loading && ref && <div className="loading">Loading macro history…</div>}

        {latestMacro && (
          <>
            <div className="grid cols-3" style={{ marginTop: 14 }}>
              <Stat
                label="Direction"
                value={
                  <Badge kind={directionKind(latestMacro.direction)}>
                    {latestMacro.direction}
                  </Badge>
                }
              />
              <Stat
                label="PD delta (bps)"
                value={<span className="num">{latestMacro.pdDeltaBps}</span>}
                tone={latestMacro.pdDeltaBps > 0 ? "var(--bad)" : undefined}
              />
              <Stat label="Notch estimate" value={latestMacro.notchEstimate ?? "—"} />
            </div>

            <div className="grid cols-2" style={{ marginTop: 10 }}>
              <Stat label="Baseline PD" value={fmt.pct(latestMacro.baselinePd, 2)} />
              <Stat label="Stressed PD" value={fmt.pct(latestMacro.stressedPd, 2)} />
            </div>

            {latestMacro.rationale && (
              <div className="prov" style={{ marginTop: 10 }}>{latestMacro.rationale}</div>
            )}

            {latestMacro.advisory && (
              <div className="muted" style={{ marginTop: 6, fontSize: 12 }}>{latestMacro.advisory}</div>
            )}

            {latestMacro.contributions && Object.keys(latestMacro.contributions).length > 0 && (
              <div style={{ marginTop: 14 }}>
                <h4>Contributions</h4>
                <table>
                  <thead>
                    <tr><th>Driver</th><th className="num">PD multiplier Δ</th><th>Basis</th></tr>
                  </thead>
                  <tbody>
                    {Object.entries(latestMacro.contributions).map(([k, v]: [string, any]) => {
                      const isObj = v && typeof v === "object";
                      if (k === "net_multiplier") {
                        return (
                          <tr key={k}>
                            <td className="mono"><b>{k}</b></td>
                            <td className="num"><b>×{typeof v === "number" ? v.toFixed(2) : String(v)}</b></td>
                            <td><small className="prov">applied to baseline PD</small></td>
                          </tr>
                        );
                      }
                      const delta = isObj ? v.pdMultiplierDelta : v;
                      const note = isObj ? v.note : "";
                      return (
                        <tr key={k}>
                          <td className="mono">{k}</td>
                          <td className="num">{typeof delta === "number" ? (delta >= 0 ? "+" : "") + (delta * 100).toFixed(1) + "%" : String(delta)}</td>
                          <td><small className="prov">{note}</small></td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <small className="prov" style={{ display: "block", marginTop: 8 }}>
              Scenario: <b>{latestMacro.scenarioName}</b> · assessed {new Date(latestMacro.createdAt).toLocaleString()}
            </small>
          </>
        )}

        {!macroAsync.loading && !latestMacro && ref && (
          <div className="muted" style={{ marginTop: 8 }}>No macro assessments yet — configure a scenario and run.</div>
        )}
      </Card>

      {ref && <QualitativeCard refValue={ref} grade={ratingAsync.data?.rating?.finalGrade} />}
      </>
      )}
    </div>
  );
}

/**
 * Qualitative scorecard — advisory, prompt-driven scoring of the qualitative rating
 * parameters (the QUAL_SCORECARD prompt library). Each parameter is AI-recommended,
 * grounded on deal data, traceable to the prompt that produced it, and human-confirmed.
 * The authoritative grade on the right is never touched — pure advisory overlay.
 */
function QualitativeCard({ refValue, grade }: { refValue: string; grade?: string }) {
  const { actor, notify } = useApp();
  const qa = useAsync(() => risk.qualitative(refValue).catch(() => null), [refValue]);
  const [busy, setBusy] = useState(false);
  const [openPrompt, setOpenPrompt] = useState<number | null>(null);
  const [modelText, setModelText] = useState("");

  async function assess() {
    setBusy(true);
    try { await risk.qualitativeAssess(refValue, actor); notify("Qualitative scorecard drafted — advisory; review each parameter"); qa.reload(); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }
  async function confirm(id: number, suggested: number) {
    const adj = prompt("Confirm score (blank = accept the AI recommendation)", String(suggested));
    if (adj === null) return;
    const score = adj.trim() === "" ? undefined : parseFloat(adj);
    try { await risk.qualitativeConfirm(id, { score }, actor); notify("Parameter confirmed"); qa.reload(); }
    catch (e: any) { notify(e.message, true); }
  }
  async function extractModel() {
    if (!modelText.trim()) { notify("Paste the rating model's qualitative section first", true); return; }
    try {
      const r = await modelDoc.extract({ text: modelText, jurisdiction: null }, actor);
      notify(`Extracted ${r.parametersFound} parameter(s) + prompts — pending approval in Masters`);
      setModelText("");
    } catch (e: any) { notify(e.message, true); }
  }

  const v = qa.data;
  const bandKind = (b: string) => (b === "STRONG" ? "ok" : b === "WEAK" ? "bad" : "warn");
  return (
    <Card title="Qualitative scorecard"
      sub="AI-recommended qualitative parameter scores, prompt-driven (QUAL_SCORECARD) and grounded on deal data. Advisory & human-confirmed — the authoritative grade is never changed."
      right={<AiBadge label="ADVISORY · PROMPT-DRIVEN" />}>
      <div className="btnrow" style={{ marginBottom: 10 }}>
        <Button kind="primary" busy={busy} onClick={assess}>
          {v && v.parameterCount > 0 ? "Re-assess" : "Assess qualitative"}
        </Button>
        {v && v.parameterCount > 0 && (
          <>
            <Stat label="Composite" value={`${v.compositeScore.toFixed(1)}/100`} />
            <Badge kind={bandKind(v.compositeBand)}>{v.compositeBand}</Badge>
            <span className="muted">Advisory readout — any rating change is a manual override</span>
            <Badge kind="ok">grade {grade ?? v.authoritativeGrade ?? "—"} · UNCHANGED</Badge>
          </>
        )}
      </div>
      {v && v.parameters && v.parameters.length > 0 ? (
        <table>
          <thead>
            <tr><th>Parameter</th><th className="num">Weight</th><th className="num">AI score</th>
                <th className="num">Confirmed</th><th>Band</th><th>Source</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {v.parameters.map((p: any) => (
              <Fragment key={p.id}>
                <tr>
                  <td>{p.displayName}</td>
                  <td className="num">{(p.weight * 100).toFixed(0)}%</td>
                  <td className="num">{p.suggestedScore.toFixed(0)}</td>
                  <td className="num">{p.status === "CONFIRMED" ? p.score.toFixed(0) : "—"}</td>
                  <td><Badge kind={bandKind(p.band)}>{p.band}</Badge></td>
                  <td><Badge kind={p.promptSource === "MODEL_DOC" ? "info" : ""}>{p.promptSource}</Badge></td>
                  <td><Badge kind={p.status === "CONFIRMED" ? "ok" : "warn"}>{p.status}</Badge></td>
                  <td style={{ display: "flex", gap: 4 }}>
                    <Button kind="ghost" onClick={() => setOpenPrompt(openPrompt === p.id ? null : p.id)}>Why?</Button>
                    {p.status !== "CONFIRMED" && <Button kind="subtle" onClick={() => confirm(p.id, p.suggestedScore)}>Confirm</Button>}
                  </td>
                </tr>
                {openPrompt === p.id && (
                  <tr><td colSpan={8} style={{ background: "var(--surface-2, rgba(0,0,0,0.02))", fontSize: 12 }}>
                    <div style={{ marginBottom: 6 }}><b>Rationale:</b> {p.rationale}</div>
                    <div style={{ opacity: 0.8 }}><b>Prompt used:</b> {p.prompt}</div>
                  </td></tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      ) : (
        <EmptyState glyph="◷" title="No qualitative assessment yet"
          sub="Run the qualitative scorecard — it recommends a score per parameter from the configured prompts, grounded on this deal's data." />
      )}
      <div style={{ marginTop: 14, borderTop: "1px solid var(--border, #eee)", paddingTop: 12 }}>
        <Field label="Configure the scorecard from your rating model document">
          <textarea rows={3} value={modelText} onChange={(e) => setModelText(e.target.value)}
            placeholder="Paste your model's qualitative module (e.g. 'Management Quality (25%): …'). The parameters + scoring prompts are extracted and queued for maker-checker approval in Masters." />
        </Field>
        <Button kind="subtle" onClick={extractModel}>Extract qualitative prompts from model doc</Button>
      </div>
    </Card>
  );
}
