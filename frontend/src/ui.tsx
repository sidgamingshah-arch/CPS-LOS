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

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="field"><span className="lbl">{label}</span>{children}</label>;
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
