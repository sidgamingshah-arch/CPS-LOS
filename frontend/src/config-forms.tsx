/**
 * Schema-aware config UX — shared building blocks for the config screens
 * (Masters · RulePacks · ModelBuilder).
 *
 * Everything here is additive to the existing raw-JSON flows. The raw-JSON
 * textarea is preserved as an escape hatch on every editor (PayloadEditor keeps
 * a "Raw JSON" mode), so an operator can always fall back to hand-editing a
 * payload for a master/pack type that has no curated schema.
 *
 * Components:
 *  - PayloadEditor  — Form (schema-driven) ↔ Raw JSON toggle over one payload.
 *  - SchemaForm     — typed inputs (text/number/select/checkbox/textarea/tags/json)
 *                     built from a field descriptor list, with inline validation.
 *  - JsonDiff       — before/after key-level diff for the "diff on submit" gate.
 *  - PayloadView    — full, collapsible, pretty-printed payload (replaces the old
 *                     truncated 4-key preview).
 *  - RationaleBox   — a required-rationale field with inline error.
 *  - BulkUpload     — CSV / JSON bulk-load of master rows through the existing
 *                     master maker-checker path, with per-row preview + results.
 *  - useSearchPaginate / PagerBar — inline search + pagination for long lists.
 *
 * Schemas are a curated map (no backend schema endpoint exists for masters); any
 * type not in the map falls back to raw JSON automatically.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { masters } from "./api";
import { Badge, Button, Field } from "./ui";

/* =============================== schema model ============================== */

export type SchemaFieldType =
  | "text" | "number" | "select" | "checkbox" | "textarea" | "tags" | "json";

export type SchemaField = {
  name: string;
  label: string;
  type: SchemaFieldType;
  required?: boolean;
  enum?: string[];
  hint?: string;
  placeholder?: string;
  default?: any;
};

export type MasterSchema = {
  keyHint?: string;   // hint shown under the record-key input
  fields: SchemaField[];
};

/* ---- curated schemas for the common master types (shape mirrors the seed data).
   Anything not listed falls back to the raw-JSON editor. ---- */
export const MASTER_SCHEMAS: Record<string, MasterSchema> = {
  FACILITY_MASTER: {
    keyHint: "e.g. TERM_LOAN, WORKING_CAPITAL, LETTER_OF_CREDIT",
    fields: [
      { name: "classification", label: "Classification", type: "select", required: true,
        enum: ["FUND_BASED", "NON_FUND_BASED"] },
      { name: "type", label: "Type", type: "select", required: true,
        enum: ["REVOLVING", "NON_REVOLVING"] },
      { name: "category", label: "Category", type: "select", required: true,
        enum: ["SHORT_TERM", "LONG_TERM"] },
    ],
  },
  COLLATERAL_MASTER: {
    keyHint: "e.g. CASH, PROPERTY, RECEIVABLES",
    fields: [
      { name: "group", label: "Group", type: "select", required: true, enum: ["TANGIBLE", "INTANGIBLE"] },
      { name: "subGroup", label: "Sub-group", type: "select", required: true, enum: ["FINANCIAL", "NON_FINANCIAL"] },
      { name: "type", label: "Type", type: "text", required: true, hint: "e.g. MARKETABLE_SECURITIES, IMMOVABLE_FIXED_ASSET" },
      { name: "subType", label: "Sub-type", type: "text", required: true, hint: "e.g. BOND, COMMERCIAL_BUILDING" },
      { name: "riskWeight", label: "Risk weight (haircut)", type: "number", required: true,
        hint: "0.00–1.00 (0 = cash, 0.40 = property)" },
      { name: "valuationMethod", label: "Valuation method", type: "select", required: true,
        enum: ["MARK_TO_MARKET", "VALUATION_REPORT", "BOOK_VALUE"] },
    ],
  },
  COVENANT_LIBRARY: {
    keyHint: "e.g. DSCR, NET_LEVERAGE, NEGATIVE_PLEDGE",
    fields: [
      { name: "category", label: "Category", type: "select", required: true,
        enum: ["FINANCIAL", "INFORMATION", "NEGATIVE"] },
      { name: "operator", label: "Operator", type: "select",
        enum: [">=", "<=", ">", "<", "=", "BY_DATE"], hint: "Omit for pure narrative covenants" },
      { name: "defaultThreshold", label: "Default threshold", type: "number",
        hint: "The default trigger level (numeric covenants only)" },
      { name: "definition", label: "Definition", type: "textarea", required: true },
    ],
  },
  EWS_TRIGGER: {
    keyHint: "e.g. DPD, NET_LEVERAGE, DSCR",
    fields: [
      { name: "enabled", label: "Enabled", type: "checkbox", default: true },
      { name: "criticality", label: "Criticality", type: "select", required: true,
        enum: ["HIGH", "MEDIUM", "LOW"] },
      { name: "nature", label: "Nature", type: "select", enum: ["REAL_TIME", "LAGGING"] },
      { name: "thresholds", label: "Thresholds (JSON)", type: "json",
        hint: 'e.g. {"red":">=90","amber":">=30","green":"<30"}' },
    ],
  },
  EMAIL_TEMPLATE: {
    keyHint: "eventType key, e.g. COVENANT_DUE, MER_OVERDUE",
    fields: [
      { name: "subject", label: "Subject", type: "text", required: true, hint: "{{placeholders}} allowed" },
      { name: "body", label: "Body", type: "textarea", required: true, hint: "{{placeholders}} allowed" },
    ],
  },
  NOTIFICATION_ROUTE: {
    keyHint: "eventType key, e.g. COVENANT_BREACH",
    fields: [
      { name: "roles", label: "Recipient roles", type: "tags", required: true,
        hint: "comma-separated, e.g. RM, CREDIT_OFFICER" },
    ],
  },
  NEGATIVE_LIST: {
    keyHint: "e.g. country:CU, entity:ACME_SHELL",
    fields: [
      { name: "type", label: "Type", type: "select", required: true, enum: ["COUNTRY", "ENTITY", "INDIVIDUAL"] },
      { name: "value", label: "Value", type: "text", required: true },
      { name: "reason", label: "Reason", type: "text", required: true },
    ],
  },
  INACTIVITY_THRESHOLD: { fields: [{ name: "days", label: "Days", type: "number", required: true }] },
  DRAFT_CLEANUP: { fields: [{ name: "months", label: "Months", type: "number", required: true }] },
  VALUATION_AGENCY: {
    fields: [
      { name: "name", label: "Agency name", type: "text", required: true },
      { name: "empanelled", label: "Empanelled", type: "checkbox", default: true },
    ],
  },
  CHARGE_AGENCY: {
    fields: [
      { name: "name", label: "Agency name", type: "text", required: true },
      { name: "country", label: "Country (ISO-2)", type: "text", required: true, placeholder: "IN" },
    ],
  },
  EXTERNAL_RATING_AGENCY: {
    fields: [
      { name: "name", label: "Agency name", type: "text", required: true },
      { name: "scale", label: "Rating scale", type: "tags", required: true,
        hint: "comma-separated, best → worst" },
    ],
  },
  INDUSTRY_BENCHMARK: {
    keyHint: "sector key, e.g. MANUFACTURING",
    fields: [
      { name: "ebitdaMargin", label: "EBITDA margin", type: "number" },
      { name: "netLeverage", label: "Net leverage", type: "number" },
      { name: "currentRatio", label: "Current ratio", type: "number" },
      { name: "interestCoverage", label: "Interest coverage", type: "number" },
      { name: "dscr", label: "DSCR", type: "number" },
    ],
  },
  RAROC_FTP: { fields: [{ name: "rate", label: "FTP rate", type: "number", required: true, hint: "decimal, e.g. 0.075" }] },
  RAROC_LIQUIDITY_PREMIUM: { fields: [{ name: "rate", label: "Liquidity premium", type: "number", required: true, hint: "decimal" }] },
  RAROC_OPERATING_COST: { keyHint: "segment key, e.g. MID_CORPORATE",
    fields: [{ name: "rate", label: "Operating cost rate", type: "number", required: true, hint: "decimal" }] },
  RAROC_BENCHMARK: { fields: [{ name: "rate", label: "Benchmark rate", type: "number", required: true, hint: "decimal" }] },
  CHECKLIST_MASTER: {
    fields: [{ name: "items", label: "Checklist items", type: "tags", required: true, hint: "comma-separated" }],
  },
  DOC_TEMPLATE_MASTER: {
    fields: [
      { name: "format", label: "Format", type: "select", required: true, enum: ["DOCX", "PDF"] },
      { name: "clauses", label: "Clauses", type: "tags", required: true, hint: "comma-separated clause keys" },
    ],
  },
  TNC_MASTER: {
    fields: [
      { name: "text", label: "Clause text", type: "textarea", required: true },
      { name: "appliesTo", label: "Applies to", type: "text", hint: "e.g. PROPERTY" },
    ],
  },
  SCORING_APPROVAL_POLICY: {
    keyHint: "policy key, e.g. default (jurisdiction-overridable)",
    fields: [
      {
        name: "rules",
        label: "Approval rules (ordered · first-match-wins)",
        type: "json",
        required: true,
        hint:
          'Each rule: {"id","when":{...},"requireApproval":true,"approverAuthority":"CREDIT_OFFICER|CREDIT_COMMITTEE|CRO"}. ' +
          "when keys (all optional, ALL must hold): exposureGte / exposureLte, gradeIn (list) / gradeWorseThan (BB), " +
          "scoreBandIn (STRONG/ADEQUATE/WEAK), overrideNotchesGte, overriddenEq, segmentIn, jurisdictionIn. " +
          "An empty when {} is the catch-all default.",
        default: [
          { id: "large-exposure", when: { exposureGte: 10000000000 }, requireApproval: true, approverAuthority: "CREDIT_COMMITTEE" },
          { id: "deep-override", when: { overrideNotchesGte: 2 }, requireApproval: true, approverAuthority: "CRO" },
          { id: "sub-investment", when: { gradeWorseThan: "BB" }, requireApproval: true, approverAuthority: "CREDIT_COMMITTEE" },
          { id: "default", when: {}, requireApproval: true, approverAuthority: "CREDIT_OFFICER" },
        ],
      },
    ],
  },
};

export function schemaFor(type: string): MasterSchema | null {
  return MASTER_SCHEMAS[type] || null;
}

/* =============================== SchemaForm =============================== */

function hydrate(schema: MasterSchema, payload: any): Record<string, any> {
  const form: Record<string, any> = {};
  for (const f of schema.fields) {
    const v = payload ? payload[f.name] : undefined;
    switch (f.type) {
      case "checkbox":
        form[f.name] = typeof v === "boolean" ? v : (f.default ?? false); break;
      case "tags":
        form[f.name] = Array.isArray(v) ? v.join(", ") : (v != null ? String(v) : (f.default ?? "")); break;
      case "json":
        form[f.name] = v != null
          ? JSON.stringify(v, null, 2)
          : (f.default != null ? JSON.stringify(f.default, null, 2) : ""); break;
      case "number":
        form[f.name] = v != null ? String(v) : (f.default != null ? String(f.default) : ""); break;
      default:
        form[f.name] = v != null ? String(v) : (f.default != null ? String(f.default) : "");
    }
  }
  return form;
}

function extraKeys(schema: MasterSchema, payload: any): Record<string, any> {
  const known = new Set(schema.fields.map((f) => f.name));
  const extra: Record<string, any> = {};
  Object.keys(payload || {}).forEach((k) => { if (!known.has(k)) extra[k] = payload[k]; });
  return extra;
}

function toPayload(schema: MasterSchema, form: Record<string, any>, extra: Record<string, any>) {
  const payload: Record<string, any> = { ...extra };
  const errors: Record<string, string> = {};
  for (const f of schema.fields) {
    const raw = form[f.name];
    switch (f.type) {
      case "text": case "textarea": case "select": {
        const s = raw == null ? "" : String(raw);
        if (s.length) payload[f.name] = s;
        else if (f.required) errors[f.name] = "Required";
        break;
      }
      case "number": {
        const s = raw == null ? "" : String(raw).trim();
        if (!s.length) { if (f.required) errors[f.name] = "Required"; }
        else { const n = Number(s); if (Number.isNaN(n)) errors[f.name] = "Must be a number"; else payload[f.name] = n; }
        break;
      }
      case "checkbox":
        payload[f.name] = !!raw; break;
      case "tags": {
        const arr = String(raw || "").split(",").map((s) => s.trim()).filter(Boolean);
        if (arr.length) payload[f.name] = arr;
        else if (f.required) errors[f.name] = "Required";
        break;
      }
      case "json": {
        const s = raw == null ? "" : String(raw).trim();
        if (!s.length) { if (f.required) errors[f.name] = "Required"; }
        else { try { payload[f.name] = JSON.parse(s); } catch { errors[f.name] = "Invalid JSON"; } }
        break;
      }
    }
  }
  return { payload, errors };
}

export function SchemaForm({ schema, initial, resetKey, showErrors, onChange }: {
  schema: MasterSchema;
  initial: any;
  resetKey?: any;
  showErrors?: boolean;
  onChange: (payload: any, errors: Record<string, string>) => void;
}) {
  const [form, setForm] = useState<Record<string, any>>(() => hydrate(schema, initial));
  const [extra, setExtra] = useState<Record<string, any>>(() => extraKeys(schema, initial));

  // Re-hydrate on an explicit resetKey change (clone-from-active / reset / type switch).
  const firstRun = useRef(true);
  useEffect(() => {
    if (firstRun.current) { firstRun.current = false; return; }
    setForm(hydrate(schema, initial));
    setExtra(extraKeys(schema, initial));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey]);

  const out = useMemo(() => toPayload(schema, form, extra), [schema, form, extra]);

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  useEffect(() => { onChangeRef.current(out.payload, out.errors); }, [out]);

  const set = (name: string, value: any) => setForm((f) => ({ ...f, [name]: value }));
  const extraNames = Object.keys(extra);

  return (
    <div>
      <div className="grid cols-2">
        {schema.fields.map((f) => {
          const err = showErrors ? out.errors[f.name] : undefined;
          const wide = f.type === "textarea" || f.type === "json";
          return (
            <div key={f.name} style={wide ? { gridColumn: "1 / -1" } : undefined}>
              <Field label={f.label} required={f.required} hint={f.hint} error={err}>
                {f.type === "select" ? (
                  <select value={form[f.name] ?? ""} onChange={(e) => set(f.name, e.target.value)}>
                    <option value="">— select —</option>
                    {(f.enum || []).map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                ) : f.type === "checkbox" ? (
                  <label className="inline" style={{ padding: "6px 0" }}>
                    <input type="checkbox" checked={!!form[f.name]} style={{ width: "auto" }}
                      onChange={(e) => set(f.name, e.target.checked)} />
                    <span className="muted" style={{ fontSize: 12 }}>{form[f.name] ? "true" : "false"}</span>
                  </label>
                ) : f.type === "textarea" ? (
                  <textarea rows={4} value={form[f.name] ?? ""} placeholder={f.placeholder}
                    onChange={(e) => set(f.name, e.target.value)} />
                ) : f.type === "json" ? (
                  <textarea rows={5} value={form[f.name] ?? ""} placeholder={f.placeholder}
                    style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 12 }}
                    onChange={(e) => set(f.name, e.target.value)} />
                ) : f.type === "number" ? (
                  <input type="number" value={form[f.name] ?? ""} placeholder={f.placeholder}
                    onChange={(e) => set(f.name, e.target.value)} />
                ) : (
                  <input value={form[f.name] ?? ""} placeholder={f.placeholder}
                    onChange={(e) => set(f.name, e.target.value)} />
                )}
              </Field>
            </div>
          );
        })}
      </div>
      {extraNames.length > 0 && (
        <div className="muted" style={{ fontSize: 11.5, marginTop: 2 }}>
          + {extraNames.length} unmodelled field(s) preserved: <span className="mono">{extraNames.join(", ")}</span>
          {" "}(edit via Raw JSON)
        </div>
      )}
    </div>
  );
}

/* ============================== PayloadEditor =============================
   Form (schema) ↔ Raw JSON, over one payload. Raw JSON is always available so
   the escape hatch is never removed; when no schema exists it is the only mode. */
export function PayloadEditor({ schema, initial, resetKey, onChange }: {
  schema?: MasterSchema | null;
  initial: any;
  resetKey?: any;
  onChange: (payload: any, errors: Record<string, string>) => void;
}) {
  const [mode, setMode] = useState<"form" | "raw">(schema ? "form" : "raw");
  const [payload, setPayload] = useState<any>(initial ?? {});
  const [rawText, setRawText] = useState<string>(() => JSON.stringify(initial ?? {}, null, 2));
  const [rawError, setRawError] = useState<string>("");
  const [formResetKey, setFormResetKey] = useState(0);
  const [showErrors, setShowErrors] = useState(false);

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  // Full reset when the parent bumps resetKey (type switch / clone / reset).
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; return; }
    const base = initial ?? {};
    setPayload(base);
    setRawText(JSON.stringify(base, null, 2));
    setRawError("");
    setShowErrors(false);
    setMode(schema ? "form" : "raw");
    setFormResetKey((k) => k + 1);
    onChangeRef.current(base, {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey]);

  function toForm() {
    // parse the raw buffer into the form so edits carry across
    try {
      const p = JSON.parse(rawText);
      setPayload(p);
      setRawError("");
      onChangeRef.current(p, {});
    } catch { /* keep last good payload */ }
    setFormResetKey((k) => k + 1);
    setMode("form");
  }
  function toRaw() {
    setRawText(JSON.stringify(payload ?? {}, null, 2));
    setMode("raw");
  }

  return (
    <div>
      <div className="btnrow" style={{ marginBottom: 8, gap: 6 }}>
        {schema && (
          <>
            <button className={`btn ${mode === "form" ? "" : "subtle"}`} style={{ fontSize: 11 }}
              onClick={() => (mode === "form" ? undefined : toForm())}>Form</button>
            <button className={`btn ${mode === "raw" ? "" : "subtle"}`} style={{ fontSize: 11 }}
              onClick={() => (mode === "raw" ? undefined : toRaw())}>Raw JSON</button>
            <span className="muted" style={{ fontSize: 11 }}>
              {mode === "form" ? "schema-guided" : "escape hatch — hand-edit"}
            </span>
          </>
        )}
        {!schema && <Badge>no schema — raw JSON</Badge>}
      </div>

      {mode === "form" && schema ? (
        <div onBlur={() => setShowErrors(true)}>
          <SchemaForm schema={schema} initial={payload} resetKey={formResetKey} showErrors={showErrors}
            onChange={(p, errs) => { setPayload(p); onChangeRef.current(p, errs); }} />
        </div>
      ) : (
        <Field label="Payload (JSON)" error={rawError || undefined}>
          <textarea rows={12} value={rawText}
            style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 12 }}
            onChange={(e) => {
              setRawText(e.target.value);
              try {
                const p = JSON.parse(e.target.value);
                setPayload(p); setRawError("");
                onChangeRef.current(p, {});
              } catch (err: any) {
                setRawError("Invalid JSON: " + err.message);
                onChangeRef.current(payload, { _raw: "Invalid JSON" });
              }
            }} />
        </Field>
      )}
    </div>
  );
}

/* ================================ JsonDiff ================================ */

type DiffRow = { key: string; status: "added" | "removed" | "changed" | "same"; before?: any; after?: any };

function diffRows(before: any, after: any): DiffRow[] {
  const b = before && typeof before === "object" ? before : {};
  const a = after && typeof after === "object" ? after : {};
  const keys = Array.from(new Set([...Object.keys(b), ...Object.keys(a)])).sort();
  return keys.map((key) => {
    const inB = key in b, inA = key in a;
    const bs = JSON.stringify(b[key]);
    const as = JSON.stringify(a[key]);
    let status: DiffRow["status"] = "same";
    if (inB && !inA) status = "removed";
    else if (!inB && inA) status = "added";
    else if (bs !== as) status = "changed";
    return { key, status, before: b[key], after: a[key] };
  });
}

function cell(v: any): string {
  if (v === undefined) return "—";
  if (v === null) return "null";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

export function JsonDiff({ before, after }: { before: any; after: any }) {
  const [showAll, setShowAll] = useState(false);
  const rows = useMemo(() => diffRows(before, after), [before, after]);
  const changed = rows.filter((r) => r.status !== "same");
  const shown = showAll ? rows : changed;
  const tone: Record<DiffRow["status"], string> = {
    added: "ok", removed: "bad", changed: "warn", same: "",
  };
  return (
    <div>
      <div className="flexbetween" style={{ marginBottom: 6 }}>
        <span className="muted" style={{ fontSize: 12 }}>
          {changed.length === 0 ? "No changes vs the active version." : `${changed.length} field(s) changed`}
        </span>
        <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }}
          onClick={() => setShowAll((s) => !s)}>{showAll ? "Only changes" : "Show all fields"}</button>
      </div>
      {shown.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead><tr><th>Field</th><th>Change</th><th>Before</th><th>After</th></tr></thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.key}>
                  <td className="mono">{r.key}</td>
                  <td><Badge kind={tone[r.status]}>{r.status.toUpperCase()}</Badge></td>
                  <td className="mono cfg-cell">{cell(r.before)}</td>
                  <td className="mono cfg-cell">{cell(r.after)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* =============================== PayloadView ==============================
   Full, collapsible, pretty-printed payload (replaces the truncated preview). */
export function PayloadView({ payload, defaultOpen = false }: { payload: any; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  if (!payload || typeof payload !== "object") return <span className="muted">—</span>;
  const keys = Object.keys(payload);
  return (
    <div>
      <button className="btn subtle" style={{ fontSize: 11, padding: "2px 8px" }}
        onClick={() => setOpen((o) => !o)}>
        {open ? "▾" : "▸"} {keys.length} field{keys.length === 1 ? "" : "s"}
      </button>
      {open && (
        <pre className="trace" style={{ marginTop: 6, maxHeight: 260 }}>{JSON.stringify(payload, null, 2)}</pre>
      )}
    </div>
  );
}

/* =============================== RationaleBox ============================= */
export function RationaleBox({ value, onChange, show, required = true, label = "Change rationale" }: {
  value: string; onChange: (v: string) => void; show?: boolean; required?: boolean; label?: string;
}) {
  const err = show && required && !value.trim()
    ? "A rationale is required for the maker-checker trail." : undefined;
  return (
    <Field label={label} required={required}
      hint="Recorded on the pending record and stamped into the audit trail." error={err}>
      <textarea rows={2} value={value} onChange={(e) => onChange(e.target.value)}
        placeholder="Why is this change being made?" />
    </Field>
  );
}

/* ============================ search + pagination ========================= */
export function useSearchPaginate<T>(rows: T[], textOf: (row: T) => string, pageSize = 10) {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) => textOf(r).toLowerCase().includes(q));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, query]);
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, pageCount - 1);
  useEffect(() => { if (page !== safePage) setPage(safePage); }, [page, safePage]);
  const start = safePage * pageSize;
  const pageRows = filtered.slice(start, start + pageSize);
  return {
    query, setQuery: (q: string) => { setQuery(q); setPage(0); },
    page: safePage, setPage, pageCount,
    total: rows.length, filteredTotal: filtered.length,
    pageRows, start,
  };
}

export function PagerBar({ page, pageCount, setPage, start, shown, filteredTotal, total }: {
  page: number; pageCount: number; setPage: (n: number) => void;
  start: number; shown: number; filteredTotal: number; total: number;
}) {
  if (total === 0) return null;
  return (
    <div className="table-more">
      <span>
        {filteredTotal === 0 ? "0 matches"
          : `${start + 1}–${start + shown} of ${filteredTotal}`}
        {filteredTotal !== total && ` (filtered from ${total})`}
      </span>
      {pageCount > 1 && (
        <div className="btnrow" style={{ gap: 6 }}>
          <button disabled={page <= 0} onClick={() => setPage(page - 1)}>‹ Prev</button>
          <span className="mono" style={{ fontSize: 11.5 }}>{page + 1}/{pageCount}</span>
          <button disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)}>Next ›</button>
        </div>
      )}
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder = "Search…" }: {
  value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder}
      style={{ maxWidth: 280 }} />
  );
}

/* ================================ BulkUpload ==============================
   Upload / paste CSV or JSON, preview parsed rows, validate, then submit each
   row through the existing master maker-checker path (masters.submit — same
   server logic the /{type}/bulk endpoint runs internally). Per-row results are
   shown; rows land as PENDING_APPROVAL for a checker. */
export type BulkRow = { recordKey: string; payload: any; jurisdiction?: string; comment?: string };

function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false; }
      else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n") { row.push(field); rows.push(row); row = []; field = ""; }
    else if (c === "\r") { /* ignore */ }
    else field += c;
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row); }
  return rows.filter((r) => r.some((cell) => cell.trim().length > 0));
}

function coerceCell(raw: string): any {
  const s = raw.trim();
  if (s === "") return "";
  try { return JSON.parse(s); } catch { return s; }
}

export function parseBulk(text: string, mode: "csv" | "json"): { rows: BulkRow[]; error?: string } {
  if (!text.trim()) return { rows: [], error: "Nothing to parse." };
  if (mode === "json") {
    let data: any;
    try { data = JSON.parse(text); } catch (e: any) { return { rows: [], error: "Invalid JSON: " + e.message }; }
    if (!Array.isArray(data)) return { rows: [], error: "JSON must be an array of row objects." };
    const rows: BulkRow[] = data.map((d: any) => ({
      recordKey: String(d?.recordKey ?? ""),
      payload: d?.payload && typeof d.payload === "object" ? d.payload : {},
      jurisdiction: d?.jurisdiction ? String(d.jurisdiction) : undefined,
      comment: d?.comment ? String(d.comment) : (d?.rationale ? String(d.rationale) : undefined),
    }));
    return { rows };
  }
  const table = parseCsv(text);
  if (table.length < 2) return { rows: [], error: "CSV needs a header row and at least one data row." };
  const header = table[0].map((h) => h.trim());
  const lc = header.map((h) => h.toLowerCase());
  const keyIdx = lc.indexOf("recordkey");
  if (keyIdx < 0) return { rows: [], error: "CSV must have a 'recordKey' column." };
  const jurIdx = lc.indexOf("jurisdiction");
  const comIdx = lc.indexOf("comment") >= 0 ? lc.indexOf("comment") : lc.indexOf("rationale");
  const payloadCols = header
    .map((h, i) => ({ h, i }))
    .filter(({ h, i }) => i !== keyIdx && i !== jurIdx && i !== comIdx && h.length);
  const rows: BulkRow[] = table.slice(1).map((cells) => {
    const payload: Record<string, any> = {};
    for (const { h, i } of payloadCols) {
      const v = (cells[i] ?? "").trim();
      if (v !== "") payload[h] = coerceCell(v);
    }
    const r: BulkRow = { recordKey: (cells[keyIdx] ?? "").trim(), payload };
    if (jurIdx >= 0 && (cells[jurIdx] ?? "").trim()) r.jurisdiction = cells[jurIdx].trim();
    if (comIdx >= 0 && (cells[comIdx] ?? "").trim()) r.comment = cells[comIdx].trim();
    return r;
  });
  return { rows };
}

type RowResult = { ok: boolean; id?: number; status?: string; error?: string };

export function BulkUpload({ type, actor, notify, onDone }: {
  type: string; actor: string; notify: (t: string, e?: boolean) => void; onDone: () => void;
}) {
  const [mode, setMode] = useState<"csv" | "json">("csv");
  const [text, setText] = useState("");
  const [rows, setRows] = useState<BulkRow[]>([]);
  const [parseError, setParseError] = useState<string>("");
  const [results, setResults] = useState<RowResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const isJson = file.name.toLowerCase().endsWith(".json");
    setMode(isJson ? "json" : "csv");
    const reader = new FileReader();
    reader.onload = () => { setText(String(reader.result || "")); setRows([]); setResults(null); };
    reader.readAsText(file);
  }

  function preview() {
    const { rows: parsed, error } = parseBulk(text, mode);
    setParseError(error || "");
    setRows(parsed);
    setResults(null);
  }

  const invalidRows = rows.filter((r) => !r.recordKey.trim());
  const canSubmit = rows.length > 0 && invalidRows.length === 0 && !busy;

  async function submitAll() {
    setBusy(true);
    const out: RowResult[] = [];
    for (const r of rows) {
      try {
        const body: any = { recordKey: r.recordKey.trim(), payload: r.payload };
        if (r.jurisdiction) body.jurisdiction = r.jurisdiction;
        if (r.comment) body.comment = r.comment;
        const m = await masters.submit(type, body, actor);
        out.push({ ok: true, id: m.id, status: m.status });
      } catch (e: any) {
        out.push({ ok: false, error: e.message || "failed" });
      }
    }
    setResults(out);
    setBusy(false);
    const ok = out.filter((o) => o.ok).length;
    notify(`Bulk submit: ${ok}/${out.length} row(s) queued for approval`);
    if (ok > 0) onDone();
  }

  const csvSample = `recordKey,jurisdiction,criticality,enabled\nCHEQUE_RETURNS,,HIGH,true\nGST_MISMATCH,IN-RBI,MEDIUM,true`;
  const jsonSample = `[\n  { "recordKey": "CHEQUE_RETURNS", "payload": { "enabled": true, "criticality": "HIGH" } }\n]`;

  return (
    <div>
      <div className="btnrow" style={{ marginBottom: 8, gap: 6 }}>
        <button className={`btn ${mode === "csv" ? "" : "subtle"}`} style={{ fontSize: 11 }}
          onClick={() => setMode("csv")}>CSV</button>
        <button className={`btn ${mode === "json" ? "" : "subtle"}`} style={{ fontSize: 11 }}
          onClick={() => setMode("json")}>JSON</button>
        <label className="btn subtle" style={{ fontSize: 11, cursor: "pointer" }}>
          Choose file…
          <input type="file" accept=".csv,.json,text/csv,application/json" style={{ display: "none" }} onChange={onFile} />
        </label>
        <button className="btn subtle" style={{ fontSize: 11 }}
          onClick={() => { setText(mode === "csv" ? csvSample : jsonSample); setRows([]); setResults(null); }}>
          Load sample
        </button>
      </div>

      <Field label={mode === "csv" ? "Paste CSV (header row + rows; a recordKey column is required, other columns become payload keys)"
        : "Paste JSON (array of { recordKey, payload, jurisdiction?, comment? })"}
        error={parseError || undefined}>
        <textarea rows={7} value={text} onChange={(e) => setText(e.target.value)}
          placeholder={mode === "csv" ? csvSample : jsonSample}
          style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 12 }} />
      </Field>

      <div className="btnrow">
        <Button kind="ghost" onClick={preview}>Parse &amp; preview</Button>
        <Button kind="primary" busy={busy} disabled={!canSubmit}
          onClick={submitAll}>Submit {rows.length || ""} row(s) for approval</Button>
        {invalidRows.length > 0 && <Badge kind="bad">{invalidRows.length} row(s) missing recordKey</Badge>}
      </div>

      {rows.length > 0 && (
        <div className="table-scroll" style={{ marginTop: 10 }}>
          <table>
            <thead><tr><th>#</th><th>Record key</th><th>Juris.</th><th>Payload</th><th>Rationale</th>{results && <th>Result</th>}</tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  <td className="mono">{i + 1}</td>
                  <td className="mono">{r.recordKey || <Badge kind="bad">missing</Badge>}</td>
                  <td className="mono"><small>{r.jurisdiction || "—"}</small></td>
                  <td><PayloadView payload={r.payload} /></td>
                  <td><small className="muted">{r.comment || "—"}</small></td>
                  {results && (
                    <td>
                      {results[i]
                        ? (results[i].ok
                          ? <Badge kind="ok">#{results[i].id} {results[i].status}</Badge>
                          : <Badge kind="bad" >{results[i].error}</Badge>)
                        : "—"}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
