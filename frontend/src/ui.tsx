import React, { useCallback, useEffect, useState } from "react";

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
  return (
    <label className={`field${error ? " has-error" : ""}`}>
      <span className="lbl">{label}{required && <span className="req" aria-hidden="true"> *</span>}</span>
      {children}
      {error
        ? <span className="field-error" role="alert">{error}</span>
        : hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

/** A friendly placeholder for a screen (or card) that needs a selection/action first. */
export function EmptyState({ glyph = "◴", title, sub, action }: {
  glyph?: string; title: string; sub?: string; action?: React.ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-glyph">{glyph}</div>
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
  return <div className={`toast ${msg.err ? "err" : ""}`}>{msg.text}</div>;
}

/* ---- Governance design language ----
   The product's spine: AI where it helps · Humans where regulation demands ·
   Deterministic figures throughout. These badges are used consistently wherever an
   action is AI-generated, human-gated, or a deterministic figure that AI never touches. */

export function AiBadge({ label = "AI · ADVISORY" }: { label?: string }) {
  return <span className="gov-badge ai" title="AI-generated · advisory · non-binding"><span className="gdot" />{label}</span>;
}

export function HumanBadge({ label = "HUMAN-GATED" }: { label?: string }) {
  return <span className="gov-badge human" title="Requires a named accountable human"><span className="gdot" />{label}</span>;
}

export function DeterministicBadge({ label = "DETERMINISTIC" }: { label?: string }) {
  return <span className="gov-badge det" title="Deterministic figure — AI never produces this"><span className="gdot" />{label}</span>;
}

/** A small "● UNCHANGED / PRESERVED" tag for an authoritative figure an AI overlay left untouched. */
export function Unchanged({ label = "UNCHANGED" }: { label?: string }) {
  return <span className="unchanged"><span className="gdot" />{label}</span>;
}

/** AI-drafts → human-confirms flow indicator for suggest/confirm screens. */
export function GovFlow({ ai, human, note }: { ai: string; human: string; note?: string }) {
  return (
    <div className="gov-flow">
      <AiBadge label={ai} /> <span className="gov-flow-arrow">→</span> <HumanBadge label={human} />
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
      <div className="gov-arrow">→</div>
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
