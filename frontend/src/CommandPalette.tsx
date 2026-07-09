/**
 * Command palette (⌘K / Ctrl-K). Fuzzy jump across every screen plus live
 * counterparties and deals. Keyboard-first: arrows to move, Enter to go, Esc
 * to close. Counterparties/deals are fetched lazily on first open and cached.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { counterparty, origination } from "./api";

export type NavTarget = { key: string; label: string };

type Row =
  | { kind: "screen"; key: string; label: string; section: string }
  | { kind: "counterparty"; label: string; meta: string; ref?: string }
  | { kind: "deal"; label: string; meta: string; ref: string };

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
  const inputRef = useRef<HTMLInputElement>(null);

  // Lazy-load directory data the first time the palette opens.
  useEffect(() => {
    if (!open) return;
    setQ("");
    setActive(0);
    setTimeout(() => inputRef.current?.focus(), 0);
    if (cps === null) counterparty.list().then(setCps).catch(() => setCps([]));
    if (deals === null) origination.list().then(setDeals).catch(() => setDeals([]));
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo<Row[]>(() => {
    const needle = q.trim().toLowerCase();
    const match = (s: string) => s.toLowerCase().includes(needle);

    const screenRows: Row[] = screens
      .filter((s) => !needle || match(s.label) || match(s.section))
      .map((s) => ({ kind: "screen", key: s.key, label: s.label, section: s.section }));

    const cpRows: Row[] = (cps ?? [])
      .filter((c) => !needle || match(c.legalName ?? "") || match(c.reference ?? "") || match(c.recordType ?? ""))
      .slice(0, 6)
      .map((c) => ({
        kind: "counterparty",
        label: c.legalName ?? c.reference,
        meta: `${c.reference} · ${c.recordType ?? "—"}`,
        ref: c.reference,
      }));

    const dealRows: Row[] = (deals ?? [])
      .filter((d) => !needle || match(d.reference ?? "") || match(d.counterpartyName ?? ""))
      .slice(0, 6)
      .map((d) => ({
        kind: "deal",
        label: d.reference,
        meta: `${d.counterpartyName ?? "—"} · ${d.status ?? ""}`,
        ref: d.reference,
      }));

    return [...screenRows, ...cpRows, ...dealRows];
  }, [q, screens, cps, deals]);

  useEffect(() => { if (active >= rows.length) setActive(0); }, [rows.length, active]);

  const choose = useCallback(
    (row: Row) => {
      if (row.kind === "screen") onPick(row.key);
      else if (row.kind === "deal") onPick("workspace", row.ref);
      else onPick("counterparties");
      onClose();
    },
    [onPick, onClose],
  );

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") { onClose(); return; }
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, rows.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === "Enter") { e.preventDefault(); if (rows[active]) choose(rows[active]); }
  };

  if (!open) return null;

  // Group rows for rendering with section headers, preserving flat index for arrow-nav.
  let idx = -1;
  const screenRows = rows.filter((r) => r.kind === "screen");
  const cpRows = rows.filter((r) => r.kind === "counterparty");
  const dealRows = rows.filter((r) => r.kind === "deal");

  const renderRow = (row: Row) => {
    idx += 1;
    const i = idx;
    const meta = row.kind === "screen" ? row.section : row.meta;
    return (
      <div
        key={`${row.kind}-${i}-${row.label}`}
        className={`cmdk-item${i === active ? " active" : ""}`}
        onMouseEnter={() => setActive(i)}
        onClick={() => choose(row)}
      >
        <span className="ci-dot" />
        <span>{row.label}</span>
        <span className="ci-meta">{meta}</span>
      </div>
    );
  };

  return (
    <div className="cmdk-scrim" onClick={onClose}>
      <div className="cmdk" onClick={(e) => e.stopPropagation()} onKeyDown={onKey}>
        <div className="cmdk-input-row">
          <span className="ico">⌕</span>
          <input
            ref={inputRef}
            placeholder="Jump to a screen, counterparty, or deal…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="cmdk-list">
          {rows.length === 0 && <div className="cmdk-empty">No matches for “{q}”.</div>}
          {screenRows.length > 0 && <div className="cmdk-section">Screens</div>}
          {screenRows.map(renderRow)}
          {cpRows.length > 0 && <div className="cmdk-section">Counterparties</div>}
          {cpRows.map(renderRow)}
          {dealRows.length > 0 && <div className="cmdk-section">Deals</div>}
          {dealRows.map(renderRow)}
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
