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
  const toolbarRef = useRef<HTMLDivElement>(null);
  const colsBtnRef = useRef<HTMLButtonElement>(null);
  const viewsBtnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

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
    const esc = (s: string) => (/[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s);
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
                  return (
                    <tr key={k} className={onRowClick ? "rowlink" : undefined}
                      onClick={onRowClick ? () => onRowClick(row) : undefined}>
                      {visibleCols.map((c) => (
                        <td key={c.key} className={alignCls(c)}>
                          {c.render ? c.render(row) : String((row as any)[c.key] ?? "")}
                        </td>
                      ))}
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
