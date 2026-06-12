import { useState } from "react";
import { masters } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, useAsync } from "../ui";

/**
 * Generic master-data admin (PRD Master-Data engine + maker-checker SoD).
 * Pick a master type → view the active records, the pending queue, and approve /
 * reject pending submissions. Server-side enforces checker ≠ maker.
 */
const TYPES: { key: string; label: string }[] = [
  { key: "ACTOR_ROLE", label: "Actor roles (RBAC)" },
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

  const reload = () => { active.reload(); pending.reload(); };
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  const pendingForType = (pending.data || []).filter((p: any) => p.masterType === type);
  const pendingTotal = (pending.data || []).length;

  return (
    <div className="grid">
      <Card title="Master data · maker-checker"
        sub="Generic Master-Data engine: dedup rules, negative lists, facility/collateral/covenant masters, RAROC tables, EWS triggers, checklists and email templates. Every change goes through a maker-checker workflow; server-side enforces checker ≠ maker."
        right={<Badge kind={pendingTotal > 0 ? "warn" : ""}>{pendingTotal} pending</Badge>}>
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Master type">
            <select value={type} onChange={(e) => setType(e.target.value)}>
              {TYPES.map((t) => <option key={t.key} value={t.key}>{t.label} · {t.key}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: "span 2" }} className="btnrow">
            <Button kind="ghost" onClick={() => setProposing((o) => !o)}>{proposing ? "Cancel" : "+ Propose new"}</Button>
          </div>
        </div>
      </Card>

      {proposing && (
        <ProposeRecord type={type} actor={actor} notify={notify}
          onDone={() => { setProposing(false); reload(); }} />
      )}

      <div className="grid cols-2">
        <Card title={`Active records · ${type}`}
          sub={active.loading ? "Loading…" : `${(active.data || []).length} active record(s) · version-controlled`}>
          {(active.data || []).length === 0 ? (
            <div className="muted">No active records.</div>
          ) : (
            <table>
              <thead><tr><th>Key</th><th>Ver</th><th>Maker</th><th>Checker</th><th>Payload</th></tr></thead>
              <tbody>
                {(active.data || []).map((r: any) => (
                  <tr key={r.id}>
                    <td><b>{r.recordKey}</b></td>
                    <td className="mono">v{r.version}</td>
                    <td className="mono"><small>{r.maker || "—"}</small></td>
                    <td className="mono"><small>{r.checker || "—"}</small></td>
                    <td><PayloadPreview payload={r.payload} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="Pending approval"
          sub={pendingForType.length === 0 ? `No pending records for ${type}.` : `${pendingForType.length} pending for ${type} · SoD enforced`}>
          {pendingForType.length === 0 ? (
            (pending.data || []).length > 0
              ? <div className="muted">{pendingTotal} pending across other master types — switch the selector.</div>
              : <div className="muted">No pending submissions.</div>
          ) : (
            <table>
              <thead><tr><th>Key</th><th>Maker</th><th>When</th><th>Payload</th><th>Decide</th></tr></thead>
              <tbody>
                {pendingForType.map((r: any) => (
                  <tr key={r.id}>
                    <td><b>{r.recordKey}</b></td>
                    <td className="mono"><small>{r.maker}</small></td>
                    <td className="mono"><small>{new Date(r.makerAt).toLocaleString()}</small></td>
                    <td><PayloadPreview payload={r.payload} /></td>
                    <td>
                      <div className="btnrow">
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => run(() => masters.approve(r.id, actor), "Approved")}>Approve</button>
                        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
                          onClick={() => run(() => masters.reject(r.id, actor), "Rejected")}>Reject</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>
    </div>
  );
}

function PayloadPreview({ payload }: { payload: any }) {
  if (!payload || typeof payload !== "object") return <span className="muted">—</span>;
  const entries = Object.entries(payload).slice(0, 4);
  return (
    <small className="prov" style={{ fontFamily: "ui-monospace, Menlo, monospace" }}>
      {entries.map(([k, v]) => `${k}: ${preview(v)}`).join(" · ")}
      {Object.keys(payload).length > entries.length && " …"}
    </small>
  );
}

function preview(v: any): string {
  if (v == null) return "—";
  if (Array.isArray(v)) return `[${v.length}]`;
  if (typeof v === "object") return "{…}";
  const s = String(v);
  return s.length > 28 ? s.slice(0, 25) + "…" : s;
}

function ProposeRecord({ type, actor, notify, onDone }:
                       { type: string; actor: string; notify: any; onDone: () => void }) {
  const [recordKey, setRecordKey] = useState("");
  const [payloadText, setPayloadText] = useState('{\n  "enabled": true\n}');
  const submit = async () => {
    if (!recordKey.trim()) { notify("Record key required", true); return; }
    let payload: any;
    try { payload = JSON.parse(payloadText); }
    catch (e: any) { notify("Payload must be valid JSON: " + e.message, true); return; }
    try {
      await masters.submit(type, { recordKey: recordKey.trim(), payload }, actor);
      notify(`Proposed ${type}:${recordKey} (pending checker approval)`);
      onDone();
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <Card title={`Propose new ${type}`} sub="The submission enters PENDING_APPROVAL — a different actor must approve before it becomes active.">
      <div className="grid cols-2">
        <Field label="Record key">
          <input value={recordKey} onChange={(e) => setRecordKey(e.target.value)} placeholder="e.g. NEW_THRESHOLD" />
        </Field>
        <Field label="Acting as">
          <input value={actor} disabled className="mono" />
        </Field>
      </div>
      <Field label="Payload (JSON)">
        <textarea value={payloadText} onChange={(e) => setPayloadText(e.target.value)}
          rows={6} style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 12 }} />
      </Field>
      <Button onClick={submit}>Submit for approval</Button>
    </Card>
  );
}
