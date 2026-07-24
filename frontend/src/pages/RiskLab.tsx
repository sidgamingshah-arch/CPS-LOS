/**
 * RiskLab — advisory risk overlays: statistical RAG scoring + macro directional
 * impact assessments. Non-binding; never rewrites the authoritative deterministic
 * rating produced by the risk engine.
 */
import { useState } from "react";
import { origination, risk, models, fmt } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, DeterministicBadge, EmptyState, Field, GradeBadge, GovFlow, GovSplit, HumanBadge, Stat, Unchanged, useAsync } from "../ui";
import { ExplainCard, type XaiFactor } from "../xai";
import { useCodes } from "../code-values";
import { authorityLabel, canRole } from "../authz";

type SectorOutlook = string;

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

function approvalStatusKind(status?: string): string {
  if (status === "APPROVED") return "ok";
  if (status === "PENDING_APPROVAL") return "warn";
  return "info";
}

/**
 * Configurable, parameter-routed scoring approval. The score is computed deterministically (AI
 * where it helps stays out of the figure path); a named human holding the routed authority
 * APPROVES it. Approval is a gate — it never moves the authoritative grade / PD.
 */
function ScoringApproval({ approval, confirmed, busy, actor, onApprove }: {
  approval: any;
  confirmed: boolean;
  busy: boolean;
  actor: string;
  onApprove: () => void;
}) {
  const { roles } = useApp();
  const authority: string = approval?.requiredAuthority ?? "CREDIT_OFFICER";
  const status: string = approval?.approvalStatus ?? (confirmed ? "APPROVED" : "PENDING_APPROVAL");
  const required: boolean = approval?.approvalRequired !== false;
  const decided = status === "APPROVED" || confirmed;
  // Advisory authority hint — mirrors the server confirm-rank gate (default-permissive).
  const mayApprove = canRole(authority, roles, actor);
  return (
    <div style={{ marginTop: 14, paddingTop: 12, borderTop: "1px solid var(--border, #e5e7eb)" }}>
      <div className="inline" style={{ gap: 12, flexWrap: "wrap", alignItems: "center" }}>
        <GovFlow ai="AI SCORES" human="HUMAN APPROVES" note="deterministic figure · human gate" />
        <Badge kind="info">Routed to {authority}</Badge>
        <Badge kind={approvalStatusKind(status)}>{status}</Badge>
        {!required && <Badge>approval not required</Badge>}
        <Unchanged label="GRADE / PD PRESERVED" />
      </div>
      <div className="inline" style={{ gap: 10, marginTop: 10, alignItems: "center" }}>
        <span className="authz-gate"
          title={mayApprove ? undefined : `Requires ${authorityLabel(authority)} authority`}>
          <Button kind="primary" busy={busy} disabled={busy || decided || !mayApprove} onClick={onApprove}>
            {decided ? "Approved" : `Approve as ${authority}`}
          </Button>
        </span>
        <span className="muted" style={{ fontSize: 12 }}>
          Acting as {actor} · confirming actor must hold {authority} authority; the overrider cannot self-approve.
        </span>
      </div>
    </div>
  );
}

export default function RiskLab() {
  const { actor, notify, ref: ctxRef } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const sectorOutlooks = useCodes("SECTOR_OUTLOOK");
  const [ref, setRef] = useState<string>(ctxRef ?? "");

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

  // Scoring model — shared so the manual-override card can echo the advisory
  // composite as read-only context (stays in sync as answers are captured/confirmed).
  const modelAsync = useAsync(
    () => (ref ? models.render(ref).catch(() => null) : Promise.resolve(null)),
    [ref],
  );

  // Configurable, parameter-routed scoring approval — the routed authority + status.
  const approvalAsync = useAsync(
    () => (ref ? risk.scoringApproval(ref).catch(() => null) : Promise.resolve(null)),
    [ref],
  );

  // RAG busy state
  const [ragBusy, setRagBusy] = useState(false);
  const [approveBusy, setApproveBusy] = useState(false);

  // Macro form state
  const [macroForm, setMacroForm] = useState<MacroForm>(BLANK_MACRO);
  const [macroBusy, setMacroBusy] = useState(false);

  const reloadAll = () => {
    ratingAsync.reload();
    ragAsync.reload();
    macroAsync.reload();
    modelAsync.reload();
    approvalAsync.reload();
  };

  // Human approval gate: confirming the score (the confirming actor must hold ≥ the routed
  // authority; the overrider cannot self-confirm). The authoritative figures never move.
  const handleApproveScore = async () => {
    if (!ref) return;
    setApproveBusy(true);
    try {
      await risk.confirmRating(ref, actor);
      notify("Score approved (rating confirmed)");
      ratingAsync.reload();
      approvalAsync.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setApproveBusy(false);
    }
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
  const capital = sum?.capital ?? null;
  const perFacilityCapital: any[] = capital?.trace?.perFacility ?? [];
  const latestRag = (ragAsync.data ?? [])[0] ?? null;
  const latestMacro = (macroAsync.data ?? [])[0] ?? null;

  // Advisory RAG overlay — factor breakdown, mapped onto the unified XAI factor model.
  const ragFactors: XaiFactor[] = (latestRag?.factors?.breakdown ?? []).map((r: any) => ({
    label: <span className="mono">{r.key}</span>,
    value: fmt.num(r.value, 2),
    subScore: fmt.num(r.subScore, 1),
    weight: fmt.num(r.weight, 2),
    contribution: fmt.num(r.contribution, 2),
    tag: r.imputed ? { label: "imputed", kind: "info" } : undefined,
  }));

  // Advisory macro overlay — per-driver PD-multiplier contributions, unified XAI model.
  const macroFactors: XaiFactor[] = latestMacro?.contributions
    ? Object.entries(latestMacro.contributions).map(([k, v]: [string, any]) => {
        const isObj = v && typeof v === "object";
        const isNet = k === "net_multiplier";
        const deltaVal = isNet ? v : (isObj ? v.pdMultiplierDelta : v);
        const note = isNet ? "applied to baseline PD" : (isObj ? v.note : "");
        return {
          label: isNet ? <b>{k}</b> : <span className="mono">{k}</span>,
          contribution: isNet
            ? <b>×{typeof v === "number" ? v.toFixed(2) : String(v)}</b>
            : (typeof deltaVal === "number" ? (deltaVal >= 0 ? "+" : "") + (deltaVal * 100).toFixed(1) + "%" : String(deltaVal)),
          note: note ? <small className="prov">{note}</small> : undefined,
          emphasise: isNet,
        } as XaiFactor;
      })
    : [];

  // Authoritative scorecard — the QUANTITATIVE rating basis. Every factor is a ratio auto-derived
  // from the analyst-confirmed financial spread; the weighted roll-up is deterministic and AI-free.
  // Shown for transparency only — no advisory overlay ever moves it.
  const scorecardFactors: XaiFactor[] = Object.entries(rating?.scoreBreakdown?.factors ?? {}).map(
    ([k, v]: [string, any]) => ({
      label: <span className="mono">{k}</span>,
      value: fmt.num(v.value, 2),
      subScore: fmt.num(v.score, 1),
      weight: fmt.num(v.weight, 2),
      contribution: fmt.num(v.contribution, 2),
    }),
  );
  const gradeSource: string | undefined = rating?.scoreBreakdown?.gradeSource;
  const scorecardSource: string | undefined = rating?.scoreBreakdown?.scorecardSource;

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
              <>
                <div className="grid cols-3">
                  <Stat label="Final grade" value={<GradeBadge grade={rating.finalGrade} />} />
                  <Stat label="Model grade" value={<GradeBadge grade={rating.modelGrade} />} />
                  <Stat label="PD" value={fmt.pct(rating.pd, 2)} />
                </div>
                <ScoringApproval
                  approval={approvalAsync.data}
                  confirmed={!!rating.confirmed}
                  busy={approveBusy}
                  actor={actor}
                  onApprove={handleApproveScore}
                />

                {/* Quantitative rating basis — the deterministic scorecard, auto from the spread. */}
                {scorecardFactors.length > 0 && (
                  <div style={{ marginTop: 16, paddingTop: 12, borderTop: "1px solid var(--border, #e5e7eb)" }}>
                    <div className="inline" style={{ gap: 10, marginBottom: 8, alignItems: "center", flexWrap: "wrap" }}>
                      <DeterministicBadge label="QUANTITATIVE · AUTO FROM SPREAD" />
                      <span className="muted" style={{ fontSize: 12 }}>
                        The default scorecard is 100% quantitative — computed from ratios in the analyst-confirmed
                        spread. Deterministic and AI-free; qualitative parameters live in the scoring model below.
                        {gradeSource === "MODEL_OF_RECORD" &&
                          " (This deal's grade of record is the qualitative model composite; the scorecard is the quantitative reference.)"}
                      </span>
                    </div>
                    <ExplainCard
                      title="Quantitative scorecard — auto from spread"
                      subtitle="Each factor is a ratio derived from the analyst-confirmed financial spread; the weighted roll-up is the deterministic scorecard score. Deliberately AI-free."
                      compact
                      explanation={{
                        factors: scorecardFactors,
                        factorHeaders: { factor: "Factor", value: "Ratio", subScore: "Score / 100", weight: "Weight", contribution: "Contribution" },
                        method: `Deterministic scorecard · source ${scorecardSource ?? "built-in"}`,
                      }}
                    />
                  </div>
                )}
              </>
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

      {/* Deterministic capital — deal aggregate + per-facility RWA (when multiple facilities). */}
      {ref && capital && (
        <Card
          title="Capital — deterministic RWA"
          sub="Standardised-approach RWA & capital. Multi-facility deals aggregate a per-facility RWA (each facility's CCF + its linked/apportioned collateral); a single facility is the historical single-figure computation."
          right={<DeterministicBadge label="CAPITAL · DETERMINISTIC" />}
        >
          <div className="grid cols-3">
            <Stat label="Exposure class" value={capital.exposureClass} />
            <Stat label="EAD (post-CCF)" value={<span className="num">{fmt.money(capital.ead)}</span>} />
            <Stat label="Applied risk weight" value={fmt.pct(capital.appliedRiskWeight, 0)} />
            <Stat label="Secured portion" value={<span className="num">{fmt.money(capital.securedPortion)}</span>} />
            <Stat label="RWA" value={<span className="num">{fmt.money(capital.rwa)}</span>} />
            <Stat label="Capital required" value={<span className="num">{fmt.money(capital.capitalRequired)}</span>} />
          </div>

          {perFacilityCapital.length > 0 ? (
            <div style={{ marginTop: 12 }}>
              <div className="prov" style={{ marginBottom: 6 }}>
                Per-facility RWA (aggregates to the deal RWA above):
              </div>
              <table>
                <thead>
                  <tr>
                    <th>Facility</th>
                    <th>Type</th>
                    <th className="num">Amount</th>
                    <th className="num">CCF</th>
                    <th className="num">Exposure</th>
                    <th className="num">Collateral cover</th>
                    <th className="num">RWA</th>
                    <th className="num">Capital</th>
                  </tr>
                </thead>
                <tbody>
                  {perFacilityCapital.map((f: any) => (
                    <tr key={f.facilityReference}>
                      <td className="mono">{f.facilityReference}</td>
                      <td>{f.facilityType}</td>
                      <td className="num">{fmt.money(f.nominalAmount)}</td>
                      <td className="num">{fmt.num(f.ccf, 2)}</td>
                      <td className="num">{fmt.money(f.exposureAfterCcf)}</td>
                      <td className="num">{fmt.money(f.collateralCover)}</td>
                      <td className="num">{fmt.money(f.rwa)}</td>
                      <td className="num">{fmt.money(f.capitalRequired)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="muted" style={{ marginTop: 10 }}>
              Single-facility deal — RWA is the historical single-figure computation (no per-facility split).
            </div>
          )}
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
          <Button onClick={handleAssessRag} disabled={!ref || ragBusy} busy={ragBusy}>
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

            {latestRag && (
              <div style={{ marginTop: 14 }}>
                <ExplainCard
                  title="Why this RAG band"
                  subtitle="Statistical roll-up of deal-level risk indicators into the advisory band."
                  compact
                  explanation={{
                    factors: ragFactors,
                    factorHeaders: { factor: "Factor", value: "Value", subScore: "Sub-score", weight: "Weight", contribution: "Contribution" },
                    method: `Assessed ${new Date(latestRag.createdAt).toLocaleString()} · method: ${latestRag.method}`,
                  }}
                />
              </div>
            )}
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
              {sectorOutlooks.map((o) => (
                <option key={o.code} value={o.code}>{o.label}</option>
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
          <Button onClick={handleMacroSubmit} disabled={!ref || macroBusy} busy={macroBusy}>
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

            {latestMacro && (
              <div style={{ marginTop: 14 }}>
                <ExplainCard
                  title="Why this macro impact"
                  subtitle="Each driver's directional PD-multiplier contribution, and the net multiplier applied to the baseline PD."
                  compact
                  explanation={{
                    factors: macroFactors,
                    factorHeaders: { factor: "Driver", contribution: "PD multiplier Δ", note: "Basis" },
                    whatIf: `Under "${latestMacro.scenarioName}", modelled PD moves ${fmt.pct(latestMacro.baselinePd, 2)} → ${fmt.pct(latestMacro.stressedPd, 2)} (${latestMacro.pdDeltaBps >= 0 ? "+" : ""}${latestMacro.pdDeltaBps} bps). Directional overlay only — the authoritative rating is unchanged.`,
                    method: `Scenario: ${latestMacro.scenarioName} · assessed ${new Date(latestMacro.createdAt).toLocaleString()}`,
                  }}
                />
              </div>
            )}
          </>
        )}

        {!macroAsync.loading && !latestMacro && ref && (
          <div className="muted" style={{ marginTop: 8 }}>No macro assessments yet — configure a scenario and run.</div>
        )}
      </Card>

      {ref && (
        <ModelCard
          refValue={ref}
          grade={ratingAsync.data?.rating?.finalGrade}
          model={modelAsync}
        />
      )}

      {ref && rating && (
        <ManualOverrideCard
          refValue={ref}
          rating={rating}
          model={modelAsync.data}
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
function ManualOverrideCard({ refValue, rating, model, onChanged }: {
  refValue: string; rating: any; model: any; onChanged: () => void;
}) {
  const { actor, notify, roles: actorRoles } = useApp();
  const grades = useCodes("GRADE_SCALE");
  const reasons = useCodes("OVERRIDE_REASON");
  const roles = useCodes("OVERRIDE_ROLE");
  const gradeCodes = grades.map((g) => g.code);
  // Per-role notch ceilings, keyed by (upper-cased) role code — the SAME OVERRIDE_ROLE master
  // the backend now reads (its `score` is the notch limit). Editing the master moves both.
  const roleLimits: Record<string, number> = Object.fromEntries(
    roles.map((r) => [r.code.toUpperCase(), r.score ?? 1]),
  );
  // The acting identity's OWN override authority — the max notch ceiling across the roles the
  // ACTOR_ROLE master grants this actor. This MIRRORS the server's resolveNotchLimit: authority
  // comes from the authenticated actor's real roles, NEVER a picked/claimed role. When roles are
  // unknown (directory not yet loaded) we don't pre-block — the server stays the source of truth.
  const rolesLoaded = (actorRoles ?? []).length > 0;
  const knownActorRoles = (actorRoles ?? []).filter((r) => roleLimits[r.toUpperCase()] != null);
  // Actor's real notch authority. If roles are LOADED but none map to an OVERRIDE_ROLE, the actor
  // has no override authority (limit 0 -> block, matching the server's max-over-zero result). Only
  // when roles aren't loaded yet do we fail-open (99) and let the server stay the source of truth.
  const actorLimit = knownActorRoles.length
    ? Math.max(...knownActorRoles.map((r) => roleLimits[r.toUpperCase()]))
    : (rolesLoaded ? 0 : 99);
  const [proposedGrade, setProposedGrade] = useState("");
  const [reasonCode, setReasonCode] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  const modelGrade: string | undefined = rating?.modelGrade;
  const finalGrade: string | undefined = rating?.finalGrade;
  // + = upgrade (toward AAA), matching MasterScale.notches on the server.
  const notch = proposedGrade && modelGrade
    ? gradeCodes.indexOf(modelGrade) - gradeCodes.indexOf(proposedGrade)
    : 0;
  // Authority is the actor's own resolved limit (server is the source of truth). No "acting as"
  // picker and no separate can()-gate: exceedsLimit already reflects the actor's real notch ceiling
  // (including 0 for an actor with no mapped override role), which is the honest, non-decorative gate.
  const exceedsLimit = Math.abs(notch) > actorLimit;

  async function submit() {
    if (!proposedGrade) { notify("Pick the grade you are overriding to", true); return; }
    if (!reasonCode) { notify("Select a reason code", true); return; }
    setBusy(true);
    try {
      // `role` is intentionally NOT sent — the server resolves notch authority from the actor.
      await risk.overrideRating(refValue, { proposedGrade, reasonCode, note }, actor);
      notify(`Rating overridden to ${proposedGrade}`);
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
      sub="A named human changes the authoritative grade — notch-limited by the actor's own role authority, reason-coded, and segregation-of-duties checked. 100% manual: no AI recommendation pre-fills or drives this."
      right={<HumanBadge label="HUMAN · MANUAL" />}>

      {/* Authoritative figures of record (what an override would change). */}
      <div className="grid cols-3" style={{ marginBottom: 12 }}>
        <Stat label="Model grade" value={<GradeBadge grade={modelGrade} />} />
        <Stat label="Current final grade" value={<GradeBadge grade={finalGrade} />} />
        <Stat label="PD" value={fmt.pct(rating?.pd, 2)} />
      </div>

      {/* Read-only model composite — informs the human, never pre-fills the grade. */}
      <div className="prov" style={{ marginBottom: 14, display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        <AiBadge label="ADVISORY CONTEXT" />
        {model && model.answeredCount > 0 ? (
          <>
            <span>Model composite</span>
            <b>{model.compositeScore.toFixed(1)}/100</b>
            <Badge kind={bandKind(model.compositeBand)}>{model.compositeBand}</Badge>
            {model.status !== "CONFIRMED" && <span className="muted">(not yet confirmed)</span>}
            <span className="muted">— read-only reference; it suggests no grade and does not drive this override.</span>
          </>
        ) : (
          <span className="muted">No model assessment yet — score the model above for advisory context. It is optional and never pre-fills the override.</span>
        )}
      </div>

      <div className="grid cols-3" style={{ alignItems: "end" }}>
        <Field label="Override to grade">
          <select value={proposedGrade} onChange={(e) => setProposedGrade(e.target.value)}>
            <option value="">— select grade —</option>
            {grades.map((g) => <option key={g.code} value={g.code}>{g.label}</option>)}
          </select>
        </Field>
        <Field label="Reason code">
          <select value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
            <option value="">— select reason —</option>
            {reasons.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
          </select>
        </Field>
        <Field label="Your override authority">
          {/* Read-only — the server enforces this from the actor's ACTOR_ROLE roles, not a picker. */}
          <div className="prov" style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", minHeight: 34 }}>
            <HumanBadge label={actor} />
            <span className="muted">
              {knownActorRoles.length ? knownActorRoles.map((r) => r.replace(/_/g, " ")).join(", ") : "no mapped override role"}
              {" · "}
              <b>{actorLimit >= 99 ? "unlimited notches" : `≤ ${actorLimit} notch`}</b>
            </span>
          </div>
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
              Exceeds your authority of {actorLimit} notch(es) — will be rejected and must be escalated to a higher authority.
            </span>
          )}
        </div>
      )}

      <div className="btnrow">
        <span className="authz-gate"
          title={exceedsLimit ? `Beyond your override authority (≤ ${actorLimit} notch) — escalate to a higher authority` : undefined}>
          <Button kind="primary" busy={busy} onClick={submit} disabled={!proposedGrade || !reasonCode || exceedsLimit || busy}>
            Apply manual override
          </Button>
        </span>
        <span className="muted">Acting as {actor}</span>
        <Unchanged label="DETERMINISTIC PD/CAPITAL RE-DERIVED" />
      </div>
    </Card>
  );
}

/**
 * Scoring-model runtime — renders the MODEL_DEFINITION resolved for this deal
 * (sections of typed questions, visibility rules, master-driven options, iterative
 * groups), captures answers, computes a deterministic weighted composite, and
 * human-confirms. Advisory overlay: the composite never moves the authoritative grade.
 */
function bandKindOf(b: string) { return b === "STRONG" ? "ok" : b === "WEAK" ? "bad" : b === "N/A" ? "" : "warn"; }

function ModelCard({ refValue, grade, model }: {
  refValue: string; grade?: string; model: { data: any; reload: () => void };
}) {
  const { actor, notify } = useApp();
  const [busy, setBusy] = useState(false);
  const v = model.data;

  async function run(fn: () => Promise<any>, ok: string) {
    setBusy(true);
    try { await fn(); notify(ok); model.reload(); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }
  const submit = (answers: any[]) =>
    run(() => models.answer(refValue, answers, actor), "Answer captured");

  if (!v) {
    return (
      <Card title="Scoring model — qualitative path" right={<AiBadge label="ADVISORY · CONFIGURABLE" />}>
        <EmptyState glyph="◷" title="No scoring model resolved yet"
          sub="This is the QUALITATIVE path — where qualitative parameters live in the MODEL_DEFINITION model of record (human-input, or governed AI-suggest→human-confirm). Rate the deal first; the engine resolves the model for this jurisdiction/segment, then score it here." />
        <Button kind="subtle" busy={busy} onClick={() => run(() => models.resolve(refValue, actor), "Model resolved")}>
          Resolve model
        </Button>
      </Card>
    );
  }

  // Section-level weights → the unified XAI factor model. The composite is a
  // deterministic weighted roll-up of these sections; shown here as explainability.
  const modelFactors: XaiFactor[] = (v.sections || []).map((s: any) => ({
    label: s.label,
    subScore: s.sectionScore != null ? fmt.num(s.sectionScore, 1) : undefined,
    weight: `${(s.weight * 100).toFixed(0)}%`,
    contribution: s.sectionScore != null ? fmt.num(s.sectionScore * s.weight, 1) : undefined,
    tag: s.sectionBand ? { label: s.sectionBand, kind: bandKindOf(s.sectionBand) } : undefined,
  }));

  return (
    <Card title={`Scoring model (qualitative path) · ${v.displayName}`}
      sub={`Model ${v.modelKey} v${v.modelVersion} · the QUALITATIVE model of record — qualitative parameters are human-input or governed AI-suggest→human-confirm, alongside module-sourced quantitative inputs. Advisory & human-confirmed; the authoritative grade is never changed.`}
      right={<AiBadge label="ADVISORY · CONFIGURABLE" />}>
      <div className="inline" style={{ marginBottom: 10 }}>
        <GovFlow ai="AI SUGGESTS" human="HUMAN CONFIRMS" note="qualitative parameters · advisory" />
      </div>
      <div className="btnrow" style={{ marginBottom: 10, flexWrap: "wrap", gap: 8 }}>
        <Button kind="primary" busy={busy} disabled={busy}
          onClick={() => run(() => models.suggest(refValue, actor), "Auto-scored — module params pulled, standalone params model-scored; review & confirm")}>
          Auto-score
        </Button>
        <Stat label="Composite" value={`${(v.compositeScore ?? 0).toFixed(1)}/100`} />
        <Badge kind={bandKindOf(v.compositeBand)}>{v.compositeBand}</Badge>
        <Badge kind={v.status === "CONFIRMED" ? "ok" : "warn"}>{v.status}</Badge>
        <Badge kind="ok">grade {grade ?? v.authoritativeGrade ?? "—"} · UNCHANGED</Badge>
        <Button kind="subtle" busy={busy} disabled={busy || !v.valid}
          onClick={() => run(() => models.confirm(refValue, actor), "Model confirmed")}>
          Confirm model
        </Button>
      </div>

      {!v.valid && (v.errors || []).length > 0 && (
        <div className="alert err" style={{ marginBottom: 10 }}>
          Unmet constraints: {v.errors.join(" · ")}
        </div>
      )}

      {modelFactors.length > 0 && (
        <ExplainCard
          title="Why this composite score"
          subtitle="Deterministic weighted roll-up of the model's sections — advisory context for the credit decision, never a driver of the authoritative grade."
          compact
          explanation={{
            factors: modelFactors,
            factorHeaders: { factor: "Section", subScore: "Score / 100", weight: "Weight", contribution: "Weighted" },
            method: `Model ${v.modelKey} v${v.modelVersion} · composite ${(v.compositeScore ?? 0).toFixed(1)}/100 (${v.compositeBand})`,
          }}
        />
      )}

      {(v.sections || []).map((s: any) => (
        <div key={s.key} style={{ marginBottom: 16 }}>
          <div className="flexbetween" style={{ marginBottom: 6 }}>
            <b>{s.label} <span className="muted">· {(s.weight * 100).toFixed(0)}% · {s.kind}</span></b>
            <span>
              <Badge kind={bandKindOf(s.sectionBand)}>{s.sectionBand}</Badge>
              {s.sectionScore != null && <span className="mono" style={{ marginLeft: 6 }}>{s.sectionScore.toFixed(1)}/100</span>}
            </span>
          </div>
          <table>
            <thead><tr><th>Question</th><th>Source</th><th>Answer</th><th className="num">Weight</th><th className="num">Score</th></tr></thead>
            <tbody>
              {(s.questions || []).filter((q: any) => q.visible).map((q: any) => {
                const isModule = q.source && q.source !== "STANDALONE";
                return (
                <tr key={q.key}>
                  <td>{q.label}{q.required && <span style={{ color: "var(--bad,#c0392b)" }}> *</span>}
                    {q.visibleWhen && <div className="muted" style={{ fontSize: 11 }}>shown when {q.visibleWhen}</div>}
                    {q.rationale && <div className="muted" style={{ fontSize: 11 }} title={q.rationale}>ⓘ {q.rationale.length > 90 ? q.rationale.slice(0, 90) + "…" : q.rationale}</div>}</td>
                  <td title={isModule ? `Sourced from ${q.source}` : "Scored by the model — not fed by another module"}>
                    {isModule
                    ? <Badge kind="info">{q.source}</Badge>
                    : <Badge>model-scored</Badge>}
                    {q.answerSource && <span className="muted" style={{ fontSize: 11, marginLeft: 4 }}>{q.answerSource}</span>}</td>
                  <td><QuestionInput q={q} disabled={busy || isModule} onAnswer={submit} /></td>
                  <td className="num">{q.weight ? (q.weight * 100).toFixed(0) + "%" : "—"}</td>
                  <td className="num">{q.score == null ? "—" : q.score.toFixed(0)}</td>
                </tr>
              ); })}
            </tbody>
          </table>
        </div>
      ))}
      <div className="prov">
        Composite is a deterministic weighted roll-up of the answers; it is advisory context for the
        credit decision and never pre-fills or moves the authoritative grade.
      </div>
    </Card>
  );
}

/** Renders the input for one question by type, submitting answers on change. */
function QuestionInput({ q, disabled, onAnswer }: { q: any; disabled: boolean; onAnswer: (a: any[]) => void }) {
  const [newItem, setNewItem] = useState<Record<string, string>>({});
  if (q.type === "DROPDOWN") {
    return (
      <select disabled={disabled} value={q.answer ?? ""}
        onChange={(e) => onAnswer([{ questionKey: q.key, value: e.target.value }])}>
        <option value="">— select —</option>
        {(q.options || []).map((o: any) => <option key={o.label} value={o.label}>{o.label} ({o.score})</option>)}
      </select>
    );
  }
  if (q.type === "ITERATIVE") {
    const items: any[] = q.items || [];
    const fields: any[] = q.itemFields || [];
    const addItem = () => {
      const idx = items.reduce((m, it) => Math.max(m, (it._index ?? 0) + 1), 0);
      const answers = fields
        .filter((f) => (newItem[f.key] ?? "").trim() !== "")
        .map((f) => ({ questionKey: q.key, itemIndex: idx, itemFieldKey: f.key, value: newItem[f.key] }));
      if (answers.length) { onAnswer(answers); setNewItem({}); }
    };
    const removeItem = (it: any) =>
      onAnswer(fields.map((f) => ({ questionKey: q.key, itemIndex: it._index, itemFieldKey: f.key, value: "" })));
    return (
      <div>
        {items.length > 0 && (
          <table style={{ marginBottom: 6 }}>
            <tbody>
              {items.map((it) => (
                <tr key={it._index}>
                  {fields.map((f) => <td key={f.key} className="mono">{it[f.key] ?? "—"}</td>)}
                  <td><Button kind="ghost" onClick={() => removeItem(it)}>×</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
          {fields.map((f) => (
            <input key={f.key} placeholder={f.label} disabled={disabled} value={newItem[f.key] ?? ""}
              style={{ width: 110 }}
              onChange={(e) => setNewItem((m) => ({ ...m, [f.key]: e.target.value }))} />
          ))}
          <Button kind="subtle" disabled={disabled} onClick={addItem}>Add</Button>
        </div>
        <span className="muted" style={{ fontSize: 11 }}>{items.length} item(s){q.min ? ` · min ${q.min}` : ""}{q.max ? ` · max ${q.max}` : ""}</span>
      </div>
    );
  }
  // INPUT / NUMBER
  return (
    <input type={q.type === "NUMBER" ? "number" : "text"} disabled={disabled}
      defaultValue={q.answer ?? ""} style={{ width: 140 }}
      onBlur={(e) => { if ((e.target.value ?? "") !== (q.answer ?? "")) onAnswer([{ questionKey: q.key, value: e.target.value }]); }} />
  );
}
