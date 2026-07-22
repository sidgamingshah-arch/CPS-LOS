/**
 * Decision Cockpit — a read-first, single-screen decision surface for an approver.
 *
 * Everything a named human needs to decide a routed deal, on one page: the deal
 * summary + DoA authority, the authoritative rating (grade / PD / LGD / EAD + the
 * top rating factors), the RAROC pricing (rate vs hurdle), the covenant posture,
 * the relationship exposure (Customer-360), and the AI-drafted credit summary —
 * with a prominent, sticky decision action bar.
 *
 * Governance: this screen NEVER computes or mutates a figure. Every figure is
 * quoted verbatim from the deterministic engines (DeterministicBadge); the AI
 * summary is clearly tagged advisory (AiBadge); the decision itself is a named
 * human action bound to `decision.decide` at the required authority (HumanBadge).
 * The signature GovSplit frame shows the AI advisory view beside the authoritative
 * rating of record, marked UNCHANGED — so advisory-vs-authoritative is unambiguous.
 */
import { useMemo, useState } from "react";
import { origination, risk, decision, commentary, mis, fmt } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, DeterministicBadge, EmptyState, Field,
  GovSplit, GradeBadge, HumanBadge, Stat, Unchanged, statusTone, useAsync,
} from "../ui";

/** The four approver actions, mapped to the backend DecisionOutcome codes. */
const ACTIONS = [
  { code: "APPROVE", label: "Approve", kind: "ok" },
  { code: "CONDITIONAL_APPROVE", label: "Approve with conditions", kind: "warn" },
  { code: "REFER", label: "Refer back", kind: "info" },
  { code: "DECLINE", label: "Reject", kind: "bad" },
] as const;

type ActionCode = (typeof ACTIONS)[number]["code"];

export default function DecisionCockpit() {
  const { actor, ref: ctxRef, nav, notify } = useApp();
  const apps = useAsync(() => origination.list().catch(() => [] as any[]), []);
  const [ref, setRef] = useState<string>(ctxRef ?? "");

  // Every read is fail-soft: an unreachable service degrades its card to empty,
  // never blanks the page. Nothing here is a write — this is a decision surface.
  const dealA = useAsync(() => (ref ? origination.get(ref).catch(() => null) : Promise.resolve(null)), [ref]);
  const sumA = useAsync(() => (ref ? risk.summary(ref).catch(() => null) : Promise.resolve(null)), [ref]);
  const decA = useAsync(() => (ref ? decision.latest(ref).catch(() => null) : Promise.resolve(null)), [ref]);
  const covsA = useAsync(() => (ref ? decision.covenants(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);
  const testsA = useAsync(() => (ref ? decision.covenantTests(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);
  const propA = useAsync(() => (ref ? decision.latestProposal(ref).catch(() => null) : Promise.resolve(null)), [ref]);
  const commA = useAsync(() => (ref ? commentary.list(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);
  const c360A = useAsync(() => (ref ? mis.customer360(ref).catch(() => null) : Promise.resolve(null)), [ref]);

  const a = dealA.data;
  const sum = sumA.data;
  const rating = sum?.rating ?? null;
  const pricing = sum?.pricing ?? null;
  const d = decA.data;
  const covs = covsA.data ?? [];
  const tests = testsA.data ?? [];
  const proposal = propA.data;
  const comments = commA.data ?? [];
  const c360 = c360A.data;

  const reloadDecision = () => { decA.reload(); dealA.reload(); };

  // Top rating factors — biggest absolute contributions to the deterministic score.
  const topFactors = useMemo(() => {
    const f = rating?.scoreBreakdown?.factors;
    if (!f || typeof f !== "object") return [] as { key: string; value: number; score: number; contribution: number }[];
    return Object.entries(f)
      .map(([key, v]: [string, any]) => ({
        key,
        value: v?.value ?? 0,
        score: v?.score ?? 0,
        contribution: v?.contribution ?? 0,
      }))
      .sort((x, y) => Math.abs(y.contribution) - Math.abs(x.contribution))
      .slice(0, 5);
  }, [rating]);

  const breaches = tests.filter((t: any) => t && t.passed === false);
  // First confirmed commentary (else first draft) — the AI advisory credit view.
  const leadComment = useMemo(() => {
    const confirmed = comments.find((c: any) => c.status === "CONFIRMED");
    return confirmed ?? comments[0] ?? null;
  }, [comments]);

  const limits = c360?.limitsAndUtilisation || {};
  const c360raroc = c360?.raroc || {};
  const c360prov = c360?.provisioning || {};

  const loadingAny = ref && (dealA.loading || sumA.loading || decA.loading);

  return (
    <div className="grid dc-page">
      {/* ── deal selector ─────────────────────────────────────────────── */}
      <Card
        title="Decision Cockpit"
        sub="One read-first surface for an approver: deal, rating, pricing, covenants, exposure and the AI credit summary — with a prominent decision action bar. No maker actions here."
        right={<HumanBadge label="NAMED-HUMAN DECISION" />}
      >
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Deal">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select a deal —</option>
              {(apps.data ?? []).map((x: any) => (
                <option key={x.reference} value={x.reference}>
                  {x.reference} · {x.counterpartyName} · {x.status}
                </option>
              ))}
            </select>
          </Field>
          {ref && (
            <Button kind="subtle" onClick={() => { dealA.reload(); sumA.reload(); decA.reload(); covsA.reload(); testsA.reload(); propA.reload(); commA.reload(); c360A.reload(); }}>
              Refresh
            </Button>
          )}
        </div>
      </Card>

      {!ref && (
        <Card>
          <EmptyState
            glyph="⚖"
            title="Select a deal to open the cockpit"
            sub="Pick a routed deal above — or arrive here from “Awaiting my decision” on your workspace. The cockpit lays out everything you need to decide on one screen; it never mutates a figure."
          />
        </Card>
      )}

      {loadingAny && <Card><div className="loading">Loading decision surface…</div></Card>}

      {ref && a && (
        <>
          {/* ── deal summary hero ───────────────────────────────────────── */}
          <div className="dc-hero">
            <div className="dc-hero-main">
              <div className="dc-hero-eyebrow">DEAL UNDER REVIEW</div>
              <h2 className="dc-hero-title">{a.counterpartyName}</h2>
              <div className="dc-hero-meta">
                <span className="mono">{a.reference}</span>
                <span className="dc-dot" />
                <span>{a.facilityType || "—"}</span>
                <span className="dc-dot" />
                <span>{a.segment || "—"}</span>
                <span className="dc-dot" />
                <span>{a.jurisdiction || "—"}</span>
              </div>
            </div>
            <div className="dc-hero-figures">
              <div className="dc-hero-amount">
                <div className="dc-hero-amount-lbl">Requested amount <DeterministicBadge label="DETERMINISTIC" /></div>
                <div className="dc-hero-amount-val">{fmt.money(a.requestedAmount, a.currency)}</div>
              </div>
              <div className="dc-hero-badges">
                <Badge kind={statusTone(a.status)}>{a.status}</Badge>
                {d ? (
                  <Badge kind="warn">DoA · {d.requiredAuthority}</Badge>
                ) : (
                  <Badge kind="info">not routed</Badge>
                )}
                {d?.committeeMode && <Badge kind="info">committee tier</Badge>}
              </div>
            </div>
          </div>

          {/* ── signature governance frame: AI advisory ↔ authoritative rating ─ */}
          <Card
            title="Governance view"
            sub="One glance: the AI credit view on the left, the authoritative rating & pricing of record on the right — untouched by anything AI produces."
          >
            <GovSplit
              advisoryLabel="AI credit summary (advisory)"
              advisory={
                leadComment ? (
                  <div>
                    <Badge kind="ai">{leadComment.section ? String(leadComment.section).replace(/_/g, " ") : "commentary"}</Badge>
                    <div style={{ marginTop: 8, lineHeight: 1.5 }}>
                      {String(leadComment.narrative || leadComment.advisory || "").slice(0, 320)}
                      {String(leadComment.narrative || "").length > 320 ? "…" : ""}
                    </div>
                  </div>
                ) : proposal ? (
                  <span className="muted">AI-drafted credit proposal v{proposal.version} available below — advisory, grounded in this deal's figures.</span>
                ) : (
                  <span className="muted">No AI commentary or proposal drafted yet. Decide on the deterministic figures on the right.</span>
                )
              }
              authLabel="Authoritative rating & pricing"
              auth={
                <div className="inline" style={{ gap: 14, flexWrap: "wrap" }}>
                  <GradeBadge grade={rating?.finalGrade} />
                  <span className="muted" style={{ fontSize: 13 }}>PD {rating ? fmt.pct(rating.pd, 2) : "—"}</span>
                  <span className="muted" style={{ fontSize: 13 }}>Rate {pricing ? fmt.pct(pricing.recommendedRate, 2) : "—"}</span>
                </div>
              }
            />
          </Card>

          {/* ── rating · pricing · exposure ─────────────────────────────── */}
          <div className="grid cols-3">
            {/* Rating */}
            <Card title="Risk rating" right={<DeterministicBadge label="DETERMINISTIC" />}>
              {!rating ? (
                <div className="muted">Not rated yet.</div>
              ) : (
                <>
                  <div className="inline" style={{ gap: 18, marginBottom: 10 }}>
                    <div><div className="muted">Final</div><GradeBadge grade={rating.finalGrade} /></div>
                    <div><div className="muted">Model</div><GradeBadge grade={rating.modelGrade} /></div>
                    <div><div className="muted">Status</div>{rating.confirmed ? <Badge kind="ok">confirmed</Badge> : <Badge kind="warn">unconfirmed</Badge>}</div>
                  </div>
                  <div className="kv">
                    <div className="k">PD</div><div className="v">{fmt.pct(rating.pd, 2)}</div>
                    <div className="k">LGD</div><div className="v">{fmt.pct(rating.lgd, 0)}</div>
                    <div className="k">EAD</div><div className="v">{fmt.money(rating.ead, "")}</div>
                    {rating.overridden && (
                      <>
                        <div className="k">Override</div>
                        <div className="v">{rating.overrideNotches > 0 ? "+" : ""}{rating.overrideNotches} notch · {rating.reasonCode}</div>
                      </>
                    )}
                  </div>
                  {topFactors.length > 0 && (
                    <div style={{ marginTop: 12 }}>
                      <div className="dc-subhead">Top rating factors</div>
                      <table className="dc-mini-table">
                        <thead><tr><th>Factor</th><th className="num">Score</th><th className="num">Contrib.</th></tr></thead>
                        <tbody>
                          {topFactors.map((f) => (
                            <tr key={f.key}>
                              <td>{f.key}</td>
                              <td className="num">{fmt.num(f.score, 0)}</td>
                              <td className="num">{fmt.num(f.contribution, 1)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              )}
            </Card>

            {/* Pricing / RAROC */}
            <Card title="RAROC pricing" right={<DeterministicBadge label="DETERMINISTIC" />}>
              {!pricing ? (
                <div className="muted">Not priced yet.</div>
              ) : (
                <>
                  <div className="grid cols-2">
                    <Stat label="Recommended rate" value={<span className="num">{fmt.pct(pricing.recommendedRate, 2)}</span>} />
                    <Stat label="RAROC" value={<span className="num">{fmt.pct(pricing.raroc, 1)}</span>} />
                  </div>
                  <div className="kv" style={{ marginTop: 8 }}>
                    <div className="k">Hurdle RAROC</div><div className="v">{fmt.pct(pricing.hurdleRaroc, 1)}</div>
                    {pricing.expectedLoss != null && (<><div className="k">Expected loss</div><div className="v">{fmt.money(pricing.expectedLoss, "")}</div></>)}
                    {pricing.capitalCharge != null && (<><div className="k">Capital charge</div><div className="v">{fmt.money(pricing.capitalCharge, "")}</div></>)}
                  </div>
                  <div style={{ marginTop: 12 }}>
                    {pricing.belowHurdle
                      ? <Badge kind="bad">BELOW HURDLE — escalate</Badge>
                      : <Badge kind="ok">clears hurdle</Badge>}
                  </div>
                </>
              )}
            </Card>

            {/* Exposure (relationship) */}
            <Card title="Relationship exposure" sub="Customer-360 — obligor exposure & conduct." right={<DeterministicBadge label="DETERMINISTIC" />}>
              {!c360 ? (
                <div className="muted">No exposure booked yet.</div>
              ) : (
                <>
                  <div className="grid cols-2">
                    <Stat label="EAD" value={fmt.money(limits.ead || 0)} />
                    <Stat label="RWA" value={fmt.money(limits.rwa || 0)} />
                  </div>
                  <div className="kv" style={{ marginTop: 8 }}>
                    <div className="k">Days past due</div>
                    <div className="v" style={{ color: (limits.daysPastDue ?? 0) > 0 ? "var(--bad)" : undefined }}>{limits.daysPastDue ?? 0}</div>
                    {c360prov.stage && (
                      <>
                        <div className="k">Provisioning</div>
                        <div className="v"><Badge kind={c360prov.stage === "STAGE_3" ? "bad" : c360prov.stage === "STAGE_2" ? "warn" : "ok"}>{c360prov.stage}</Badge></div>
                      </>
                    )}
                    {c360raroc.tracked && (
                      <>
                        <div className="k">RAROC (actual)</div>
                        <div className="v" style={{ color: (c360raroc.latestVariance || 0) < 0 ? "var(--bad)" : "var(--ok)" }}>{fmt.pct(c360raroc.latestActual || 0, 2)}</div>
                      </>
                    )}
                  </div>
                  <div className="dc-note" style={{ marginTop: 10 }}>
                    <Button kind="ghost" onClick={() => nav("customer360", ref)}>Open Customer-360 →</Button>
                  </div>
                </>
              )}
            </Card>
          </div>

          {/* ── covenants ───────────────────────────────────────────────── */}
          <Card
            title="Covenants"
            sub="Structured covenants attached to the deal, and the latest deterministic test observations."
            right={<DeterministicBadge label="DETERMINISTIC TESTS" />}
          >
            <div className="inline" style={{ gap: 18, marginBottom: 10, flexWrap: "wrap" }}>
              <div><span className="muted">Covenants</span> <b>{covs.length}</b></div>
              <div><span className="muted">Tests run</span> <b>{tests.length}</b></div>
              <div>
                <span className="muted">Breaches</span>{" "}
                {breaches.length > 0 ? <Badge kind="bad">{breaches.length} breach{breaches.length === 1 ? "" : "es"}</Badge> : <Badge kind="ok">none</Badge>}
              </div>
            </div>
            {covs.length === 0 ? (
              <div className="muted">No covenants set on this deal.</div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Metric</th><th>Test</th><th>Freq</th><th>Severity</th></tr></thead>
                  <tbody>
                    {covs.map((c: any) => (
                      <tr key={c.id}>
                        <td>{c.metric}</td>
                        <td className="mono">{c.operator} {c.threshold}</td>
                        <td>{c.testFrequency}</td>
                        <td><Badge kind={c.breachSeverity === "CRITICAL" ? "bad" : c.breachSeverity === "MAJOR" ? "warn" : "info"}>{c.breachSeverity}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {breaches.length > 0 && (
              <div style={{ marginTop: 12 }}>
                <div className="dc-subhead" style={{ color: "var(--bad)" }}>Open breaches</div>
                <div className="table-scroll">
                  <table>
                    <thead><tr><th>When</th><th>Metric</th><th>Test</th><th className="num">Observed</th><th>Severity</th></tr></thead>
                    <tbody>
                      {breaches.slice(0, 8).map((t: any) => (
                        <tr key={t.id}>
                          <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(t.testedAt)}</td>
                          <td>{t.metric}</td>
                          <td className="mono">{t.operator} {t.threshold}</td>
                          <td className="num">{fmt.num(t.observed, 2)}</td>
                          <td><Badge kind={t.breachSeverity === "CRITICAL" ? "bad" : t.breachSeverity === "MAJOR" ? "warn" : "info"}>{t.breachSeverity}</Badge></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </Card>

          {/* ── AI credit summary (advisory) ────────────────────────────── */}
          <Card
            title="AI credit summary"
            sub="AI-drafted, grounded in this deal's figures. Advisory & non-binding — every figure it quotes is deterministic and unchanged; the decision remains a named human action."
            right={<AiBadge label="AI · ADVISORY" />}
          >
            {proposal ? (
              <>
                <div className="inline" style={{ gap: 10, marginBottom: 10, flexWrap: "wrap" }}>
                  <Badge kind="info">Credit proposal v{proposal.version}</Badge>
                  {proposal.format && <Badge kind="info">CAM · {proposal.format}</Badge>}
                  <Unchanged label="FIGURES QUOTED VERBATIM" />
                  <small className="prov">Generated {fmt.dateTime(proposal.generatedAt)} by {proposal.generatedBy}</small>
                </div>
                <div className="dc-proposal" dangerouslySetInnerHTML={{ __html: proposal.html }} />
              </>
            ) : comments.length > 0 ? (
              <div className="dc-comments">
                {comments.slice(0, 6).map((c: any) => (
                  <div key={c.id} className="dc-comment">
                    <div className="inline" style={{ gap: 8, marginBottom: 4, flexWrap: "wrap" }}>
                      <Badge kind="ai">{String(c.section || "").replace(/_/g, " ") || "commentary"}</Badge>
                      <Badge kind={c.status === "CONFIRMED" ? "ok" : c.status === "REJECTED" ? "bad" : "warn"}>{c.status}</Badge>
                    </div>
                    <div style={{ lineHeight: 1.55 }}>{c.narrative}</div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="muted">No AI credit summary drafted yet. It is optional — the decision stands on the deterministic figures above.</div>
            )}
          </Card>

          {/* ── decision action bar (sticky) ────────────────────────────── */}
          <DecisionBar
            reference={ref}
            decision={d}
            actor={actor}
            committee={!!d?.committeeMode}
            onDecided={reloadDecision}
            onOpenCommittee={() => nav("committee", ref)}
            notify={notify}
          />
        </>
      )}

      {ref && !dealA.loading && !a && (
        <Card>
          <EmptyState glyph="⚠" title="Deal not found" sub="This reference did not resolve to an application. Pick another deal above." />
        </Card>
      )}
    </div>
  );
}

/**
 * The sticky decision action bar. Read-first everywhere else on the page; this is
 * the one place a named human acts. It binds to `decision.decide` at the deal's
 * required DoA authority. A 403 (insufficient authority / segregation of duties)
 * surfaces as a clear error toast. On a committee-tier deal it also links to the
 * Committee Room, where the vote is cast and quorum tallied.
 */
function DecisionBar({
  reference, decision: d, actor, committee, onDecided, onOpenCommittee, notify,
}: {
  reference: string;
  decision: any;
  actor: string;
  committee: boolean;
  onDecided: () => void;
  onOpenCommittee: () => void;
  notify: (text: string, err?: boolean) => void;
}) {
  const [action, setAction] = useState<ActionCode>("APPROVE");
  const [rationale, setRationale] = useState("");
  const [conditions, setConditions] = useState("");
  const [busy, setBusy] = useState(false);

  const authority: string = d?.requiredAuthority || "—";
  const decided = d?.status === "DECIDED";
  const routed = !!d;
  const needsConditions = action === "CONDITIONAL_APPROVE";

  async function submit() {
    if (!rationale.trim()) { notify("Enter a decision rationale first", true); return; }
    const conds = conditions.split("\n").map((s) => s.trim()).filter(Boolean);
    if (needsConditions && conds.length === 0) { notify("A conditional approval needs at least one condition", true); return; }
    setBusy(true);
    try {
      await decision.decide(reference, { outcome: action, role: authority, rationale, conditions: conds }, actor);
      notify(committee ? `Vote recorded by ${actor}` : `Decision recorded: ${action}`);
      onDecided();
    } catch (e: any) {
      // 403 (forbiddenAutonomy — insufficient authority / SoD) and every other
      // server rejection surface here as a clear error toast.
      notify(e?.message || "Decision failed", true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="dc-actionbar" role="region" aria-label="Decision action bar">
      <div className="dc-actionbar-inner">
        <div className="dc-actionbar-head">
          <HumanBadge label="NAMED-HUMAN DECISION" />
          <span className="dc-actionbar-authority">
            {routed ? <>Required authority <b>{authority}</b>{committee && " · committee tier"}</> : "This deal is not routed for approval yet"}
          </span>
          {decided && (
            <span className="dc-actionbar-decided">
              <Badge kind={statusTone(d.outcome)}>{d.outcome}</Badge>
              <span className="muted"> decided by {d.decidedBy || "—"}</span>
            </span>
          )}
        </div>

        {!routed && (
          <div className="dc-note">Route the deal for approval from the Deal Workspace first; the cockpit will then show the decision controls here.</div>
        )}

        {routed && decided && (
          <div className="dc-decided-panel">
            <span className="muted">Decision of record captured.</span>
            {(d.conditions || []).length > 0 && (
              <div style={{ marginTop: 6 }}>
                <b>Conditions:</b>
                <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
                  {d.conditions.map((c: string, i: number) => <li key={i}>{c}</li>)}
                </ul>
              </div>
            )}
          </div>
        )}

        {routed && !decided && (
          <>
            {committee && (
              <div className="dc-committee-note">
                <Badge kind="info">committee tier</Badge>
                <span>Quorum voting is tallied in the Committee Room. Casting a vote below records your named vote.</span>
                <Button kind="ghost" onClick={onOpenCommittee}>Open Committee Room →</Button>
              </div>
            )}
            <div className="dc-actions">
              {ACTIONS.map((x) => (
                <button
                  key={x.code}
                  type="button"
                  className={`dc-action ${x.kind}${action === x.code ? " active" : ""}`}
                  aria-pressed={action === x.code}
                  onClick={() => setAction(x.code)}
                >
                  {x.label}
                </button>
              ))}
            </div>
            <div className="dc-decide-fields">
              <Field label="Rationale (audited)">
                <input value={rationale} onChange={(e) => setRationale(e.target.value)} placeholder="Basis for this decision" />
              </Field>
              {needsConditions && (
                <Field label="Conditions (one per line)">
                  <textarea rows={2} value={conditions} onChange={(e) => setConditions(e.target.value)} placeholder="e.g. Maintain DSCR ≥ 1.25x" />
                </Field>
              )}
            </div>
            <div className="dc-decide-submit">
              <Button kind="primary" busy={busy} disabled={busy} onClick={submit}>
                {committee ? `Cast vote as ${authority}` : `Record decision as ${authority}`}
              </Button>
              <span className="muted">Acting as <b className="mono">{actor}</b> · a 403 means your identity lacks this authority (or SoD blocks it).</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
