import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";

export function Card({ title, sub, right, children }: {
  title?: string; sub?: string; right?: React.ReactNode; children: React.ReactNode;
}) {
  return (
    <div className="card">
      {(title || right) && (
        <div className="flexbetween">
          <div>{title && <h3>{title}</h3>}{sub && <div className="sub">{sub}</div>}</div>
          {right}
        </div>
      )}
      {children}
    </div>
  );
}

export function Stat({ label, value, delta, tone }: { label: string; value: React.ReactNode; delta?: string; tone?: string }) {
  return (
    <div className="card stat">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {delta && <div className="delta" style={{ color: tone }}>{delta}</div>}
    </div>
  );
}

export function Badge({ kind = "", children }: { kind?: string; children: React.ReactNode }) {
  return <span className={`badge ${kind}`}>{children}</span>;
}

const GRADE_TONE: Record<string, string> = {
  AAA: "ok", AA: "ok", A: "ok", BBB: "ok", BB: "warn", B: "warn", CCC: "bad", CC: "bad", C: "bad", D: "bad",
};
export function GradeBadge({ grade }: { grade?: string }) {
  if (!grade) return <span className="muted">—</span>;
  return <span className={`badge grade ${GRADE_TONE[grade] || ""}`}>{grade}</span>;
}

export function statusTone(status?: string): string {
  if (!status) return "";
  const s = status.toUpperCase();
  if (["VERIFIED", "APPROVED", "DECIDED", "STANDARD", "STAGE_1", "OK", "STRONG"].includes(s)) return "ok";
  if (["DECLINED", "REJECTED", "STAGE_3", "SEVERE", "LOSS", "EXIT"].includes(s)) return "bad";
  if (["PENDING_APPROVAL", "RE_KYC_DUE", "STAGE_2", "HIGH", "ESCALATED", "CONDITIONAL_APPROVE", "OPEN"].includes(s)) return "warn";
  return "info";
}

export function Button({ children, onClick, kind = "", disabled, busy }: {
  children: React.ReactNode; onClick?: () => void; kind?: string; disabled?: boolean; busy?: boolean;
}) {
  return (
    <button className={`btn ${kind}`} onClick={onClick} disabled={disabled || busy}>
      {busy ? "Working…" : children}
    </button>
  );
}

export function Field({ label, children, required, hint, error }: {
  label: string; children: React.ReactNode; required?: boolean; hint?: string; error?: string | null;
}) {
  // Stable, collision-free ids so the label / hint / error associate with the
  // control for assistive tech. Additive only — the visual output is unchanged.
  const rid = useId();
  const hintId = hint ? `${rid}-hint` : undefined;
  const errorId = error ? `${rid}-err` : undefined;
  const describedBy = error ? errorId : hintId;

  // If the control is a single element (input/select/textarea/etc.) we clone it to
  // inject an id + aria-invalid + aria-describedby, preserving any props it already
  // has. Fragments / multiple / non-element children fall back to the wrapping
  // label's implicit association (still accessible), with no htmlFor.
  let control = children;
  let forId: string | undefined;
  if (React.isValidElement(children) && children.type !== React.Fragment) {
    const cp = children.props as Record<string, unknown>;
    forId = (cp.id as string | undefined) ?? `${rid}-ctl`;
    const mergedDescribedBy =
      [cp["aria-describedby"] as string | undefined, describedBy].filter(Boolean).join(" ") || undefined;
    control = React.cloneElement(children as React.ReactElement<Record<string, unknown>>, {
      id: forId,
      "aria-invalid": error ? true : cp["aria-invalid"],
      "aria-describedby": mergedDescribedBy,
    });
  }

  return (
    <label className={`field${error ? " has-error" : ""}`} htmlFor={forId}>
      <span className="lbl">{label}{required && <span className="req" aria-hidden="true"> *</span>}</span>
      {control}
      {error
        ? <span className="field-error" id={errorId} role="alert">{error}</span>
        : hint && <span className="field-hint" id={hintId}>{hint}</span>}
    </label>
  );
}

/** A friendly placeholder for a screen (or card) that needs a selection/action first. */
export function EmptyState({ glyph = "◴", title, sub, action }: {
  glyph?: string; title: string; sub?: string; action?: React.ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-glyph" aria-hidden="true">{glyph}</div>
      <div className="empty-title">{title}</div>
      {sub && <div className="empty-sub">{sub}</div>}
      {action && <div style={{ marginTop: 10 }}>{action}</div>}
    </div>
  );
}

/** Async data hook with a manual refresh trigger. */
export function useAsync<T>(fn: () => Promise<T>, deps: any[] = []): {
  data: T | null; error: string | null; loading: boolean; reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    fn().then((d) => { if (alive) { setData(d); setError(null); } })
      .catch((e) => { if (alive) setError(e.message); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, loading, reload };
}

export function Toast({ msg, onClose }: { msg: { text: string; err?: boolean } | null; onClose: () => void }) {
  useEffect(() => {
    if (msg) { const t = setTimeout(onClose, 4200); return () => clearTimeout(t); }
  }, [msg, onClose]);
  if (!msg) return null;
  return (
    <div
      className={`toast ${msg.err ? "err" : ""}`}
      role={msg.err ? "alert" : "status"}
      aria-live={msg.err ? "assertive" : "polite"}
    >
      {msg.text}
    </div>
  );
}

/* ---- Governance design language ----
   The product's spine: AI where it helps · Humans where regulation demands ·
   Deterministic figures throughout. These badges are used consistently wherever an
   action is AI-generated, human-gated, or a deterministic figure that AI never touches. */

export function AiBadge({ label = "AI · ADVISORY" }: { label?: string }) {
  return <span className="gov-badge ai" title="AI-generated · advisory · non-binding"><span className="gdot" aria-hidden="true" />{label}</span>;
}

export function HumanBadge({ label = "HUMAN-GATED" }: { label?: string }) {
  return <span className="gov-badge human" title="Requires a named accountable human"><span className="gdot" aria-hidden="true" />{label}</span>;
}

export function DeterministicBadge({ label = "DETERMINISTIC" }: { label?: string }) {
  return <span className="gov-badge det" title="Deterministic figure — AI never produces this"><span className="gdot" aria-hidden="true" />{label}</span>;
}

/** A small "● UNCHANGED / PRESERVED" tag for an authoritative figure an AI overlay left untouched. */
export function Unchanged({ label = "UNCHANGED" }: { label?: string }) {
  return <span className="unchanged"><span className="gdot" aria-hidden="true" />{label}</span>;
}

/** AI-drafts → human-confirms flow indicator for suggest/confirm screens. */
export function GovFlow({ ai, human, note }: { ai: string; human: string; note?: string }) {
  return (
    <div className="gov-flow">
      <AiBadge label={ai} /> <span className="gov-flow-arrow" aria-hidden="true">→</span> <HumanBadge label={human} />
      {note && <span className="gov-flow-note">{note}</span>}
    </div>
  );
}

/** The three-part governance promise, rendered as a slim strip (used in the app shell). */
export function GovernanceStrip() {
  return (
    <div className="gov-strip">
      <span><b>AI</b> where it helps</span>
      <span className="gsep" />
      <span><b>Humans</b> where regulation demands</span>
      <span className="gsep" />
      <span><b>Deterministic figures</b> throughout</span>
    </div>
  );
}

/**
 * The signature governance frame: AI ADVISORY on the left, the AUTHORITATIVE figure
 * on the right marked UNCHANGED. One glance says "AI recommends, humans decide,
 * the figure of record is preserved."
 */
export function GovSplit({ advisoryLabel, advisory, authLabel, auth }: {
  advisoryLabel: string; advisory: React.ReactNode; authLabel: string; auth: React.ReactNode;
}) {
  return (
    <div className="gov-split">
      <div className="gov-pane ai">
        <div className="gov-pane-head"><AiBadge /> <span>{advisoryLabel}</span></div>
        <div className="gov-pane-body">{advisory}</div>
      </div>
      <div className="gov-arrow" aria-hidden="true">→</div>
      <div className="gov-pane auth">
        <div className="gov-pane-head"><DeterministicBadge label="AUTHORITATIVE" /> <Unchanged /></div>
        <div className="gov-pane-body">
          <div className="gov-auth-label">{authLabel}</div>
          {auth}
        </div>
      </div>
    </div>
  );
}

/* ============================================================================
   DataTable — one reusable, dependency-free list surface. Client-side keyword
   search, per-column filters, single-column sort, a column chooser, pagination,
   CSV export and named saved views. Column-chooser hidden set, page size and
   saved views persist to localStorage under `helix.dt.<id>.*`. Every table in
   the app should adopt this instead of hand-rolling a <table>.
   ============================================================================ */

export type Col<T> = {
  key: string;
  header: string;
  /** Cell renderer; defaults to String(row[key]). */
  render?: (row: T) => React.ReactNode;
  /** Sort/filter/CSV basis; defaults to row[key]. */
  value?: (row: T) => string | number;
  /** Sortable header (default true). */
  sortable?: boolean;
  /** Included in keyword search + per-column filter (default true). */
  filterable?: boolean;
  /** Included in CSV export (default true). */
  csv?: boolean;
  width?: string;
  /** Cell/header alignment; "right" reuses the numeric (tabular) column style. */
  align?: "left" | "right" | "center";
  /**
   * Opt-in per-cell inline editing (CLoM F5). Only takes effect when the table is
   * also given an `onCellSave` handler; otherwise it is ignored and the cell renders
   * and behaves exactly as before. When ON, clicking the cell opens an inline editor
   * (Enter commits, Escape / blur cancel).
   */
  editable?: boolean;
};

export type DataTableProps<T> = {
  /** Stable id → localStorage key for the column chooser, page size + saved views. */
  id: string;
  columns: Col<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  initialPageSize?: number;
  empty?: React.ReactNode;
  /** Slot for page-specific actions, right of the toolbar. */
  toolbarRight?: React.ReactNode;
  /** Optional extra class(es) per row (e.g. to mark a selected row). Additive: when
   *  absent the row markup is byte-identical to before. */
  rowClassName?: (row: T) => string | undefined;
  /**
   * Inline-edit commit handler. When provided together with a column's `editable`
   * flag, editable cells become click-to-edit. Awaited on Enter: resolve to close
   * the editor, reject to surface the error inline and keep the editor open.
   */
  onCellSave?: (row: T, colKey: string, newValue: string) => Promise<void>;
};

type DtSort = { key: string; dir: "asc" | "desc" } | null;
type DtView = {
  name: string;
  keyword: string;
  colFilters: Record<string, string>;
  sort: DtSort;
  hidden: string[];
  pageSize: number;
};

function dtLoad<V>(key: string, fallback: V): V {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as V) : fallback;
  } catch {
    return fallback;
  }
}
function dtStore(key: string, val: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(val));
  } catch {
    /* localStorage unavailable — feature degrades to session-only */
  }
}

export function DataTable<T>({
  id, columns, rows, rowKey, onRowClick, initialPageSize = 25, empty, toolbarRight,
  rowClassName, onCellSave,
}: DataTableProps<T>) {
  const colsKey = `helix.dt.${id}.cols`;
  const sizeKey = `helix.dt.${id}.size`;
  const viewsKey = `helix.dt.${id}.views`;

  const [keyword, setKeyword] = useState("");
  const [showFilters, setShowFilters] = useState(false);
  const [colFilters, setColFilters] = useState<Record<string, string>>({});
  const [sort, setSort] = useState<DtSort>(null);
  const [hidden, setHidden] = useState<string[]>(() => dtLoad<string[]>(colsKey, []));
  const [pageSize, setPageSize] = useState<number>(() => dtLoad<number>(sizeKey, initialPageSize));
  const [page, setPage] = useState(0);
  const [views, setViews] = useState<DtView[]>(() => dtLoad<DtView[]>(viewsKey, []));
  const [menu, setMenu] = useState<null | "cols" | "views">(null);
  const [viewName, setViewName] = useState("");
  // Inline-edit state (inert unless a column is `editable` AND onCellSave is set).
  const [editing, setEditing] = useState<{ rowKey: string; colKey: string } | null>(null);
  const [editValue, setEditValue] = useState("");
  const [editError, setEditError] = useState<string | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const colsBtnRef = useRef<HTMLButtonElement>(null);
  const viewsBtnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  // Guards against a double-submit (Enter pressed twice, or blur racing a commit).
  const savingRef = useRef(false);

  useEffect(() => { dtStore(colsKey, hidden); }, [colsKey, hidden]);
  useEffect(() => { dtStore(sizeKey, pageSize); }, [sizeKey, pageSize]);
  useEffect(() => { dtStore(viewsKey, views); }, [viewsKey, views]);

  const colFiltersKey = JSON.stringify(colFilters);
  // Any change to the filtered/sorted shape returns to the first page.
  useEffect(() => { setPage(0); }, [keyword, colFiltersKey, pageSize, sort]);

  // Close an open menu on an outside click.
  useEffect(() => {
    if (!menu) return;
    const onDown = (e: MouseEvent) => {
      if (toolbarRef.current && !toolbarRef.current.contains(e.target as Node)) setMenu(null);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [menu]);

  // Keyboard operability for the column-chooser / saved-views menus: move focus into
  // the menu on open, close on Escape and return focus to the trigger that opened it.
  useEffect(() => {
    if (!menu) return;
    menuRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        const trigger = menu === "cols" ? colsBtnRef.current : viewsBtnRef.current;
        setMenu(null);
        trigger?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menu]);

  const basis = useCallback((col: Col<T>, row: T): string | number => {
    if (col.value) return col.value(row);
    const v = (row as any)[col.key];
    if (v == null) return "";
    return typeof v === "number" ? v : String(v);
  }, []);
  const basisStr = useCallback((col: Col<T>, row: T) => String(basis(col, row)), [basis]);

  // ---- inline cell editing ----
  const startEdit = useCallback((row: T, col: Col<T>, rk: string) => {
    if (savingRef.current) return;
    // Re-clicking the cell already being edited must not wipe the in-progress draft.
    if (editing && editing.rowKey === rk && editing.colKey === col.key) return;
    setEditValue(basisStr(col, row));
    setEditError(null);
    setEditing({ rowKey: rk, colKey: col.key });
  }, [basisStr, editing]);

  const cancelEdit = useCallback(() => {
    if (savingRef.current) return; // never tear down mid-commit
    setEditing(null);
    setEditError(null);
  }, []);

  const commitEdit = useCallback(async (row: T, col: Col<T>) => {
    if (savingRef.current || !onCellSave) return; // in-flight guard
    savingRef.current = true;
    setEditBusy(true);
    setEditError(null);
    try {
      await onCellSave(row, col.key, editValue);
      savingRef.current = false;
      setEditBusy(false);
      setEditing(null); // success → close the editor
    } catch (e: any) {
      // Reject → keep the editor open and surface the reason inline.
      savingRef.current = false;
      setEditBusy(false);
      setEditError(e && e.message ? String(e.message) : "Save failed");
    }
  }, [onCellSave, editValue]);

  const visibleCols = columns.filter((c) => !hidden.includes(c.key));

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    const active = Object.entries(colFilters).filter(([, v]) => v.trim() !== "");
    if (!kw && active.length === 0) return rows;
    return rows.filter((row) => {
      if (kw) {
        const hit = columns.some((c) => c.filterable !== false && basisStr(c, row).toLowerCase().includes(kw));
        if (!hit) return false;
      }
      for (const [key, val] of active) {
        const col = columns.find((c) => c.key === key);
        if (col && !basisStr(col, row).toLowerCase().includes(val.trim().toLowerCase())) return false;
      }
      return true;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, columns, keyword, colFiltersKey, basisStr]);

  const sorted = useMemo(() => {
    if (!sort) return filtered;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return filtered;
    const dir = sort.dir === "asc" ? 1 : -1;
    return [...filtered].sort((a, b) => {
      const av = basis(col, a), bv = basis(col, b);
      let cmp: number;
      if (typeof av === "number" && typeof bv === "number") cmp = av - bv;
      else cmp = String(av).localeCompare(String(bv), undefined, { numeric: true, sensitivity: "base" });
      return cmp * dir;
    });
  }, [filtered, sort, columns, basis]);

  const total = sorted.length;
  const eff = pageSize === 0 ? (total || 1) : pageSize;
  const pageCount = Math.max(1, Math.ceil(total / eff));
  const safePage = Math.min(page, pageCount - 1);
  const start = safePage * eff;
  const visible = sorted.slice(start, start + eff);

  const toggleSort = (col: Col<T>) => {
    if (col.sortable === false) return;
    setSort((s) => {
      if (!s || s.key !== col.key) return { key: col.key, dir: "asc" };
      if (s.dir === "asc") return { key: col.key, dir: "desc" };
      return null;
    });
  };

  const toggleColumn = (key: string) =>
    setHidden((prev) => (prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]));

  const exportCsv = () => {
    const cols = visibleCols.filter((c) => c.csv !== false);
    const esc = (s: string) => {
      // Neutralise CSV/spreadsheet formula injection: a value whose first character is
      // =,+,-,@,tab or CR is interpreted as a formula by Excel/Sheets. Prefix a single
      // quote so the cell is rendered as inert text, then apply quote/comma escaping.
      const guarded = /^[=+\-@\t\r]/.test(s) ? "'" + s : s;
      return /[",\n]/.test(guarded) ? '"' + guarded.replace(/"/g, '""') + '"' : guarded;
    };
    const lines = [cols.map((c) => esc(c.header)).join(",")];
    for (const row of sorted) lines.push(cols.map((c) => esc(basisStr(c, row))).join(","));
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const d = new Date();
    const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
    const a = document.createElement("a");
    a.href = url;
    a.download = `${id}-${stamp}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const saveView = () => {
    const name = viewName.trim();
    if (!name) return;
    const v: DtView = { name, keyword, colFilters, sort, hidden, pageSize };
    setViews((prev) => [...prev.filter((x) => x.name !== name), v]);
    setViewName("");
  };
  const applyView = (v: DtView) => {
    setKeyword(v.keyword || "");
    setColFilters(v.colFilters || {});
    setSort(v.sort ?? null);
    setHidden(v.hidden || []);
    setPageSize(v.pageSize ?? initialPageSize);
    setMenu(null);
  };
  const deleteView = (name: string) => setViews((prev) => prev.filter((x) => x.name !== name));

  const alignCls = (c: Col<T>) => (c.align === "right" ? "num" : c.align === "center" ? "dt-center" : "");

  return (
    <div className="dt">
      <div className="dt-toolbar" ref={toolbarRef}>
        <div className="dt-toolbar-left">
          <div className="dt-search">
            <span className="dt-search-ico" aria-hidden="true">⌕</span>
            <input
              className="dt-search-input" type="search" value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="Search…" aria-label="Search table"
            />
          </div>
          <button className={`btn subtle dt-btn${showFilters ? " active" : ""}`}
            aria-pressed={showFilters} onClick={() => setShowFilters((s) => !s)}>Filters</button>

          <div className="dt-menu-wrap">
            <button ref={colsBtnRef} className="btn subtle dt-btn" aria-haspopup="true" aria-expanded={menu === "cols"}
              onClick={() => setMenu(menu === "cols" ? null : "cols")}>Columns</button>
            {menu === "cols" && (
              <div className="dt-menu" role="menu" aria-label="Show columns" tabIndex={-1} ref={menuRef}>
                <div className="dt-menu-title">Show columns</div>
                {columns.map((c) => (
                  <label key={c.key} className="dt-menu-check">
                    <input type="checkbox" checked={!hidden.includes(c.key)}
                      onChange={() => toggleColumn(c.key)} aria-label={`Toggle column ${c.header || c.key}`} />
                    <span>{c.header || c.key}</span>
                  </label>
                ))}
              </div>
            )}
          </div>

          <div className="dt-menu-wrap">
            <button ref={viewsBtnRef} className="btn subtle dt-btn" aria-haspopup="true" aria-expanded={menu === "views"}
              onClick={() => setMenu(menu === "views" ? null : "views")}>Views</button>
            {menu === "views" && (
              <div className="dt-menu" role="menu" aria-label="Saved views" tabIndex={-1} ref={menuRef}>
                <div className="dt-menu-title">Saved views</div>
                {views.length === 0 && <div className="dt-menu-empty">No saved views yet.</div>}
                {views.map((v) => (
                  <div key={v.name} className="dt-view-row">
                    <button className="dt-view-apply" onClick={() => applyView(v)}>{v.name}</button>
                    <button className="dt-view-del" aria-label={`Delete view ${v.name}`}
                      onClick={() => deleteView(v.name)}>×</button>
                  </div>
                ))}
                <div className="dt-view-save">
                  <input value={viewName} onChange={(e) => setViewName(e.target.value)}
                    placeholder="Name this view" aria-label="New view name"
                    onKeyDown={(e) => { if (e.key === "Enter") saveView(); }} />
                  <button className="btn subtle dt-btn" disabled={!viewName.trim()} onClick={saveView}>Save</button>
                </div>
              </div>
            )}
          </div>

          <button className="btn subtle dt-btn" onClick={exportCsv}>Export CSV</button>
        </div>
        {toolbarRight && <div className="dt-toolbar-right">{toolbarRight}</div>}
      </div>

      {rows.length === 0 ? (
        <div className="dt-empty">{empty ?? <div className="muted">No rows.</div>}</div>
      ) : (
        <>
          <div className="dt-table-wrap">
            <table className="dt-table">
              <thead>
                <tr>
                  {visibleCols.map((c) => {
                    const sortable = c.sortable !== false;
                    const active = sort?.key === c.key;
                    const arrow = active ? (sort!.dir === "asc" ? "▲" : "▼") : "";
                    const ariaSort: React.AriaAttributes["aria-sort"] = !sortable
                      ? undefined
                      : active
                        ? (sort!.dir === "asc" ? "ascending" : "descending")
                        : "none";
                    return (
                      <th key={c.key} scope="col" aria-sort={ariaSort}
                        className={alignCls(c)} style={c.width ? { width: c.width } : undefined}>
                        {sortable ? (
                          <button className={`dt-sort${active ? " active" : ""}`}
                            onClick={() => toggleSort(c)} aria-label={`Sort by ${c.header}`}>
                            <span>{c.header}</span>
                            {arrow && <span className="dt-sort-arrow" aria-hidden="true">{arrow}</span>}
                          </button>
                        ) : <span>{c.header}</span>}
                      </th>
                    );
                  })}
                </tr>
                {showFilters && (
                  <tr className="dt-filter-row">
                    {visibleCols.map((c) => (
                      <th key={c.key} className={alignCls(c)}>
                        {c.filterable !== false && (
                          <input className="dt-filter-input" value={colFilters[c.key] || ""}
                            onChange={(e) => setColFilters((f) => ({ ...f, [c.key]: e.target.value }))}
                            placeholder="Filter" aria-label={`Filter ${c.header}`} />
                        )}
                      </th>
                    ))}
                  </tr>
                )}
              </thead>
              <tbody>
                {visible.map((row) => {
                  const k = rowKey(row);
                  const extraCls = rowClassName?.(row);
                  const trCls = [onRowClick ? "rowlink" : "", extraCls || ""].filter(Boolean).join(" ") || undefined;
                  return (
                    <tr key={k} className={trCls}
                      onClick={onRowClick ? () => onRowClick(row) : undefined}>
                      {visibleCols.map((c) => {
                        // Editing only engages when the column opts in AND the table has a save
                        // handler. Otherwise the cell is byte-identical to the original markup.
                        const cellEditable = !!(c.editable && onCellSave);
                        if (!cellEditable) {
                          return (
                            <td key={c.key} className={alignCls(c)}>
                              {c.render ? c.render(row) : String((row as any)[c.key] ?? "")}
                            </td>
                          );
                        }
                        const isEditingCell = editing?.rowKey === k && editing?.colKey === c.key;
                        return (
                          <td key={c.key} className={[alignCls(c), "dt-cell-edit"].filter(Boolean).join(" ")}
                            onClick={(e) => { e.stopPropagation(); if (!isEditingCell) startEdit(row, c, k); }}>
                            {isEditingCell ? (
                              <span className={`dt-edit${editBusy ? " busy" : ""}${editError ? " has-error" : ""}`}>
                                <input
                                  className="dt-edit-input" autoFocus value={editValue} readOnly={editBusy}
                                  aria-label={`Edit ${c.header || c.key}`} aria-invalid={editError ? true : undefined}
                                  onClick={(e) => e.stopPropagation()}
                                  onChange={(e) => setEditValue(e.target.value)}
                                  onKeyDown={(e) => {
                                    if ((e.nativeEvent as any).isComposing) return; // don't commit mid-IME
                                    if (e.key === "Enter") { e.preventDefault(); commitEdit(row, c); }
                                    else if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); cancelEdit(); }
                                  }}
                                  onBlur={() => { if (!savingRef.current) cancelEdit(); }}
                                />
                                {editBusy && <span className="dt-edit-spin" aria-hidden="true" />}
                                {editError && <span className="dt-edit-err" role="alert">{editError}</span>}
                              </span>
                            ) : (
                              <span className="dt-edit-trigger" role="button" tabIndex={0}
                                aria-label={`Edit ${c.header || c.key}`} title="Click to edit"
                                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); startEdit(row, c, k); } }}>
                                {c.render ? c.render(row) : String((row as any)[c.key] ?? "")}
                                <span className="dt-edit-pencil" aria-hidden="true">✎</span>
                              </span>
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  );
                })}
                {visible.length === 0 && (
                  <tr><td colSpan={Math.max(1, visibleCols.length)} className="muted dt-no-match">
                    No rows match the current filters.
                  </td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="dt-footer">
            <label className="dt-pagesize">
              <span>Rows</span>
              <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))} aria-label="Rows per page">
                <option value={10}>10</option>
                <option value={25}>25</option>
                <option value={50}>50</option>
                <option value={100}>100</option>
                <option value={0}>All</option>
              </select>
            </label>
            <div className="dt-footer-right">
              <span className="dt-range">
                {total === 0 ? "0" : `${start + 1}–${Math.min(start + eff, total)}`} of {total}
              </span>
              <button className="btn subtle dt-btn" aria-label="Previous page"
                disabled={safePage <= 0} onClick={() => setPage(Math.max(0, safePage - 1))}>Prev</button>
              <button className="btn subtle dt-btn" aria-label="Next page"
                disabled={safePage >= pageCount - 1} onClick={() => setPage(Math.min(pageCount - 1, safePage + 1))}>Next</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/* ============================================================================
   QuickCreate — a reusable "＋ Quick create" modal (gap #52). A compact,
   config-driven form rendered inside a focus-trapped, Escape-dismissable dialog.
   Each row reuses Field (hint / required / error slots) so inline validation and
   accessibility come for free. Adopted on list screens (Counterparties, Deals) as
   a fast create path that sits ALONGSIDE — never replaces — the full create forms.
   a11y: role="dialog" + aria-modal + aria-labelledby; focus moves in on open,
   is trapped while open, and is restored to the trigger on close.
   ============================================================================ */

export type QuickField = {
  name: string;
  label: string;
  /** Control type; defaults to "text". */
  type?: "text" | "number" | "select" | "textarea";
  required?: boolean;
  hint?: string;
  placeholder?: string;
  /** Options for a "select" field. */
  options?: { value: string; label: string }[];
};

export function QuickCreate({
  buttonLabel = "＋ Quick create",
  buttonKind = "ghost",
  title,
  sub,
  fields,
  submitLabel = "Create",
  onSubmit,
}: {
  buttonLabel?: React.ReactNode;
  buttonKind?: string;
  title: string;
  sub?: string;
  fields: QuickField[];
  submitLabel?: string;
  /** Resolve to close the modal; reject (Error) to keep it open and show the reason. */
  onSubmit: (values: Record<string, string>) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const seed = useCallback(() => {
    const init: Record<string, string> = {};
    for (const f of fields) init[f.name] = f.type === "select" ? (f.options?.[0]?.value ?? "") : "";
    setValues(init);
    setErrors({});
  }, [fields]);

  const openModal = () => { seed(); setBusy(false); setOpen(true); };
  const close = useCallback(() => {
    setOpen(false);
    setBusy(false);
    triggerRef.current?.focus();
  }, []);

  // Move focus in, trap Tab within the dialog, and close on Escape while open.
  useEffect(() => {
    if (!open) return;
    const dlg = dialogRef.current;
    const focusables = () =>
      Array.from(dlg?.querySelectorAll<HTMLElement>(
        'input, select, textarea, button, [href], [tabindex]:not([tabindex="-1"])',
      ) ?? []).filter((el) => !el.hasAttribute("disabled"));
    focusables()[0]?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); close(); return; }
      if (e.key === "Tab") {
        const els = focusables();
        if (els.length === 0) return;
        const first = els[0], last = els[els.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => document.removeEventListener("keydown", onKey, true);
  }, [open, close]);

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    for (const f of fields) {
      const v = (values[f.name] ?? "").trim();
      if (f.required && !v) errs[f.name] = `${f.label} is required`;
      else if (f.type === "number" && v && Number.isNaN(Number(v))) errs[f.name] = "Enter a valid number";
    }
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const submit = async () => {
    if (busy) return;
    if (!validate()) return;
    setBusy(true);
    try {
      await onSubmit(values);
      close();
    } catch (e: any) {
      // Keep the modal open; surface the server / caller message against the form.
      setErrors((prev) => ({ ...prev, _form: e?.message ? String(e.message) : "Create failed" }));
      setBusy(false);
    }
  };

  const setVal = (name: string, v: string) => setValues((prev) => ({ ...prev, [name]: v }));

  return (
    <>
      <button ref={triggerRef} type="button" className={`btn ${buttonKind}`} onClick={openModal}>{buttonLabel}</button>
      {open && (
        <div className="qc-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
          <div
            className="qc-modal" role="dialog" aria-modal="true" aria-labelledby={titleId}
            ref={dialogRef} onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="qc-head">
              <div>
                <h3 id={titleId}>{title}</h3>
                {sub && <div className="sub">{sub}</div>}
              </div>
              <button className="qc-close" type="button" aria-label="Close" onClick={close}>×</button>
            </div>
            <div className="qc-body">
              {fields.map((f) => {
                const err = errors[f.name] || null;
                const shared = {
                  value: values[f.name] ?? "",
                  onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
                    setVal(f.name, e.target.value),
                  placeholder: f.placeholder,
                };
                return (
                  <Field key={f.name} label={f.label} required={f.required} hint={f.hint} error={err}>
                    {f.type === "select" ? (
                      <select {...shared}>
                        {(f.options ?? []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </select>
                    ) : f.type === "textarea" ? (
                      <textarea rows={3} {...shared} />
                    ) : (
                      <input type={f.type === "number" ? "number" : "text"} {...shared} />
                    )}
                  </Field>
                );
              })}
              {errors._form && <div className="alert err" role="alert">{errors._form}</div>}
            </div>
            <div className="qc-foot">
              <Button kind="subtle" onClick={close}>Cancel</Button>
              <Button onClick={submit} busy={busy}>{submitLabel}</Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/* ============================================================================
   RichText + MarkdownView (gap #58) — an XSS-safe, dependency-free rich-text
   field for free-text / clause / notes inputs. To stay CSP- and XSS-safe with
   NO external library it is a lightweight MARKDOWN editor: a small toolbar
   (bold · italic · bullet · numbered · link) that edits and STORES a markdown
   string (the same string flows through the existing API calls — no backend
   change), paired with `MarkdownView`, a strict-whitelist renderer.

   MarkdownView NEVER uses dangerouslySetInnerHTML: it parses markdown into React
   elements from a fixed whitelist (p, strong, em, ul/ol/li, a[href], br). React
   escapes all text content, and hrefs are scheme-checked, so injected HTML or
   javascript:/data: URLs can never execute.
   ============================================================================ */

/** Allow only http(s)/mailto and site-relative (#, /) hrefs. Returns null for
 *  anything else (javascript:, data:, vbscript:, …) so the link degrades to text. */
export function sanitizeHref(href: string): string | null {
  const t = (href || "").trim();
  if (!t) return null;
  if (/^(https?:|mailto:)/i.test(t)) return t;
  if (/^[/#]/.test(t)) return t;
  return null;
}

/** Render inline markdown (**bold**, *italic*, [text](href)) to escaped React nodes. */
function renderInline(text: string, keyPrefix: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  const re = /(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(\[([^\]]+)\]\(([^)\s]+)\))/;
  let rest = text;
  let k = 0;
  while (rest.length) {
    const m = re.exec(rest);
    if (!m) { nodes.push(rest); break; }
    if (m.index > 0) nodes.push(rest.slice(0, m.index));
    if (m[1]) {
      nodes.push(<strong key={`${keyPrefix}-b${k}`}>{renderInline(m[2], `${keyPrefix}-b${k}i`)}</strong>);
    } else if (m[3]) {
      nodes.push(<em key={`${keyPrefix}-i${k}`}>{renderInline(m[4], `${keyPrefix}-i${k}i`)}</em>);
    } else {
      const href = sanitizeHref(m[7]);
      if (href) {
        nodes.push(
          <a key={`${keyPrefix}-l${k}`} href={href} target="_blank" rel="noopener noreferrer">{m[6]}</a>,
        );
      } else {
        nodes.push(m[6]); // unsafe scheme → render the link text only, inert
      }
    }
    k += 1;
    rest = rest.slice(m.index + m[0].length);
  }
  return nodes;
}

/** Strict-whitelist markdown renderer. Safe by construction (no raw HTML). */
export function MarkdownView({ md, className, empty }: {
  md?: string | null; className?: string; empty?: React.ReactNode;
}) {
  const text = (md ?? "").replace(/\r\n/g, "\n");
  if (!text.trim()) {
    return <div className={`md-view${className ? " " + className : ""}`}>{empty ?? <span className="muted">—</span>}</div>;
  }
  const lines = text.split("\n");
  const isUl = (l: string) => /^\s*[-*]\s+/.test(l);
  const isOl = (l: string) => /^\s*\d+\.\s+/.test(l);
  const blocks: React.ReactNode[] = [];
  let i = 0;
  let b = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i += 1; continue; }
    if (isUl(line)) {
      const items: string[] = [];
      while (i < lines.length && isUl(lines[i])) { items.push(lines[i].replace(/^\s*[-*]\s+/, "")); i += 1; }
      blocks.push(<ul key={`b${b}`}>{items.map((it, j) => <li key={j}>{renderInline(it, `b${b}-${j}`)}</li>)}</ul>);
    } else if (isOl(line)) {
      const items: string[] = [];
      while (i < lines.length && isOl(lines[i])) { items.push(lines[i].replace(/^\s*\d+\.\s+/, "")); i += 1; }
      blocks.push(<ol key={`b${b}`}>{items.map((it, j) => <li key={j}>{renderInline(it, `b${b}-${j}`)}</li>)}</ol>);
    } else {
      const para: string[] = [];
      while (i < lines.length && lines[i].trim() && !isUl(lines[i]) && !isOl(lines[i])) { para.push(lines[i]); i += 1; }
      const inner: React.ReactNode[] = [];
      para.forEach((p, j) => {
        if (j > 0) inner.push(<br key={`br${b}-${j}`} />);
        inner.push(...renderInline(p, `p${b}-${j}`));
      });
      blocks.push(<p key={`b${b}`}>{inner}</p>);
    }
    b += 1;
  }
  return <div className={`md-view${className ? " " + className : ""}`}>{blocks}</div>;
}

/** Controlled markdown editor with a small formatting toolbar and a Preview toggle. */
export function RichText({
  value, onChange, rows = 5, placeholder, id, ariaLabel, disabled,
}: {
  value: string;
  onChange: (v: string) => void;
  rows?: number;
  placeholder?: string;
  id?: string;
  ariaLabel?: string;
  disabled?: boolean;
}) {
  const taRef = useRef<HTMLTextAreaElement>(null);
  const [preview, setPreview] = useState(false);

  const surround = (before: string, after: string, ph: string) => {
    const ta = taRef.current;
    if (!ta) return;
    const start = ta.selectionStart ?? value.length;
    const end = ta.selectionEnd ?? value.length;
    const sel = value.slice(start, end) || ph;
    const next = value.slice(0, start) + before + sel + after + value.slice(end);
    onChange(next);
    requestAnimationFrame(() => {
      ta.focus();
      ta.selectionStart = start + before.length;
      ta.selectionEnd = start + before.length + sel.length;
    });
  };

  const prefixLines = (make: (i: number) => string) => {
    const ta = taRef.current;
    if (!ta) return;
    const start = ta.selectionStart ?? 0;
    const end = ta.selectionEnd ?? 0;
    const lineStart = value.lastIndexOf("\n", start - 1) + 1;
    const nl = value.indexOf("\n", end);
    const lineEnd = nl === -1 ? value.length : nl;
    const block = value.slice(lineStart, lineEnd) || "";
    const rebuilt = block.split("\n").map((l, idx) => make(idx) + l).join("\n");
    const next = value.slice(0, lineStart) + rebuilt + value.slice(lineEnd);
    onChange(next);
    requestAnimationFrame(() => {
      ta.focus();
      ta.selectionStart = lineStart;
      ta.selectionEnd = lineStart + rebuilt.length;
    });
  };

  const insertLink = () => {
    const url = window.prompt("Link URL (https://…)");
    if (!url) return;
    const href = sanitizeHref(url);
    if (!href) { window.alert("Only http(s), mailto or relative links are allowed."); return; }
    surround("[", `](${href})`, "link text");
  };

  const TbBtn = ({ label, title, onClick }: { label: React.ReactNode; title: string; onClick: () => void }) => (
    <button type="button" className="rt-tb-btn" title={title} aria-label={title}
      disabled={disabled || preview} onMouseDown={(e) => e.preventDefault()} onClick={onClick}>
      {label}
    </button>
  );

  return (
    <div className={`rt${disabled ? " disabled" : ""}`}>
      <div className="rt-toolbar" role="toolbar" aria-label="Text formatting">
        <TbBtn label={<b>B</b>} title="Bold" onClick={() => surround("**", "**", "bold text")} />
        <TbBtn label={<i>I</i>} title="Italic" onClick={() => surround("*", "*", "italic text")} />
        <TbBtn label="• List" title="Bullet list" onClick={() => prefixLines(() => "- ")} />
        <TbBtn label="1. List" title="Numbered list" onClick={() => prefixLines((i) => `${i + 1}. `)} />
        <TbBtn label="Link" title="Insert link" onClick={insertLink} />
        <span className="rt-tb-spacer" />
        <button type="button" className={`rt-tb-btn rt-tb-toggle${preview ? " active" : ""}`}
          aria-pressed={preview} onClick={() => setPreview((p) => !p)}>
          {preview ? "Write" : "Preview"}
        </button>
      </div>
      {preview ? (
        <div className="rt-preview"><MarkdownView md={value} empty={<span className="muted">Nothing to preview.</span>} /></div>
      ) : (
        <textarea
          ref={taRef} id={id} className="rt-textarea mono" rows={rows} value={value}
          placeholder={placeholder} aria-label={ariaLabel} disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      <div className="rt-hint">Markdown — **bold**, *italic*, - lists, [links](https://…). Stored as text.</div>
    </div>
  );
}
