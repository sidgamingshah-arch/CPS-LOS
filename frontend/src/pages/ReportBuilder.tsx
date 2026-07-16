/**
 * Ad-hoc report builder — pick a dataset, drag fields into dimensions / measures
 * / filters, preview live. Save submits a REPORT_DEFINITION master draft
 * (maker-checker via the existing master engine). Figures are deterministic —
 * the engine aggregates the system-of-record book and stamps a SYSTEM audit
 * event on every run.
 */
import { useEffect, useMemo, useState } from "react";
import { masters, reports, ReportDefinition, ReportResult } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, DeterministicBadge, EmptyState, Field, GovFlow,
  HumanBadge, useAsync,
} from "../ui";
import { useCodes } from "../code-values";

type FieldMeta = { name: string; type: string; dimension: boolean; measure: boolean };
type DatasetMeta = {
  key: string; label: string; fields: FieldMeta[];
  aggregations: string[]; stringOps: string[]; numberOps: string[];
};
type FilterRow = { field: string; op: string; value: string };
type MeasureRow = { field: string; agg: string; as: string };

const NUMERIC = (t: string) => t === "NUMBER" || t === "INT";

function parseFilterValue(op: string, raw: string, field: FieldMeta | undefined) {
  if (op === "IN" || op === "NOT_IN") {
    return raw.split(",").map((s) => s.trim()).filter(Boolean)
      .map((s) => (field && NUMERIC(field.type) ? Number(s) : s));
  }
  if (op === "BETWEEN") {
    const parts = raw.split(",").map((s) => s.trim()).map(Number);
    return parts.length >= 2 ? [parts[0], parts[1]] : [0, 0];
  }
  if (field && NUMERIC(field.type)) return Number(raw);
  return raw;
}

export default function ReportBuilder() {
  const { actor, notify } = useApp();
  const { data: dsList, loading } = useAsync(() => reports.datasets(), []);
  const sortDirections = useCodes("SORT_DIRECTION");
  const [datasetKey, setDatasetKey] = useState<string>("");
  const [title, setTitle] = useState("Ad-hoc report");
  const [dimensions, setDimensions] = useState<string[]>([]);
  const [measures, setMeasures] = useState<MeasureRow[]>(
    [{ field: "*", agg: "COUNT", as: "deals" }],
  );
  const [filters, setFilters] = useState<FilterRow[]>([]);
  const [sortBy, setSortBy] = useState<string>("");
  const [sortDir, setSortDir] = useState<"ASC" | "DESC">("DESC");
  const [limit, setLimit] = useState<number>(100);
  const [result, setResult] = useState<ReportResult | null>(null);
  const [running, setRunning] = useState(false);
  const [recordKey, setRecordKey] = useState("");

  useEffect(() => {
    if (!datasetKey && (dsList || []).length > 0) setDatasetKey((dsList as any)[0].key);
  }, [dsList, datasetKey]);

  const dataset: DatasetMeta | null = useMemo(
    () => (dsList || []).find((d: any) => d.key === datasetKey) || null,
    [dsList, datasetKey],
  );

  const fieldByName = (n: string) => dataset?.fields.find((f) => f.name === n);

  function buildDefinition(): ReportDefinition {
    return {
      title,
      dataset: datasetKey,
      dimensions: dimensions,
      measures: measures.map((m) => ({ field: m.field, agg: m.agg, as: m.as || `${m.agg}_${m.field}` })),
      filters: filters.map((f) => ({
        field: f.field,
        op: f.op,
        value: parseFilterValue(f.op, f.value, fieldByName(f.field)),
      })),
      sort: sortBy ? [{ by: sortBy, dir: sortDir }] : [],
      limit,
    };
  }

  async function run() {
    if (!datasetKey) return;
    try {
      setRunning(true);
      const r = await reports.run(buildDefinition(), actor);
      setResult(r);
    } catch (e: any) {
      notify(e.message || "Run failed", true);
    } finally { setRunning(false); }
  }

  async function saveDefinition() {
    const key = recordKey.trim();
    if (!key) { notify("Provide a record key (e.g. sub_ig_by_segment)", true); return; }
    try {
      const m = await masters.submit("REPORT_DEFINITION", {
        recordKey: key,
        payload: buildDefinition(),
      }, actor);
      notify(`Saved as REPORT_DEFINITION/${key} (PENDING_APPROVAL · id ${m.id})`);
    } catch (e: any) { notify(e.message || "Save failed", true); }
  }

  if (loading) return <div className="loading">Loading datasets…</div>;
  if (!dsList || dsList.length === 0)
    return <EmptyState title="No datasets registered" sub="Add a DatasetSpec entry in portfolio-service to expose more."/>;

  const dims = (dataset?.fields || []).filter((f) => f.dimension);
  const metricFields = (dataset?.fields || []).filter((f) => f.measure);

  return (
    <div className="grid">
      <div className="gov-banner">
        <strong>AD-HOC REPORT BUILDER</strong>
        <span style={{ marginLeft: 12 }}>
          Pick fields from a <em>whitelisted</em> dataset; the engine aggregates
          the system-of-record book and never mutates a figure. Saved
          definitions go through master maker-checker.
        </span>
        <span style={{ marginLeft: 16 }}>
          <GovFlow ai="USER · DEFINES" human="HUMAN · APPROVES SAVED DEF" note="FIGURES DETERMINISTIC"/>
        </span>
      </div>

      <Card title="Builder" sub="Live preview reruns on each Run; nothing is persisted until you Save.">
        <div className="grid cols-2">
          <Field label="Dataset">
            <select className="select" value={datasetKey} onChange={(e) => {
              setDatasetKey(e.target.value);
              setDimensions([]); setMeasures([{ field: "*", agg: "COUNT", as: "deals" }]);
              setFilters([]); setSortBy(""); setResult(null);
            }}>
              {(dsList || []).map((d: any) => <option key={d.key} value={d.key}>{d.label}</option>)}
            </select>
          </Field>
          <Field label="Title">
            <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
          </Field>
        </div>

        <h4 style={{ marginTop: 12 }}>Dimensions <span className="sub">(group by)</span></h4>
        <div className="btnrow">
          {dims.map((f) => (
            <button key={f.name}
              className={`btn ${dimensions.includes(f.name) ? "" : "subtle"}`}
              onClick={() => setDimensions((d) => d.includes(f.name) ? d.filter((x) => x !== f.name) : [...d, f.name])}>
              {f.name} <span className="sub" style={{ marginLeft: 4 }}>{f.type}</span>
            </button>
          ))}
        </div>

        <h4 style={{ marginTop: 12 }}>Measures <span className="sub">(aggregations)</span></h4>
        {measures.map((m, i) => (
          <div className="btnrow" key={i} style={{ gap: 6, marginBottom: 4 }}>
            <select className="select" value={m.agg}
                    onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, agg: e.target.value }; setMeasures(arr); }}>
              {(dataset?.aggregations || []).map((a) => <option key={a}>{a}</option>)}
            </select>
            <select className="select" value={m.field}
                    onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, field: e.target.value }; setMeasures(arr); }}>
              <option value="*">*</option>
              {metricFields.map((f) => <option key={f.name} value={f.name}>{f.name}</option>)}
            </select>
            <input className="input" placeholder="as (column name)" value={m.as}
                   onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, as: e.target.value }; setMeasures(arr); }} />
            <Button kind="subtle" onClick={() => setMeasures(measures.filter((_, j) => j !== i))}>−</Button>
          </div>
        ))}
        <Button kind="subtle"
                onClick={() => setMeasures([...measures, { field: "ead", agg: "SUM", as: "total" }])}>
          + Measure
        </Button>

        <h4 style={{ marginTop: 12 }}>Filters</h4>
        {filters.map((f, i) => {
          const fld = fieldByName(f.field);
          const ops = fld && NUMERIC(fld.type)
            ? (dataset?.numberOps || [])
            : (dataset?.stringOps || []);
          return (
            <div className="btnrow" key={i} style={{ gap: 6, marginBottom: 4 }}>
              <select className="select" value={f.field}
                      onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, field: e.target.value }; setFilters(arr); }}>
                <option value="">— pick —</option>
                {(dataset?.fields || []).map((x) => <option key={x.name} value={x.name}>{x.name}</option>)}
              </select>
              <select className="select" value={f.op}
                      onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, op: e.target.value }; setFilters(arr); }}>
                {ops.map((o) => <option key={o}>{o}</option>)}
              </select>
              <input className="input" placeholder="value (CSV for IN/BETWEEN)" value={f.value}
                     onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, value: e.target.value }; setFilters(arr); }} />
              <Button kind="subtle" onClick={() => setFilters(filters.filter((_, j) => j !== i))}>−</Button>
            </div>
          );
        })}
        <Button kind="subtle"
                onClick={() => setFilters([...filters, { field: dataset?.fields[0]?.name || "", op: "EQ", value: "" }])}>
          + Filter
        </Button>

        <h4 style={{ marginTop: 12 }}>Sort & limit</h4>
        <div className="btnrow" style={{ gap: 6 }}>
          <select className="select" value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
            <option value="">(no sort)</option>
            {measures.map((m) => <option key={m.as} value={m.as}>{m.as}</option>)}
            {dimensions.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
          <select className="select" value={sortDir} onChange={(e) => setSortDir(e.target.value as any)}>
            {sortDirections.map((d) => <option key={d.code} value={d.code}>{d.label}</option>)}
          </select>
          <input className="input" type="number" value={limit} min={0} max={5000}
                 onChange={(e) => setLimit(Number(e.target.value))} style={{ width: 90 }}/>
          <Badge>limit</Badge>
        </div>

        <div className="btnrow" style={{ marginTop: 14 }}>
          <Button kind="primary" busy={running} onClick={run}>Run preview</Button>
          <input className="input" placeholder="recordKey (for save)" value={recordKey}
                 onChange={(e) => setRecordKey(e.target.value)} style={{ width: 220 }}/>
          <Button kind="subtle" onClick={saveDefinition}>
            Save as REPORT_DEFINITION (maker-checker)
          </Button>
        </div>
      </Card>

      {result && (
        <Card title="Result" sub={`${result.returnedRows} of ${result.scannedRows} rows · deterministic — read-only.`}
              right={<DeterministicBadge label="DETERMINISTIC"/>}>
          {result.rows.length === 0
            ? <EmptyState title="No rows" sub="Filters returned an empty set."/>
            : (
              <div className="table-scroll">
                <table className="table">
                  <thead><tr>{result.columns.map((c) => (
                    <th key={c.key}>{c.label} <span className="sub">{c.type}</span></th>
                  ))}</tr></thead>
                  <tbody>
                    {result.rows.map((r, i) => (
                      <tr key={i}>{r.map((v, j) => (
                        <td key={j} className={result.columns[j].role === "MEASURE" ? "mono" : ""}>
                          {typeof v === "number" ? v.toLocaleString() : (v ?? "—")}
                        </td>
                      ))}</tr>
                    ))}
                  </tbody>
                  {Object.keys(result.totals || {}).length > 0 && (
                    <tfoot>
                      <tr>
                        {result.columns.map((c, j) =>
                          c.role === "MEASURE"
                            ? <td key={j} className="mono"><strong>{typeof result.totals[c.key] === "number"
                                ? (result.totals[c.key] as number).toLocaleString()
                                : (result.totals[c.key] ?? "—")}</strong></td>
                            : <td key={j}>{j === 0 ? <strong>Totals</strong> : ""}</td>)}
                      </tr>
                    </tfoot>
                  )}
                </table>
              </div>
            )}
        </Card>
      )}
    </div>
  );
}
