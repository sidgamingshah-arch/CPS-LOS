/**
 * Unified Explainable-AI (XAI) presentation layer.
 *
 * The platform already exposes several advisory explainability signals — RAG factor
 * breakdowns, macro PD-multiplier contributions, scoring-model section weights,
 * capital/rating factor tables, and grounded-commentary source citations. They lived
 * on five different screens with five different treatments. This module gives them ONE
 * recognisable look: a governance-consistent "Explainable AI" panel that renders, in a
 * stable layout, any of —
 *   (a) a factors / weights / contribution table (Factor · Value · Sub-score · Weight · Contribution),
 *   (b) a confidence meter,
 *   (c) a citations / sources list (source doc · page/section),
 *   (d) an optional what-if / sensitivity note.
 *
 * It is a PRESENTATION layer only. It takes a normalised `explanation` prop, reads the
 * existing advisory endpoints, and is always framed as advisory — it never implies it
 * moved an authoritative figure. Reuses the ui.tsx governance primitives (AiBadge, Badge).
 */

import React from "react";
import { AiBadge, Badge } from "./ui";

// ── normalized explanation model ─────────────────────────────────────────────

/** One row in the factor / weight / contribution table. Callers set only the fields
 *  they want shown; empty columns auto-hide so the same table serves RAG, macro,
 *  scoring-model, and rating breakdowns. Values are already-formatted React nodes. */
export interface XaiFactor {
  label: React.ReactNode;
  value?: React.ReactNode;
  subScore?: React.ReactNode;
  weight?: React.ReactNode;
  contribution?: React.ReactNode;
  /** Rationale / basis; rendered in its own column (left-aligned) when present. */
  note?: React.ReactNode;
  /** A trailing chip, e.g. "imputed" / a band label. */
  tag?: { label: string; kind?: string };
  /** Bold, accented row — e.g. a net / total line. */
  emphasise?: boolean;
}

/** One source citation. */
export interface XaiCitation {
  source: string;
  /** Page / section locator, e.g. "P12" or "§4.2". */
  locator?: string;
  detail?: React.ReactNode;
}

/** Per-column header overrides for the factor table. */
export interface XaiFactorHeaders {
  factor?: string;
  value?: string;
  subScore?: string;
  weight?: string;
  contribution?: string;
  note?: string;
}

/** The normalised shape every XAI surface maps its advisory output onto. */
export interface Explanation {
  /** Prose summary (e.g. the capital/explain narrative). */
  summary?: React.ReactNode;
  /** Factor / weight / contribution rows. */
  factors?: XaiFactor[];
  factorHeaders?: XaiFactorHeaders;
  /** 0..1 model / assessment confidence — renders a meter. */
  confidence?: number;
  confidenceNote?: React.ReactNode;
  /** Source citations. */
  citations?: XaiCitation[];
  /** Optional what-if / sensitivity note. */
  whatIf?: React.ReactNode;
  /** Method / model footnote. */
  method?: React.ReactNode;
}

// ── confidence meter (element b) ─────────────────────────────────────────────

function confTone(v: number): string {
  if (v >= 0.8) return "ok";
  if (v >= 0.5) return "warn";
  return "bad";
}

export function ConfidenceMeter({ value, note, compact }: {
  value: number; note?: React.ReactNode; compact?: boolean;
}) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  const tone = confTone(value);
  return (
    <div className={`xai-conf${compact ? " compact" : ""}`}>
      <div className="xai-conf-row">
        <span className="xai-conf-label">Confidence</span>
        <span className={`xai-conf-val ${tone}`}>{pct.toFixed(0)}%</span>
      </div>
      <div className="xai-conf-track" role="meter" aria-label="Confidence"
        aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
        <div className={`xai-conf-fill ${tone}`} style={{ width: `${pct}%` }} />
      </div>
      {note && <div className="xai-conf-note">{note}</div>}
    </div>
  );
}

// ── citations list (element c) ───────────────────────────────────────────────

const CITE_SRC_KEYS = ["source", "sourceDocument", "document", "doc", "ref", "reference", "label", "name", "title", "key"];
const CITE_LOC_KEYS = ["locator", "sourcePage", "page", "section", "clause", "coordinates", "cell", "line"];

function pick(obj: Record<string, unknown>, keys: string[]): string | undefined {
  for (const k of keys) {
    const v = obj[k];
    if (v != null && v !== "") return String(v);
  }
  return undefined;
}

/**
 * Robustly turn a heterogeneous `sources` value into a citation list. Handles:
 *   - an array of strings,
 *   - an array of citation-ish objects ({source, page, …}),
 *   - a map of { key → string } or { key → object }.
 * Never throws; returns [] for empty / unusable input so callers can fall back cleanly.
 */
export function normalizeCitations(sources: unknown): XaiCitation[] {
  if (sources == null) return [];
  const out: XaiCitation[] = [];
  const fromObject = (key: string | null, v: unknown) => {
    if (v == null) return;
    if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
      out.push(key != null ? { source: key, detail: String(v) } : { source: String(v) });
      return;
    }
    if (Array.isArray(v)) {
      out.push({ source: key ?? "", detail: v.map((x) => String(x)).join(", ") });
      return;
    }
    if (typeof v === "object") {
      const o = v as Record<string, unknown>;
      const src = pick(o, CITE_SRC_KEYS) ?? key ?? "source";
      const loc = pick(o, CITE_LOC_KEYS);
      // Anything left over becomes a compact detail line.
      const used = new Set([...CITE_SRC_KEYS, ...CITE_LOC_KEYS]);
      const rest = Object.entries(o)
        .filter(([k, val]) => !used.has(k) && val != null && val !== "")
        .map(([k, val]) => `${k}: ${typeof val === "object" ? JSON.stringify(val) : String(val)}`);
      out.push({ source: src, locator: loc, detail: rest.length ? rest.join(" · ") : undefined });
    }
  };
  if (Array.isArray(sources)) {
    for (const item of sources) fromObject(null, item);
  } else if (typeof sources === "object") {
    for (const [k, v] of Object.entries(sources as Record<string, unknown>)) fromObject(k, v);
  } else {
    out.push({ source: String(sources) });
  }
  return out;
}

export function CitationList({ citations, empty }: {
  citations: XaiCitation[]; empty?: React.ReactNode;
}) {
  if (!citations || citations.length === 0) {
    return <div className="xai-cite-empty muted">{empty ?? "No sources cited."}</div>;
  }
  return (
    <ul className="xai-cite-list">
      {citations.map((c, i) => (
        <li key={i} className="xai-cite">
          <span className="xai-cite-doc" aria-hidden="true">▪</span>
          <span className="xai-cite-src mono">{c.source || "—"}</span>
          {c.locator && <span className="xai-cite-loc">{c.locator}</span>}
          {c.detail && <span className="xai-cite-detail">{c.detail}</span>}
        </li>
      ))}
    </ul>
  );
}

// ── factor / weight / contribution table (element a) ─────────────────────────

function hasVal(v: React.ReactNode): boolean {
  return v !== undefined && v !== null && v !== "";
}

function FactorTable({ factors, headers }: { factors: XaiFactor[]; headers?: XaiFactorHeaders }) {
  const optional: { key: keyof XaiFactor; header: string; num: boolean }[] = [
    { key: "value", header: headers?.value ?? "Value", num: true },
    { key: "subScore", header: headers?.subScore ?? "Sub-score", num: true },
    { key: "weight", header: headers?.weight ?? "Weight", num: true },
    { key: "contribution", header: headers?.contribution ?? "Contribution", num: true },
    { key: "note", header: headers?.note ?? "Basis", num: false },
  ];
  const shown = optional.filter((c) => factors.some((f) => hasVal(f[c.key] as React.ReactNode)));
  const anyTag = factors.some((f) => !!f.tag);
  return (
    <div className="xai-table-wrap">
      <table className="xai-table">
        <thead>
          <tr>
            <th scope="col">{headers?.factor ?? "Factor"}</th>
            {shown.map((c) => (
              <th key={String(c.key)} scope="col" className={c.num ? "num" : ""}>{c.header}</th>
            ))}
            {anyTag && <th scope="col" aria-label="tag" />}
          </tr>
        </thead>
        <tbody>
          {factors.map((f, i) => (
            <tr key={i} className={f.emphasise ? "xai-row-net" : undefined}>
              <td className="xai-factor-label">{f.label}</td>
              {shown.map((c) => (
                <td key={String(c.key)} className={c.num ? "num" : ""}>
                  {hasVal(f[c.key] as React.ReactNode) ? (f[c.key] as React.ReactNode) : <span className="muted">—</span>}
                </td>
              ))}
              {anyTag && (
                <td className="xai-tag-cell">
                  {f.tag && <Badge kind={f.tag.kind ?? "info"}>{f.tag.label}</Badge>}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ── the panel ────────────────────────────────────────────────────────────────

export interface ExplainCardProps {
  /** Panel heading, e.g. "Why this RAG band". */
  title?: string;
  subtitle?: React.ReactNode;
  explanation?: Explanation;
  /** Governance chip label (kept advisory). */
  badgeLabel?: string;
  /**
   * The advisory framing line under the panel. Defaults to a reminder that this
   * explains the advisory signal and does not move any authoritative figure.
   * Pass `null` to suppress it (e.g. a citations-only embed inside another advisory block).
   */
  advisoryNote?: React.ReactNode;
  /** Denser paddings / type for embedding inside another card. */
  compact?: boolean;
  right?: React.ReactNode;
  /** Escape hatch: extra content rendered inside the panel body. */
  children?: React.ReactNode;
}

/**
 * The one recognisable Explainable-AI panel. Renders whichever of the four building
 * blocks the `explanation` provides — nothing forced, nothing hidden. Always advisory.
 */
export function ExplainCard({
  title = "Why this output",
  subtitle,
  explanation,
  badgeLabel = "EXPLAINABLE AI",
  advisoryNote = "Explains how the advisory signal was formed — it does not move any authoritative figure.",
  compact,
  right,
  children,
}: ExplainCardProps) {
  const e = explanation ?? {};
  const hasFactors = !!e.factors && e.factors.length > 0;
  const hasCitations = !!e.citations && e.citations.length > 0;
  const hasBody =
    e.summary != null || typeof e.confidence === "number" || hasFactors ||
    hasCitations || e.whatIf != null || e.method != null || children != null;

  return (
    <section className={`xai-card${compact ? " xai-compact" : ""}`}>
      <div className="xai-head">
        <div className="xai-title-wrap">
          <h4 className="xai-title">{title}</h4>
          {subtitle && <div className="xai-sub">{subtitle}</div>}
        </div>
        <div className="xai-head-right">
          <AiBadge label={badgeLabel} />
          {right}
        </div>
      </div>

      {hasBody && (
        <div className="xai-body">
          {e.summary != null && <div className="xai-summary">{e.summary}</div>}

          {typeof e.confidence === "number" && (
            <ConfidenceMeter value={e.confidence} note={e.confidenceNote} compact={compact} />
          )}

          {hasFactors && <FactorTable factors={e.factors!} headers={e.factorHeaders} />}

          {hasCitations && (
            <div className="xai-section">
              <div className="xai-section-label">Sources</div>
              <CitationList citations={e.citations!} />
            </div>
          )}

          {e.whatIf != null && (
            <div className="xai-whatif">
              <span className="xai-whatif-tag">What-if</span>
              <span>{e.whatIf}</span>
            </div>
          )}

          {children}

          {e.method != null && <div className="xai-method">{e.method}</div>}
        </div>
      )}

      {advisoryNote != null && <div className="xai-advisory-note">{advisoryNote}</div>}
    </section>
  );
}

/** Alias — some screens read better as "Why this output?". Same component. */
export const WhyThisOutput = ExplainCard;
