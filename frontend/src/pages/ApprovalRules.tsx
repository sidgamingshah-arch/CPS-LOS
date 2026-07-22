/**
 * Approval Rules — a visual authoring surface for the SCORING_APPROVAL_POLICY master.
 *
 * The scoring (rating) approval is parameter-routed: an ORDERED, first-match-wins list of rules
 * resolves, for a scored rating, whether approval is required and by which authority. This screen
 * renders that policy as a reorderable stack of rule cards with a condition builder + a live
 * "simulate routing" panel — far friendlier than the raw Masters JSON form — but SAVE still goes
 * through the SAME generic master maker-checker flow (submit a new version → a different approver
 * signs). Policy changes are HUMAN-governed config and never touch a figure: routing is a GATE,
 * not a rating change.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { masters, risk, fmt } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, DeterministicBadge, EmptyState, Field, HumanBadge, Unchanged, useAsync,
} from "../ui";
import { JsonDiff } from "../config-forms";

const MASTER_TYPE = "SCORING_APPROVAL_POLICY";

// AAA..D master ladder (best → worst) — mirrors MasterScale / GRADE_SCALE.
const GRADES = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"];
// Coarse model-score bands (STRONG/ADEQUATE/WEAK) the rating path routes on.
const SCORE_BANDS = ["STRONG", "ADEQUATE", "WEAK"];
// The approver authorities the routing can demand (ascending seniority).
const AUTHORITIES = ["ANALYST", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "CRO"];

type CondKind = "money" | "int" | "bool" | "gradeSingle" | "gradeMulti" | "bandMulti" | "textList";
type CondDef = { key: string; label: string; kind: CondKind; hint?: string };

// The known condition keys the risk-service ScoringApprovalPolicyClient understands. A rule matches
// when ALL of its present conditions hold; an empty {} is the catch-all default.
const CONDITIONS: CondDef[] = [
  { key: "exposureGte", label: "Exposure ≥", kind: "money", hint: "Exposure/EAD at or above (₹)" },
  { key: "exposureLte", label: "Exposure ≤", kind: "money", hint: "Exposure/EAD at or below (₹)" },
  { key: "gradeWorseThan", label: "Grade worse than", kind: "gradeSingle", hint: "Strictly weaker than this ladder rung" },
  { key: "gradeIn", label: "Grade in", kind: "gradeMulti" },
  { key: "scoreBandIn", label: "Score band in", kind: "bandMulti" },
  { key: "overrideNotchesGte", label: "Override notches ≥", kind: "int", hint: "|manual notch move| at or above" },
  { key: "overriddenEq", label: "Overridden equals", kind: "bool" },
  { key: "segmentIn", label: "Segment in", kind: "textList", hint: "comma-separated, e.g. MID_CORPORATE" },
  { key: "jurisdictionIn", label: "Jurisdiction in", kind: "textList", hint: "comma-separated, e.g. IN-RBI" },
];
const CONDITION_BY_KEY: Record<string, CondDef> = Object.fromEntries(CONDITIONS.map((c) => [c.key, c]));

type Rule = { id: string; when: Record<string, any>; requireApproval: boolean; approverAuthority?: string };

function defaultCondValue(kind: CondKind): any {
  switch (kind) {
    case "money": return 1_000_000_000;
    case "int": return 2;
    case "bool": return true;
    case "gradeSingle": return "BB";
    case "gradeMulti": return [];
    case "bandMulti": return [];
    case "textList": return [];
  }
}

/** Drop empty / meaningless conditions so a rule never becomes silently dead on save. */
function sanitiseWhen(when: Record<string, any>): Record<string, any> {
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(when || {})) {
    if (Array.isArray(v)) { if (v.length) out[k] = v; }
    else if (v === "" || v == null) { /* skip blanks */ }
    else if (typeof v === "number" && Number.isNaN(v)) { /* skip NaN */ }
    else out[k] = v;
  }
  return out;
}

/** Split the loaded rules into ordered condition rules + the pinned conditionless default. */
function split(rules: Rule[]): { conds: Rule[]; dflt: Rule } {
  const isDefault = (r: Rule) => !r.when || Object.keys(r.when).length === 0;
  const dfltIdx = rules.findIndex(isDefault);
  if (dfltIdx < 0) {
    return {
      conds: rules,
      dflt: { id: "default", when: {}, requireApproval: true, approverAuthority: "CREDIT_OFFICER" },
    };
  }
  return { conds: rules.filter((_, i) => i !== dfltIdx), dflt: rules[dfltIdx] };
}

/* ------------------------------------------------------------------ condition value editor */

function CondValue({ def, value, onChange }: { def: CondDef; value: any; onChange: (v: any) => void }) {
  if (def.kind === "money" || def.kind === "int") {
    return (
      <div>
        <input type="number" value={value ?? ""} style={{ maxWidth: 220 }}
          onChange={(e) => onChange(e.target.value === "" ? "" : Number(e.target.value))} />
        {def.kind === "money" && typeof value === "number" && !Number.isNaN(value) && (
          <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{fmt.money(value)}</div>
        )}
      </div>
    );
  }
  if (def.kind === "bool") {
    return (
      <select value={String(!!value)} style={{ maxWidth: 140 }} onChange={(e) => onChange(e.target.value === "true")}>
        <option value="true">true</option>
        <option value="false">false</option>
      </select>
    );
  }
  if (def.kind === "gradeSingle") {
    return (
      <select value={value ?? ""} style={{ maxWidth: 140 }} onChange={(e) => onChange(e.target.value)}>
        {GRADES.map((g) => <option key={g} value={g}>{g}</option>)}
      </select>
    );
  }
  // multi-value chip toggles (gradeMulti / bandMulti)
  if (def.kind === "gradeMulti" || def.kind === "bandMulti") {
    const opts = def.kind === "gradeMulti" ? GRADES : SCORE_BANDS;
    const set = new Set<string>(Array.isArray(value) ? value : []);
    const toggle = (o: string) => {
      const next = new Set(set);
      next.has(o) ? next.delete(o) : next.add(o);
      onChange([...next]);
    };
    return (
      <div className="chip-row">
        {opts.map((o) => (
          <button key={o} type="button" className={`chip-toggle${set.has(o) ? " on" : ""}`} onClick={() => toggle(o)}>
            {o}
          </button>
        ))}
      </div>
    );
  }
  // textList (segmentIn / jurisdictionIn)
  const arr: string[] = Array.isArray(value) ? value : [];
  return (
    <input value={arr.join(", ")} style={{ maxWidth: 320 }} placeholder="comma-separated"
      onChange={(e) => onChange(e.target.value.split(",").map((s) => s.trim()).filter(Boolean))} />
  );
}

/* ------------------------------------------------------------------ single rule card */

function RuleCard({
  rule, index, count, isDefault, onChange, onRemove, onMove,
}: {
  rule: Rule; index: number; count: number; isDefault?: boolean;
  onChange: (r: Rule) => void; onRemove?: () => void; onMove?: (dir: -1 | 1) => void;
}) {
  const usedKeys = new Set(Object.keys(rule.when || {}));
  const available = CONDITIONS.filter((c) => !usedKeys.has(c.key));

  const setWhen = (k: string, v: any) => onChange({ ...rule, when: { ...rule.when, [k]: v } });
  const removeCond = (k: string) => {
    const next = { ...rule.when };
    delete next[k];
    onChange({ ...rule, when: next });
  };
  const addCond = (k: string) => {
    const def = CONDITION_BY_KEY[k];
    setWhen(k, defaultCondValue(def.kind));
  };

  return (
    <div className={`rule-card${isDefault ? " is-default" : ""}`}>
      <div className="rule-card-head">
        <div className="btnrow" style={{ alignItems: "center", gap: 8 }}>
          <span className="rule-seq">{isDefault ? "↳ catch-all" : `#${index + 1}`}</span>
          <input className="rule-id-input" value={rule.id} placeholder="rule id"
            onChange={(e) => onChange({ ...rule, id: e.target.value })} />
          {isDefault && <Badge kind="info">no conditions · matches everything</Badge>}
        </div>
        {!isDefault && (
          <div className="btnrow" style={{ gap: 4 }}>
            <button className="matrix-order-btn" disabled={index === 0} aria-label="Move rule up"
              onClick={() => onMove?.(-1)}>↑</button>
            <button className="matrix-order-btn" disabled={index === count - 1} aria-label="Move rule down"
              onClick={() => onMove?.(1)}>↓</button>
            <button className="btn danger" style={{ fontSize: 11, padding: "3px 8px" }} onClick={onRemove}>Remove</button>
          </div>
        )}
      </div>

      {!isDefault && (
        <div className="rule-when">
          <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
            Matches when <b>ALL</b> of the following hold:
          </div>
          {Object.keys(rule.when || {}).length === 0 && (
            <div className="muted" style={{ fontSize: 12, fontStyle: "italic" }}>
              No conditions yet — add one below (an empty rule would match every deal).
            </div>
          )}
          {Object.keys(rule.when || {}).map((k) => {
            const def = CONDITION_BY_KEY[k];
            if (!def) return null;
            return (
              <div className="cond-row" key={k}>
                <span className="cond-key">{def.label}</span>
                <CondValue def={def} value={rule.when[k]} onChange={(v) => setWhen(k, v)} />
                <button className="cond-del" aria-label={`Remove ${def.label} condition`} onClick={() => removeCond(k)}>×</button>
                {def.hint && <span className="cond-hint">{def.hint}</span>}
              </div>
            );
          })}
          {available.length > 0 && (
            <div className="btnrow" style={{ marginTop: 8, gap: 6, alignItems: "center" }}>
              <span className="muted" style={{ fontSize: 11 }}>Add condition:</span>
              <select value="" onChange={(e) => { if (e.target.value) addCond(e.target.value); }} style={{ maxWidth: 210 }}>
                <option value="">— pick a condition —</option>
                {available.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
              </select>
            </div>
          )}
        </div>
      )}

      <div className="rule-outcome">
        <label className="inline" style={{ gap: 8 }}>
          <input type="checkbox" style={{ width: "auto" }} checked={rule.requireApproval}
            onChange={(e) => onChange({ ...rule, requireApproval: e.target.checked })} />
          <span style={{ fontSize: 13 }}>Require approval</span>
        </label>
        <Field label="Approver authority">
          <select value={rule.approverAuthority ?? ""} disabled={!rule.requireApproval}
            onChange={(e) => onChange({ ...rule, approverAuthority: e.target.value })}>
            {AUTHORITIES.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
        </Field>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ simulate routing panel */

function SimulatePanel() {
  const [exposure, setExposure] = useState<number>(800_000_000);
  const [grade, setGrade] = useState("BBB");
  const [overrideNotches, setOverrideNotches] = useState<number>(0);
  const [overridden, setOverridden] = useState(false);
  const [scoreBand, setScoreBand] = useState("");
  const [segment, setSegment] = useState("");
  const [jurisdiction, setJurisdiction] = useState("");
  const [result, setResult] = useState<{ matchedRuleId: string; requireApproval: boolean; requiredAuthority: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const simulate = async () => {
    setBusy(true); setErr(null);
    try {
      const r = await risk.simulateScoringApproval({
        exposure, grade, overrideNotches, overridden,
        segment: segment || undefined, jurisdiction: jurisdiction || undefined,
        scoreBand: scoreBand || undefined,
      });
      setResult(r);
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  };

  return (
    <Card title="Simulate routing"
      sub="Evaluates the ACTIVE (approved) policy for a hypothetical scored rating — read-only, non-persisting. Save & approve your edits above first to route against them."
      right={<DeterministicBadge label="READ-ONLY · NO PERSIST" />}>
      <div className="grid cols-3">
        <Field label="Exposure / EAD (₹)" hint={fmt.money(exposure)}>
          <input type="number" value={exposure} onChange={(e) => setExposure(Number(e.target.value))} />
        </Field>
        <Field label="Final grade">
          <select value={grade} onChange={(e) => setGrade(e.target.value)}>
            {GRADES.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
        </Field>
        <Field label="Score band">
          <select value={scoreBand} onChange={(e) => setScoreBand(e.target.value)}>
            <option value="">(none)</option>
            {SCORE_BANDS.map((b) => <option key={b} value={b}>{b}</option>)}
          </select>
        </Field>
        <Field label="Override notches">
          <input type="number" value={overrideNotches} onChange={(e) => setOverrideNotches(Number(e.target.value))} />
        </Field>
        <Field label="Overridden">
          <label className="inline" style={{ padding: "6px 0", gap: 8 }}>
            <input type="checkbox" style={{ width: "auto" }} checked={overridden} onChange={(e) => setOverridden(e.target.checked)} />
            <span className="muted" style={{ fontSize: 12 }}>{overridden ? "true" : "false"}</span>
          </label>
        </Field>
        <Field label="Segment"><input value={segment} onChange={(e) => setSegment(e.target.value)} placeholder="(any)" /></Field>
        <Field label="Jurisdiction"><input value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} placeholder="(any)" /></Field>
      </div>
      <div className="btnrow" style={{ marginTop: 8 }}>
        <Button onClick={simulate} busy={busy}>Simulate routing</Button>
      </div>
      {err && <div className="muted" style={{ color: "var(--bad, #b00)", fontSize: 12, marginTop: 8 }}>{err}</div>}
      {result && (
        <div className="sim-result">
          <div>
            <div className="muted" style={{ fontSize: 11 }}>Matched rule</div>
            <div className="mono" style={{ fontWeight: 600 }}>{result.matchedRuleId}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 11 }}>Approval required</div>
            <Badge kind={result.requireApproval ? "warn" : "ok"}>{result.requireApproval ? "YES" : "NO"}</Badge>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 11 }}>Routed to</div>
            <Badge kind="info">{result.requiredAuthority ?? "—"}</Badge>
          </div>
        </div>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ main screen */

export default function ApprovalRules() {
  const { actor, notify } = useApp();
  const active = useAsync(() => masters.list(MASTER_TYPE), []);
  const pending = useAsync(() => masters.pending(), []);

  // The active record we author (prefer the jurisdiction-agnostic "default").
  const activeRecords = (active.data || []) as any[];
  const activeRec = useMemo(
    () => activeRecords.find((r) => r.recordKey === "default") || activeRecords[0] || null,
    [activeRecords],
  );
  const pendingRec = useMemo(
    () => (pending.data || []).find((p: any) => p.masterType === MASTER_TYPE) || null,
    [pending.data],
  );

  const [recordKey, setRecordKey] = useState("default");
  const [jurisdiction, setJurisdiction] = useState("");
  const [conds, setConds] = useState<Rule[]>([]);
  const [dflt, setDflt] = useState<Rule>({ id: "default", when: {}, requireApproval: true, approverAuthority: "CREDIT_OFFICER" });
  const [comment, setComment] = useState("");
  const [saving, setSaving] = useState(false);
  const seededFrom = useRef<number | null>(null);

  // Seed the editor from the active record once (and again when it reloads to a new version).
  useEffect(() => {
    if (!activeRec) return;
    if (seededFrom.current === activeRec.id) return;
    seededFrom.current = activeRec.id;
    const rules: Rule[] = Array.isArray(activeRec.payload?.rules) ? activeRec.payload.rules : [];
    const { conds: c, dflt: d } = split(rules);
    setConds(c);
    setDflt(d);
    setRecordKey(activeRec.recordKey || "default");
    setJurisdiction(activeRec.jurisdiction || "");
  }, [activeRec]);

  const builtRules = useMemo(
    () => [...conds, dflt].map((r) => ({ ...r, when: sanitiseWhen(r.when) })),
    [conds, dflt],
  );
  const builtPayload = useMemo(() => ({ rules: builtRules }), [builtRules]);

  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= conds.length) return;
    const next = [...conds];
    [next[i], next[j]] = [next[j], next[i]];
    setConds(next);
  };
  const updateCond = (i: number, r: Rule) => setConds((cs) => cs.map((c, k) => (k === i ? r : c)));
  const removeCond = (i: number) => setConds((cs) => cs.filter((_, k) => k !== i));
  const addRule = () =>
    setConds((cs) => [...cs, { id: `rule-${cs.length + 1}`, when: { exposureGte: 1_000_000_000 }, requireApproval: true, approverAuthority: "CREDIT_COMMITTEE" }]);

  const reload = () => { active.reload(); pending.reload(); };

  const save = async () => {
    if (!recordKey.trim()) { notify("A record key is required", true); return; }
    if (builtRules.some((r) => !r.id.trim())) { notify("Every rule needs an id", true); return; }
    setSaving(true);
    try {
      const body: any = { recordKey: recordKey.trim(), payload: builtPayload };
      if (jurisdiction.trim()) body.jurisdiction = jurisdiction.trim();
      if (comment.trim()) body.comment = comment.trim();
      const m = await masters.submit(MASTER_TYPE, body, actor);
      notify(`Submitted ${MASTER_TYPE}/${recordKey} v${m.version} — awaiting a different approver (SoD)`);
      setComment("");
      seededFrom.current = null;   // allow re-seed from the new active record after approval
      reload();
    } catch (e: any) { notify(e.message, true); } finally { setSaving(false); }
  };

  const approve = async (id: number) => {
    try { await masters.approve(id, actor); notify("Policy version approved & activated"); seededFrom.current = null; reload(); }
    catch (e: any) { notify(e.message, true); }
  };
  const reject = async (id: number) => {
    try { await masters.reject(id, actor); notify("Pending policy version rejected"); reload(); }
    catch (e: any) { notify(e.message, true); }
  };

  return (
    <div className="grid">
      <Card title="Approval Rules · scoring (rating) approval policy"
        sub="Author the ordered, first-match-wins routing that decides which scored ratings need approval and by whom. This is configuration — saving goes through the generic master maker-checker flow (a different person must approve). Routing is a GATE: it never changes a grade, PD or price."
        right={<div className="btnrow" style={{ gap: 6 }}><HumanBadge label="HUMAN-GOVERNED CONFIG" /><Unchanged label="FIGURES UNCHANGED" /></div>}>
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <div>
            <div className="muted" style={{ fontSize: 11 }}>Active version</div>
            <div style={{ fontWeight: 600 }}>
              {activeRec ? <span className="mono">v{activeRec.version}</span> : <span className="muted">—</span>}
              {activeRec?.checker && <span className="muted" style={{ fontSize: 11, marginLeft: 8 }}>approved by {activeRec.checker}</span>}
            </div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 11 }}>Pending version</div>
            <div style={{ fontWeight: 600 }}>
              {pendingRec
                ? <><span className="mono">v{pendingRec.version}</span> <Badge kind="warn">PENDING</Badge></>
                : <span className="muted">none</span>}
            </div>
          </div>
          <div className="btnrow">
            <Button kind="ghost" onClick={reload}>Reload</Button>
          </div>
        </div>
      </Card>

      {active.loading && <div className="loading">Loading policy…</div>}
      {!active.loading && !activeRec && (
        <Card><EmptyState glyph="⚖" title="No active SCORING_APPROVAL_POLICY found"
          sub="Seed one via Master Data, or add rules below and submit the first version for approval." /></Card>
      )}

      {/* ── the rule matrix ── */}
      <Card title="Rule matrix · ordered · first-match-wins"
        sub="Rules are evaluated top-to-bottom; the FIRST rule whose conditions all hold decides the routing. Reorder with the ↑/↓ controls. The catch-all default is pinned last."
        right={<Button kind="ghost" onClick={addRule}>+ Add rule</Button>}>
        {conds.map((r, i) => (
          <RuleCard key={i} rule={r} index={i} count={conds.length}
            onChange={(nr) => updateCond(i, nr)} onRemove={() => removeCond(i)} onMove={(dir) => move(i, dir)} />
        ))}
        <RuleCard rule={dflt} index={conds.length} count={conds.length + 1} isDefault
          onChange={(nr) => setDflt({ ...nr, when: {} })} />
      </Card>

      {/* ── save through maker-checker ── */}
      <Card title="Save policy · governed change"
        sub="Submitting queues a new master version for a DIFFERENT approver (segregation of duties). The active policy does not change until a second person approves."
        right={<HumanBadge label="MAKER-CHECKER" />}>
        <div className="grid cols-2">
          <Field label="Record key" hint="jurisdiction-overridable; 'default' is the base policy">
            <input value={recordKey} onChange={(e) => setRecordKey(e.target.value)} placeholder="default" />
          </Field>
          <Field label="Jurisdiction (optional override)">
            <input value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} placeholder="(default — applies everywhere)" />
          </Field>
        </div>
        <Field label="Change rationale" hint="Recorded on the pending record and stamped into the audit trail.">
          <textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)}
            placeholder="Why is this routing change being made?" />
        </Field>

        {activeRec && (
          <Card title="Diff vs active version" sub="What this submission changes on approval.">
            <JsonDiff before={activeRec.payload} after={builtPayload} />
          </Card>
        )}

        <details style={{ marginTop: 8 }}>
          <summary className="muted" style={{ cursor: "pointer", fontSize: 12 }}>Payload preview (JSON)</summary>
          <pre className="trace" style={{ marginTop: 6, maxHeight: 300 }}>{JSON.stringify(builtPayload, null, 2)}</pre>
        </details>

        <div className="btnrow" style={{ marginTop: 10 }}>
          <Button onClick={save} busy={saving}>Submit for approval</Button>
          <small className="prov">Acting as {actor} · a different actor must approve</small>
        </div>
      </Card>

      {/* ── pending queue (a different person approves) ── */}
      {pendingRec && (
        <Card title="Pending approval · SoD enforced"
          sub="A new policy version is awaiting a second person. The maker cannot approve their own submission.">
          <div className="table-scroll">
            <table>
              <thead><tr><th>Key</th><th>Ver</th><th>Maker</th><th>When</th><th>Rationale</th><th>Decide</th></tr></thead>
              <tbody>
                <tr>
                  <td><b>{pendingRec.recordKey}</b>{pendingRec.jurisdiction && <div className="muted" style={{ fontSize: 11 }}>{pendingRec.jurisdiction}</div>}</td>
                  <td className="mono">v{pendingRec.version}</td>
                  <td className="mono"><small>{pendingRec.maker}</small></td>
                  <td className="mono"><small>{fmt.dateTime(pendingRec.makerAt)}</small></td>
                  <td><small className="muted">{pendingRec.comment || "—"}</small></td>
                  <td>
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                        disabled={pendingRec.maker === actor}
                        title={pendingRec.maker === actor ? "SoD: the maker cannot approve their own record" : undefined}
                        onClick={() => approve(pendingRec.id)}>Approve</button>
                      <button className="btn danger" style={{ fontSize: 11, padding: "3px 8px" }}
                        disabled={pendingRec.maker === actor}
                        onClick={() => reject(pendingRec.id)}>Reject</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* ── simulate routing ── */}
      <SimulatePanel />
    </div>
  );
}
