import { useState } from "react";
import { decision, origination, portfolio, risk, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, GradeBadge, statusTone, useAsync } from "../ui";

const REASON_CODES = ["POST_BALANCE_SHEET_EVENT", "MANAGEMENT_QUALITY", "GROUP_SUPPORT", "SECTOR_OUTLOOK", "DATA_QUALITY", "COLLATERAL_STRENGTH", "OTHER"];

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

export default function DealWorkspace({ reference }: { reference: string }) {
  const { actor, notify, nav } = useApp();
  const app = useAsync(() => origination.get(reference), [reference]);
  const analysis = useAsync(() => origination.analysis(reference), [reference]);
  const docs = useAsync(() => origination.docs(reference), [reference]);
  const summary = useAsync(() => risk.summary(reference), [reference]);
  const dec = useAsync(() => decision.latest(reference).catch(() => null), [reference]);
  const covs = useAsync(() => decision.covenants(reference), [reference]);
  const signals = useAsync(() => portfolio.scan(reference, actor).catch(() => [] as any[]), [reference]);

  const reload = () => { app.reload(); analysis.reload(); docs.reload(); summary.reload(); dec.reload(); covs.reload(); };
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  const a = app.data, an = analysis.data, sum = summary.data, d = dec.data;
  const rating = sum?.rating, capital = sum?.capital, pricing = sum?.pricing;

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

  if (app.loading) return <div className="loading">Loading deal…</div>;
  if (!a) return <Card title="Not found"><div className="muted">No application {reference}.</div></Card>;
  const latest = an?.periods?.[0];

  return (
    <div className="grid">
      <Card title={`${a.counterpartyName}`} sub={`${a.reference} · ${a.facilityType} · ${fmt.money(a.requestedAmount, a.currency)} · ${a.segment} · ${a.jurisdiction}`}
        right={<Badge kind={statusTone(a.status)}>{a.status}</Badge>}>
        <div className="pill-steps">
          {steps.map((s) => <span key={s.k} className={`step ${s.done ? "done" : ""}`}>{s.done ? "✓ " : ""}{s.k}</span>)}
        </div>
        <Button kind="subtle" onClick={() => nav("deals")}>← Back to pipeline</Button>
      </Card>

      {/* Documents */}
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

      {/* Spreading */}
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
                    {Object.entries(an.trends).map(([k, v]: any) => (<><div className="k" key={k}>{k}</div><div className="v" key={k + "v"}>{fmt.pct(v as number, 1)}</div></>))}
                  </div></>
                )}
              </div>
            </div>
          </>
        )}
      </Card>

      {/* Rating */}
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

      {/* Capital */}
      <Card title="Regulatory capital (RWA)" sub="Deterministic SA engine via the jurisdiction rule pack; every figure traces to inputs + rule version (PRD §6)."
        right={<Button disabled={!rating} onClick={() => run(() => risk.capital(reference, actor), "Capital computed")}>{capital ? "Recompute" : "Compute capital"}</Button>}>
        {!capital ? <div className="muted">Rate the deal, then compute capital.</div> : <CapitalView reference={reference} capital={capital} />}
      </Card>

      {/* Pricing */}
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

      {/* Approval */}
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

      {/* Book & monitor */}
      <Card title="Book, provision & monitor" sub="ECL/IRAC provisioning, EWS — all human-gated for staging/remediation (PRD §11–12).">
        <BookBlock reference={reference} decided={d?.status === "DECIDED"} onChange={reload} signals={signals} />
      </Card>
    </div>
  );

  function OverrideForm({ reference, onDone }: { reference: string; onDone: () => void }) {
    const [open, setOpen] = useState(false);
    const [g, setG] = useState("BBB"); const [rc, setRc] = useState(REASON_CODES[0]); const [role, setRole] = useState("CREDIT_OFFICER"); const [note, setNote] = useState("");
    if (!open) return <Button kind="subtle" onClick={() => setOpen(true)}>Override rating…</Button>;
    return (
      <div className="card" style={{ marginTop: 10, background: "#fbfaff" }}>
        <div className="grid cols-2">
          <Field label="Proposed grade"><select value={g} onChange={(e) => setG(e.target.value)}>{["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"].map((x) => <option key={x}>{x}</option>)}</select></Field>
          <Field label="Role"><select value={role} onChange={(e) => setRole(e.target.value)}>{["ANALYST", "CREDIT_OFFICER", "CREDIT_COMMITTEE"].map((x) => <option key={x}>{x}</option>)}</select></Field>
          <Field label="Reason code"><select value={rc} onChange={(e) => setRc(e.target.value)}>{REASON_CODES.map((x) => <option key={x}>{x}</option>)}</select></Field>
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
          <Field label="Outcome"><select value={outcome} onChange={(e) => setOutcome(e.target.value)}>{["APPROVE", "CONDITIONAL_APPROVE", "DECLINE", "REFER"].map((x) => <option key={x}>{x}</option>)}</select></Field>
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
}
