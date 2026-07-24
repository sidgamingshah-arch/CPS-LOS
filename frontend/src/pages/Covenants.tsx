/**
 * Covenants — a dedicated workspace over the decision-service covenant surface
 * (previously reachable only inside the Deal Workspace). Pick a deal, then:
 *  - view its covenants (metric · test · frequency · severity) and add AI-suggested ones;
 *  - run covenant tests and see the latest observations + breaches (deterministic recompute);
 *  - track the covenant schedule state machine (init · test-due · waiver/extension w/ SoD);
 *  - use AI covenant intelligence: extract covenant defs/thresholds from CP free text and
 *    assess a compliance certificate — both advisory, each materialised only on human-confirm.
 *
 * Governance: AI extracts / reads; a named human confirms. The deterministic recompute is the
 * arbiter — a borrower-reported status that disagrees with it is flagged for analyst review.
 */
import { useState } from "react";
import { decision, origination, risk, tracking as covTracking, fmt } from "../api";
import { useApp } from "../app-context";
import { AiBadge, Badge, Button, Card, EmptyState, Field, GovFlow, useAsync } from "../ui";

function sevTone(s?: string): string {
  return s === "CRITICAL" || s === "HIGH" ? "bad" : s === "MAJOR" || s === "MEDIUM" ? "warn" : "info";
}
function schedStatusTone(s?: string): string {
  if (s === "COMPLIANT") return "ok";
  if (s === "BREACHED" || s === "OVERDUE") return "bad";
  if (s === "WAIVED" || s === "EXTENDED") return "info";
  return "";
}

export default function Covenants() {
  const { actor, notify, ref: ctxRef } = useApp();
  const deals = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");

  const selectedDeal = (deals.data ?? []).find((d: any) => d.reference === ref) ?? null;
  const dealTitle = ref ? [selectedDeal?.counterpartyName, ref].filter(Boolean).join(" · ") : "";

  const summary = useAsync(() => (ref ? risk.summary(ref).catch(() => null) : Promise.resolve(null)), [ref]);
  const covs = useAsync(() => (ref ? decision.covenants(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);
  const tests = useAsync(() => (ref ? decision.covenantTests(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);

  const grade: string | undefined = summary.data?.rating?.finalGrade;
  const covList = covs.data || [];
  const testList = tests.data || [];
  const breaches = testList.filter((t: any) => t.passed === false);

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); } catch (e: any) { notify(e.message, true); }
  };

  const addSuggested = () =>
    run(async () => {
      const sugg = await decision.suggest(grade || "BBB");
      for (const s of sugg) await decision.addCovenant(ref, s, actor);
      covs.reload();
    }, "Added AI-suggested covenants");

  const runTests = () =>
    run(async () => { await decision.testCovenants(ref, actor); tests.reload(); }, "Covenants tested");

  return (
    <div className="grid">
      <Card
        title="Covenants"
        sub="Financial / information / negative covenants for a deal — definitions, scheduled testing, and AI covenant intelligence. Every borrower-reported status is checked against the deterministic spread recompute."
        right={<GovFlow ai="AI EXTRACTS / READS" human="HUMAN CONFIRMS" note="deterministic recompute is the arbiter" />}
      >
        <div className="grid cols-2" style={{ alignItems: "end" }}>
          <Field label="Deal">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select a deal —</option>
              {(deals.data ?? []).map((d: any) => (
                <option key={d.reference} value={d.reference}>
                  {d.reference} · {d.counterpartyName} · {d.status}
                </option>
              ))}
            </select>
          </Field>
          {ref && (
            <div className="inline" style={{ gap: 10, alignItems: "center", flexWrap: "wrap" }}>
              <span className="muted" style={{ fontSize: 12 }}>Selected deal</span>
              <b>{dealTitle}</b>
              {grade && <Badge kind="info">grade {grade}</Badge>}
            </div>
          )}
        </div>
      </Card>

      {!ref && (
        <Card>
          <EmptyState
            glyph="§"
            title="Select a deal to manage its covenants"
            sub="Pick an application above. You can then view and add covenants, run tests, track the covenant schedule, and use AI covenant intelligence (extract from CP text → human confirm)."
          />
        </Card>
      )}

      {ref && (
        <>
          {/* Breach banner */}
          {breaches.length > 0 && (
            <Card>
              <div className="alert err">
                <b>{breaches.length}</b> covenant breach(es) on the latest tests — {breaches.map((t: any) => t.metric).join(", ")}. Deterministic recompute; review required.
              </div>
            </Card>
          )}

          {/* Covenants list */}
          <Card
            title="Covenants of record"
            sub={covs.loading ? "Loading…" : `${covList.length} covenant(s)`}
            right={<Button kind="subtle" onClick={addSuggested}>+ Add AI-suggested covenants</Button>}
          >
            {covList.length === 0 ? (
              <div className="muted">No covenants yet — add AI-suggested covenants (grounded on the deal grade) or confirm one from the CP text below.</div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Metric</th><th>Test</th><th>Frequency</th><th>Severity</th></tr></thead>
                  <tbody>
                    {covList.map((c: any) => (
                      <tr key={c.id}>
                        <td><b>{c.metric}</b></td>
                        <td className="mono">{c.operator} {c.threshold ?? "—"}</td>
                        <td>{c.testFrequency}</td>
                        <td><Badge kind={sevTone(c.breachSeverity)}>{c.breachSeverity}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          {/* Covenant testing */}
          <Card
            title="Covenant testing"
            sub="Tests every active covenant against the latest confirmed spread ratios; observations are persisted to history. Deterministic — AI never produces the pass/breach figure."
            right={<Button kind="subtle" disabled={covList.length === 0} onClick={runTests}>Run covenant tests</Button>}
          >
            {testList.length === 0 ? (
              <div className="muted">No tests yet — set up covenants and run a test.</div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead><tr><th>When</th><th>Metric</th><th>Test</th><th className="num">Observed</th><th>Pass</th><th>Severity</th></tr></thead>
                  <tbody>
                    {testList.slice(0, 20).map((t: any) => (
                      <tr key={t.id}>
                        <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(t.testedAt)}</td>
                        <td>{t.metric}</td>
                        <td className="mono">{t.operator} {t.threshold}</td>
                        <td className="num">{fmt.num(t.observed, 2)}</td>
                        <td>{t.passed ? <Badge kind="ok">pass</Badge> : <Badge kind="bad">breach</Badge>}</td>
                        <td><Badge kind={sevTone(t.breachSeverity)}>{t.breachSeverity}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          {/* Covenant tracking schedule */}
          <CovenantTracking reference={ref} run={run} actor={actor} />

          {/* Covenant intelligence — AI extraction + certificate assessment */}
          <CovenantIntel reference={ref} onConfirmed={() => covs.reload()} />
        </>
      )}
    </div>
  );
}

// ── Covenant tracking schedule (state machine · maker-checker waiver/extension) ──
function CovenantTracking({ reference, run, actor }: {
  reference: string; run: (fn: () => Promise<any>, ok: string) => Promise<void>; actor: string;
}) {
  const list = useAsync(() => covTracking.list(reference).catch(() => [] as any[]), [reference]);
  const init = () =>
    run(async () => {
      await covTracking.init({
        applicationRef: reference,
        startDate: new Date().toISOString().slice(0, 10),
        endDate: new Date(Date.now() + 3 * 365 * 86400_000).toISOString().slice(0, 10),
      }, actor);
      list.reload();
    }, "Schedules initialised");
  const runDue = () => run(async () => { await covTracking.runDue(reference, actor); list.reload(); }, "Tested due schedules");

  const rows = list.data || [];
  return (
    <Card
      title="Covenant tracking · workflow"
      sub="Schedule (frequency · period · grace) drives the state machine; waiver / extension requests go through maker-checker with SoD (requester ≠ approver)."
      right={
        <div className="btnrow">
          <Button kind="subtle" onClick={init}>Initialise schedules</Button>
          <Button kind="ghost" disabled={rows.length === 0} onClick={runDue}>Test due</Button>
        </div>
      }
    >
      {rows.length === 0 ? (
        <div className="muted">No schedules yet — initialise from the deal's active covenants.</div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead><tr><th>Metric</th><th>Frequency</th><th>Next due</th><th>Status</th><th>Last tested</th><th>Actions</th></tr></thead>
            <tbody>
              {rows.map((s: any) => (
                <tr key={s.id}>
                  <td><b>{s.metric}</b></td>
                  <td>{s.testFrequency}</td>
                  <td className="mono">{fmt.date(s.currentDueDate)}</td>
                  <td><Badge kind={schedStatusTone(s.status)}>{s.status}</Badge></td>
                  <td className="mono">{fmt.dateTime(s.lastTestedAt)}</td>
                  <td>
                    {["BREACHED", "OVERDUE"].includes(s.status) && (
                      <div className="btnrow">
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => {
                            const reason = window.prompt("Waiver reason:");
                            if (reason) run(async () => { await covTracking.requestWaiver(s.id, { reason }, actor); list.reload(); }, "Waiver requested");
                          }}>Request waiver</button>
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => {
                            const d = window.prompt("New due date (YYYY-MM-DD):");
                            if (d) run(async () => { await covTracking.requestExtension(s.id, { newDueDate: d, reason: "extension" }, actor); list.reload(); }, "Extension requested");
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
      <small className="prov">Pending waivers / extensions are decided by the credit team — SoD enforced (requester ≠ approver).</small>
    </Card>
  );
}

// ── Covenant intelligence: AI extraction from CP free text + certificate assessment ──
function CovenantIntel({ reference, onConfirmed }: { reference: string; onConfirmed: () => void }) {
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
    <Card
      title="Covenant intelligence"
      sub="AI extracts covenant definitions + thresholds from CP free text, and reads compliance certificates — flagging taxonomy mismatches and disagreements vs the deterministic spread recompute. Advisory; a human confirms each."
      right={<GovFlow ai="AI EXTRACTS / READS" human="HUMAN CONFIRMS" note="never auto-applied to figures" />}
    >
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
            <table>
              <thead><tr><th>Metric</th><th>Test</th><th className="num">Conf.</th><th>Status</th><th></th></tr></thead>
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
                </tr>))}</tbody>
            </table>
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
            <table>
              <thead><tr><th>Metric</th><th>Reported</th><th>Recompute</th><th>Verdict</th></tr></thead>
              <tbody>{assessments.data!.map((a: any) => (
                <tr key={a.id}>
                  <td>
                    {a.systemMetric}
                    {a.taxonomyMismatch && <div><Badge kind="warn">taxonomy mismatch</Badge></div>}
                    <div className="prov" style={{ fontSize: 11 }}>“{a.reportedLabel}”</div>
                  </td>
                  <td><Badge kind={a.reportedStatus === "COMPLIED" ? "ok" : a.reportedStatus === "NOT_COMPLIED" ? "bad" : "info"}>{a.reportedStatus}</Badge></td>
                  <td className="mono">{a.recomputedObserved == null ? "—" : `${fmt.num(a.recomputedObserved, 2)} ${a.operator} ${a.threshold}`}<br />
                    {a.recomputedPassed == null ? "" : a.recomputedPassed ? <Badge kind="ok">pass</Badge> : <Badge kind="bad">breach</Badge>}</td>
                  <td>{a.agreement == null ? <span className="muted">—</span>
                    : a.agreement ? <Badge kind="ok">agrees</Badge> : <Badge kind="bad">DISAGREES</Badge>}</td>
                </tr>))}</tbody>
            </table>
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
