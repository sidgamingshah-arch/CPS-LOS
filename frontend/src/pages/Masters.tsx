import { useMemo, useState } from "react";
import { fmt, masters } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, useAsync } from "../ui";
import {
  BulkUpload, JsonDiff, PagerBar, PayloadEditor, PayloadView, RationaleBox,
  SearchBox, schemaFor, useSearchPaginate,
} from "../config-forms";

/**
 * Generic master-data admin (PRD Master-Data engine + maker-checker SoD).
 * Pick a master type → view / search the active records, the pending queue, and
 * approve / reject pending submissions. Server-side enforces checker ≠ maker.
 *
 * Schema-aware editing: common master types render a guided form (see
 * config-forms.tsx) with inline validation, clone-from-active, a before/after
 * diff and a mandatory change rationale on edits. The raw-JSON escape hatch is
 * always available, and types with no curated schema fall back to it. A bulk
 * CSV/JSON upload feeds the same maker-checker path.
 */
const TYPES: { key: string; label: string }[] = [
  { key: "ACTOR_ROLE", label: "Actor roles (RBAC)" },
  { key: "MODEL_DEFINITION", label: "Scoring models (definitions)" },
  { key: "FINANCIAL_TEMPLATE", label: "Financial templates (charts of accounts)" },
  { key: "ESG_BAND", label: "ESG bands (model-driven options)" },
  { key: "BENCHMARK", label: "Floating-rate benchmarks" },
  { key: "DEDUP_RULES", label: "Dedup rules" },
  { key: "NEGATIVE_LIST", label: "Negative list" },
  { key: "FACILITY_MASTER", label: "Facility master" },
  { key: "COLLATERAL_MASTER", label: "Collateral master" },
  { key: "COVENANT_LIBRARY", label: "Covenant library" },
  { key: "EWS_TRIGGER", label: "EWS triggers" },
  { key: "CHECKLIST_MASTER", label: "Documentation checklist" },
  { key: "DOC_TEMPLATE_MASTER", label: "Doc templates" },
  { key: "TNC_MASTER", label: "T&C master" },
  { key: "EMAIL_TEMPLATE", label: "Email templates" },
  { key: "INDUSTRY_BENCHMARK", label: "Industry benchmarks" },
  { key: "INACTIVITY_THRESHOLD", label: "Inactivity threshold" },
  { key: "DRAFT_CLEANUP", label: "Draft cleanup policy" },
  { key: "CHARGE_AGENCY", label: "Charge agencies" },
  { key: "VALUATION_AGENCY", label: "Valuation agencies" },
  { key: "EXTERNAL_RATING_AGENCY", label: "External rating agencies" },
  { key: "RAROC_BENCHMARK", label: "RAROC · benchmarks" },
  { key: "RAROC_FTP", label: "RAROC · FTP" },
  { key: "RAROC_CCF", label: "RAROC · CCF" },
  { key: "RAROC_LIQUIDITY_PREMIUM", label: "RAROC · liquidity premium" },
  { key: "RAROC_OPERATING_COST", label: "RAROC · operating cost" },
  { key: "RAROC_PD_TERM_STRUCTURE", label: "RAROC · PD term structure" },
];

export default function Masters() {
  const { actor, notify } = useApp();
  const [type, setType] = useState(TYPES[0].key);
  const active = useAsync(() => masters.list(type), [type]);
  const pending = useAsync(() => masters.pending(), []);
  const [proposing, setProposing] = useState(false);
  const [bulking, setBulking] = useState(false);
  const [cloneSeed, setCloneSeed] = useState<any>(null);

  const reload = () => { active.reload(); pending.reload(); };
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  const activeRecords = (active.data || []) as any[];
  const pendingForType = (pending.data || []).filter((p: any) => p.masterType === type);
  const pendingTotal = (pending.data || []).length;
  const hasSchema = !!schemaFor(type);

  const search = useSearchPaginate<any>(
    activeRecords,
    (r) => `${r.recordKey} ${r.jurisdiction || ""} ${JSON.stringify(r.payload || {})}`,
    10,
  );

  function startClone(rec: any) {
    setCloneSeed(rec);
    setProposing(true);
    setBulking(false);
  }

  return (
    <div className="grid">
      <Card title="Master data · maker-checker"
        sub="Generic Master-Data engine: dedup rules, negative lists, facility/collateral/covenant masters, RAROC tables, EWS triggers, checklists and email templates. Every change goes through a maker-checker workflow; server-side enforces checker ≠ maker."
        right={<Badge kind={pendingTotal > 0 ? "warn" : ""}>{pendingTotal} pending</Badge>}>
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Master type" hint={hasSchema ? "Guided form available" : "Raw-JSON editing"}>
            <select value={type} onChange={(e) => { setType(e.target.value); setCloneSeed(null); }}>
              {TYPES.map((t) => <option key={t.key} value={t.key}>{t.label} · {t.key}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: "span 2" }} className="btnrow">
            <Button kind="ghost" onClick={() => { setCloneSeed(null); setProposing((o) => !o); }}>
              {proposing ? "Cancel" : "+ Propose new"}
            </Button>
            <Button kind="subtle" onClick={() => setBulking((o) => !o)}>
              {bulking ? "Cancel bulk" : "⇪ Bulk upload"}
            </Button>
            {hasSchema && <Badge kind="info">schema</Badge>}
          </div>
        </div>
      </Card>

      {bulking && (
        <Card title={`Bulk upload · ${type}`}
          sub="Upload or paste CSV / JSON. Each row is submitted through the same maker-checker path (lands as PENDING_APPROVAL for a checker). A recordKey is required per row.">
          <BulkUpload type={type} actor={actor} notify={notify}
            onDone={() => reload()} />
        </Card>
      )}

      {proposing && (
        <ProposeRecord key={`${type}:${cloneSeed?.id ?? "new"}`}
          type={type} actor={actor} notify={notify}
          activeRecords={activeRecords} clone={cloneSeed}
          onDone={() => { setProposing(false); setCloneSeed(null); reload(); }} />
      )}

      <div className="grid cols-2">
        <Card title={`Active records · ${type}`}
          sub={active.loading ? "Loading…" : `${activeRecords.length} active record(s) · version-controlled`}
          right={<SearchBox value={search.query} onChange={search.setQuery} placeholder="Search records…" />}>
          {activeRecords.length === 0 ? (
            <div className="muted">No active records.</div>
          ) : (
            <>
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Key</th><th>Ver</th><th>Maker</th><th>Checker</th><th>Payload</th><th /></tr></thead>
                  <tbody>
                    {search.pageRows.map((r: any) => (
                      <tr key={r.id}>
                        <td><b>{r.recordKey}</b>{r.jurisdiction && <div className="muted" style={{ fontSize: 11 }}>{r.jurisdiction}</div>}</td>
                        <td className="mono">v{r.version}</td>
                        <td className="mono"><small>{r.maker || "—"}</small></td>
                        <td className="mono"><small>{r.checker || "—"}</small></td>
                        <td><PayloadView payload={r.payload} /></td>
                        <td><Button kind="ghost" onClick={() => startClone(r)}>Clone / edit</Button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <PagerBar page={search.page} pageCount={search.pageCount} setPage={search.setPage}
                start={search.start} shown={search.pageRows.length}
                filteredTotal={search.filteredTotal} total={search.total} />
            </>
          )}
        </Card>

        <Card title="Pending approval"
          sub={pendingForType.length === 0 ? `No pending records for ${type}.` : `${pendingForType.length} pending for ${type} · SoD enforced`}>
          {pendingForType.length === 0 ? (
            (pending.data || []).length > 0
              ? <div className="muted">{pendingTotal} pending across other master types — switch the selector.</div>
              : <div className="muted">No pending submissions.</div>
          ) : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Key</th><th>Maker</th><th>When</th><th>Rationale</th><th>Payload</th><th>Decide</th></tr></thead>
                <tbody>
                  {pendingForType.map((r: any) => {
                    const isMaker = r.maker === actor;
                    return (
                      <tr key={r.id}>
                        <td><b>{r.recordKey}</b> <span className="muted">v{r.version}</span></td>
                        <td className="mono"><small>{r.maker}</small></td>
                        <td className="mono"><small>{fmt.dateTime(r.makerAt)}</small></td>
                        <td><small className="muted">{r.comment || "—"}</small></td>
                        <td><PayloadView payload={r.payload} /></td>
                        <td>
                          <div className="btnrow">
                            <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                              disabled={isMaker}
                              title={isMaker ? "SoD: the maker cannot approve their own record" : undefined}
                              onClick={() => run(() => masters.approve(r.id, actor), "Approved")}>Approve</button>
                            <button className="btn danger" style={{ fontSize: 11, padding: "3px 8px" }}
                              disabled={isMaker}
                              onClick={() => {
                                if (window.confirm(`Reject pending record ${r.recordKey}?`))
                                  run(() => masters.reject(r.id, actor), "Rejected");
                              }}>Reject</button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

function ProposeRecord({ type, actor, notify, activeRecords, clone, onDone }: {
  type: string; actor: string; notify: (t: string, e?: boolean) => void;
  activeRecords: any[]; clone: any | null; onDone: () => void;
}) {
  const schema = schemaFor(type);
  const [recordKey, setRecordKey] = useState(clone?.recordKey || "");
  const [jurisdiction, setJurisdiction] = useState(clone?.jurisdiction || "");
  const [payload, setPayload] = useState<any>(clone?.payload || {});
  const [editorInitial, setEditorInitial] = useState<any>(clone?.payload || {});
  const [payloadErrors, setPayloadErrors] = useState<Record<string, string>>({});
  const [rationale, setRationale] = useState("");
  const [attempted, setAttempted] = useState(false);
  const [resetKey, setResetKey] = useState(0);

  // The active record the edit would supersede (same key + jurisdiction scope).
  const activeMatch = useMemo(() => {
    const jur = jurisdiction.trim();
    return activeRecords.find((r) =>
      r.recordKey === recordKey.trim() && (r.jurisdiction || "") === jur) || null;
  }, [activeRecords, recordKey, jurisdiction]);
  const before = activeMatch?.payload || null;
  const isEdit = !!activeMatch;

  const submit = async () => {
    setAttempted(true);
    if (!recordKey.trim()) { notify("Record key required", true); return; }
    if (Object.keys(payloadErrors).length > 0) { notify("Fix the highlighted payload fields first", true); return; }
    if (isEdit && !rationale.trim()) { notify("A change rationale is required for an edit", true); return; }
    try {
      const body: any = { recordKey: recordKey.trim(), payload };
      if (jurisdiction.trim()) body.jurisdiction = jurisdiction.trim();
      if (rationale.trim()) body.comment = rationale.trim();
      const m = await masters.submit(type, body, actor);
      notify(`Proposed ${type}/${recordKey} v${m.version} (pending checker approval)`);
      onDone();
    } catch (e: any) { notify(e.message, true); }
  };

  return (
    <Card title={`${clone ? "Clone / edit" : "Propose new"} ${type}`}
      sub="The submission enters PENDING_APPROVAL — a different actor must approve before it becomes active."
      right={isEdit ? <Badge kind="warn">EDIT · supersedes v{activeMatch.version}</Badge>
        : <Badge kind="ok">NEW</Badge>}>
      <div className="grid cols-2">
        <Field label="Record key" required hint={schema?.keyHint}>
          <input value={recordKey} onChange={(e) => setRecordKey(e.target.value)}
            placeholder="e.g. NEW_THRESHOLD" />
        </Field>
        <Field label="Jurisdiction (optional override)">
          <input value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} placeholder="(default)" />
        </Field>
      </div>

      {activeRecords.length > 0 && (
        <div className="btnrow" style={{ marginBottom: 8, gap: 6, alignItems: "center" }}>
          <span className="muted" style={{ fontSize: 11 }}>Clone from active:</span>
          {activeRecords.slice(0, 8).map((r) => (
            <button key={r.id} className="btn subtle" style={{ fontSize: 11, padding: "2px 8px" }}
              onClick={() => {
                setRecordKey(r.recordKey);
                setJurisdiction(r.jurisdiction || "");
                setPayload(r.payload || {});
                setEditorInitial(r.payload || {});
                setResetKey((k) => k + 1);
              }}>{r.recordKey}</button>
          ))}
          {activeRecords.length > 8 && <span className="muted" style={{ fontSize: 11 }}>…and {activeRecords.length - 8} more (use the list's Clone / edit)</span>}
        </div>
      )}

      <PayloadEditor schema={schema} initial={editorInitial} resetKey={resetKey}
        onChange={(p, errs) => { setPayload(p); setPayloadErrors(errs); }} />

      {isEdit && (
        <Card title="Diff vs active version" sub="What this submission changes on approval.">
          <JsonDiff before={before} after={payload} />
        </Card>
      )}

      <RationaleBox value={rationale} onChange={setRationale}
        show={attempted} required={isEdit}
        label={isEdit ? "Change rationale (required)" : "Rationale (optional for a new record)"} />

      <Button onClick={submit}>Submit for approval</Button>
    </Card>
  );
}
