/**
 * Universal search palette (⌘K / Ctrl-K). Fuzzy jump across every screen plus
 * live directory data — counterparties, deals, borrower groups, jurisdictions /
 * rule packs, country limits and portfolio exposures. Keyboard-first: arrows to
 * move, Enter to go, Esc to close. Directory data is fetched lazily on first
 * open and cached for the session. The last few chosen targets are persisted to
 * localStorage and surfaced as a "Recent" section when the query is empty.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { config, counterparty, initiation, limits, origination, portfolio } from "./api";

export type NavTarget = { key: string; label: string };

type Row =
  | { kind: "recent"; view: string; label: string; meta: string; ref?: string }
  | { kind: "screen"; key: string; label: string; section: string }
  | { kind: "counterparty"; label: string; meta: string; ref?: string }
  | { kind: "deal"; label: string; meta: string; ref: string }
  | { kind: "group"; label: string; meta: string }
  | { kind: "jurisdiction"; label: string; meta: string }
  | { kind: "country"; label: string; meta: string }
  | { kind: "exposure"; label: string; meta: string };

// Where each row routes to, plus what we persist as a recent target.
type Target = { view: string; ref?: string; label: string; meta: string };

function toTarget(row: Row): Target {
  switch (row.kind) {
    case "recent": return { view: row.view, ref: row.ref, label: row.label, meta: row.meta };
    case "screen": return { view: row.key, label: row.label, meta: row.section };
    case "counterparty": return { view: "counterparties", label: row.label, meta: row.meta };
    case "deal": return { view: "workspace", ref: row.ref, label: row.label, meta: row.meta };
    case "group": return { view: "groups", label: row.label, meta: row.meta };
    case "jurisdiction": return { view: "rulepacks", label: row.label, meta: row.meta };
    case "country": return { view: "limits", label: row.label, meta: row.meta };
    case "exposure": return { view: "mis", label: row.label, meta: row.meta };
  }
}

// ---- recent-target persistence (best-effort; never throws) ----
const RECENT_KEY = "helix.search.recent";
const RECENT_MAX = 5;

function loadRecent(): Target[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr.slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

function saveRecent(list: Target[]) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX))); } catch { /* ignore */ }
}

export default function CommandPalette({
  open,
  onClose,
  screens,
  onPick,
}: {
  open: boolean;
  onClose: () => void;
  screens: { section: string; key: string; label: string }[];
  onPick: (view: string, ref?: string) => void;
}) {
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const [cps, setCps] = useState<any[] | null>(null);
  const [deals, setDeals] = useState<any[] | null>(null);
  const [groups, setGroups] = useState<any[] | null>(null);
  const [jurisdictions, setJurisdictions] = useState<any[] | null>(null);
  const [countries, setCountries] = useState<any[] | null>(null);
  const [exposures, setExposures] = useState<any[] | null>(null);
  const [recent, setRecent] = useState<Target[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  // Refresh directory data every time the palette opens; refresh recents. The dynamic
  // directories (counterparties, deals, groups, exposures) are re-fetched on EACH open so an
  // entity created earlier in the session is immediately searchable — the previous
  // fetch-once-per-session cache left, e.g., a just-onboarded counterparty invisible until a
  // full reload. The config-level directories (jurisdictions, country limits) change rarely, so
  // they stay fetch-once to avoid needless calls.
  useEffect(() => {
    if (!open) return;
    setQ("");
    setActive(0);
    setRecent(loadRecent());
    setTimeout(() => inputRef.current?.focus(), 0);
    counterparty.list().then(setCps).catch(() => setCps([]));
    origination.list().then(setDeals).catch(() => setDeals([]));
    initiation.listGroups().then(setGroups).catch(() => setGroups([]));
    portfolio.exposures().then(setExposures).catch(() => setExposures([]));
    if (jurisdictions === null) config.jurisdictions().then(setJurisdictions).catch(() => setJurisdictions([]));
    if (countries === null) limits.countries().then(setCountries).catch(() => setCountries([]));
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo<Row[]>(() => {
    const needle = q.trim().toLowerCase();
    const match = (s: string) => s.toLowerCase().includes(needle);
    const matchAny = (...vals: (string | undefined | null)[]) =>
      !needle || vals.some((v) => v != null && match(v));

    // Recent shortcuts only when the query is empty.
    const recentRows: Row[] = needle
      ? []
      : recent.map((t) => ({ kind: "recent", view: t.view, ref: t.ref, label: t.label, meta: t.meta }));

    const screenRows: Row[] = screens
      .filter((s) => matchAny(s.label, s.section))
      .map((s) => ({ kind: "screen", key: s.key, label: s.label, section: s.section }));

    const cpRows: Row[] = (cps ?? [])
      .filter((c) => matchAny(c.legalName, c.reference, c.recordType))
      .slice(0, 6)
      .map((c) => ({
        kind: "counterparty",
        label: c.legalName ?? c.reference,
        meta: `${c.reference ?? "—"} · ${c.recordType ?? "—"}`,
        ref: c.reference,
      }));

    const dealRows: Row[] = (deals ?? [])
      .filter((d) => matchAny(d.reference, d.counterpartyName))
      .slice(0, 6)
      .map((d) => ({
        kind: "deal",
        label: d.reference,
        meta: `${d.counterpartyName ?? "—"} · ${d.status ?? ""}`,
        ref: d.reference,
      }));

    const groupRows: Row[] = (groups ?? [])
      .filter((g) => matchAny(g.groupName, g.name, g.reference))
      .slice(0, 5)
      .map((g) => ({
        kind: "group",
        label: g.groupName ?? g.name ?? g.reference ?? "Group",
        meta: `${g.reference ?? "—"} · group`,
      }));

    const jurisdictionRows: Row[] = (jurisdictions ?? [])
      .filter((j) => matchAny(j.name, j.label, j.code, j.jurisdiction))
      .slice(0, 6)
      .map((j) => ({
        kind: "jurisdiction",
        label: j.name ?? j.label ?? j.code ?? j.jurisdiction ?? "Jurisdiction",
        meta: `${j.code ?? j.jurisdiction ?? "—"} · rule packs`,
      }));

    const countryRows: Row[] = (countries ?? [])
      .filter((c) => matchAny(c.country, c.name, c.code))
      .slice(0, 5)
      .map((c) => ({
        kind: "country",
        label: c.country ?? c.name ?? c.code ?? "Country",
        meta: `${c.code ?? c.country ?? "—"} · country limit`,
      }));

    const exposureRows: Row[] = (exposures ?? [])
      .filter((e) => matchAny(e.counterpartyName, e.reference, e.iracStage, e.stage))
      .slice(0, 5)
      .map((e) => ({
        kind: "exposure",
        label: e.counterpartyName ?? e.reference ?? "Exposure",
        meta: `${e.reference ?? "—"} · ${e.iracStage ?? e.stage ?? e.status ?? "exposure"}`,
      }));

    return [
      ...recentRows,
      ...screenRows,
      ...cpRows,
      ...dealRows,
      ...groupRows,
      ...jurisdictionRows,
      ...countryRows,
      ...exposureRows,
    ];
  }, [q, screens, recent, cps, deals, groups, jurisdictions, countries, exposures]);

  useEffect(() => { if (active >= rows.length) setActive(0); }, [rows.length, active]);

  const recordRecent = useCallback((t: Target) => {
    setRecent((prev) => {
      const deduped = prev.filter((p) => !(p.view === t.view && p.ref === t.ref && p.label === t.label));
      const next = [t, ...deduped].slice(0, RECENT_MAX);
      saveRecent(next);
      return next;
    });
  }, []);

  const choose = useCallback(
    (row: Row) => {
      const t = toTarget(row);
      recordRecent(t);
      onPick(t.view, t.ref);
      onClose();
    },
    [onPick, onClose, recordRecent],
  );

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") { onClose(); return; }
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, rows.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === "Enter") { e.preventDefault(); if (rows[active]) choose(rows[active]); }
  };

  if (!open) return null;

  // Render with section headers, preserving a flat index for arrow-nav. The
  // render order below MUST match the concatenation order of `rows` above.
  let idx = -1;
  const metaOf = (row: Row) => (row.kind === "screen" ? row.section : row.meta);
  const renderRow = (row: Row) => {
    idx += 1;
    const i = idx;
    return (
      <div
        key={`${row.kind}-${i}-${row.label}`}
        className={`cmdk-item${i === active ? " active" : ""}`}
        onMouseEnter={() => setActive(i)}
        onClick={() => choose(row)}
      >
        <span className={`ci-dot ci-${row.kind}`} />
        <span>{row.label}</span>
        <span className="ci-meta">{metaOf(row)}</span>
      </div>
    );
  };

  const section = (title: string, kind: Row["kind"]) => {
    const items = rows.filter((r) => r.kind === kind);
    if (items.length === 0) return null;
    return (
      <>
        <div className="cmdk-section">{title}</div>
        {items.map(renderRow)}
      </>
    );
  };

  return (
    <div className="cmdk-scrim" onClick={onClose}>
      <div className="cmdk" onClick={(e) => e.stopPropagation()} onKeyDown={onKey}>
        <div className="cmdk-input-row">
          <span className="ico">⌕</span>
          <input
            ref={inputRef}
            placeholder="Search everything — screens, borrowers, deals, groups, jurisdictions…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="cmdk-list">
          {rows.length === 0 && (
            <div className="cmdk-empty">
              {q ? `No matches for “${q}”.` : "Start typing to search across the platform."}
            </div>
          )}
          {section("Recent", "recent")}
          {section("Screens", "screen")}
          {section("Counterparties", "counterparty")}
          {section("Deals", "deal")}
          {section("Borrower groups", "group")}
          {section("Jurisdictions & rule packs", "jurisdiction")}
          {section("Country limits", "country")}
          {section("Portfolio exposures", "exposure")}
        </div>
        <div className="cmdk-foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> navigate</span>
          <span><kbd>↵</kbd> open</span>
          <span><kbd>esc</kbd> close</span>
        </div>
      </div>
    </div>
  );
}
