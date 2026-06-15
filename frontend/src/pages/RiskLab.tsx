/**
 * RiskLab — advisory risk overlays: statistical RAG scoring + macro directional
 * impact assessments. Non-binding; never rewrites the authoritative deterministic
 * rating produced by the risk engine.
 */
import { Fragment, useState } from "react";
import { origination, risk, modelDoc, fmt } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, EmptyState, Field, GradeBadge, GovSplit, HumanBadge, Stat, Unchanged, useAsync } from "../ui";

const SECTOR_OUTLOOKS = ["IMPROVING", "STABLE", "DETERIORATING"] as const;
type SectorOutlook = (typeof SECTOR_OUTLOOKS)[number];

// Manual-override vocabulary, mirrored from the risk-service (MasterScale.GRADES +
// RiskDtos.REASON_CODES + the per-role notch limits). The override is 100% human —
// these only populate the dropdowns; nothing here is AI-derived.
const GRADE_SCALE = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"] as const;
const OVERRIDE_REASONS = [
  "POST_BALANCE_SHEET_EVENT", "MANAGEMENT_QUALITY", "GROUP_SUPPORT",
  "SECTOR_OUTLOOK", "DATA_QUALITY", "COLLATERAL_STRENGTH", "OTHER",
] as const;
const OVERRIDE_ROLES = [
  { role: "ANALYST", notches: 1 },
  { role: "CREDIT_OFFICER", notches: 2 },
  { role: "CREDIT_COMMITTEE", notches: 99 },
  { role: "CRO", notches: 99 },
] as const;

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

  // Qualitative scorecard — shared so the manual-override card can echo the composite
  // as read-only context (stays in sync when a parameter is confirmed).
  const qualAsync = useAsync(
    () => (ref ? risk.qualitative(ref).catch(() => null) : Promise.resolve(null)),
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
    qualAsync.reload();
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

      {ref && (
        <QualitativeCard
          refValue={ref}
          grade={ratingAsync.data?.rating?.finalGrade}
          qa={qualAsync}
        />
      )}

      {ref && rating && (
        <ManualOverrideCard
          refValue={ref}
          rating={rating}
          qual={qualAsync.data}
          onChanged={reloadAll}
        />
      )}
      </>
      )}
    </div>
  );
}

/**
 * Manual rating override — a named human changes the authoritative grade. This is
 * deliberately 100% manual: nothing here is AI-derived, no notch is suggested, and
 * the qualitative composite is shown only as READ-ONLY CONTEXT the human may weigh.
 * The backend enforces the per-role notch limit, reason code, and segregation of
 * duties; the implied-notch hint below is plain arithmetic on the human's selection.
 */
function ManualOverrideCard({ refValue, rating, qual, onChanged }: {
  refValue: string; rating: any; qual: any; onChanged: () => void;
}) {
  const { actor, notify } = useApp();
  const [proposedGrade, setProposedGrade] = useState("");
  const [reasonCode, setReasonCode] = useState("");
  const [role, setRole] = useState<string>("ANALYST");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  const modelGrade: string | undefined = rating?.modelGrade;
  const finalGrade: string | undefined = rating?.finalGrade;
  // + = upgrade (toward AAA), matching MasterScale.notches on the server.
  const notch = proposedGrade && modelGrade
    ? GRADE_SCALE.indexOf(modelGrade as any) - GRADE_SCALE.indexOf(proposedGrade as any)
    : 0;
  const roleLimit = OVERRIDE_ROLES.find((r) => r.role === role)?.notches ?? 1;
  const exceedsLimit = Math.abs(notch) > roleLimit;

  async function submit() {
    if (!proposedGrade) { notify("Pick the grade you are overriding to", true); return; }
    if (!reasonCode) { notify("Select a reason code", true); return; }
    setBusy(true);
    try {
      await risk.overrideRating(refValue, { proposedGrade, reasonCode, note, role }, actor);
      notify(`Rating overridden to ${proposedGrade} (${role})`);
      setProposedGrade(""); setReasonCode(""); setNote("");
      onChanged();
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }

  const bandKind = (b: string) => (b === "STRONG" ? "ok" : b === "WEAK" ? "bad" : "warn");
  const notchLabel = notch === 0
    ? "no change"
    : `${Math.abs(notch)}-notch ${notch > 0 ? "upgrade" : "downgrade"}`;

  return (
    <Card title="Manual rating override"
      sub="A named human changes the authoritative grade — notch-limited by role, reason-coded, and segregation-of-duties checked. 100% manual: no AI recommendation pre-fills or drives this."
      right={<HumanBadge label="HUMAN · MANUAL" />}>

      {/* Authoritative figures of record (what an override would change). */}
      <div className="grid cols-3" style={{ marginBottom: 12 }}>
        <Stat label="Model grade" value={<GradeBadge grade={modelGrade} />} />
        <Stat label="Current final grade" value={<GradeBadge grade={finalGrade} />} />
        <Stat label="PD" value={fmt.pct(rating?.pd, 2)} />
      </div>

      {/* Read-only qualitative context — informs the human, never pre-fills the grade. */}
      <div className="prov" style={{ marginBottom: 14, display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        <AiBadge label="ADVISORY CONTEXT" />
        {qual && qual.parameterCount > 0 ? (
          <>
            <span>Qualitative composite</span>
            <b>{qual.compositeScore.toFixed(1)}/100</b>
            <Badge kind={bandKind(qual.compositeBand)}>{qual.compositeBand}</Badge>
            {!qual.allConfirmed && <span className="muted">(some parameters not yet confirmed)</span>}
            <span className="muted">— read-only reference; it suggests no grade and does not drive this override.</span>
          </>
        ) : (
          <span className="muted">No qualitative assessment yet — run the scorecard above for advisory context. It is optional and never pre-fills the override.</span>
        )}
      </div>

      <div className="grid cols-3" style={{ alignItems: "end" }}>
        <Field label="Override to grade">
          <select value={proposedGrade} onChange={(e) => setProposedGrade(e.target.value)}>
            <option value="">— select grade —</option>
            {GRADE_SCALE.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
        </Field>
        <Field label="Reason code">
          <select value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
            <option value="">— select reason —</option>
            {OVERRIDE_REASONS.map((r) => <option key={r} value={r}>{r.replace(/_/g, " ")}</option>)}
          </select>
        </Field>
        <Field label="Acting as role">
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            {OVERRIDE_ROLES.map((r) => (
              <option key={r.role} value={r.role}>
                {r.role.replace(/_/g, " ")}{r.notches < 99 ? ` (≤${r.notches} notch)` : " (unlimited)"}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <Field label="Justification note">
        <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)}
          placeholder="Why is the model grade being overridden? (audited)" />
      </Field>

      {proposedGrade && (
        <div className="prov" style={{ marginBottom: 10 }}>
          Implied move from model grade <b>{modelGrade}</b> → <b>{proposedGrade}</b>: <b>{notchLabel}</b>.
          {exceedsLimit && (
            <span style={{ color: "var(--bad, #c0392b)", marginLeft: 6 }}>
              Exceeds the {role.replace(/_/g, " ")} limit of {roleLimit} notch(es) — will be rejected and must be escalated.
            </span>
          )}
        </div>
      )}

      <div className="btnrow">
        <Button kind="primary" busy={busy} onClick={submit} disabled={!proposedGrade || !reasonCode || exceedsLimit}>
          Apply manual override
        </Button>
        <span className="muted">Acting as {actor}</span>
        <Unchanged label="DETERMINISTIC PD/CAPITAL RE-DERIVED" />
      </div>
    </Card>
  );
}

/**
 * Qualitative scorecard — advisory, prompt-driven scoring of the qualitative rating
 * parameters (the QUAL_SCORECARD prompt library). Each parameter is AI-recommended,
 * grounded on deal data, traceable to the prompt that produced it, and human-confirmed.
 * The authoritative grade on the right is never touched — pure advisory overlay.
 */
function QualitativeCard({ refValue, grade, qa }: { refValue: string; grade?: string; qa: { data: any; reload: () => void } }) {
  const { actor, notify } = useApp();
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
