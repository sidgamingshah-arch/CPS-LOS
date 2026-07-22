import { Fragment, useState } from "react";
import { decision, origination, portfolio, risk, tracking as covTracking, workflow, WorkflowView, fmt } from "../api";
import { useApp, isNavEnabled } from "../app-context";
import { AiBadge, Badge, Button, Card, Field, GovFlow, GradeBadge, statusTone, useAsync } from "../ui";
import { ExplainCard, type XaiFactor } from "../xai";
import { useCodes } from "../code-values";
import CopilotPanel from "../CopilotPanel";

// CODE_VALUE-backed dropdowns now drive every selector below — admin-editable
// under maker-checker. FALLBACK_REASON kept ONLY for the initial useState seed.
const FALLBACK_REASON = "OTHER";

function line(v: number) {
  return { value: v, sourceDocument: "audited_financials_FY24.pdf", sourcePage: "P12", coordinates: "tbl1", confidence: 0.97 };
}
const SAMPLE_PERIODS = {
  periods: [
    { label: "FY2024", gaap: "IND_AS", currency: "INR", lines: {
      REVENUE: line(5e9), COGS: line(3.2e9), OPERATING_EXPENSES: line(0.9e9), DEPRECIATION: line(0.2e9),
      INTEREST_EXPENSE: line(0.15e9), TAX: line(0.12e9), TOTAL_ASSETS: line(6e9), CURRENT_ASSETS: line(2.5e9),
      CASH: line(0.6e9), CURRENT_LIABILITIES: line(1.5e9), SHORT_TERM_DEBT: line(0.5e9), LONG_TERM_DEBT: line(1.2e9),
      CURRENT_PORTION_LTD: line(0.2e9), NET_WORTH: line(2.8e9), CFO: line(0.7e9) } },
    { label: "FY2023", gaap: "IND_AS", currency: "INR", lines: {
      REVENUE: line(4.5e9), COGS: line(2.95e9), OPERATING_EXPENSES: line(0.85e9), DEPRECIATION: line(0.18e9),
      INTEREST_EXPENSE: line(0.16e9), TAX: line(0.1e9), TOTAL_ASSETS: line(5.6e9), CURRENT_ASSETS: line(2.3e9),
      CASH: line(0.5e9), CURRENT_LIABILITIES: line(1.45e9), SHORT_TERM_DEBT: line(0.55e9), LONG_TERM_DEBT: line(1.25e9),
      CURRENT_PORTION_LTD: line(0.2e9), NET_WORTH: line(2.5e9), CFO: line(0.6e9) } },
  ],
};

/* ── Navigable case-file chrome ─────────────────────────────────────────────
   Stable section anchors for the jump-list + next-action CTA, the action that
   clears each pill-step, and the deal-scoped screens this workspace deep-links
   into (nav carries the reference so each screen opens on this deal). */
const WS_SECTIONS: { id: string; label: string }[] = [
  { id: "ws-facilities", label: "Facilities" },
  { id: "ws-collateral", label: "Collateral" },
  { id: "ws-documents", label: "Documents" },
  { id: "ws-spreading", label: "Spreading" },
  { id: "ws-rating", label: "Rating" },
  { id: "ws-capital", label: "Capital" },
  { id: "ws-pricing", label: "Pricing" },
  { id: "ws-approval", label: "Approval" },
  { id: "ws-covenants", label: "Covenants" },
  { id: "ws-proposal", label: "Proposal" },
  { id: "ws-booking", label: "Booking" },
];

const NEXT_ACTION: Record<string, { label: string; anchor: string }> = {
  Intake: { label: "Add facilities", anchor: "ws-facilities" },
  Documents: { label: "Attach documents", anchor: "ws-documents" },
  Spread: { label: "Enter spreading", anchor: "ws-spreading" },
  Confirmed: { label: "Confirm spread", anchor: "ws-spreading" },
  Rated: { label: "Run rating", anchor: "ws-rating" },
  Capital: { label: "Compute capital", anchor: "ws-capital" },
  Priced: { label: "Run pricing", anchor: "ws-pricing" },
  Decided: { label: "Route for decision", anchor: "ws-approval" },
};

const OPEN_IN: { view: string; label: string }[] = [
  { view: "risklab", label: "Risk Lab" },
  { view: "pricinglab", label: "Pricing Lab" },
  { view: "spreading", label: "Financial Spreading" },
  { view: "docintel", label: "Doc Intelligence" },
  { view: "commentary", label: "AI Commentary" },
  { view: "docgen", label: "Doc Generation" },
  { view: "committee", label: "Committee Room" },
  { view: "disbursement", label: "Disbursement" },
  { view: "cad", label: "CAD" },
  { view: "drawingpower", label: "Drawing Power" },
  { view: "customer360", label: "Customer-360" },
  { view: "structuring", label: "Deal Structuring" },
];

const scrollToSection = (id: string) =>
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });

export default function DealWorkspace({ reference }: { reference: string }) {
  const { actor, notify, nav, aiEnabled } = useApp();
  // CODE_VALUE dropdowns shared across the nested forms below.
  const grades = useCodes("GRADE_SCALE");
  const overrideReasons = useCodes("OVERRIDE_REASON");
  const overrideRoles = useCodes("OVERRIDE_ROLE");
  const decisionOutcomes = useCodes("DECISION_OUTCOME");
  const facilityTypes = useCodes("FACILITY_TYPE");
  const collateralTypes = useCodes("COLLATERAL_TYPE");
  const app = useAsync(() => origination.get(reference), [reference]);
  const analysis = useAsync(() => origination.analysis(reference), [reference]);
  const docs = useAsync(() => origination.docs(reference), [reference]);
  const summary = useAsync(() => risk.summary(reference), [reference]);
  const dec = useAsync(() => decision.latest(reference).catch(() => null), [reference]);
  const covs = useAsync(() => decision.covenants(reference), [reference]);
  const signals = useAsync(() => portfolio.scan(reference, actor).catch(() => [] as any[]), [reference]);
  const facs = useAsync(() => origination.facilityViews(reference), [reference]);
  const cols = useAsync(() => origination.collaterals(reference), [reference]);
  const covTests = useAsync(() => decision.covenantTests(reference).catch(() => [] as any[]), [reference]);
  const rarocHist = useAsync(() => portfolio.rarocHistory(reference).catch(() => [] as any[]), [reference]);
  const proposalLatest = useAsync(() => decision.latestProposal(reference).catch(() => null as any), [reference]);
  // Available CAM proposal formats for the picker (read-only). Degrades to an empty list.
  const proposalFormats = useAsync(() => decision.proposalFormats().catch(() => [] as any[]), [reference]);
  const [proposalFormat, setProposalFormat] = useState<string>("");
  // Workflow instance (current lifecycle stage + SLA). Chrome only — degrades
  // silently (renders nothing) when the engine errors or has no instance.
  const wfView = useAsync(() => workflow.view(reference).catch(() => null as WorkflowView | null), [reference]);

  const reload = () => { app.reload(); analysis.reload(); docs.reload(); summary.reload(); dec.reload(); covs.reload();
    facs.reload(); cols.reload(); covTests.reload(); rarocHist.reload(); proposalLatest.reload(); };
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  const a = app.data, an = analysis.data, sum = summary.data, d = dec.data;
  const rating = sum?.rating, capital = sum?.capital, pricing = sum?.pricing;

  // CAM proposal format picker: default to the deal segment's format (else STANDARD); the user may
  // pick another. The chosen code is passed to generate — STANDARD stays byte-identical to today.
  const fmtList = (proposalFormats.data || []) as any[];
  const segFmtDefault =
    fmtList.find((f) => f.segment && a?.segment && String(f.segment).toUpperCase() === String(a.segment).toUpperCase())?.code
    || "STANDARD";
  const chosenFormat = proposalFormat || segFmtDefault;

  const steps = [
    { k: "Intake", done: !!a },
    { k: "Documents", done: (docs.data || []).length > 0 },
    { k: "Spread", done: (an?.periods || []).length > 0 },
    { k: "Confirmed", done: !!a?.spreadConfirmed },
    { k: "Rated", done: !!rating },
    { k: "Capital", done: !!capital },
    { k: "Priced", done: !!pricing },
    { k: "Decided", done: d?.status === "DECIDED" },
  ];
  const nextStep = steps.find((s) => !s.done);
  const nextAction = nextStep ? NEXT_ACTION[nextStep.k] : undefined;

  const wfInst = wfView.data?.instance;
  const wfStage = wfInst ? (wfView.data?.stages || []).find((s) => s.stageKey === wfInst.currentStageKey) : undefined;

  if (app.loading) return <div className="loading">Loading deal…</div>;
  if (!a) return <Card title="Not found"><div className="muted">No application {reference}.</div></Card>;
  const latest = an?.periods?.[0];

  return (
    <div className="grid">
      <Card title={`${a.counterpartyName}`} sub={`${a.reference} · ${a.facilityType} · ${fmt.money(a.requestedAmount, a.currency)} · ${a.segment} · ${a.jurisdiction}`}
        right={<Badge kind={statusTone(a.status)}>{a.status}</Badge>}>
        {wfInst && (
          <div className="ws-workflow-strip">
            <span className="wsw-label">Workflow</span>
            <b className="mono">{wfInst.currentStageKey || wfInst.status}</b>
            {wfStage && <span className="wsw-stage">{wfStage.label}</span>}
            {wfStage && (
              <Badge kind={wfStage.status === "COMPLETE" ? "ok" : wfStage.status === "BLOCKED" ? "warn" : "info"}>
                {wfStage.status}
              </Badge>
            )}
            {wfStage?.slaBreached
              ? <Badge kind="bad">SLA BREACHED</Badge>
              : wfStage?.slaDueAt
                ? <span className="wsw-sla">SLA due {fmt.dateTime(wfStage.slaDueAt)}</span>
                : null}
          </div>
        )}
        <div className="pill-steps">
          {steps.map((s) => (
            <span key={s.k} className={`step ${s.done ? "done" : ""}${nextStep?.k === s.k ? " active" : ""}`}>
              {s.done ? "✓ " : ""}{s.k}
            </span>
          ))}
          {nextAction && (
            <button className="ws-next-cta" onClick={() => scrollToSection(nextAction.anchor)}
              title="Jump to the section that clears this step">
              Next: {nextAction.label} ↓
            </button>
          )}
        </div>
        <div className="ws-links">
          <span className="ws-links-label">Open in</span>
          {OPEN_IN.filter((l) => isNavEnabled(l.view, aiEnabled)).map((l) => (
            <button key={l.view} className="ws-link-chip" onClick={() => nav(l.view, reference)}>{l.label}</button>
          ))}
        </div>
        <Button kind="subtle" onClick={() => nav("deals")}>← Back to pipeline</Button>
      </Card>

      {/* Section jump-list — sticky, plain anchors into the case file */}
      <nav className="ws-jumpbar" aria-label="Deal sections">
        {WS_SECTIONS.map((s) => (
          <button key={s.id} className="ws-jump-chip" onClick={() => scrollToSection(s.id)}>{s.label}</button>
        ))}
      </nav>

      {/* Facilities proposed */}
      <section id="ws-facilities" className="ws-anchor">
      <Card title="Facilities proposed · sublimits · interchangeability"
        sub="An application can propose multiple facilities; each facility may carry sublimits (CC · LC · BG · PCFC …) with optional interchangeability groups. Sublimits in the same group share a combined cap — utilisation may move freely within. Cap overflow is enforced server-side."
        right={<AddFacilityButton reference={reference} onAdded={facs.reload} />}>
        <FacilitiesTable facs={facs.data || []} onChange={() => { facs.reload(); reload(); }} />
      </Card>
      </section>

      {/* Collateral */}
      <section id="ws-collateral" className="ws-anchor">
      <Card title="Collateral and security"
        sub="First-class collateral items: type, market value, supervisory haircut, perfection status. Effective cover after haircut feeds the capital projection."
        right={<AddCollateralButton reference={reference} facilities={facs.data || []} onAdded={cols.reload} />}>
        <CollateralsTable cols={cols.data || []} onChange={() => { cols.reload(); reload(); }} />
      </Card>
      </section>

      {/* Collateral intelligence: type-aware extraction + LTV revaluation + charge-Excel */}
      <CollateralIntelBlock reference={reference} cols={cols.data || []} onChange={cols.reload} />

      {/* Documents */}
      <section id="ws-documents" className="ws-anchor">
      <Card title="Documents" sub="AI classification with confidence; low-confidence routed to human review (PRD §3).">
        <div className="btnrow" style={{ marginBottom: 10 }}>
          <Button kind="ghost" onClick={() => run(() => origination.uploadDoc(reference, { fileName: "audited_financials_FY24.pdf" }, actor), "Uploaded financials")}>+ Upload financials</Button>
          <Button kind="ghost" onClick={() => run(() => origination.uploadDoc(reference, { fileName: "MOA_AOA.pdf" }, actor), "Uploaded MOA/AOA")}>+ Upload MOA/AOA</Button>
          <Button kind="ghost" onClick={() => run(() => origination.uploadDoc(reference, { fileName: "misc_scan.pdf" }, actor), "Uploaded misc")}>+ Upload low-confidence doc</Button>
        </div>
        {(docs.data || []).length > 0 && (
          <table><thead><tr><th>File</th><th>Classified</th><th className="num">Confidence</th><th>Routing</th></tr></thead>
            <tbody>{docs.data!.map((doc: any) => (
              <tr key={doc.id}><td>{doc.fileName}</td><td><span className="mono">{doc.classifiedType}</span></td>
                <td className="num">{fmt.pct(doc.classificationConfidence, 0)}</td>
                <td>{doc.needsReview ? <Badge kind="warn">review queue</Badge> : <Badge kind="ok">auto-routed</Badge>}</td></tr>
            ))}</tbody></table>
        )}
      </Card>
      </section>

      {/* Spreading */}
      <section id="ws-spreading" className="ws-anchor">
      <Card title="Financial spreading" sub="Canonical chart with figure-level provenance; derived lines computed; analyst overrides retained (PRD §4)."
        right={<div className="btnrow">
          <Button kind="ghost" onClick={() => run(() => origination.spread(reference, SAMPLE_PERIODS, actor), "Spread generated")}>Auto-spread sample financials</Button>
          <Button disabled={!an?.periods?.length || a.spreadConfirmed}
            onClick={() => run(() => origination.confirmSpread(reference, actor), "Spread confirmed")}>Confirm spread</Button>
        </div>}>
        {!latest ? <div className="muted">No spread yet — auto-spread the sample financials.</div> : (
          <>
            {a.spreadConfirmed ? <Badge kind="ok">Confirmed — may feed rating/capital/pricing</Badge>
              : <div className="gate">HITL gate: the analyst must confirm the spread before it feeds rating. A material override re-opens this gate.</div>}
            {an.benchmarkFlags?.length > 0 && <div className="alert err" style={{ marginTop: 10 }}>{an.benchmarkFlags.join(" · ")}</div>}
            <div className="grid cols-2" style={{ marginTop: 12 }}>
              <div>
                <h4>{latest.label} canonical spread</h4>
                <table><thead><tr><th>Line</th><th className="num">Value</th><th>Source</th></tr></thead>
                  <tbody>{latest.lines.map((c: any) => (
                    <tr key={c.id}>
                      <td>{c.label}{c.derived && <small className="prov"> · derived</small>}</td>
                      <td className="num">
                        {c.derived ? fmt.money(c.value, "") : (
                          <input style={{ width: 130, textAlign: "right", padding: "3px 6px" }} defaultValue={c.value}
                            onBlur={(e) => {
                              const v = +e.target.value;
                              if (v === c.value) return;
                              const apply = (reason?: string) => origination.override(c.id, { value: v, reason }, actor);
                              apply().then(() => { notify("Override applied"); reload(); })
                                .catch((err) => {
                                  if (String(err.message).includes("requires a reason")) {
                                    const r = window.prompt("Material override — reason required:");
                                    if (r) apply(r).then(() => { notify("Material override applied — re-confirmation required"); reload(); }).catch((e2) => notify(e2.message, true));
                                  } else notify(err.message, true);
                                });
                            }} />
                        )}
                      </td>
                      <td>{c.overridden ? <Badge kind={c.materialOverride ? "bad" : "info"}>{c.materialOverride ? "material override" : "override"}</Badge>
                        : c.derived ? <span className="muted">—</span> : <small className="prov">{c.sourceDocument} {c.sourcePage}</small>}</td>
                    </tr>
                  ))}</tbody></table>
              </div>
              <div>
                <h4>Ratios &amp; trends</h4>
                <div className="kv">
                  {Object.entries(latest.ratios).map(([k, v]: any) => (
                    <>
                      <div className="k" key={k + "k"}>{k}</div>
                      <div className="v" key={k + "v"}>{k.includes("MARGIN") || k.includes("ROE") || k.includes("RETURN") ? fmt.pct(v as number, 1) : fmt.num(v as number, 2)}</div>
                    </>
                  ))}
                </div>
                {an.trends && Object.keys(an.trends).length > 0 && (
                  <><h4 style={{ marginTop: 12 }}>YoY</h4><div className="kv">
                    {Object.entries(an.trends).map(([k, v]: any) => (<Fragment key={k}><div className="k">{k}</div><div className="v">{fmt.pct(v as number, 1)}</div></Fragment>))}
                  </div></>
                )}
              </div>
            </div>
          </>
        )}
      </Card>
      </section>

      {/* Rating */}
      <section id="ws-rating" className="ws-anchor">
      <Card title="Risk rating" sub="Scorecard PD/LGD/EAD with per-factor contributions; overrides are notch-limited and logged (PRD §5)."
        right={<Button disabled={!a.spreadConfirmed} onClick={() => run(() => risk.rate(reference, actor), "Rating produced")}>{rating ? "Re-rate" : "Rate"}</Button>}>
        {!rating ? <div className="muted">{a.spreadConfirmed ? "Rate the deal." : "Confirm the spread first."}</div> : (
          <div className="grid cols-2">
            <div>
              <div className="inline" style={{ gap: 16 }}>
                <div><div className="muted">Model</div><GradeBadge grade={rating.modelGrade} /></div>
                <div><div className="muted">Final</div><GradeBadge grade={rating.finalGrade} /></div>
                <div><div className="muted">Score</div><b>{fmt.num(rating.modelScore, 1)}</b></div>
                <div><div className="muted">Status</div>{rating.confirmed ? <Badge kind="ok">confirmed</Badge> : <Badge kind="warn">unconfirmed</Badge>}</div>
              </div>
              <div className="kv" style={{ marginTop: 12 }}>
                <div className="k">PD</div><div className="v">{fmt.pct(rating.pd, 2)}</div>
                <div className="k">LGD</div><div className="v">{fmt.pct(rating.lgd, 0)}</div>
                <div className="k">EAD</div><div className="v">{fmt.money(rating.ead, "")}</div>
                {rating.overridden && <><div className="k">Override</div><div className="v">{rating.overrideNotches > 0 ? "+" : ""}{rating.overrideNotches} notch · {rating.reasonCode}</div></>}
              </div>
              <div className="gate" style={{ marginTop: 10 }}>HITL gate: analyst proposes, approver confirms. Every override feeds the model-monitoring override-rate signal.</div>
              <div className="btnrow">
                <Button kind="ghost" disabled={rating.confirmed} onClick={() => run(() => risk.confirmRating(reference, actor), "Rating confirmed")}>Confirm rating</Button>
              </div>
              <OverrideForm reference={reference} onDone={reload} />
            </div>
            <div>
              <h4>Factor contributions</h4>
              {rating.scoreBreakdown?.factors && (
                <table><thead><tr><th>Factor</th><th className="num">Value</th><th className="num">Score</th><th className="num">Contrib.</th></tr></thead>
                  <tbody>{Object.entries(rating.scoreBreakdown.factors).map(([k, f]: any) => (
                    <tr key={k}><td>{k}</td><td className="num">{fmt.num(f.value, 2)}</td><td className="num">{fmt.num(f.score, 0)}</td><td className="num">{fmt.num(f.contribution, 1)}</td></tr>
                  ))}</tbody></table>
              )}
            </div>
          </div>
        )}
      </Card>
      </section>

      {/* Capital */}
      <section id="ws-capital" className="ws-anchor">
      <Card title="Capital projection (for RAROC)" sub="Internal projection used to drive RAROC pricing — the bank's capital engine remains the system of record. Deterministic, every figure traces to inputs + rule-pack version."
        right={<Button disabled={!rating} onClick={() => run(() => risk.capital(reference, actor), "Capital computed")}>{capital ? "Recompute" : "Compute capital"}</Button>}>
        {!capital ? <div className="muted">Rate the deal, then compute capital.</div> : <CapitalView reference={reference} capital={capital} />}
      </Card>
      </section>

      {/* Why this rating — unified Explainable-AI view over the existing rating
          factor contributions + the grounded capital/explain narrative. Advisory. */}
      {rating && <WhyThisRatingCard reference={reference} rating={rating} capital={capital} />}

      {/* Pricing */}
      <section id="ws-pricing" className="ws-anchor">
      <Card title="RAROC pricing" sub="Risk-adjusted price — advisory only; below-hurdle flagged for escalation (PRD §7)."
        right={<Button disabled={!capital} onClick={() => run(() => risk.pricing(reference, actor), "Pricing computed")}>{pricing ? "Reprice" : "Compute price"}</Button>}>
        {!pricing ? <div className="muted">Compute capital first.</div> : (
          <div className="inline" style={{ gap: 24 }}>
            <div><div className="muted">Recommended rate</div><div className="stat"><b style={{ fontSize: 22 }}>{fmt.pct(pricing.recommendedRate, 2)}</b></div></div>
            <div><div className="muted">RAROC</div><div className="stat"><b style={{ fontSize: 22 }}>{fmt.pct(pricing.raroc, 1)}</b></div></div>
            <div><div className="muted">Hurdle</div><div>{fmt.pct(pricing.hurdleRaroc, 1)}</div></div>
            <div>{pricing.belowHurdle ? <Badge kind="bad">BELOW HURDLE — escalate</Badge> : <Badge kind="ok">clears hurdle</Badge>}</div>
            <div className="muted">EL {fmt.money(pricing.expectedLoss, "")} · capital {fmt.money(pricing.capitalCharge, "")}</div>
          </div>
        )}
      </Card>
      </section>

      {/* Approval */}
      <section id="ws-approval" className="ws-anchor">
      <Card title="Approval workflow" sub="DoA routing on amount × rating × deviations; the decision is a named human action — AI never approves (PRD §8)."
        right={<Button disabled={!pricing || !rating?.confirmed} onClick={() => run(() => decision.route(reference, actor), "Routed for approval")}>{d ? "Re-route" : "Route for approval"}</Button>}>
        <CovenantsBlock reference={reference} grade={rating?.finalGrade} covs={covs.data || []} onChange={() => covs.reload()} />
        {d && (
          <>
            <div className="flexbetween" style={{ marginTop: 14 }}>
              <div>Requires <Badge kind="warn">{d.requiredAuthority}</Badge>{d.status === "DECIDED" && <> · <Badge kind={statusTone(d.outcome)}>{d.outcome}</Badge> by {d.decidedBy}</>}</div>
            </div>
            {d.deviations?.length > 0 && <div className="alert err" style={{ marginTop: 8 }}>Deviations: {d.deviations.join(" · ")}</div>}
            <CommitteeNote reference={reference} />
            {d.status !== "DECIDED" && <DecideForm reference={reference} required={d.requiredAuthority} onDone={reload} />}
            {d.status === "DECIDED" && d.conditions?.length > 0 && (
              <div style={{ marginTop: 10 }}><b>Conditions:</b><ul>{d.conditions.map((c: string, i: number) => <li key={i}>{c}</li>)}</ul></div>
            )}
          </>
        )}
      </Card>
      </section>

      {/* Covenant testing history */}
      <section id="ws-covenants" className="ws-anchor">
      <Card title="Covenant testing"
        sub="Tests every active covenant against the latest spread ratios; observations persisted to history (PRD §7 sample covenant rule, §11 monitoring)."
        right={<Button kind="subtle" disabled={!(covs.data || []).length}
          onClick={() => run(() => decision.testCovenants(reference, actor), "Covenants tested")}>Run covenant tests</Button>}>
        {(covTests.data || []).length === 0 ? <div className="muted">No tests yet — set up covenants and run a test.</div> : (
          <div className="table-scroll">
          <table><thead><tr><th>When</th><th>Metric</th><th>Test</th><th className="num">Observed</th><th>Pass</th><th>Severity</th></tr></thead>
            <tbody>{covTests.data!.slice(0, 12).map((t: any) => (
              <tr key={t.id}>
                <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(t.testedAt)}</td>
                <td>{t.metric}</td><td className="mono">{t.operator} {t.threshold}</td>
                <td className="num">{fmt.num(t.observed, 2)}</td>
                <td>{t.passed ? <Badge kind="ok">pass</Badge> : <Badge kind="bad">breach</Badge>}</td>
                <td><Badge kind={t.breachSeverity === "CRITICAL" ? "bad" : t.breachSeverity === "MAJOR" ? "warn" : "info"}>{t.breachSeverity}</Badge></td>
              </tr>))}</tbody></table>
          </div>
        )}
      </Card>

      {/* Covenant intelligence: extract from CP free text + assess compliance certificate */}
      <CovenantIntelBlock reference={reference} onConfirmed={() => covs.reload()} />

      {/* Covenant tracking workflow */}
      <CovenantTrackingBlock reference={reference} />
      </section>

      {/* Credit proposal */}
      <section id="ws-proposal" className="ws-anchor">
      <Card title="Credit proposal"
        sub="A formal committee memo grounded in this deal's data — every figure quoted from the engines, section-to-source citations stored (PRD §8 US-8.3, §13). Pick a CAM format to reshape the section layout; the figures are unchanged (a format is a rendering, not a figure source)."
        right={
          <div className="btnrow">
            <select value={chosenFormat} onChange={(e) => setProposalFormat(e.target.value)} title="CAM format" style={{ maxWidth: 220 }}>
              {fmtList.length === 0 && <option value="STANDARD">Standard universal CAM</option>}
              {fmtList.map((f) => (
                <option key={f.code} value={f.code}>{(f.label || f.code)}{f.code === segFmtDefault ? " · default" : ""}</option>
              ))}
            </select>
            <Button onClick={() => run(() => decision.generateProposal(reference, actor, chosenFormat), "Proposal generated")}>{proposalLatest.data ? "Re-generate" : "Generate proposal"}</Button>
          </div>
        }>
        {!proposalLatest.data ? <div className="muted">No proposal yet.</div> : (
          <>
            <div className="btnrow" style={{ marginBottom: 10 }}>
              <Badge kind="info">v{proposalLatest.data.version}</Badge>
              {proposalLatest.data.format && <Badge kind="info">CAM · {proposalLatest.data.format}</Badge>}
              <small className="prov">Generated {fmt.dateTime(proposalLatest.data.generatedAt)} by {proposalLatest.data.generatedBy}</small>
            </div>
            <div style={{ background: "#fff", border: "1px solid var(--line)", borderRadius: 10, padding: 16, maxHeight: 460, overflow: "auto", fontSize: 13 }}
              dangerouslySetInnerHTML={{ __html: proposalLatest.data.html }} />
            <div style={{ marginTop: 10 }}>
              <small className="prov">Citations:</small>
              <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
                {Object.entries(proposalLatest.data.citations || {}).map(([k, v]: any) => (
                  <li key={k}><small className="prov"><span className="mono">{k}</span> — {String(v)}</small></li>
                ))}
              </ul>
            </div>
          </>
        )}
      </Card>
      </section>

      {/* RAROC tracking */}
      <Card title="Projected vs actual RAROC"
        sub="The bank's capital engine remains the system of record; here we close the loop on pricing — projected at booking, actual recomputed from realised conduct, variance feeds model-fit governance (PRD §7, §11).">
        <RarocBlock reference={reference} history={rarocHist.data || []} onChange={rarocHist.reload} />
      </Card>

      {/* Book & monitor */}
      <section id="ws-booking" className="ws-anchor">
      <Card title="Book, provision & monitor" sub="ECL/IRAC provisioning, EWS — all human-gated for staging/remediation (PRD §11–12).">
        <BookBlock reference={reference} decided={d?.status === "DECIDED"} onChange={reload} signals={signals} />
      </Card>
      </section>

      {/* Copilot, scoped to this deal */}
      <Card title="Ask the copilot about this deal" sub="Scoped to your role, grounded in this deal's data, non-binding (PRD §6.6).">
        <CopilotPanel reference={reference} compact />
      </Card>
    </div>
  );

  function OverrideForm({ reference, onDone }: { reference: string; onDone: () => void }) {
    const [open, setOpen] = useState(false);
    const [g, setG] = useState("BBB"); const [rc, setRc] = useState(FALLBACK_REASON); const [role, setRole] = useState("CREDIT_OFFICER"); const [note, setNote] = useState("");
    if (!open) return <Button kind="subtle" onClick={() => setOpen(true)}>Override rating…</Button>;
    return (
      <div className="card" style={{ marginTop: 10, background: "#fbfaff" }}>
        <div className="grid cols-2">
          <Field label="Proposed grade"><select value={g} onChange={(e) => setG(e.target.value)}>{grades.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}</select></Field>
          <Field label="Role"><select value={role} onChange={(e) => setRole(e.target.value)}>{overrideRoles.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}</select></Field>
          <Field label="Reason code"><select value={rc} onChange={(e) => setRc(e.target.value)}>{overrideReasons.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}</select></Field>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        </div>
        <div className="btnrow">
          <Button onClick={() => run(() => risk.overrideRating(reference, { proposedGrade: g, reasonCode: rc, role, note }, actor), "Override applied").then(() => { setOpen(false); onDone(); })}>Apply override</Button>
          <Button kind="subtle" onClick={() => setOpen(false)}>Cancel</Button>
        </div>
        <small className="prov">Notch limits: ANALYST 1 · CREDIT_OFFICER 2 · COMMITTEE unlimited.</small>
      </div>
    );
  }

  function CapitalView({ reference, capital }: { reference: string; capital: any }) {
    const expl = useAsync(() => risk.explainCapital(reference), [reference, capital.id]);
    return (
      <div className="grid cols-2">
        <div>
          <div className="grid cols-2">
            <div className="stat card"><div className="label">RWA</div><div className="value">{fmt.money(capital.rwa, "")}</div></div>
            <div className="stat card"><div className="label">Capital required</div><div className="value">{fmt.money(capital.capitalRequired, "")}</div></div>
          </div>
          <div className="kv" style={{ marginTop: 12 }}>
            <div className="k">Exposure class</div><div className="v">{capital.exposureClass}</div>
            <div className="k">Applied risk weight</div><div className="v">{fmt.pct(capital.appliedRiskWeight, 0)}</div>
            <div className="k">CRM secured portion</div><div className="v">{fmt.money(capital.securedPortion, "")}</div>
            <div className="k">DD uplift applied</div><div className="v">{String(capital.dueDiligenceUpliftApplied)}</div>
            <div className="k">Rule pack</div><div className="v mono">{capital.capitalPackCode} v{capital.capitalPackVersion}</div>
          </div>
          {expl.data && <div className="gate" style={{ marginTop: 10 }}>AI explanation (grounded, quotes engine values): {expl.data.explanation}</div>}
        </div>
        <div><h4>Computation trace</h4><pre className="trace">{JSON.stringify(capital.trace, null, 2)}</pre></div>
      </div>
    );
  }

  /**
   * Compact "Why this rating" — the same Explainable-AI panel used on Risk Lab, here
   * surfaced next to the rating/capital cards. It reads the EXISTING advisory endpoints
   * (rating scoreBreakdown factors + the grounded capital/explain narrative) and never
   * moves an authoritative figure — presentation only.
   */
  function WhyThisRatingCard({ reference, rating, capital }: { reference: string; rating: any; capital: any }) {
    const expl = useAsync(() => risk.explainCapital(reference).catch(() => null), [reference, capital?.id]);
    const factors: Record<string, any> = rating?.scoreBreakdown?.factors ?? {};
    const ratingFactors: XaiFactor[] = Object.entries(factors).map(([k, f]: [string, any]) => ({
      label: k,
      value: fmt.num(f.value, 2),
      subScore: fmt.num(f.score, 0),
      contribution: fmt.num(f.contribution, 1),
    }));
    const methodBits = ["Deterministic scorecard PD/LGD/EAD"];
    if (capital?.capitalPackCode) methodBits.push(`capital pack ${capital.capitalPackCode} v${capital.capitalPackVersion}`);
    return (
      <ExplainCard
        title="Why this rating"
        subtitle={`Model ${rating.modelGrade ?? "—"} → final ${rating.finalGrade ?? "—"} · PD ${fmt.pct(rating.pd, 2)}`}
        compact
        right={<GradeBadge grade={rating.finalGrade} />}
        explanation={{
          summary: expl.data?.explanation
            ? <><b>Capital:</b> {expl.data.explanation}</>
            : undefined,
          factors: ratingFactors,
          factorHeaders: { factor: "Factor", value: "Value", subScore: "Score", contribution: "Contribution" },
          method: methodBits.join(" · "),
        }}
      />
    );
  }

  function CommitteeNote({ reference }: { reference: string }) {
    const [note, setNote] = useState<string | null>(null);
    return (
      <div style={{ marginTop: 10 }}>
        <Button kind="subtle" onClick={() => decision.committeeNote(reference).then((r) => setNote(r.draft)).catch((e) => notify(e.message, true))}>Draft committee note (AI)</Button>
        {note && <pre className="trace" style={{ marginTop: 8, whiteSpace: "pre-wrap" }}>{note}</pre>}
      </div>
    );
  }

  function CovenantsBlock({ reference, grade, covs, onChange }: { reference: string; grade?: string; covs: any[]; onChange: () => void }) {
    const addSuggested = async () => {
      try {
        const sugg = await decision.suggest(grade || "BBB");
        for (const s of sugg) await decision.addCovenant(reference, s, actor);
        notify(`Added ${sugg.length} covenants`); onChange();
      } catch (e: any) { notify(e.message, true); }
    };
    return (
      <div>
        <div className="flexbetween"><h4>Covenants</h4><Button kind="subtle" onClick={addSuggested}>+ Add AI-suggested covenants</Button></div>
        {covs.length > 0 ? (
          <table><thead><tr><th>Metric</th><th>Test</th><th>Freq</th><th>Severity</th></tr></thead>
            <tbody>{covs.map((c) => (
              <tr key={c.id}><td>{c.metric}</td><td className="mono">{c.operator} {c.threshold}</td><td>{c.testFrequency}</td>
                <td><Badge kind={c.breachSeverity === "CRITICAL" ? "bad" : c.breachSeverity === "MAJOR" ? "warn" : "info"}>{c.breachSeverity}</Badge></td></tr>
            ))}</tbody></table>
        ) : <div className="muted">No covenants yet.</div>}
      </div>
    );
  }

  function DecideForm({ reference, required, onDone }: { reference: string; required: string; onDone: () => void }) {
    const [outcome, setOutcome] = useState("CONDITIONAL_APPROVE");
    const [conds, setConds] = useState("Maintain DSCR >= 1.25x\nCharge over plant & machinery");
    const [rationale, setRationale] = useState("Strong coverage and collateral; standard conditions.");
    return (
      <div className="card" style={{ marginTop: 12, background: "#fbfaff" }}>
        <div className="gate">Named human decision required at {required} authority. AI cannot approve.</div>
        <div className="grid cols-2">
          <Field label="Outcome"><select value={outcome} onChange={(e) => setOutcome(e.target.value)}>{decisionOutcomes.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}</select></Field>
          <Field label="Your authority (acting as decider)"><input value={required} disabled /></Field>
        </div>
        <Field label="Conditions (one per line)"><textarea rows={2} value={conds} onChange={(e) => setConds(e.target.value)} /></Field>
        <Field label="Rationale"><input value={rationale} onChange={(e) => setRationale(e.target.value)} /></Field>
        <Button onClick={() => run(() => decision.decide(reference, {
          outcome, role: required, rationale, conditions: conds.split("\n").map((s) => s.trim()).filter(Boolean),
        }, actor), "Decision recorded").then(onDone)}>Record decision</Button>
      </div>
    );
  }

  function BookBlock({ reference, decided, onChange, signals }: { reference: string; decided: boolean; onChange: () => void; signals: any }) {
    const [dpd, setDpd] = useState(0);
    const [ecl, setEcl] = useState<any>(null);
    const [exp, setExp] = useState<any>(null);
    const book = async () => {
      try { const e = await portfolio.register(reference, dpd, actor); setExp(e); notify("Exposure booked"); onChange(); } catch (e: any) { notify(e.message, true); }
    };
    const computeEcl = async () => {
      try { const r = await portfolio.ecl(reference, actor); setEcl(r); notify(`ECL ${fmt.money(r.reportedProvision, "")}`); } catch (e: any) { notify(e.message, true); }
    };
    return (
      <div className="grid cols-2">
        <div>
          <div className="inline">
            <Field label="Days past due (conduct)"><input type="number" value={dpd} onChange={(e) => setDpd(+e.target.value)} style={{ width: 120 }} /></Field>
            <Button disabled={!decided} onClick={book}>Book exposure</Button>
            <Button kind="ghost" disabled={!exp} onClick={computeEcl}>Compute ECL</Button>
            <Button kind="subtle" onClick={() => signals.reload()}>Run EWS scan</Button>
          </div>
          {!decided && <div className="muted">Record a decision before booking.</div>}
          {ecl && (
            <div className="kv" style={{ marginTop: 10 }}>
              <div className="k">Stage</div><div className="v"><Badge kind={ecl.stage === "STAGE_3" ? "bad" : ecl.stage === "STAGE_2" ? "warn" : "ok"}>{ecl.stage}</Badge></div>
              <div className="k">ECL (IFRS 9)</div><div className="v">{fmt.money(ecl.ecl, "")}</div>
              <div className="k">IRAC ({ecl.iracClass})</div><div className="v">{fmt.money(ecl.iracProvision, "")}</div>
              <div className="k">Reported ({ecl.reportedProvisionPolicy})</div><div className="v"><b>{fmt.money(ecl.reportedProvision, "")}</b></div>
            </div>
          )}
        </div>
        <div>
          <h4>Early-warning signals</h4>
          {(signals.data || []).length === 0 ? <div className="muted">No signals (clean name) — or scan after booking.</div> : (
            <table><thead><tr><th>Type</th><th>Severity</th><th className="num">Score</th></tr></thead>
              <tbody>{signals.data!.map((s: any) => (
                <tr key={s.id}><td><span className="mono">{s.signalType}</span><br /><small className="prov">{s.proposedAction}</small></td>
                  <td><Badge kind={s.severity === "SEVERE" ? "bad" : s.severity === "HIGH" ? "warn" : "info"}>{s.severity}</Badge></td>
                  <td className="num">{fmt.num(s.score, 2)}</td></tr>
              ))}</tbody></table>
          )}
        </div>
      </div>
    );
  }

  function AddFacilityButton({ reference, onAdded }: { reference: string; onAdded: () => void }) {
    const [open, setOpen] = useState(false);
    const [f, setF] = useState<any>({ facilityType: "WORKING_CAPITAL", amount: 100_000_000, currency: "INR", tenorMonths: 12, purpose: "" });
    if (!open) return <Button kind="ghost" onClick={() => setOpen(true)}>+ Add facility</Button>;
    return (
      <span>
        <select value={f.facilityType} onChange={(e) => setF({ ...f, facilityType: e.target.value })} style={{ width: 170, marginRight: 6 }}>
          {facilityTypes.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
        </select>
        <input type="number" value={f.amount} onChange={(e) => setF({ ...f, amount: +e.target.value })} style={{ width: 130, marginRight: 6 }} />
        <input type="number" value={f.tenorMonths} onChange={(e) => setF({ ...f, tenorMonths: +e.target.value })} style={{ width: 70, marginRight: 6 }} />
        <Button onClick={() => { run(() => origination.addFacility(reference, f, actor), "Facility added").then(() => { setOpen(false); onAdded(); }); }}>Add</Button>
        <Button kind="subtle" onClick={() => setOpen(false)}>Cancel</Button>
      </span>
    );
  }

  function FacilitiesTable({ facs, onChange }: { facs: any[]; onChange: () => void }) {
    if (facs.length === 0) return <div className="muted">No facilities recorded.</div>;
    return (
      <div>
        {facs.map((f: any) => (
          <FacilityCard key={f.id} f={f} onChange={onChange} />
        ))}
      </div>
    );
  }

  function FacilityCard({ f, onChange }: { f: any; onChange: () => void }) {
    const [addingSublimit, setAddingSublimit] = useState(false);
    const hasSublimits = (f.sublimits || []).length > 0;
    return (
      <div style={{ border: "1px solid var(--line)", borderRadius: 10, padding: 12, marginBottom: 10, background: "#fcfcff" }}>
        <div className="flexbetween">
          <div>
            <b>#{f.ordinal}{f.primary && <span title="primary"> ★</span>} · {f.facilityType}</b>
            <span className="mono" style={{ marginLeft: 8, color: "var(--muted)", fontSize: 11 }}>{f.reference}</span>
          </div>
          <div className="btnrow">
            <Badge>{fmt.money(f.amount, f.currency)}</Badge>
            <Badge kind="info">{f.tenorMonths}m</Badge>
            {f.indicativeRate != null && <Badge>{fmt.pct(f.indicativeRate, 2)}</Badge>}
            <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
              onClick={() => setAddingSublimit((s) => !s)}>{addingSublimit ? "Close" : "+ Sublimit"}</button>
            {!f.primary && (
              <button className="btn danger" style={{ fontSize: 11, padding: "3px 8px" }}
                onClick={() => {
                  if (!window.confirm(`Remove facility #${f.ordinal} ${f.facilityType} (${f.reference})?`)) return;
                  run(() => origination.removeFacility(f.id, actor), "Facility removed").then(onChange);
                }}>Remove</button>
            )}
          </div>
        </div>
        {f.purpose && <small className="prov">{f.purpose}</small>}

        {(hasSublimits || addingSublimit) && (
          <div style={{ marginTop: 10, paddingTop: 8, borderTop: "1px dashed var(--line)" }}>
            <div className="flexbetween" style={{ marginBottom: 6 }}>
              <small className="prov">
                Sublimits {hasSublimits ? `· allocated ${fmt.money(f.sublimitTotal, f.currency)} · headroom ${fmt.money(f.sublimitHeadroom, f.currency)}` : ""}
              </small>
            </div>
            {hasSublimits && (
              <div className="table-scroll" style={{ marginBottom: 8 }}>
              <table>
                <thead><tr><th>Code</th><th>Product</th><th className="num">Amount</th><th>Tenor</th><th>Interchangeable group</th><th>Purpose</th><th></th></tr></thead>
                <tbody>{f.sublimits.map((s: any) => (
                  <tr key={s.id}>
                    <td><b>{s.code}</b></td>
                    <td><span className="mono">{s.productType}</span></td>
                    <td className="num">{fmt.money(s.amount, s.currency)}</td>
                    <td>{s.tenorMonths ? s.tenorMonths + "m" : "—"}</td>
                    <td>{s.fungible
                      ? <Badge kind="ai">{s.interchangeableGroup}</Badge>
                      : <Badge>fixed (hard cap)</Badge>}</td>
                    <td>{s.purpose || "—"}</td>
                    <td><button className="btn danger" style={{ fontSize: 11, padding: "2px 6px" }} title={`Remove sublimit ${s.code}`}
                      onClick={() => {
                        if (!window.confirm(`Remove sublimit ${s.code} (${s.productType})?`)) return;
                        run(() => origination.removeSublimit(s.id, actor), "Sublimit removed").then(onChange);
                      }}>×</button></td>
                  </tr>))}</tbody>
              </table>
              </div>
            )}
            {(f.interchangeabilityGroups || []).length > 0 && (
              <div style={{ marginBottom: 8 }}>
                <small className="prov">Fungibility pools — utilisation may move freely within each:</small>
                <ul style={{ margin: "4px 0 0", paddingLeft: 18, fontSize: 12 }}>
                  {f.interchangeabilityGroups.map((g: any) => (
                    <li key={g.groupKey}>
                      <Badge kind="ai">{g.groupKey}</Badge> · combined cap <b>{fmt.money(g.combinedCap, g.currency)}</b> · members: {g.memberCodes.join(", ")}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {addingSublimit && (
              <AddSublimitForm facility={f} onDone={() => { setAddingSublimit(false); onChange(); }} />
            )}
          </div>
        )}
      </div>
    );
  }

  function AddSublimitForm({ facility, onDone }: { facility: any; onDone: () => void }) {
    const PRODUCTS: Record<string, string> = {
      "CC": "CASH_CREDIT", "OD": "OVERDRAFT", "BD": "BILL_DISCOUNTING",
      "PCFC": "PACKING_CREDIT", "LC_INLAND": "LETTER_OF_CREDIT",
      "LC_FOREIGN": "LETTER_OF_CREDIT", "BG_PERF": "BANK_GUARANTEE",
      "BG_FIN": "BANK_GUARANTEE",
    };
    const [s, setS] = useState<any>({
      code: "CC", productType: "CASH_CREDIT", amount: 50_000_000,
      tenorMonths: facility.tenorMonths, purpose: "", interchangeableGroup: "WC_FUNDED",
    });
    return (
      <div style={{ background: "#fff", border: "1px solid var(--line)", borderRadius: 8, padding: 10 }}>
        <div className="grid cols-4" style={{ gap: 8 }}>
          <Field label="Code">
            <select value={s.code} onChange={(e) => setS({ ...s, code: e.target.value, productType: PRODUCTS[e.target.value] || s.productType })}>
              {Object.keys(PRODUCTS).map((c) => <option key={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="Product"><input value={s.productType} onChange={(e) => setS({ ...s, productType: e.target.value })} /></Field>
          <Field label="Amount"><input type="number" value={s.amount} onChange={(e) => setS({ ...s, amount: +e.target.value })} /></Field>
          <Field label="Interchangeable group (blank = hard cap)">
            <input value={s.interchangeableGroup || ""} onChange={(e) => setS({ ...s, interchangeableGroup: e.target.value })} placeholder="e.g. WC_FUNDED" />
          </Field>
        </div>
        <div className="btnrow">
          <Button onClick={() => run(() => origination.addSublimit(facility.id,
            { ...s, currency: facility.currency }, actor), "Sublimit added").then(onDone)}>Add sublimit</Button>
          <Button kind="subtle" onClick={onDone}>Cancel</Button>
          <small className="prov">Facility cap is enforced — overflow is rejected.</small>
        </div>
      </div>
    );
  }

  function AddCollateralButton({ reference, facilities, onAdded }: { reference: string; facilities: any[]; onAdded: () => void }) {
    const [open, setOpen] = useState(false);
    const [c, setC] = useState<any>({
      collateralType: "PROPERTY", description: "", marketValue: 100_000_000, haircut: 0.4,
      perfectionStatus: "IN_PROGRESS", facilityId: null, owner: "",
    });
    if (!open) return <Button kind="ghost" onClick={() => setOpen(true)}>+ Add collateral</Button>;
    return (
      <span>
        <select value={c.collateralType} onChange={(e) => setC({ ...c, collateralType: e.target.value })} style={{ width: 150, marginRight: 6 }}>
          {collateralTypes.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
        </select>
        <input placeholder="description" value={c.description} onChange={(e) => setC({ ...c, description: e.target.value })} style={{ width: 160, marginRight: 6 }} />
        <input type="number" value={c.marketValue} onChange={(e) => setC({ ...c, marketValue: +e.target.value })} style={{ width: 120, marginRight: 6 }} />
        <select value={c.facilityId || ""} onChange={(e) => setC({ ...c, facilityId: e.target.value ? +e.target.value : null })} style={{ width: 140, marginRight: 6 }}>
          <option value="">(pooled to deal)</option>
          {facilities.map((f) => <option key={f.id} value={f.id}>#{f.ordinal} {f.facilityType}</option>)}
        </select>
        <Button onClick={() => { run(() => origination.addCollateral(reference, c, actor), "Collateral added").then(() => { setOpen(false); onAdded(); }); }}>Add</Button>
        <Button kind="subtle" onClick={() => setOpen(false)}>Cancel</Button>
      </span>
    );
  }

  function CollateralsTable({ cols, onChange }: { cols: any[]; onChange: () => void }) {
    if (cols.length === 0) return <div className="muted">No collateral recorded.</div>;
    const totalCover = cols.reduce((s, c) => s + (c.marketValue * (1 - c.haircut)), 0);
    return (
      <>
        <div className="table-scroll">
        <table><thead><tr><th>Type</th><th>Description</th><th className="num">Market value</th><th>Haircut</th><th className="num">Effective</th><th>Perfection</th><th>Facility</th><th></th></tr></thead>
          <tbody>{cols.map((c: any) => (
            <tr key={c.id}>
              <td><span className="mono">{c.collateralType}</span></td>
              <td>{c.description || "—"}</td>
              <td className="num">{fmt.money(c.marketValue, "")}</td>
              <td>{fmt.pct(c.haircut, 0)}</td>
              <td className="num">{fmt.money(c.marketValue * (1 - c.haircut), "")}</td>
              <td><Badge kind={c.perfectionStatus === "PERFECTED" ? "ok" : c.perfectionStatus === "IN_PROGRESS" ? "warn" : "info"}>{c.perfectionStatus}</Badge></td>
              <td>{c.facilityId ? <span className="mono">F#{c.facilityId}</span> : <span className="muted">pooled</span>}</td>
              <td>{c.perfectionStatus !== "PERFECTED" &&
                <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                  onClick={() => run(() => origination.perfectCollateral(c.id, actor), "Charge perfected").then(onChange)}>Perfect</button>}</td>
            </tr>))}</tbody></table>
        </div>
        <div style={{ marginTop: 8 }}><b>Total effective coverage:</b> {fmt.money(totalCover, "")}</div>
      </>
    );
  }

  function RarocBlock({ reference, history, onChange }: { reference: string; history: any[]; onChange: () => void }) {
    const [period, setPeriod] = useState("2026Q2");
    const [delta, setDelta] = useState(0);
    const origin = history.find((h) => h.origination);
    const actuals = history.filter((h) => !h.origination);
    return (
      <div className="grid cols-2">
        <div>
          <div className="btnrow" style={{ marginBottom: 8 }}>
            <Button kind="subtle" onClick={() => run(() => portfolio.rarocSnapshot(reference, actor), "Projected RAROC snapshotted").then(onChange)}>Snapshot projected</Button>
          </div>
          <div className="inline" style={{ gap: 8, marginBottom: 8 }}>
            <Field label="Period"><input value={period} onChange={(e) => setPeriod(e.target.value)} style={{ width: 110 }} /></Field>
            <Field label="Realised provision Δ"><input type="number" value={delta} onChange={(e) => setDelta(+e.target.value)} style={{ width: 140 }} /></Field>
            <Button onClick={() => run(() => portfolio.rarocCompute(reference, period, delta, actor), "Actual RAROC computed").then(onChange)}>Compute actual</Button>
          </div>
          {origin && (
            <div className="kv">
              <div className="k">Projected RAROC (origination)</div><div className="v"><b>{fmt.pct(origin.projectedRaroc, 2)}</b></div>
              <div className="k">Recommended rate</div><div className="v">{fmt.pct(origin.projectedRecommendedRate, 2)}</div>
              <div className="k">Projected capital</div><div className="v">{fmt.money(origin.projectedCapitalCharge, "")}</div>
            </div>
          )}
        </div>
        <div>
          <h4>Variance history</h4>
          {actuals.length === 0 ? <div className="muted">No actual observations yet.</div> : (
            <table><thead><tr><th>Period</th><th className="num">Projected</th><th className="num">Actual</th><th className="num">Δ</th><th>|Δ| / projected</th></tr></thead>
              <tbody>{actuals.map((h: any) => {
                const material = h.absVariancePct > 0.25;
                return (
                  <tr key={h.id}>
                    <td>{h.periodLabel}</td>
                    <td className="num">{fmt.pct(h.projectedRaroc, 2)}</td>
                    <td className="num">{fmt.pct(h.actualRaroc, 2)}</td>
                    <td className="num" style={{ color: h.variance < 0 ? "var(--bad)" : "var(--ok)" }}>{fmt.pct(h.variance, 2)}</td>
                    <td>{material ? <Badge kind="bad">material miss</Badge> : <Badge kind="ok">within</Badge>}</td>
                  </tr>);
              })}</tbody></table>
          )}
        </div>
      </div>
    );
  }

  function CovenantTrackingBlock({ reference }: { reference: string }) {
    const list = useAsync(() => covTracking.list(reference).catch(() => [] as any[]), [reference]);
    const init = async () => run(() => covTracking.init({ applicationRef: reference,
      startDate: new Date().toISOString().slice(0, 10),
      endDate: new Date(Date.now() + 3 * 365 * 86400_000).toISOString().slice(0, 10) }, actor), "Schedules initialised").then(() => list.reload());
    const runDue = async () => run(() => covTracking.runDue(reference, actor), "Tested due schedules").then(() => list.reload());
    return (
      <Card title="Covenant tracking · workflow"
        sub="Schedule (frequency · period · grace) drives the state machine; waiver/extension goes through maker-checker with SoD (PRD covenant tracking module)."
        right={<div className="btnrow">
          <Button kind="subtle" onClick={init}>Initialise schedules</Button>
          <Button kind="ghost" disabled={(list.data || []).length === 0} onClick={runDue}>Test due</Button>
        </div>}>
        {(list.data || []).length === 0 ? <div className="muted">No schedules yet.</div> : (
          <div className="table-scroll">
          <table>
            <thead><tr><th>Metric</th><th>Frequency</th><th>Next due</th><th>Status</th><th>Last tested</th><th>Actions</th></tr></thead>
            <tbody>
              {list.data!.map((s: any) => (
                <tr key={s.id}>
                  <td><b>{s.metric}</b></td>
                  <td>{s.testFrequency}</td>
                  <td className="mono">{fmt.date(s.currentDueDate)}</td>
                  <td><Badge kind={s.status === "COMPLIANT" ? "ok"
                    : s.status === "BREACHED" || s.status === "OVERDUE" ? "bad"
                    : s.status === "WAIVED" || s.status === "EXTENDED" ? "info" : ""}>{s.status}</Badge></td>
                  <td className="mono">{fmt.dateTime(s.lastTestedAt)}</td>
                  <td>
                    {["BREACHED", "OVERDUE"].includes(s.status) && (
                      <div className="btnrow">
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => {
                            const r = window.prompt("Waiver reason:");
                            if (r) run(() => covTracking.requestWaiver(s.id, { reason: r }, actor), "Waiver requested").then(() => list.reload());
                          }}>Request waiver</button>
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => {
                            const d = window.prompt("New due date (YYYY-MM-DD):");
                            if (d) run(() => covTracking.requestExtension(s.id, { newDueDate: d, reason: "extension" }, actor), "Extension requested").then(() => list.reload());
                          }}>Request extension</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
        <small className="prov">Tip: pending waivers/extensions are decided by credit team via <span className="mono">POST /actions/&#123;id&#125;/decision</span>. SoD enforced (requester ≠ approver).</small>
      </Card>
    );
  }
}

// ── Covenant intelligence: AI extraction from CP free text + certificate assessment ──
function CovenantIntelBlock({ reference, onConfirmed }: { reference: string; onConfirmed: () => void }) {
  const { actor, notify } = useApp();
  const extractions = useAsync(() => decision.covExtractions(reference).catch(() => [] as any[]), [reference]);
  const assessments = useAsync(() => decision.certAssessments(reference).catch(() => [] as any[]), [reference]);

  const [clauseText, setClauseText] = useState(
    "The Borrower shall maintain a DSCR of at least 1.40x tested quarterly.\n" +
    "Net leverage (Net Debt to EBITDA) shall not exceed 2.5x at all times.\n" +
    "Interest coverage to be no less than 3.0 times, tested annually.",
  );
  const [certText, setCertText] = useState(
    "Covenant compliance certificate for the quarter:\n" +
    "1. DSCR for the period stood at 2.00x — Complied.\n" +
    "2. Debt/EBITDA reported at 2.10x — Complied.",
  );
  const [busy, setBusy] = useState(false);

  const go = async (fn: () => Promise<any>, ok: string) => {
    setBusy(true);
    try { await fn(); notify(ok); }
    catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Covenant intelligence"
      sub="AI extracts covenant definitions + thresholds from CP free text, and reads compliance certificates — flagging taxonomy mismatches and disagreements vs the deterministic spread recompute. Advisory; a human confirms each."
      right={<GovFlow ai="AI EXTRACTS / READS" human="HUMAN CONFIRMS" note="never auto-applied to figures" />}>

      {/* Extraction from CP free text */}
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div>
          <Field label="Covenant clauses (free text from the CP)">
            <textarea rows={5} value={clauseText} onChange={(e) => setClauseText(e.target.value)} />
          </Field>
          <Button busy={busy} disabled={!clauseText.trim()}
            onClick={() => go(async () => { await decision.covExtract(reference, clauseText, actor); extractions.reload(); }, "Covenants extracted — review below")}>
            Extract covenants
          </Button>
        </div>
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>Extracted candidates</div>
          {(extractions.data || []).length === 0 ? <div className="muted">None yet.</div> : (
            <table><thead><tr><th>Metric</th><th>Test</th><th className="num">Conf.</th><th>Status</th><th></th></tr></thead>
              <tbody>{extractions.data!.map((e: any) => (
                <tr key={e.id}>
                  <td>{e.metric}<div className="prov" style={{ fontSize: 11 }}>“{e.reportedLabel}”</div></td>
                  <td className="mono">{e.operator} {e.threshold ?? "—"}</td>
                  <td className="num">{Math.round((e.confidence || 0) * 100)}%</td>
                  <td><Badge kind={e.status === "CONFIRMED" ? "ok" : e.status === "REJECTED" ? "bad" : "ai"}>{e.status}</Badge></td>
                  <td>{e.status === "DRAFT" && (
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await decision.covConfirmExtraction(e.id, {}, actor); extractions.reload(); onConfirmed(); }, "Covenant created")}>Confirm</button>
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await decision.covRejectExtraction(e.id, { note: "rejected" }, actor); extractions.reload(); }, "Rejected")}>Reject</button>
                    </div>
                  )}</td>
                </tr>))}</tbody></table>
          )}
        </div>
      </div>

      <div className="hr" style={{ margin: "16px 0", borderTop: "1px solid var(--line)" }} />

      {/* Certificate assessment */}
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div>
          <Field label="Compliance certificate (borrower-submitted)">
            <textarea rows={4} value={certText} onChange={(e) => setCertText(e.target.value)} />
          </Field>
          <Button busy={busy} disabled={!certText.trim()}
            onClick={() => go(async () => { await decision.certAssess(reference, certText, actor); assessments.reload(); }, "Certificate assessed — review below")}>
            Assess certificate
          </Button>
        </div>
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>Assessment vs deterministic recompute</div>
          {(assessments.data || []).length === 0 ? <div className="muted">None yet.</div> : (
            <table><thead><tr><th>Metric</th><th>Reported</th><th>Recompute</th><th>Verdict</th></tr></thead>
              <tbody>{assessments.data!.map((a: any) => (
                <tr key={a.id}>
                  <td>
                    {a.systemMetric}
                    {a.taxonomyMismatch && <div><Badge kind="warn">taxonomy mismatch</Badge></div>}
                    <div className="prov" style={{ fontSize: 11 }}>“{a.reportedLabel}”</div>
                  </td>
                  <td><Badge kind={a.reportedStatus === "COMPLIED" ? "ok" : a.reportedStatus === "NOT_COMPLIED" ? "bad" : "info"}>{a.reportedStatus}</Badge></td>
                  <td className="mono">{a.recomputedObserved == null ? "—" : `${fmt.num(a.recomputedObserved, 2)} ${a.operator} ${a.threshold}`}<br/>
                    {a.recomputedPassed == null ? "" : a.recomputedPassed ? <Badge kind="ok">pass</Badge> : <Badge kind="bad">breach</Badge>}</td>
                  <td>{a.agreement == null ? <span className="muted">—</span>
                    : a.agreement ? <Badge kind="ok">agrees</Badge> : <Badge kind="bad">DISAGREES</Badge>}</td>
                </tr>))}</tbody></table>
          )}
          {(assessments.data || []).some((a: any) => a.agreement === false) && (
            <div className="alert err" style={{ marginTop: 8 }}>
              <AiBadge label="AI · advisory" /> One or more borrower-reported statuses disagree with the deterministic recompute — analyst review required.
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}

// ── Collateral intelligence: type-aware extraction + LTV revaluation + charge-Excel ──
function CollateralIntelBlock({ reference, cols, onChange }: { reference: string; cols: any[]; onChange: () => void }) {
  const { actor, notify } = useApp();
  const extractions = useAsync(() => origination.colExtractions(reference).catch(() => [] as any[]), [reference]);
  const revaluations = useAsync(() => origination.colRevaluations(reference).catch(() => [] as any[]), [reference]);

  const KINDS = ["VALUATION_REPORT", "TITLE_DEED", "INSURANCE_POLICY", "VEHICLE_RC", "BOND_CERT", "PG_DEED"];
  const [docKind, setDocKind] = useState("VALUATION_REPORT");
  const [docText, setDocText] = useState(
    "VALUATION REPORT\nProperty type: Industrial warehouse\nAddress: Plot 14, Phase 2, Pune\nMarket value: INR 4,80,00,000\nValuation date: 2026-03-15\nValuer: Knight & Co Surveyors\n",
  );

  const [revalCollateralId, setRevalCollateralId] = useState<number | "">("");
  const [revalNewMV, setRevalNewMV] = useState<string>("");
  const [revalDrawn, setRevalDrawn] = useState<string>("");
  const [busy, setBusy] = useState(false);

  const go = async (fn: () => Promise<any>, ok: string) => {
    setBusy(true);
    try { await fn(); notify(ok); }
    catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  const sevTone = (s: string) => s === "BREACH" ? "bad" : s === "WARN" ? "warn" : "info";

  return (
    <Card title="Collateral intelligence"
      sub="Type-aware extraction over an uploaded collateral document, LTV-driven revaluation alerts, and the charge-Excel export. Advisory; live collateral values move only on human confirm."
      right={<GovFlow ai="AI EXTRACTS / FLAGS" human="HUMAN CONFIRMS" note="capital projection untouched" />}>

      {/* Type-aware extraction */}
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div>
          <Field label="Document kind">
            <select value={docKind} onChange={(e) => setDocKind(e.target.value)}>
              {KINDS.map((k) => <option key={k} value={k}>{k.replace(/_/g, " ")}</option>)}
            </select>
          </Field>
          <Field label="Document text">
            <textarea rows={6} value={docText} onChange={(e) => setDocText(e.target.value)} />
          </Field>
          <Button busy={busy} disabled={!docText.trim()}
            onClick={() => go(async () => { await origination.colExtract(reference, { documentKind: docKind, text: docText }, actor); extractions.reload(); }, "Collateral extracted — review below")}>
            Extract collateral
          </Button>
        </div>
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>Extracted candidates</div>
          {(extractions.data || []).length === 0 ? <div className="muted">None yet.</div> : (
            <table>
              <thead><tr><th>Type · kind</th><th>Fields</th><th>Missing</th><th>Status</th><th></th></tr></thead>
              <tbody>{extractions.data!.map((e: any) => (
                <tr key={e.id}>
                  <td>{e.collateralType}<div className="prov" style={{ fontSize: 11 }}>{e.documentKind}</div></td>
                  <td className="num">{Object.keys(e.fields || {}).length}<div className="prov" style={{ fontSize: 11 }}>conf {Math.round((e.overallConfidence || 0) * 100)}%</div></td>
                  <td>{(e.missingMandatory || []).length === 0
                    ? <Badge kind="ok">complete</Badge>
                    : <Badge kind="warn">{(e.missingMandatory || []).length} missing</Badge>}</td>
                  <td><Badge kind={e.status === "CONFIRMED" ? "ok" : e.status === "REJECTED" ? "bad" : "ai"}>{e.status}</Badge></td>
                  <td>{e.status === "SUGGESTED" && (
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await origination.colConfirm(e.id, {}, actor); extractions.reload(); onChange(); }, "Collateral created")}>Confirm</button>
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await origination.colReject(e.id, { note: "rejected" }, actor); extractions.reload(); }, "Rejected")}>Reject</button>
                    </div>
                  )}</td>
                </tr>))}</tbody>
            </table>
          )}
        </div>
      </div>

      <div style={{ margin: "16px 0", borderTop: "1px solid var(--line)" }} />

      {/* LTV revaluation */}
      <div className="grid cols-2" style={{ alignItems: "start" }}>
        <div>
          <Field label="Collateral">
            <select value={revalCollateralId} onChange={(e) => setRevalCollateralId(e.target.value ? Number(e.target.value) : "")}>
              <option value="">— select collateral —</option>
              {(cols || []).map((c: any) => (
                <option key={c.id} value={c.id}>#{c.id} · {c.collateralType} · {fmt.money(c.marketValue, "")}</option>
              ))}
            </select>
          </Field>
          <div className="grid cols-2">
            <Field label="New market value"><input type="number" value={revalNewMV} onChange={(e) => setRevalNewMV(e.target.value)} /></Field>
            <Field label="Drawn exposure"><input type="number" value={revalDrawn} onChange={(e) => setRevalDrawn(e.target.value)} /></Field>
          </div>
          <Button busy={busy} disabled={!revalCollateralId || !revalNewMV || !revalDrawn}
            onClick={() => go(async () => {
              await origination.colRevalue(Number(revalCollateralId),
                { newMarketValue: Number(revalNewMV), drawnExposure: Number(revalDrawn),
                  trigger: "VALUATION_UPDATE", ltvThreshold: 0.80 }, actor);
              revaluations.reload();
            }, "Revaluation captured — review below")}>
            Capture revaluation
          </Button>
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            <AiBadge label="AI · advisory" /> Captured values are advisory until a human applies them to the live collateral row.
          </div>
        </div>
        <div>
          <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>Recent revaluations</div>
          {(revaluations.data || []).length === 0 ? <div className="muted">None yet.</div> : (
            <div className="table-scroll">
            <table>
              <thead><tr><th>When</th><th>Col</th><th>MV</th><th>LTV</th><th>Severity</th><th>Status</th><th></th></tr></thead>
              <tbody>{revaluations.data!.slice(0, 8).map((r: any) => (
                <tr key={r.id}>
                  <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(r.createdAt)}</td>
                  <td>#{r.collateralId}</td>
                  <td className="mono">{fmt.money(r.previousMarketValue, "")} → {fmt.money(r.newMarketValue, "")}</td>
                  <td className="mono">{r.ltvBefore.toFixed(2)} → {r.ltvAfter.toFixed(2)}</td>
                  <td><Badge kind={sevTone(r.alertSeverity)}>{r.alertSeverity}</Badge></td>
                  <td><Badge kind={r.confirmStatus === "APPLIED" ? "ok" : r.confirmStatus === "REJECTED" ? "bad" : "warn"}>{r.confirmStatus}</Badge></td>
                  <td>{r.confirmStatus === "PENDING" && (
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await origination.colReviewRevaluation(r.id, { apply: true, note: "confirmed" }, actor); revaluations.reload(); onChange(); }, "Applied to live collateral")}>Apply</button>
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        onClick={() => go(async () => { await origination.colReviewRevaluation(r.id, { apply: false, note: "rejected" }, actor); revaluations.reload(); }, "Rejected")}>Reject</button>
                    </div>
                  )}</td>
                </tr>))}</tbody>
            </table>
            </div>
          )}
        </div>
      </div>

      <div style={{ margin: "16px 0", borderTop: "1px solid var(--line)" }} />

      {/* Charge-Excel */}
      <div className="flexbetween">
        <div>
          <b>Charge-Excel</b>
          <div className="muted" style={{ fontSize: 12 }}>
            CSV of every collateral with registration / valuation / perfection — the pre-release file for the limit-release checklist.
          </div>
        </div>
        <a className="btn" href={origination.chargeExcelUrl(reference)} target="_blank" rel="noreferrer">Download CSV</a>
      </div>
    </Card>
  );
}
