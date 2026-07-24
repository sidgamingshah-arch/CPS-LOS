/**
 * Ad-hoc report builder — pick a dataset, then DRAG fields from the palette into
 * the Rows / Measures / Filters zones (or use the keyboard "add" affordances).
 * Reorder within a zone by dragging; remove by dragging a chip back onto the
 * palette or clicking its ×. Run previews live. Save submits a REPORT_DEFINITION
 * master draft (maker-checker via the existing master engine). Figures are
 * deterministic — the engine aggregates the system-of-record book and stamps a
 * SYSTEM audit event on every run.
 *
 * The drag-and-drop is purely a nicer way to assemble the SAME query object the
 * backend already accepts (see buildDefinition) — the report run/save payloads
 * are unchanged.
 */
import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { masters, reports, ReportDefinition, ReportResult } from "../api";
import { useApp } from "../app-context";
import {
  Badge, Button, Card, DeterministicBadge, EmptyState, Field, GovFlow, useAsync,
} from "../ui";
import { useCodes } from "../code-values";

type FieldMeta = { name: string; type: string; dimension: boolean; measure: boolean };
type DatasetMeta = {
  key: string; label: string; fields: FieldMeta[];
  aggregations: string[]; stringOps: string[]; numberOps: string[];
};
type FilterRow = { field: string; op: string; value: string };
type MeasureRow = { field: string; agg: string; as: string };

type Zone = "dim" | "measure" | "filter";
// The payload carried during an HTML5 drag: either a field from the palette, or
// an existing item within a zone (for reorder / drag-out-to-remove).
type DragItem = { source: "palette"; field: string } | { source: Zone; index: number };

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

// ---- pure array helpers for drag insert / reorder ----
function insertAt<T>(arr: T[], index: number, item: T): T[] {
  const copy = arr.slice();
  copy.splice(Math.max(0, Math.min(index, copy.length)), 0, item);
  return copy;
}
function arrayMove<T>(arr: T[], from: number, to: number): T[] {
  const copy = arr.slice();
  const [item] = copy.splice(from, 1);
  const target = from < to ? to - 1 : to; // account for the removal shift
  copy.splice(Math.max(0, Math.min(target, copy.length)), 0, item);
  return copy;
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

  // ---- drag-and-drop state ----
  // dragRef is the authoritative payload read on drop; the two states drive the
  // visual affordances (insertion caret + "drop to remove" hint).
  const dragRef = useRef<DragItem | null>(null);
  const [draggingItem, setDraggingItem] = useState(false);
  const [dropHint, setDropHint] = useState<{ zone: Zone; index: number } | null>(null);

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

  // ---- zone mutations (shared by drag and the keyboard "add" buttons) ----
  function addToZone(zone: Zone, field: string, index: number) {
    const f = fieldByName(field);
    if (zone === "dim") {
      if (!f?.dimension) { notify(`"${field}" is a measure, not a dimension`, true); return; }
      setDimensions((prev) => (prev.includes(field) ? prev : insertAt(prev, index, field)));
    } else if (zone === "measure") {
      if (!f?.measure) { notify(`"${field}" can't be aggregated as a measure`, true); return; }
      const agg = f && NUMERIC(f.type) ? "SUM" : "COUNT";
      setMeasures((prev) => insertAt(prev, index, { field, agg, as: `${agg.toLowerCase()}_${field}` }));
    } else {
      const op = f && NUMERIC(f.type) ? (dataset?.numberOps?.[0] || "EQ") : (dataset?.stringOps?.[0] || "EQ");
      setFilters((prev) => insertAt(prev, index, { field, op, value: "" }));
    }
  }

  function reorder(zone: Zone, from: number, to: number) {
    if (from === to) return;
    if (zone === "dim") setDimensions((prev) => arrayMove(prev, from, to));
    else if (zone === "measure") setMeasures((prev) => arrayMove(prev, from, to));
    else setFilters((prev) => arrayMove(prev, from, to));
  }

  function removeItem(zone: Zone, index: number) {
    if (zone === "dim") setDimensions((prev) => prev.filter((_, i) => i !== index));
    else if (zone === "measure") setMeasures((prev) => prev.filter((_, i) => i !== index));
    else setFilters((prev) => prev.filter((_, i) => i !== index));
  }

  // ---- drag lifecycle ----
  function startPaletteDrag(e: React.DragEvent, field: string) {
    dragRef.current = { source: "palette", field };
    setDraggingItem(false);
    e.dataTransfer.effectAllowed = "copy";
    e.dataTransfer.setData("text/plain", field); // Firefox requires a payload to start a drag
  }
  function startItemDrag(e: React.DragEvent, zone: Zone, index: number) {
    dragRef.current = { source: zone, index };
    setDraggingItem(true);
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", `${zone}:${index}`);
  }
  function clearDrag() {
    dragRef.current = null;
    setDraggingItem(false);
    setDropHint(null);
  }

  function onZoneOver(e: React.DragEvent, zone: Zone, len: number) {
    if (!dragRef.current) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = dragRef.current.source === "palette" ? "copy" : "move";
    // Default to appending; an item's own handler (which stops propagation) refines the index.
    setDropHint((h) => (h && h.zone === zone && h.index === len ? h : { zone, index: len }));
  }
  function onItemOver(e: React.DragEvent, zone: Zone, index: number) {
    if (!dragRef.current) return;
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = dragRef.current.source === "palette" ? "copy" : "move";
    setDropHint({ zone, index });
  }
  function onZoneDrop(e: React.DragEvent, zone: Zone, index: number) {
    e.preventDefault();
    e.stopPropagation();
    const d = dragRef.current;
    clearDrag();
    if (!d) return;
    if (d.source === "palette") addToZone(zone, d.field, index);
    else if (d.source === zone) reorder(zone, d.index, index);
    // cross-zone item drops are intentionally ignored (type constraints differ per zone).
  }
  function onPaletteDrop(e: React.DragEvent) {
    e.preventDefault();
    const d = dragRef.current;
    clearDrag();
    if (!d || d.source === "palette") return;
    removeItem(d.source, d.index); // dragged a zone chip back onto the palette → remove
  }

  if (loading) return <div className="loading">Loading datasets…</div>;
  if (!dsList || dsList.length === 0)
    return <EmptyState title="No datasets registered" sub="Add a DatasetSpec entry in portfolio-service to expose more." />;

  const fields = dataset?.fields || [];

  // A thin insertion caret rendered before item `i` (or at the end) of a zone
  // while a compatible drag hovers it.
  const caretAt = (zone: Zone, i: number) =>
    dropHint && dropHint.zone === zone && dropHint.index === i
      ? <div className={`rb-caret ${zone === "dim" ? "v" : "h"}`} aria-hidden="true" />
      : null;

  const typeTag = (t: string) => <span className={`rb-type ${NUMERIC(t) ? "num" : "str"}`}>{t}</span>;

  return (
    <div className="grid">
      <div className="gov-banner">
        <strong>AD-HOC REPORT BUILDER</strong>
        <span style={{ marginLeft: 12 }}>
          Drag fields from a <em>whitelisted</em> dataset into the zones; the engine
          aggregates the system-of-record book and never mutates a figure. Saved
          definitions go through master maker-checker.
        </span>
        <span style={{ marginLeft: 16 }}>
          <GovFlow ai="USER · DEFINES" human="HUMAN · APPROVES SAVED DEF" note="FIGURES DETERMINISTIC" />
        </span>
      </div>

      <Card title="Builder" sub="Drag fields into the zones (or use the ＋ buttons). Live preview reruns on each Run; nothing is persisted until you Save.">
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

        <div className="rb-grid">
          {/* ---- Fields palette (drag source · drop-to-remove target) ---- */}
          <div
            className={`rb-palette${draggingItem ? " removable" : ""}`}
            onDragOver={(e) => { if (draggingItem) { e.preventDefault(); e.dataTransfer.dropEffect = "move"; } }}
            onDrop={onPaletteDrop}
          >
            <div className="rb-palette-head">
              <span>Fields</span>
              <span className="sub">{fields.length} available</span>
            </div>
            <div className="rb-palette-list">
              {fields.map((f) => (
                <div
                  key={f.name}
                  className="rb-field"
                  draggable
                  onDragStart={(e) => startPaletteDrag(e, f.name)}
                  onDragEnd={clearDrag}
                  title="Drag into a zone — or use the ＋ buttons"
                >
                  <span className="rb-grip" aria-hidden="true">⠿</span>
                  <span className="rb-field-name">{f.name}</span>
                  {typeTag(f.type)}
                  <span className="rb-field-add">
                    {f.dimension && (
                      <button type="button" className="rb-add" title="Add to Rows / Dimensions"
                        aria-label={`Add ${f.name} to rows / dimensions`}
                        onClick={() => addToZone("dim", f.name, dimensions.length)}>＋Row</button>
                    )}
                    {f.measure && (
                      <button type="button" className="rb-add" title="Add to Measures"
                        aria-label={`Add ${f.name} to measures`}
                        onClick={() => addToZone("measure", f.name, measures.length)}>＋Σ</button>
                    )}
                    <button type="button" className="rb-add" title="Add to Filters"
                      aria-label={`Add ${f.name} to filters`}
                      onClick={() => addToZone("filter", f.name, filters.length)}>＋Filter</button>
                  </span>
                </div>
              ))}
            </div>
            <div className="rb-palette-foot">
              {draggingItem ? "Drop here to remove" : "Drag a field right, or click ＋"}
            </div>
          </div>

          {/* ---- Drop zones ---- */}
          <div className="rb-zones">
            {/* Rows / Dimensions */}
            <div
              className={`rb-zone rb-zone-dims${dropHint?.zone === "dim" ? " over" : ""}`}
              onDragOver={(e) => onZoneOver(e, "dim", dimensions.length)}
              onDrop={(e) => onZoneDrop(e, "dim", dropHint?.zone === "dim" ? dropHint.index : dimensions.length)}
            >
              <div className="rb-zone-head">
                <span className="rb-zone-dot dim" aria-hidden="true" />
                <strong>Rows / Dimensions</strong>
                <span className="sub">group by</span>
                <Badge>{dimensions.length}</Badge>
              </div>
              <div className="rb-zone-body dims">
                {dimensions.length === 0 && <div className="rb-empty">Drag dimension fields here to group rows.</div>}
                {dimensions.map((name, i) => (
                  <Fragment key={name}>
                    {caretAt("dim", i)}
                    <div
                      className="rb-chip"
                      draggable
                      onDragStart={(e) => startItemDrag(e, "dim", i)}
                      onDragEnd={clearDrag}
                      onDragOver={(e) => onItemOver(e, "dim", i)}
                      onDrop={(e) => onZoneDrop(e, "dim", i)}
                    >
                      <span className="rb-grip" aria-hidden="true">⠿</span>
                      <span className="rb-field-name">{name}</span>
                      {typeTag(fieldByName(name)?.type || "STRING")}
                      <button type="button" className="rb-x" aria-label={`Remove ${name}`}
                        onClick={() => removeItem("dim", i)}>×</button>
                    </div>
                  </Fragment>
                ))}
                {caretAt("dim", dimensions.length)}
              </div>
            </div>

            {/* Measures */}
            <div
              className={`rb-zone rb-zone-msr${dropHint?.zone === "measure" ? " over" : ""}`}
              onDragOver={(e) => onZoneOver(e, "measure", measures.length)}
              onDrop={(e) => onZoneDrop(e, "measure", dropHint?.zone === "measure" ? dropHint.index : measures.length)}
            >
              <div className="rb-zone-head">
                <span className="rb-zone-dot msr" aria-hidden="true" />
                <strong>Measures</strong>
                <span className="sub">aggregations</span>
                <Badge>{measures.length}</Badge>
              </div>
              <div className="rb-zone-body">
                {measures.length === 0 && <div className="rb-empty">Drag numeric fields here, or add a COUNT(*).</div>}
                {measures.map((m, i) => (
                  <Fragment key={i}>
                    {caretAt("measure", i)}
                    <div
                      className="rb-row"
                      onDragOver={(e) => onItemOver(e, "measure", i)}
                      onDrop={(e) => onZoneDrop(e, "measure", i)}
                    >
                      <span className="rb-grip" draggable
                        onDragStart={(e) => startItemDrag(e, "measure", i)} onDragEnd={clearDrag}
                        title="Drag to reorder" aria-label="Drag to reorder">⠿</span>
                      <select className="select" value={m.agg}
                        onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, agg: e.target.value }; setMeasures(arr); }}>
                        {(dataset?.aggregations || []).map((a) => <option key={a}>{a}</option>)}
                      </select>
                      <select className="select" value={m.field}
                        onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, field: e.target.value }; setMeasures(arr); }}>
                        <option value="*">*</option>
                        {fields.filter((f) => f.measure).map((f) => <option key={f.name} value={f.name}>{f.name}</option>)}
                      </select>
                      <input className="input" placeholder="as (column name)" value={m.as}
                        onChange={(e) => { const arr = [...measures]; arr[i] = { ...m, as: e.target.value }; setMeasures(arr); }} />
                      <button type="button" className="rb-x" aria-label="Remove measure"
                        onClick={() => setMeasures(measures.filter((_, j) => j !== i))}>×</button>
                    </div>
                  </Fragment>
                ))}
                {caretAt("measure", measures.length)}
                <div className="rb-zone-actions">
                  <Button kind="subtle" onClick={() => setMeasures([...measures, { field: "*", agg: "COUNT", as: "count" }])}>
                    ＋ COUNT(*)
                  </Button>
                </div>
              </div>
            </div>

            {/* Filters */}
            <div
              className={`rb-zone rb-zone-flt${dropHint?.zone === "filter" ? " over" : ""}`}
              onDragOver={(e) => onZoneOver(e, "filter", filters.length)}
              onDrop={(e) => onZoneDrop(e, "filter", dropHint?.zone === "filter" ? dropHint.index : filters.length)}
            >
              <div className="rb-zone-head">
                <span className="rb-zone-dot flt" aria-hidden="true" />
                <strong>Filters</strong>
                <span className="sub">where</span>
                <Badge>{filters.length}</Badge>
              </div>
              <div className="rb-zone-body">
                {filters.length === 0 && <div className="rb-empty">Drag any field here to filter the book.</div>}
                {filters.map((f, i) => {
                  const fld = fieldByName(f.field);
                  const ops = fld && NUMERIC(fld.type) ? (dataset?.numberOps || []) : (dataset?.stringOps || []);
                  return (
                    <Fragment key={i}>
                      {caretAt("filter", i)}
                      <div
                        className="rb-row"
                        onDragOver={(e) => onItemOver(e, "filter", i)}
                        onDrop={(e) => onZoneDrop(e, "filter", i)}
                      >
                        <span className="rb-grip" draggable
                          onDragStart={(e) => startItemDrag(e, "filter", i)} onDragEnd={clearDrag}
                          title="Drag to reorder" aria-label="Drag to reorder">⠿</span>
                        <select className="select" value={f.field}
                          onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, field: e.target.value }; setFilters(arr); }}>
                          <option value="">— pick —</option>
                          {fields.map((x) => <option key={x.name} value={x.name}>{x.name}</option>)}
                        </select>
                        <select className="select" value={f.op}
                          onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, op: e.target.value }; setFilters(arr); }}>
                          {ops.map((o) => <option key={o}>{o}</option>)}
                        </select>
                        <input className="input" placeholder="value (CSV for IN/BETWEEN)" value={f.value}
                          onChange={(e) => { const arr = [...filters]; arr[i] = { ...f, value: e.target.value }; setFilters(arr); }} />
                        <button type="button" className="rb-x" aria-label="Remove filter"
                          onClick={() => setFilters(filters.filter((_, j) => j !== i))}>×</button>
                      </div>
                    </Fragment>
                  );
                })}
                {caretAt("filter", filters.length)}
                <div className="rb-zone-actions">
                  <Button kind="subtle"
                    onClick={() => setFilters([...filters, { field: dataset?.fields[0]?.name || "", op: "EQ", value: "" }])}>
                    ＋ Blank filter
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <h4 style={{ marginTop: 16 }}>Sort &amp; limit</h4>
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
            onChange={(e) => setLimit(Number(e.target.value))} style={{ width: 90 }} />
          <Badge>limit</Badge>
        </div>

        <div className="btnrow" style={{ marginTop: 14 }}>
          <Button kind="primary" busy={running} onClick={run}>Run preview</Button>
          <input className="input" placeholder="recordKey (for save)" value={recordKey}
            onChange={(e) => setRecordKey(e.target.value)} style={{ width: 220 }} />
          <Button kind="subtle" onClick={saveDefinition}>
            Save as REPORT_DEFINITION (maker-checker)
          </Button>
        </div>
      </Card>

      {result && (
        <Card title="Result" sub={`${result.returnedRows} of ${result.scannedRows} rows · deterministic — read-only.`}
          right={<DeterministicBadge label="DETERMINISTIC" />}>
          {result.rows.length === 0
            ? <EmptyState title="No rows" sub="Filters returned an empty set." />
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
